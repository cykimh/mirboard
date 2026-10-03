package com.mirboard.domain.game.skullking.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.skullking.event.SkullKingMatchCompleted;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-115 — 스컬킹 매치 종료를 {@code skullking_match_results/participants} 에 적재하고 게임별
 * 전적(SKULL_KING 행)을 갱신한다. 티츄 {@code MatchResultRecorder} 와 같은 규칙:
 * 봇(D-71)·게스트(D-117)가 낀 매치는 승패만, ELO 는 제외({@link RatedMatchPolicy}).
 * 탈주자는 패 + desert_count(D-75).
 *
 * <p>ELO 는 개인전 쌍대 방식({@link EloCalculator#applyFreeForAll}) — 탈주 좌석은 점수와
 * 무관하게 최하위다.
 */
@Component
public class SkullKingMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(SkullKingMatchRecorder.class);
    private static final String GAME = SkullKingGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SkullKingMatchRecorder(JdbcTemplate jdbc,
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
    public void onMatchCompleted(SkullKingMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        boolean rated = ratedMatchPolicy.eloApplies(playerIds);

        Long matchId = jdbc.queryForObject(
                "INSERT INTO skullking_match_results (room_id, finished_at, rounds_played, payload_json)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), event.roundsPlayed(), payload(event));

        // ELO 입력은 갱신 전 전적으로 만든다 (K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> newRatings = Map.of();
        if (rated) {
            List<EloCalculator.Placement> placements = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                long userId = playerIds.get(seat);
                GameStats current = stats.get(userId, GAME);
                placements.add(new EloCalculator.Placement(
                        new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed()),
                        event.finalScores().getOrDefault(seat, 0),
                        event.desertedSeats().contains(seat)));
            }
            newRatings = EloCalculator.applyFreeForAll(placements);
        }

        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            boolean win = event.winners().contains(seat);
            boolean deserted = event.desertedSeats().contains(seat);
            jdbc.update("INSERT INTO skullking_match_participants"
                            + " (match_id, user_id, seat, final_score, is_win, deserted) VALUES (?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, event.finalScores().getOrDefault(seat, 0), win, deserted);
            // 봇 계정 자신은 전적을 쌓지 않는다 (랭킹 대상 아님).
            if (!bots.isBot(userId)) {
                stats.record(userId, GAME, win, newRatings.get(userId), deserted);
            }
        }

        log.info("SkullKing match recorded: room={} matchId={} winners={} deserted={} rounds={} eloApplied={} ratings={}",
                event.roomId(), matchId, event.winners(), event.desertedSeats(),
                event.roundsPlayed(), rated, newRatings);
    }

    private String payload(SkullKingMatchCompleted event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "finalScores", event.finalScores(),
                    "winners", event.winners(),
                    "desertedSeats", event.desertedSeats()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize skull king match payload", e);
        }
    }
}
