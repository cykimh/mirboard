package com.mirboard.domain.game.tichu.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 매치(여러 라운드 합산) 가 종료되면 {@link TichuMatchCompleted} 를 받아
 * {@code tichu_match_results} + {@code tichu_match_participants} 에 1행씩 적재하고
 * 각 유저의 {@code win_count}/{@code lose_count} 를 증분. 라운드별 점수는 payload_json
 * 의 roundScores 배열로 같이 저장.
 */
@Component
public class MatchResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(MatchResultRecorder.class);

    private final TichuMatchResultRepository matchRepo;
    private final TichuMatchParticipantRepository participantRepo;
    private final UserRepository userRepo;
    private final BotUserRegistry bots;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MatchResultRecorder(TichuMatchResultRepository matchRepo,
                               TichuMatchParticipantRepository participantRepo,
                               UserRepository userRepo,
                               BotUserRegistry bots,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.matchRepo = matchRepo;
        this.participantRepo = participantRepo;
        this.userRepo = userRepo;
        this.bots = bots;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onMatchCompleted(TichuMatchCompleted event) {
        // Phase 16(#4) — 봇 포함 매치도 win/lose·match_result 는 기록하되 ELO(rating)
        // 만 제외 (rating 인플레이션 방지). 사람 4인 매치만 ELO 반영.
        boolean hasBots = event.playerIds().stream().anyMatch(bots::isBot);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of(
                    "cumulativeA", event.cumulativeTeamAScore(),
                    "cumulativeB", event.cumulativeTeamBScore(),
                    "winningTeam", event.winningTeam().name(),
                    "roundScores", event.roundScores(),
                    "stake", event.stake())); // D-81 — 칩 정산 감사용.
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
            Optional<User> u = userRepo.findById(userId);
            int rating = u.map(User::getRating).orElse(1000);
            int games = u.map(usr -> usr.getWinCount() + usr.getLoseCount()).orElse(0);
            var input = new EloCalculator.PlayerInput(userId, rating, games);
            if (Team.ofSeat(seat) == Team.A) teamAInput.add(input);
            else teamBInput.add(input);
        }
        // 봇 포함 매치는 ELO 미적용 — 빈 맵이면 아래 updateRating 이 skip.
        Map<Long, Integer> newRatings = hasBots
                ? Map.of()
                : EloCalculator.applyMatch(teamAInput, teamBInput, winner == Team.A);

        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            Team team = Team.ofSeat(seat);
            boolean isWin = team == winner;
            participantRepo.save(new TichuMatchParticipant(
                    result.getId(), userId, team.name(), isWin));
            if (isWin) {
                userRepo.incrementWinCount(userId);
            } else {
                userRepo.incrementLoseCount(userId);
            }
            // D-81 — 가상 칩 정산(판돈>0, 사람 매치만 — 봇 매치는 ELO 처럼 제외).
            // 승팀 +판돈 / 패팀 −판돈(0 까지만 차감해 음수 방지). 같은 트랜잭션 → 원자적.
            if (event.stake() > 0 && !hasBots) {
                if (isWin) {
                    userRepo.incrementChip(userId, event.stake());
                } else {
                    userRepo.decrementChipCapped(userId, event.stake());
                }
            }
            Integer newRating = newRatings.get(userId);
            if (newRating != null) {
                userRepo.updateRating(userId, newRating);
            }
        }

        // Phase 19(#3, D-75) — 게임중 탈주로 종료된 매치면 탈주자 desert_count 도
        // 같은 트랜잭션에서 증분 (win/lose/ELO 는 위 winner 기준으로 이미 반영).
        if (event.deserterUserId() != null) {
            userRepo.incrementDesertCount(event.deserterUserId());
            log.info("Desertion recorded: room={} deserterUserId={}",
                    event.roomId(), event.deserterUserId());
        }

        log.info("Match recorded: room={}, winner={}, A={}/B={}, rounds={}, eloApplied={}, ratings={}, stake={}, chipsSettled={}",
                event.roomId(), winner,
                event.cumulativeTeamAScore(), event.cumulativeTeamBScore(),
                event.roundScores().size(), !hasBots, newRatings,
                event.stake(), event.stake() > 0 && !hasBots);
    }
}
