package com.mirboard.domain.game.tichu.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.tichu.event.TichuRoundCompleted;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * `TichuRoundCompleted` ApplicationEvent 를 받아 매치 결과를 영속화 + 유저 전적
 * 증분한다. 라운드 점수 자체는 RoundScore 가 결정한 winning team 기준 — 동점은
 * 양 팀 모두 패배(현실에서는 드물지만 데이터 일관성 보호).
 */
@Component
public class MatchResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(MatchResultRecorder.class);

    private final TichuMatchResultRepository matchRepo;
    private final TichuMatchParticipantRepository participantRepo;
    private final UserRepository userRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MatchResultRecorder(TichuMatchResultRepository matchRepo,
                               TichuMatchParticipantRepository participantRepo,
                               UserRepository userRepo,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.matchRepo = matchRepo;
        this.participantRepo = participantRepo;
        this.userRepo = userRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onRoundCompleted(TichuRoundCompleted event) {
        RoundScore score = event.score();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(score);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize RoundScore", e);
        }

        TichuMatchResult result = matchRepo.save(new TichuMatchResult(
                event.roomId(),
                Instant.now(clock),
                score.teamAScore(),
                score.teamBScore(),
                payloadJson));

        Team winner = winnerOf(score);
        List<Long> playerIds = event.playerIds();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            Team team = Team.ofSeat(seat);
            boolean isWin = winner != null && team == winner;
            participantRepo.save(new TichuMatchParticipant(
                    result.getId(), userId, team.name(), isWin));
            if (winner == null) {
                continue; // tie — neither increments
            }
            if (isWin) {
                userRepo.incrementWinCount(userId);
            } else {
                userRepo.incrementLoseCount(userId);
            }
        }

        log.info("Match recorded: room={}, winner={}, A={}/B={}",
                event.roomId(), winner, score.teamAScore(), score.teamBScore());
    }

    private static Team winnerOf(RoundScore score) {
        if (score.teamAScore() > score.teamBScore()) return Team.A;
        if (score.teamBScore() > score.teamAScore()) return Team.B;
        return null;  // tie
    }
}
