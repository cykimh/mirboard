package com.mirboard.domain.game.tichu.bot;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 13D(#6) — 턴 타임아웃 시 자동으로 취할 "안전한" 액션을 결정적으로 선택.
 *
 * <p>{@link LegalActionEnumerator} 가 산출한 합법 액션 중 우선순위로 1개 고른다.
 * RandomBotPolicy 와 달리 무작위성 없음 (재현 가능):
 * <ol>
 *   <li>GiveDragonTrick — Dragon 트릭 양도 보류 해소 (상대팀 첫 좌석)</li>
 *   <li>Ready — Dealing 단계</li>
 *   <li>PassCards — Passing 단계 (enumerator 가 3장 조합 1개 제시)</li>
 *   <li>PassTrick — Playing, 리드가 아니면 가장 안전 (손패 보존)</li>
 *   <li>PlayCard — 리드라 패스 불가하면 가장 약한 단일 카드</li>
 * </ol>
 * 합법 액션이 하나도 없으면 null (스케줄러가 로깅 후 종료 — 데드락 방어는
 * BotMatch 시뮬레이션/엔진 불변식이 별도 보장).
 */
public final class TimeoutActionPolicy {

    private TimeoutActionPolicy() {
    }

    public static TichuAction choose(TichuState state, int seat) {
        List<TichuAction> legal = LegalActionEnumerator.enumerate(state, seat);
        if (legal.isEmpty()) return null;

        TichuAction give = first(legal, TichuAction.GiveDragonTrick.class);
        if (give != null) return give;

        TichuAction ready = first(legal, TichuAction.Ready.class);
        if (ready != null) return ready;

        TichuAction pass = first(legal, TichuAction.PassCards.class);
        if (pass != null) return pass;

        TichuAction passTrick = first(legal, TichuAction.PassTrick.class);
        if (passTrick != null) return passTrick;

        // 리드 차례 등 — 가장 약한 단일 카드 PlayCard.
        return legal.stream()
                .filter(a -> a instanceof TichuAction.PlayCard pc && pc.cards().size() == 1)
                .min(Comparator.comparingInt(a -> weakest((TichuAction.PlayCard) a)))
                .orElse(legal.get(0));
    }

    private static <T extends TichuAction> TichuAction first(List<TichuAction> legal, Class<T> type) {
        return legal.stream().filter(type::isInstance).findFirst().orElse(null);
    }

    /** 단일 카드 PlayCard 의 정렬 키: 일반 rank, Mahjong=1, Dog=0, Phoenix=0, Dragon=100. */
    private static int weakest(TichuAction.PlayCard pc) {
        Card c = pc.cards().get(0);
        return c.rank();
    }
}
