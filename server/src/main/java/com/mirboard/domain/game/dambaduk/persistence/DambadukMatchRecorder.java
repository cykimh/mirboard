package com.mirboard.domain.game.dambaduk.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.dambaduk.DambadukGameDefinition;
import com.mirboard.domain.game.dambaduk.event.DambadukMatchCompleted;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.dambaduk.state.MatchResult;
import com.mirboard.domain.game.dambaduk.state.Standing;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-278 — 담바둑 매치 종료를 {@code dambaduk_match_results/participants} 에 적재하고 게임별 전적(DAMBADUK 행)을 갱신한다. 구름항로와 같은
 * 규칙(`docs/game-port.md` "매치 기록기 계약"): 봇·게스트가 낀 매치는 승패만, ELO 제외({@link RatedMatchPolicy}). 비봇 참가자마다 전적
 * 행을 남기고, 탈주자는 패 + desert_count(시간패로 빠진 사람은 패만 — 탈주가 아니다, 룰 §8).
 *
 * <p>승리는 1위다 — 동순위면 모두(룰 §7). 빠진 사람 없이 모두 동순위면 무승부: 승·패를 늘리지 않고 ELO 도 바꾸지 않는다(오목 D-178 과
 * 같다, 전적 행만 — {@code recordDraw}). 그 밖의 ELO 는 개인전 쌍대 방식({@link EloCalculator#applyFreeForAll}) — 순위를 점수로 넘긴다
 * (점수 = 좌석 수 − 순위), 빠진 사람은 순위가 이미 맨 아래다.
 */
@Component
public class DambadukMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(DambadukMatchRecorder.class);
    private static final String GAME = DambadukGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DambadukMatchRecorder(JdbcTemplate jdbc,
                                 UserGameStatsService stats,
                                 BotUserRegistry bots,
                                 RatedMatchPolicy ratedMatchPolicy,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.stats = stats;
        this.bots = bots;
        this.ratedMatchPolicy = ratedMatchPolicy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onMatchCompleted(DambadukMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        DambadukState state = event.state();
        MatchResult result = state.result();
        boolean draw = result.draw();
        boolean rated = !draw && ratedMatchPolicy.eloApplies(playerIds);

        Long matchId = jdbc.queryForObject(
                "INSERT INTO dambaduk_match_results (room_id, finished_at, seat_count, end_reason, moves, is_draw,"
                        + " payload_json) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), state.seatCount(), result.reason().name(),
                state.moveCount(), draw, payload(state));

        // ELO 입력은 갱신 전 전적으로 만든다 (K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> newRatings = Map.of();
        if (rated) {
            List<EloCalculator.Placement> placements = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                long userId = playerIds.get(seat);
                Standing standing = result.standings().get(seat);
                GameStats current = stats.get(userId, GAME);
                placements.add(new EloCalculator.Placement(
                        new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed()),
                        playerIds.size() - standing.rank(),
                        standing.exit() != null));
            }
            newRatings = EloCalculator.applyFreeForAll(placements);
        }

        Map<Long, String> outcome = new LinkedHashMap<>();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            Standing standing = result.standings().get(seat);
            boolean win = result.winners().contains(seat);
            boolean deserted = standing.exit() == ExitReason.DESERTED;
            jdbc.update("INSERT INTO dambaduk_match_participants"
                            + " (match_id, user_id, seat, color, final_rank, score, largest_home, exit_reason, is_win,"
                            + " is_draw, deserted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, state.colorIndex(seat), standing.rank(), standing.score(),
                    standing.largest(), standing.exit() == null ? null : standing.exit().name(), win, draw, deserted);
            if (bots.isBot(userId)) {
                continue;                                   // 봇 계정 자신은 전적을 쌓지 않는다 (랭킹 대상 아님)
            }
            if (draw) {
                stats.recordDraw(userId, GAME);
            } else {
                stats.record(userId, GAME, win, newRatings.get(userId), deserted);
            }
            outcome.put(userId, draw ? "draw" : win ? "win" : deserted ? "deserted" : "lose");
        }

        log.info("Dambaduk match recorded: room={} matchId={} seats={} reason={} outcome={} eloApplied={} ratings={}",
                event.roomId(), matchId, state.seatCount(), result.reason(), outcome, rated, newRatings);
    }

    private String payload(DambadukState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize dambaduk match payload", e);
        }
    }
}
