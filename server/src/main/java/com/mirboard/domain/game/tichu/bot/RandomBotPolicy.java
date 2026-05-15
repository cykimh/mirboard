package com.mirboard.domain.game.tichu.bot;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.List;
import java.util.Random;

/**
 * Phase 9C — 시드 가능한 랜덤 봇. 합법 액션 중 하나를 균등 분포로 선택.
 *
 * <p>단순화 휴리스틱: PlayCard 후보가 여러 개면 50% 확률로 PassTrick 선호 → 손패 보존
 * 효과 (실제로는 random 이 결국 PlayCard 도 충분히 고름).
 */
public final class RandomBotPolicy {

    private final Random random;

    public RandomBotPolicy(Random random) {
        this.random = random;
    }

    public RandomBotPolicy(long seed) {
        this(new Random(seed));
    }

    /**
     * @return null 이면 봇이 행동할 게 없음 (다른 좌석 차례 / RoundEnd).
     *
     * <p>전략: 합법 액션 균등 분포 선택. 단, 손패 소진 압력을 위해 PlayCard 후보가
     * 있을 때 PassTrick 가중치는 1/(N+1) 로 낮춤 (N = PlayCard 후보 수). 즉 PlayCard
     * 가 많을수록 패스 확률 ↓ — 라운드 종료 보장.
     */
    public TichuAction choose(TichuState state, int seat) {
        List<TichuAction> legal = LegalActionEnumerator.enumerate(state, seat);
        if (legal.isEmpty()) return null;
        if (legal.size() == 1) return legal.get(0);

        long playCount = legal.stream().filter(a -> a instanceof TichuAction.PlayCard).count();
        boolean hasPassTrick = legal.stream().anyMatch(a -> a instanceof TichuAction.PassTrick);
        if (playCount > 0 && hasPassTrick) {
            // PlayCard 들 + PassTrick 1개 → 같은 풀에서 균등 선택하면 PassTrick 비율은
            // 자연스럽게 1/(playCount+1). 손패 많을수록 play 가중치 ↑.
            return legal.get(random.nextInt(legal.size()));
        }
        return legal.get(random.nextInt(legal.size()));
    }
}
