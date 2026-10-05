package com.mirboard.domain.game.onecard.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-128 — 원카드 매치 종료를 {@code onecard_match_results/participants} 에 적재하고 게임별 전적(ONE_CARD
 * 행)을 갱신한다. 스컬킹 {@code SkullKingMatchRecorder} 와 같은 규칙: 봇(D-71)·게스트(D-117)가 낀 매치는
 * 승패만, ELO 는 제외({@link RatedMatchPolicy}). 탈주자는 패 + desert_count(D-75).
 *
 * <p>승리는 1등이다 — 동순위면 모두(룰 §11.2). ELO 는 개인전 쌍대 방식({@link EloCalculator#applyFreeForAll})에
 * 순위를 점수로 넘긴다(점수 = 좌석 수 − 순위, 순위가 같으면 무승부). 탈주 좌석은 점수와 무관하게 최하위다.
 */
@Component
public class OneCardMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(OneCardMatchRecorder.class);
    private static final String GAME = OneCardGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OneCardMatchRecorder(JdbcTemplate jdbc,
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
    public void onMatchCompleted(OneCardMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        MatchResult result = event.result();
        Map<Integer, Standing> bySeat = new HashMap<>();
        result.standings().forEach(standing -> bySeat.put(standing.seat(), standing));
        boolean rated = ratedMatchPolicy.eloApplies(playerIds);

        Long matchId = jdbc.queryForObject(
                "INSERT INTO onecard_match_results (room_id, finished_at, end_reason, payload_json)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), result.reason().name(), payload(result));

        // ELO 입력은 갱신 전 전적으로 만든다 (K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> newRatings = Map.of();
        if (rated) {
            List<EloCalculator.Placement> placements = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                long userId = playerIds.get(seat);
                Standing standing = bySeat.get(seat);
                GameStats current = stats.get(userId, GAME);
                placements.add(new EloCalculator.Placement(
                        new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed()),
                        playerIds.size() - standing.rank(),
                        standing.status() == SeatStatus.DESERTED));
            }
            newRatings = EloCalculator.applyFreeForAll(placements);
        }

        List<Integer> winners = result.winners();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            Standing standing = bySeat.get(seat);
            boolean win = winners.contains(seat);
            boolean deserted = standing.status() == SeatStatus.DESERTED;
            jdbc.update("INSERT INTO onecard_match_participants"
                            + " (match_id, user_id, seat, final_rank, cards_left, is_win, deserted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, standing.rank(), standing.cardsLeft(), win, deserted);
            // 봇 계정 자신은 전적을 쌓지 않는다 (랭킹 대상 아님).
            if (!bots.isBot(userId)) {
                stats.record(userId, GAME, win, newRatings.get(userId), deserted);
            }
        }

        log.info("OneCard match recorded: room={} matchId={} reason={} winners={} eloApplied={} ratings={}",
                event.roomId(), matchId, result.reason(), winners, rated, newRatings);
    }

    private String payload(MatchResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize one card match payload", e);
        }
    }
}
