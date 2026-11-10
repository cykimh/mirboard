package com.mirboard.domain.game.yacht.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.yacht.YachtGameDefinition;
import com.mirboard.domain.game.yacht.event.YachtMatchCompleted;
import com.mirboard.domain.game.yacht.state.MatchResult;
import com.mirboard.domain.game.yacht.state.MatchResult.Placement;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.UserRepository;
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
 * 요트 매치 종료를 {@code yacht_match_results/participants} 에 적재하고 게임별 전적(YACHT 행)을 갱신한다 (S3). 원카드
 * 기록기와 같은 규칙: 봇·게스트가 낀 매치는 승패만, ELO 는 제외({@link RatedMatchPolicy}). 탈주자는 패 + 탈주 수.
 *
 * <p><b>1인 매치는 결과·참가자·전적을 쓰지 않는다</b>(D-181) — 셋 중 일부만 쓰면 게스트 정리 계약(`docs/game-port.md`
 * "매치 기록기 계약" 2)이 깨지고, 상대 없는 판의 승패는 뜻이 없다. 1인 + 봇은 2인 이상 매치다. 대신 끝까지 친 판({@code FINISHED})의
 * 총점만 개인 최고 점수용 {@code yacht_solo_scores} 에 남긴다(D-201) — 게스트·봇은 쓰지 않는다.
 *
 * <p>승리는 1위다 — 동순위면 모두(룰 §8.2). ELO 는 개인전 쌍대 방식({@link EloCalculator#applyFreeForAll})에 순위를 점수로
 * 넘긴다(점수 = 좌석 수 − 순위).
 */
@Component
public class YachtMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(YachtMatchRecorder.class);
    private static final String GAME = YachtGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public YachtMatchRecorder(JdbcTemplate jdbc,
                              UserGameStatsService stats,
                              BotUserRegistry bots,
                              RatedMatchPolicy ratedMatchPolicy,
                              UserRepository users,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.jdbc = jdbc;
        this.stats = stats;
        this.bots = bots;
        this.ratedMatchPolicy = ratedMatchPolicy;
        this.users = users;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onMatchCompleted(YachtMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        MatchResult result = event.result();
        if (playerIds.size() < 2) {
            recordSolo(event);
            return;
        }
        Map<Integer, Placement> bySeat = new HashMap<>();
        result.placements().forEach(p -> bySeat.put(p.seat(), p));
        boolean rated = ratedMatchPolicy.eloApplies(playerIds);

        Long matchId = jdbc.queryForObject(
                "INSERT INTO yacht_match_results (room_id, finished_at, end_reason, payload_json)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), result.reason().name(), payload(result));

        // ELO 입력은 갱신 전 전적으로 만든다 (K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> newRatings = Map.of();
        if (rated) {
            List<EloCalculator.Placement> placements = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                long userId = playerIds.get(seat);
                Placement p = bySeat.get(seat);
                GameStats current = stats.get(userId, GAME);
                placements.add(new EloCalculator.Placement(
                        new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed()),
                        playerIds.size() - p.rank(),
                        p.deserted()));
            }
            newRatings = EloCalculator.applyFreeForAll(placements);
        }

        List<Integer> winners = result.winners();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            Placement p = bySeat.get(seat);
            boolean win = winners.contains(seat);
            jdbc.update("INSERT INTO yacht_match_participants"
                            + " (match_id, user_id, seat, final_rank, total_score, is_win, deserted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, p.rank(), p.total(), win, p.deserted());
            // 봇 계정 자신은 전적을 쌓지 않는다 (랭킹 대상 아님).
            if (!bots.isBot(userId)) {
                stats.record(userId, GAME, win, newRatings.get(userId), p.deserted());
            }
        }

        log.info("Yacht match recorded: room={} matchId={} reason={} winners={} eloApplied={} ratings={}",
                event.roomId(), matchId, result.reason(), winners, rated, newRatings);
    }

    /** D-201 — 혼자 끝까지 친 판의 총점만. 게스트·봇·중간에 끝난 판(나가기 등)은 남기지 않는다. */
    private void recordSolo(YachtMatchCompleted event) {
        MatchResult result = event.result();
        Integer total = result.placements().isEmpty() ? null : result.placements().get(0).total();
        long userId = event.playerIds().get(0);
        boolean keep = result.reason() == MatchResult.EndReason.FINISHED && total != null
                && !bots.isBot(userId) && !users.findById(userId).map(u -> GuestPolicy.isGuest(u.getUsername())).orElse(true);
        if (keep) {
            jdbc.update("INSERT INTO yacht_solo_scores (user_id, room_id, total_score, finished_at) VALUES (?, ?, ?, ?)",
                    userId, event.roomId(), total, Timestamp.from(Instant.now(clock)));
        }
        log.info("Yacht solo match: room={} reason={} total={} bestScoreKept={}", event.roomId(), result.reason(), total,
                keep);
    }

    private String payload(MatchResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize yacht match payload", e);
        }
    }
}
