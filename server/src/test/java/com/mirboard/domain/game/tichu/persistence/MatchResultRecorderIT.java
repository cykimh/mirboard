package com.mirboard.domain.game.tichu.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=match-recorder-test-secret-must-be-32-bytes-or-more"
})
class MatchResultRecorderIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> MYSQL = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TichuMatchResultRepository matchRepo;

    @Autowired
    TichuMatchParticipantRepository participantRepo;

    @Autowired
    UserRepository userRepo;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    Clock clock;

    @Autowired
    com.mirboard.domain.lobby.auth.BotUserRegistry bots;

    // D-115 — 티츄 전적은 users 가 아니라 게임별 테이블에 쌓인다.
    @Autowired
    com.mirboard.domain.game.scoring.UserGameStatsService stats;

    private com.mirboard.domain.game.scoring.UserGameStatsService.GameStats tichu(long userId) {
        return stats.get(userId, com.mirboard.domain.game.tichu.TichuGameDefinition.ID);
    }

    @Test
    @Transactional
    void recorder_persists_match_and_increments_winners() {
        var users = registerFour("mr_a", "mr_b", "mr_c", "mr_d");
        var ids = users.stream().map(User::getId).toList();

        // 두 라운드 결과를 합쳐 누적 1100:300 (Team A 승) 으로 매치 종료 가정.
        List<RoundScore> rounds = List.of(
                new RoundScore(500, 100, 0, false),
                new RoundScore(600, 200, 0, false));
        publisher.publishEvent(new TichuMatchCompleted(
                "room-X", ids, 1100, 300, Team.A, rounds, null, 0));

        // Match row — 누적 점수가 기록.
        var matches = matchRepo.findAll();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getRoomId()).isEqualTo("room-X");
        assertThat(matches.get(0).getTeamAScore()).isEqualTo(1100);
        assertThat(matches.get(0).getTeamBScore()).isEqualTo(300);
        assertThat(matches.get(0).getPayloadJson()).contains("\"roundScores\"");

        // Participants: A=seats 0,2 = mr_a, mr_c; B=1,3 = mr_b, mr_d.
        var matchId = matches.get(0).getId();
        var participantsByUser = participantRepo.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        TichuMatchParticipant::getUserId, p -> p));
        assertThat(participantsByUser.get(ids.get(0)).isWin()).isTrue();
        assertThat(participantsByUser.get(ids.get(2)).isWin()).isTrue();
        assertThat(participantsByUser.get(ids.get(1)).isWin()).isFalse();
        assertThat(participantsByUser.get(ids.get(3)).isWin()).isFalse();
        assertThat(participantsByUser.values()).allMatch(p -> p.getMatchId().equals(matchId));

        // Win/lose counts.
        var refreshed = ids.stream().map(this::tichu).toList();
        assertThat(refreshed.get(0).winCount()).isEqualTo(1);
        assertThat(refreshed.get(2).winCount()).isEqualTo(1);
        assertThat(refreshed.get(1).loseCount()).isEqualTo(1);
        assertThat(refreshed.get(3).loseCount()).isEqualTo(1);

        // Phase 8D — ELO 갱신 검증. 모든 신규 유저 (gamesPlayed=0 → K=40), rating=1000.
        // expected=0.5, delta = 40 * 0.5 = 20. 승팀 +20, 패팀 -20.
        assertThat(refreshed.get(0).rating()).isEqualTo(1026);
        assertThat(refreshed.get(2).rating()).isEqualTo(1026);
        assertThat(refreshed.get(1).rating()).isEqualTo(996);
        assertThat(refreshed.get(3).rating()).isEqualTo(996);

        // D-115 — users 의 옛 레이팅 컬럼은 이관 후 쓰기가 멈췄다(DROP 은 별도 마이그레이션).
        assertThat(userRepo.findById(ids.get(0)).orElseThrow().getRating()).isEqualTo(1000);
        assertThat(userRepo.findById(ids.get(0)).orElseThrow().getWinCount()).isZero();
    }

    @Test
    @Transactional
    void bot_match_records_win_lose_and_a_quarter_of_the_elo() {
        // D-299 — 봇 포함 매치: win/lose 는 기록, rating 은 사람끼리의 1/4 만 움직인다(예전에는 불변).
        var humans = registerFour("mrbot_a", "mrbot_b", "mrbot_c"); // 3 명만 등록
        long botId = bots.getBotIds().get(0);
        // 좌석 0,2 = Team A / 1,3 = Team B. 봇을 seat 3 (Team B) 에 배치.
        List<Long> ids = List.of(
                humans.get(0).getId(), humans.get(1).getId(),
                humans.get(2).getId(), botId);

        publisher.publishEvent(new TichuMatchCompleted(
                "room-bot", ids, 900, 200, Team.A,
                List.of(new RoundScore(900, 200, 0, false)), null, 0));

        // match_result + participant 는 기록됨.
        assertThat(matchRepo.findAll()).anyMatch(m -> m.getRoomId().equals("room-bot"));

        var refreshed = humans.stream().map(u -> tichu(u.getId())).toList();
        // 승패는 반영 (seat0,2 = A 승 / seat1 = B 패).
        assertThat(refreshed.get(0).winCount()).isEqualTo(1);
        assertThat(refreshed.get(2).winCount()).isEqualTo(1);
        assertThat(refreshed.get(1).loseCount()).isEqualTo(1);
        // 신규 K=40, 팀 평균 1000 vs 1000(봇은 1000 으로 계산) → 사람끼리면 +20/−10, 봇 판은 +5/−2(−2.5 반올림). 참가 점수 +2(D-301).
        assertThat(refreshed.get(0).rating()).isEqualTo(1007);
        assertThat(refreshed.get(2).rating()).isEqualTo(1007);
        assertThat(refreshed.get(1).rating()).isEqualTo(1000);
        // 봇의 레이팅·전적은 저장하지 않는다.
        assertThat(stats.playedGames(botId)).isEmpty();
    }

    @Test
    @Transactional
    void desertion_increments_desert_count_and_records_loss() {
        // Phase 19(#3, D-75) — deserterUserId 비-null 이면 win/lose/ELO 외에
        // 해당 유저 desert_count 도 같은 트랜잭션에서 +1.
        var users = registerFour("mrd_a", "mrd_b", "mrd_c", "mrd_d");
        var ids = users.stream().map(User::getId).toList();
        long deserter = ids.get(1); // seat1 = Team B → 상대팀 A 승리.

        publisher.publishEvent(new TichuMatchCompleted(
                "room-desert", ids, 120, 80, Team.A,
                List.of(new RoundScore(120, 80, 0, false)), deserter, 0));

        var refreshed = ids.stream().map(this::tichu).toList();
        // 탈주자(seat1, B) — 패배 + 탈주 카운트.
        assertThat(refreshed.get(1).loseCount()).isEqualTo(1);
        assertThat(refreshed.get(1).desertCount()).isEqualTo(1);
        assertThat(refreshed.get(1).rating()).as("탈주자는 참가 점수가 없다(D-301)").isEqualTo(990);
        // 상대팀(seat0,2 = A) — 승리, 탈주 카운트 없음.
        assertThat(refreshed.get(0).winCount()).isEqualTo(1);
        assertThat(refreshed.get(0).desertCount()).isZero();
        assertThat(refreshed.get(0).rating()).isEqualTo(1026);
        // 탈주자 파트너(seat3, B) — 같이 패배하지만 탈주 카운트 없음.
        assertThat(refreshed.get(3).loseCount()).isEqualTo(1);
        assertThat(refreshed.get(3).desertCount()).isZero();
        assertThat(refreshed.get(3).rating()).as("끝까지 한 파트너는 참가 점수를 받는다").isEqualTo(996);
    }

    @Test
    @Transactional
    void match_with_guest_rates_the_members_and_never_stores_the_guest_rating() {
        // D-299 덧붙임 — 게스트가 낀 매치도 가입한 사람의 ELO 는 사람끼리 판과 같다(예전 D-117 은 전원 미적용). 승패·참가
        // 기록은 그대로 — 게스트 정리가 "끝낸 매치"를 user_game_stats 로 판정하기 때문에도 필요.
        var humans = registerFour("mrg_a", "mrg_b", "mrg_c");
        User guest = userRepo.save(User.create("guest-mrg2a3b4",
                com.mirboard.domain.lobby.auth.GuestPolicy.NO_LOGIN_HASH, clock));
        List<Long> ids = List.of(
                humans.get(0).getId(), humans.get(1).getId(),
                humans.get(2).getId(), guest.getId());

        publisher.publishEvent(new TichuMatchCompleted(
                "room-guest", ids, 900, 200, Team.A,
                List.of(new RoundScore(900, 200, 0, false)), null, 0));

        var match = matchRepo.findAll().stream()
                .filter(m -> m.getRoomId().equals("room-guest")).findFirst().orElseThrow();
        assertThat(participantRepo.findAll())
                .filteredOn(p -> p.getMatchId().equals(match.getId()))
                .hasSize(4);

        var refreshed = ids.stream().map(this::tichu).toList();
        // 승패 증분 (seat0,2 = A 승 / seat1,3 = B 패) — 게스트(seat3)도 전적은 쌓인다.
        assertThat(refreshed.get(0).winCount()).isEqualTo(1);
        assertThat(refreshed.get(2).winCount()).isEqualTo(1);
        assertThat(refreshed.get(1).loseCount()).isEqualTo(1);
        assertThat(refreshed.get(3).loseCount()).isEqualTo(1);
        // 가입한 사람은 사람끼리 판과 같다: +20/−10(K=40) + 참가 점수 +6(D-301).
        assertThat(refreshed.get(0).rating()).isEqualTo(1026);
        assertThat(refreshed.get(2).rating()).isEqualTo(1026);
        assertThat(refreshed.get(1).rating()).isEqualTo(996);
        // 게스트 본인의 레이팅은 저장하지 않는다 — 늘 1000.
        assertThat(refreshed.get(3).rating()).isEqualTo(1000);
    }

    private List<User> registerFour(String... usernames) {
        List<User> created = new ArrayList<>();
        for (String u : usernames) {
            var existing = userRepo.findByUsername(u);
            if (existing.isPresent()) {
                created.add(existing.get());
                continue;
            }
            User saved = userRepo.save(User.create(u, passwordEncoder.encode("validpass1"), clock));
            created.add(saved);
        }
        return created;
    }
}
