package com.mirboard.domain.game.tichu.state;

import com.mirboard.domain.game.tichu.hand.Hand;
import java.util.List;
import java.util.Map;

/**
 * 모든 플레이어에게 공개되는 상태 스냅샷. 본인 손패 카드는 절대 포함하지 않고
 * 장수만 노출. {@link PrivateHand} 와 함께 직렬화 시점에서 엄격히 분리된다.
 *
 * <p>Phase 5b 에서 단계별 정보 (phase, dealingCardCount, readySeats,
 * passingSubmittedSeats) 가 추가되었고, Phase 5c 에서 매치 누적 정보 (roundNumber,
 * matchScores) 가 추가되었다. 손패 카드는 여전히 비공개.
 *
 * <p>D-108 에서 끝난 라운드들의 점수 {@link CompletedRound} 가 추가되었다. 이름이
 * {@code roundScores} 가 아닌 이유는 그 이름이 이미 <b>현재 라운드</b>의 팀별 점수로
 * 쓰이고 있기 때문이다 — 재사용하면 의미가 조용히 뒤집힌다.
 */
public record TableView(
        String phase,
        int dealingCardCount,
        List<Integer> readySeats,
        List<Integer> passingSubmittedSeats,
        int currentTurnSeat,
        Map<Integer, Integer> handCounts,
        Hand currentTop,
        int currentTopSeat,
        Map<Integer, TichuDeclaration> declarations,
        Map<Team, Integer> roundScores,
        Map<Team, Integer> matchScores,
        int roundNumber,
        List<Integer> finishingOrder,
        Integer activeWishRank,
        List<CompletedRound> completedRounds) {

    /**
     * 끝난 라운드 한 건의 공개 점수. 도메인 {@code scoring.RoundScore} 와 필드가 같지만
     * 별도 뷰 타입으로 두는 이유는 <b>패키지 순환을 막기 위해서다</b> — {@code scoring} 이
     * 이미 {@code state} 를 의존하므로(RoundScore → Team), 여기서 {@code scoring} 을
     * 되짚으면 state ↔ scoring 순환이 된다. 변환은 양쪽을 모두 아는
     * {@code TichuGameEngine} 이 맡는다.
     */
    public record CompletedRound(int teamAScore,
                                 int teamBScore,
                                 int firstFinisherSeat,
                                 boolean doubleVictory) {
    }

    public TableView {
        readySeats = List.copyOf(readySeats);
        passingSubmittedSeats = List.copyOf(passingSubmittedSeats);
        handCounts = Map.copyOf(handCounts);
        declarations = Map.copyOf(declarations);
        roundScores = Map.copyOf(roundScores);
        matchScores = Map.copyOf(matchScores);
        finishingOrder = List.copyOf(finishingOrder);
        // 구 JSON / 미지정 호출 호환 — null 을 빈 목록으로 정규화(CLAUDE.md record 규약).
        completedRounds = completedRounds == null ? List.of() : List.copyOf(completedRounds);
    }
}
