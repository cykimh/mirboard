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
                "room-X", ids, 1100, 300, Team.A, rounds));

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
        var refreshed = ids.stream().map(id -> userRepo.findById(id).orElseThrow()).toList();
        assertThat(refreshed.get(0).getWinCount()).isEqualTo(1);
        assertThat(refreshed.get(2).getWinCount()).isEqualTo(1);
        assertThat(refreshed.get(1).getLoseCount()).isEqualTo(1);
        assertThat(refreshed.get(3).getLoseCount()).isEqualTo(1);

        // Phase 8D — ELO 갱신 검증. 모든 신규 유저 (gamesPlayed=0 → K=40), rating=1000.
        // expected=0.5, delta = 40 * 0.5 = 20. 승팀 +20, 패팀 -20.
        assertThat(refreshed.get(0).getRating()).isEqualTo(1020);
        assertThat(refreshed.get(2).getRating()).isEqualTo(1020);
        assertThat(refreshed.get(1).getRating()).isEqualTo(980);
        assertThat(refreshed.get(3).getRating()).isEqualTo(980);
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
