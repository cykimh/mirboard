package com.mirboard.domain.game.tichu.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 한 매치(여러 라운드 합산) 가 종료되면 {@link TichuMatchCompleted} 를 받아
 * {@code tichu_match_results} + {@code tichu_match_participants} 에 1행씩 적재하고
 * 각 유저의 {@code win_count}/{@code lose_count} 를 증분. 라운드별 점수는 payload_json
 * 의 roundScores 배열로 같이 저장.
 *
 * <p>D-115 — 승패·레이팅·탈주 수는 {@code users} 가 아니라 게임별 {@code user_game_stats}
 * (TICHU 행)에 쌓는다. {@code users} 의 옛 컬럼은 이관 후 쓰기를 멈췄다.
 *
 * <p>D-131 — <b>기록 실패는 여기서 끝낸다</b>(ERROR 후 삼킨다). 이 이벤트는 엔진이 동기로 발행하고 리스너가 둘이다 — 이
 * 기록기와 {@code RoomChipService}(칩 정산). 스프링 멀티캐스터는 첫 예외에서 멈추고 그 예외를 발행자에게 던지므로, 기록
 * 예외가 진행 경로로 새어 마지막 방송·리매치 대기를 건너뛰고, 기록이 먼저 불리는 순서면 칩 정산까지 빠졌다. 발행 지점에서
 * 잡으면 앞의 것만 막는다. 트랜잭션 경계를 {@link TransactionTemplate} 으로 직접 감싸는 것은 커밋 실패까지 잡기 위해서다 —
 * {@code @Transactional} 프록시의 커밋은 메서드 본문이 돌아온 뒤라 본문 안의 try/catch 에 걸리지 않는다. 다른 인스턴스로 다시
 * 보내는 경로는 만들지 않는다(D-116 — 기록은 끝낸 인스턴스에서 한 번). 결과는 로그로 남겨 수동 복구할 수 있게 한다.
 */
@Component
public class MatchResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(MatchResultRecorder.class);

    private final TichuMatchResultRepository matchRepo;
    private final TichuMatchParticipantRepository participantRepo;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public MatchResultRecorder(TichuMatchResultRepository matchRepo,
                               TichuMatchParticipantRepository participantRepo,
                               UserGameStatsService stats,
                               BotUserRegistry bots,
                               RatedMatchPolicy ratedMatchPolicy,
                               ObjectMapper objectMapper,
                               Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.matchRepo = matchRepo;
        this.participantRepo = participantRepo;
        this.stats = stats;
        this.bots = bots;
        this.ratedMatchPolicy = ratedMatchPolicy;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener
    public void onMatchCompleted(TichuMatchCompleted event) {
        try {
            transactions.executeWithoutResult(status -> record(event));
        } catch (RuntimeException e) {
            log.error("Tichu match record failed, the match still ends: room={} players={} winner={} A={} B={} "
                            + "rounds={} deserterUserId={}",
                    event.roomId(), event.playerIds(), event.winningTeam(), event.cumulativeTeamAScore(),
                    event.cumulativeTeamBScore(), event.roundScores().size(), event.deserterUserId(), e);
        }
    }

    /** 한 트랜잭션 — 매치·참가자 행과 게임별 전적. */
    private void record(TichuMatchCompleted event) {
        // Phase 16(#4) — 봇 포함 매치도 win/lose·match_result 는 기록하되 ELO(rating)
        // 만 제외 (rating 인플레이션 방지). D-117 — 게스트가 낀 매치도 같은 규칙.
        // 정회원 4인 매치만 ELO 반영.
        boolean rated = ratedMatchPolicy.eloApplies(event.playerIds());
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of(
                    "cumulativeA", event.cumulativeTeamAScore(),
                    "cumulativeB", event.cumulativeTeamBScore(),
                    "winningTeam", event.winningTeam().name(),
                    "roundScores", event.roundScores()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize match payload", e);
        }

        TichuMatchResult result = matchRepo.save(new TichuMatchResult(
                event.roomId(),
                Instant.now(clock),
                event.cumulativeTeamAScore(),
                event.cumulativeTeamBScore(),
                payloadJson));

        Team winner = event.winningTeam();
        List<Long> playerIds = event.playerIds();

        // Phase 8D — ELO 계산 입력 수집 (현재 rating + 누적 게임 수). win/lose 증분
        // 이전 시점의 값을 사용해야 K-factor 임계 (30게임) 가 정확.
        List<EloCalculator.PlayerInput> teamAInput = new ArrayList<>();
        List<EloCalculator.PlayerInput> teamBInput = new ArrayList<>();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            GameStats current = stats.get(userId, TichuGameDefinition.ID);
            var input = new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed());
            if (Team.ofSeat(seat) == Team.A) teamAInput.add(input);
            else teamBInput.add(input);
        }
        // 봇·게스트 포함 매치는 ELO 미적용 — 빈 맵이면 아래 record 가 rating 을 유지.
        Map<Long, Integer> newRatings = rated
                ? EloCalculator.applyMatch(teamAInput, teamBInput, winner == Team.A)
                : Map.of();

        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            Team team = Team.ofSeat(seat);
            boolean isWin = team == winner;
            participantRepo.save(new TichuMatchParticipant(
                    result.getId(), userId, team.name(), isWin));
            // Phase 19(#3, D-75) — 탈주로 종료된 매치면 탈주자 desert_count 도 같은
            // 트랜잭션에서 증분 (win/lose/ELO 는 위 winner 기준).
            boolean deserted = event.deserterUserId() != null && event.deserterUserId() == userId;
            // 봇 계정 자신은 전적을 쌓지 않는다 (랭킹 대상 아님, D-71).
            if (!bots.isBot(userId)) {
                stats.record(userId, TichuGameDefinition.ID, isWin, newRatings.get(userId), deserted);
            }
        }
        if (event.deserterUserId() != null) {
            log.info("Desertion recorded: room={} deserterUserId={}",
                    event.roomId(), event.deserterUserId());
        }

        log.info("Match recorded: room={}, winner={}, A={}/B={}, rounds={}, eloApplied={}, ratings={}",
                event.roomId(), winner,
                event.cumulativeTeamAScore(), event.cumulativeTeamBScore(),
                event.roundScores().size(), rated, newRatings);
    }
}
