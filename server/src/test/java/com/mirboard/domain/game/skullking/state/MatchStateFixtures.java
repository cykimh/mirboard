package com.mirboard.domain.game.skullking.state;

import com.mirboard.domain.game.skullking.scoring.RoundScore;
import java.util.HashMap;
import java.util.Map;

/**
 * 테스트 전용 매치 상태 빨리감기 (D-120).
 *
 * <p>운영 코드의 {@code withRoundScored(Map<Integer,Integer>, int)} 는 라운드 기록을 남기지
 * 않는 두 번째 공개 경로라 삭제했다. 점수 증분만 필요한 테스트는 이 픽스처로
 * {@link SkullKingMatchState#withRoundCompleted} 를 탄다 — 그래서 픽스처로 빨리감은 매치도
 * {@code completedRounds} 가 라운드 수만큼 쌓인다.
 */
public final class MatchStateFixtures {

    private MatchStateFixtures() {
    }

    /**
     * 현재 라운드를 좌석별 증분 {@code deltas} 로 정산한다. 좌석마다 {@code RoundScore(0, 0, d, 0)}
     * — 예측 0·획득 0 이라 적중이므로 §11 가드(실패 시 보너스 0)에 걸리지 않고, total = d.
     */
    public static SkullKingMatchState scored(SkullKingMatchState match,
                                             Map<Integer, Integer> deltas,
                                             int seatCount) {
        Map<Integer, RoundScore> scores = new HashMap<>();
        deltas.forEach((seat, delta) -> scores.put(seat, new RoundScore(0, 0, delta, 0)));
        return match.withRoundCompleted(match.roundNumber(), scores, seatCount);
    }
}
