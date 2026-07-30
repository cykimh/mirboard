package com.mirboard.domain.game.skullking.trick;

import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import java.util.List;
import java.util.Optional;

/**
 * 리드 수트 <b>지연 확정</b> (`docs/rules-skullking.md` §6.1, §13-⑤).
 *
 * <p>명세 함정 #1: <i>"{@code leadSuit = trick.cards[0].suit} 로 구현하면 틀린다.
 * 리드 수트는 트릭이 진행되는 도중에 확정될 수 있고, 끝까지 확정되지 않을 수도 있다.
 * 즉 합법수 집합이 트릭 진행 중에 바뀐다."</i>
 *
 * <p><b>저장하지 않고 매번 파생한다 (D-101).</b> {@code TrickState} 의 필드로 들고 있으면
 * 카드를 낼 때마다 갱신해야 하고 한 경로만 빠뜨려도 stale 해진다. 트릭당 카드가 최대
 * 8장이라 매번 훑어도 비용이 없다.
 *
 * <p>세 갈래 (§6.1 표):
 * <ul>
 *   <li>첫 카드가 <b>캐릭터</b> → 그 트릭엔 리드 수트가 <b>영구히 없다</b></li>
 *   <li>첫 카드가 <b>색상</b> → 그 색으로 즉시 확정</li>
 *   <li>첫 카드가 <b>탈출</b> → 확정 보류. 그 뒤 처음 나오는 색상 카드의 색으로 확정되며,
 *       그 사이에 캐릭터가 끼어도 미확정으로 남는다 (§13-⑤)</li>
 * </ul>
 *
 * <p>"영구히 없음"과 "아직 미확정"을 둘 다 {@link Optional#empty()} 로 돌려주는 것은
 * 의도적이다 — follow 의무 관점에서 두 상태는 완전히 같다(제약 없음). 두 상태가 갈리는
 * 것은 <b>이후 색상 카드가 나왔을 때</b>뿐이고, 그 차이는 첫 카드를 다시 보는 것으로
 * 매번 올바르게 재현된다.
 */
public final class LeadSuitResolver {

    private LeadSuitResolver() {
    }

    /**
     * 현재까지 제출된 카드로부터 리드 수트를 판정한다.
     *
     * @return 확정된 리드 수트. 캐릭터 리드(영구 없음) 또는 아직 색상 카드가 나오지
     *         않은 경우 empty — 두 경우 모두 follow 의무가 없다
     */
    public static Optional<SkullSuit> resolve(List<PlayedCard> played) {
        if (played.isEmpty()) {
            return Optional.empty();
        }
        // 캐릭터로 리드 → 이후 무엇이 나오든 리드 수트는 생기지 않는다.
        if (played.get(0).kind().isCharacter()) {
            return Optional.empty();
        }
        // 첫 카드는 색상 아니면 탈출. 처음 나온 색상 카드가 리드 수트를 확정한다
        // (첫 카드가 색상이면 그 자리에서 확정, 탈출이면 뒤로 밀린다).
        for (PlayedCard pc : played) {
            if (pc.card().isSuit()) {
                return Optional.of(pc.card().suit());
            }
        }
        return Optional.empty();
    }
}
