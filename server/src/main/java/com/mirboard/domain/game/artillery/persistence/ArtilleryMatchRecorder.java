package com.mirboard.domain.game.artillery.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.game.artillery.ArtilleryGameDefinition;
import com.mirboard.domain.game.artillery.event.ArtilleryMatchCompleted;
import com.mirboard.domain.game.artillery.state.MatchResult;
import com.mirboard.domain.game.artillery.state.MatchResult.Placement;
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
 * 씨앗포 매치 종료를 {@code artillery_match_results/participants} 에 적재하고 게임별 전적(ARTILLERY 행)을 갱신한다 (S2). 요트·원카드
 * 기록기와 같은 규칙: 봇·게스트가 낀 매치는 승패만, ELO 는 제외({@link RatedMatchPolicy}). 탈주자는 패 + 탈주 수.
 *
 * <p>승리는 1위다 — 동순위면 모두(룰 §9.3). ELO 는 개인전 쌍대 방식({@link EloCalculator#applyFreeForAll})에 순위를 점수로
 * 넘긴다(점수 = 좌석 수 − 순위). 2인 이상만 시작하므로 1인 판은 없다.
 *
 * <p>2:2 팀전(D-225)은 팀 평균 방식({@link EloCalculator#applyMatch} — 오목 2:2 와 같다). 이긴 팀의 탈주자는 팀과 함께 오르지 않고 상대 팀에
 * 혼자 진 것으로 따로 잰다. 무승부는 ELO·승패 변화 없이 행만({@link UserGameStatsService#recordDraw}) — 탈주자는 무승부여도 패.
 * 참가자 행의 {@code team}(V18)은 좌석 홀짝, 개인전은 NULL.
 */
@Component
public class ArtilleryMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(ArtilleryMatchRecorder.class);
    private static final String GAME = ArtilleryGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ArtilleryMatchRecorder(JdbcTemplate jdbc,
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
    public void onMatchCompleted(ArtilleryMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        MatchResult result = event.result();
        Map<Integer, Placement> bySeat = new HashMap<>();
        result.placements().forEach(p -> bySeat.put(p.seat(), p));
        boolean rated = ratedMatchPolicy.eloApplies(playerIds);

        Long matchId = jdbc.queryForObject(
                "INSERT INTO artillery_match_results (room_id, finished_at, end_reason, payload_json)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), result.reason().name(), payload(result));

        // ELO 입력은 갱신 전 전적으로 만든다 (K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> newRatings = Map.of();
        if (rated && result.teams()) {
            newRatings = teamRatings(playerIds, bySeat, result);
        } else if (rated) {
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
            jdbc.update("INSERT INTO artillery_match_participants"
                            + " (match_id, user_id, seat, final_rank, final_hp, is_win, deserted, team)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, p.rank(), p.hp(), win, p.deserted(), result.teams() ? seat % 2 : null);
            // 봇 계정 자신은 전적을 쌓지 않는다 (랭킹 대상 아님).
            if (!bots.isBot(userId)) {
                if (result.draw() && !p.deserted()) {
                    stats.recordDraw(userId, GAME);
                } else {
                    stats.record(userId, GAME, win, newRatings.get(userId), p.deserted());
                }
            }
        }

        log.info("Artillery match recorded: room={} matchId={} reason={} winners={} eloApplied={} ratings={}",
                event.roomId(), matchId, result.reason(), winners, rated, newRatings);
    }

    /** 팀 평균 ELO (D-225). 무승부면 탈주자만 바뀐다. */
    private Map<Long, Integer> teamRatings(List<Long> playerIds, Map<Integer, Placement> bySeat, MatchResult result) {
        List<List<EloCalculator.PlayerInput>> teams = List.of(new ArrayList<>(), new ArrayList<>());
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            GameStats current = stats.get(userId, GAME);
            teams.get(seat % 2).add(new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed()));
        }
        Map<Long, Integer> ratings = new HashMap<>();
        if (result.winnerTeam() != null) {
            ratings.putAll(EloCalculator.applyMatch(teams.get(0), teams.get(1), result.winnerTeam() == 0));
        }
        for (int seat = 0; seat < playerIds.size(); seat++) {
            if (!bySeat.get(seat).deserted() || (result.winnerTeam() != null && seat % 2 != result.winnerTeam())) {
                continue;   // 진 팀의 탈주자는 팀과 함께 이미 졌다
            }
            EloCalculator.PlayerInput me = teams.get(seat % 2).get(seat / 2);
            ratings.put(me.userId(),
                    EloCalculator.applyMatch(List.of(me), teams.get(1 - seat % 2), false).get(me.userId()));
        }
        return ratings;
    }

    private String payload(MatchResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize artillery match payload", e);
        }
    }
}
