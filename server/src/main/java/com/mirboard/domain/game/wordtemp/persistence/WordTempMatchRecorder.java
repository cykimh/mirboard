package com.mirboard.domain.game.wordtemp.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.wordtemp.WordTempGameDefinition;
import com.mirboard.domain.game.wordtemp.event.WordTempMatchCompleted;
import com.mirboard.domain.game.wordtemp.state.WordTempState.MatchResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Standing;
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
 * 낱말 온도 매치 종료를 {@code word_temp_match_results/participants}(V25)에 적재하고 게임별 전적(WORD_TEMP 행)·통합 레이팅을 갱신한다
 * (S2, 룰 §13). 다른 개인전 기록기와 같은 규칙: 배율은 {@link RatedMatchPolicy#eloFactor}(봇 판 1/4 — D-299), ELO 입력은
 * {@link UserGameStatsService#ratingInput}(통합 레이팅 — D-305), 증감은 {@link EloCalculator#freeForAllDeltas}, 쓰기는
 * {@code record}/{@code recordDraw} 한 곳(참가 점수 D-301 도 거기서). 탈주자는 패 + 탈주 수.
 *
 * <p>승 = 공동 1위 전원(빠진 사람 제외). 빠진 사람 없이 모두 같은 점수면 무승부({@code recordDraw} — ELO 변화 없음). 일부만 동점이면
 * 같은 순위끼리는 비긴 것으로 친다(점수 = 좌석 수 − 순위 — 같은 순위는 같은 점수).
 *
 * <p>결과 요약(payload)에는 <b>공개된 것만</b> 남긴다 — 출제 차례별 정답·공개 힌트·온도·맞힌 좌석, 표 버전. 오답 원문·안 쓴 후보는 없다.
 */
@Component
public class WordTempMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(WordTempMatchRecorder.class);
    private static final String GAME = WordTempGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WordTempMatchRecorder(JdbcTemplate jdbc,
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
    public void onMatchCompleted(WordTempMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        MatchResult result = event.result();
        boolean draw = result.draw();
        double eloFactor = ratedMatchPolicy.eloFactor(playerIds);
        boolean rated = !draw && eloFactor > RatedMatchPolicy.UNRATED;

        Long matchId = jdbc.queryForObject(
                "INSERT INTO word_temp_match_results (room_id, finished_at, end_reason, payload_json)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), result.endReason().name(), payload(event));

        // ELO 입력은 갱신 전 전적으로 만든다(K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> ratingDeltas = Map.of();
        if (rated) {
            List<EloCalculator.Placement> placements = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                Standing standing = result.standings().get(seat);
                placements.add(new EloCalculator.Placement(
                        stats.ratingInput(playerIds.get(seat)),
                        playerIds.size() - standing.rank(),
                        standing.out()));
            }
            ratingDeltas = EloCalculator.freeForAllDeltas(placements, eloFactor);
        }

        Map<Long, String> outcome = new LinkedHashMap<>();
        // D-305 — userId 오름차순(통합 행 잠금 순서를 매치마다 같게 — 동시에 끝난 두 판의 교착 방지).
        for (int seat : UserGameStatsService.seatsInUserIdOrder(playerIds)) {
            long userId = playerIds.get(seat);
            Standing standing = result.standings().get(seat);
            boolean win = !draw && !standing.out() && standing.rank() == 1;
            jdbc.update("INSERT INTO word_temp_match_participants"
                            + " (match_id, user_id, seat, final_rank, total_points, setter_points, guess_points,"
                            + " is_win, deserted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, standing.rank(), standing.total(), standing.setterPoints(),
                    standing.guessPoints(), win, standing.out());
            if (bots.isBot(userId)) {
                continue;                                   // 봇 계정 자신은 전적을 쌓지 않는다(랭킹 대상 아님)
            }
            if (draw) {
                stats.recordDraw(userId, GAME, eloFactor);
            } else {
                stats.record(userId, GAME, win, ratingDeltas.get(userId), standing.out(), eloFactor);
            }
            outcome.put(userId, draw ? "draw" : win ? "win" : standing.out() ? "deserted" : "lose");
        }

        log.info("Word temperature match recorded: room={} matchId={} reason={} outcome={} eloApplied={} ratingDeltas={}",
                event.roomId(), matchId, result.endReason(), outcome, rated, ratingDeltas);
    }

    private String payload(WordTempMatchCompleted event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tableVersion", event.tableVersion());
        body.put("draw", event.result().draw());
        body.put("standings", event.result().standings());
        body.put("turns", event.turns());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize word temperature match payload", e);
        }
    }
}
