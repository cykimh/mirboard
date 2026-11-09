package com.mirboard.domain.game.omok.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.omok.OmokGameDefinition;
import com.mirboard.domain.game.omok.event.OmokMatchCompleted;
import com.mirboard.domain.game.omok.rules.TurnOrder;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.MatchResult;
import com.mirboard.domain.game.omok.state.OmokState;
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
 * 오목 매치 종료를 {@code omok_match_results/participants}에 적재하고 게임별 전적(OMOK 행)을 갱신한다 (룰 §10.2). 원카드
 * 기록기와 같은 규칙: 봇·게스트가 낀 매치는 ELO 제외({@link RatedMatchPolicy}), 봇 계정은 전적을 쌓지 않는다, 탈주자는 패 +
 * desert_count.
 *
 * <p>ELO 는 1:1·2:2 모두 팀 평균 방식({@link EloCalculator#applyMatch} — 1:1 은 한 명짜리 팀). 무승부는 ELO 변화 없이 행만
 * 남긴다({@link UserGameStatsService#recordDraw}). 레이팅은 모드를 나누지 않고 오목 하나다.
 */
@Component
public class OmokMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(OmokMatchRecorder.class);
    private static final String GAME = OmokGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OmokMatchRecorder(JdbcTemplate jdbc,
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
    public void onMatchCompleted(OmokMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        OmokState state = event.state();
        MatchResult result = state.result();
        boolean draw = result.isDraw();
        boolean rated = !draw && ratedMatchPolicy.eloApplies(playerIds);

        Long matchId = jdbc.queryForObject(
                "INSERT INTO omok_match_results (room_id, finished_at, mode, rule, end_reason, winner_color, payload_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), state.mode().name(), state.rule().name(),
                result.reason().name(), draw ? null : result.winnerColor().name(), payload(state));

        // ELO 입력은 갱신 전 전적으로 만든다(K-factor 의 판 수 임계가 정확하도록). 팀 = seat % 2.
        Map<Long, Integer> newRatings = Map.of();
        if (rated) {
            List<EloCalculator.PlayerInput> team0 = new ArrayList<>();
            List<EloCalculator.PlayerInput> team1 = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                long userId = playerIds.get(seat);
                GameStats current = stats.get(userId, GAME);
                EloCalculator.PlayerInput input =
                        new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed());
                (TurnOrder.team(seat) == 0 ? team0 : team1).add(input);
            }
            boolean team0Won = result.winnerSeats().contains(0);
            newRatings = EloCalculator.applyMatch(team0, team1, team0Won);
        }

        Map<Long, String> outcome = new LinkedHashMap<>();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            boolean win = result.winnerSeats().contains(seat);
            boolean deserted = result.reason() == EndReason.DESERTED && result.causeSeat() == seat;
            jdbc.update("INSERT INTO omok_match_participants"
                            + " (match_id, user_id, seat, team, color, is_win, is_draw, deserted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, TurnOrder.team(seat), state.colorOf(seat).name(), win, draw, deserted);
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

        log.info("Omok match recorded: room={} matchId={} mode={} reason={} outcome={} eloApplied={} ratings={}",
                event.roomId(), matchId, state.mode(), result.reason(), outcome, rated, newRatings);
    }

    private String payload(OmokState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize omok match payload", e);
        }
    }
}
