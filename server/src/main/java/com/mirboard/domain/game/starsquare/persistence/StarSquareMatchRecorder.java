package com.mirboard.domain.game.starsquare.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.starsquare.StarSquareGameDefinition;
import com.mirboard.domain.game.starsquare.event.StarSquareMatchCompleted;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.MatchResult.Placement;
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
 * 별자리 수판 매치 종료를 {@code star_square_match_results/participants}(V22)에 적재하고 게임별 전적(STAR_SQUARE 행)을 갱신한다(S2).
 * 얏추 기록기와 같은 규칙: 게스트가 낀 매치도 ELO 적용(게스트 본인 것은 저장 안 함 — D-299 덧붙임), 봇이 낀 매치는 ELO 를 1/4 만(D-299 — {@link RatedMatchPolicy}). 탈주자는 패 + 탈주 수.
 *
 * <p><b>1인 매치는 아무것도 쓰지 않는다</b>(룰 §12.2, D-181 과 같다 — 혼자 판 최단 시간 기록은 v1 에 없다). 1인 + 봇은 여럿 판이다.
 *
 * <p>승리는 1위다 — 동순위면 모두(룰 §8.2). ELO 는 개인전 쌍대 방식({@link EloCalculator#applyFreeForAll})에 순위를 점수로 넘긴다
 * (점수 = 좌석 수 − 순위 — 같은 순위는 같은 점수라 무승부로 친다).
 */
@Component
public class StarSquareMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(StarSquareMatchRecorder.class);
    private static final String GAME = StarSquareGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StarSquareMatchRecorder(JdbcTemplate jdbc,
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
    public void onMatchCompleted(StarSquareMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        MatchResult result = event.result();
        if (playerIds.size() < 2) {
            log.info("Star square solo match, nothing recorded: room={} reason={}", event.roomId(), result.reason());
            return;
        }
        Map<Integer, Placement> bySeat = new HashMap<>();
        result.placements().forEach(p -> bySeat.put(p.seat(), p));
        double eloFactor = ratedMatchPolicy.eloFactor(playerIds);
        boolean rated = eloFactor > RatedMatchPolicy.UNRATED;

        Long matchId = jdbc.queryForObject(
                "INSERT INTO star_square_match_results (room_id, finished_at, end_reason, payload_json)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), result.reason().name(), payload(result));

        // ELO 입력은 갱신 전 전적으로 만든다(K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> ratingDeltas = Map.of();
        if (rated) {
            List<EloCalculator.Placement> placements = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                long userId = playerIds.get(seat);
                Placement p = bySeat.get(seat);
                placements.add(new EloCalculator.Placement(
                        stats.ratingInput(userId),
                        playerIds.size() - p.rank(),
                        p.deserted()));
            }
            ratingDeltas = EloCalculator.freeForAllDeltas(placements, eloFactor);
        }

        List<Integer> winners = result.winners();
        // D-305 — userId 오름차순(통합 행 잠금 순서를 매치마다 같게 — 동시에 끝난 두 판의 교착 방지).
        for (int seat : UserGameStatsService.seatsInUserIdOrder(playerIds)) {
            long userId = playerIds.get(seat);
            Placement p = bySeat.get(seat);
            boolean win = winners.contains(seat);
            jdbc.update("INSERT INTO star_square_match_participants"
                            + " (match_id, user_id, seat, final_rank, total_points, firsts, is_win, deserted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, p.rank(), p.points(), p.firsts(), win, p.deserted());
            // 봇 계정 자신은 전적을 쌓지 않는다(랭킹 대상 아님).
            if (!bots.isBot(userId)) {
                stats.record(userId, GAME, win, ratingDeltas.get(userId), p.deserted(), eloFactor);
            }
        }

        log.info("Star square match recorded: room={} matchId={} reason={} winners={} eloApplied={} ratingDeltas={}",
                event.roomId(), matchId, result.reason(), winners, rated, ratingDeltas);
    }

    private String payload(MatchResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize star square match payload", e);
        }
    }
}
