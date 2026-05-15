package com.mirboard.domain.game.tichu.bot;

import com.mirboard.domain.game.tichu.action.ActionValidator;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.action.TichuActionRejectedException;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 9C — 봇이 자기 차례 / 결정 지점에서 시도할 후보 액션 enumerate.
 *
 * <p>접근: phase 별로 후보 목록 생성 → {@link ActionValidator} 통과한 것만 합법으로 분류.
 * 합법성 검사 로직을 봇이 직접 복제하지 않고 엔진의 단일 소스에 위임한다.
 *
 * <p>단순화 가정: PlayCard 후보는 손패 단일 카드(1장) + 동일 rank 페어(2장) + 동일 rank
 * 트리플(3장). 폭탄/스트레이트/풀하우스 등은 1차 enumerate 안 함 — random 봇이 패스를
 * 선호하면 게임은 결국 진행되며, 향후 휴리스틱에서 확장 예정. ActionValidator 가
 * canBeat 검사로 부적합 카드를 자동 제외.
 */
public final class LegalActionEnumerator {

    private LegalActionEnumerator() {
    }

    public static List<TichuAction> enumerate(TichuState state, int seat) {
        List<TichuAction> candidates = candidates(state, seat);
        List<TichuAction> legal = new ArrayList<>();
        for (TichuAction action : candidates) {
            try {
                ActionValidator.validate(state, seat, action);
                legal.add(action);
            } catch (TichuActionRejectedException ignored) {
                // not legal in this state — drop silently.
            }
        }
        return legal;
    }

    private static List<TichuAction> candidates(TichuState state, int seat) {
        return switch (state) {
            case TichuState.Dealing __ -> List.of(new TichuAction.Ready());
            case TichuState.Passing p -> passingCandidates(p, seat);
            case TichuState.Playing pl -> playingCandidates(pl, seat);
            case TichuState.RoundEnd __ -> List.of();
        };
    }

    private static List<TichuAction> passingCandidates(TichuState.Passing p, int seat) {
        if (p.submitted().containsKey(seat)) return List.of();
        PlayerState me = p.players().get(seat);
        List<Card> hand = me.hand();
        if (hand.size() < 3) return List.of();
        // 첫 3장 결정적 선택 (deterministic). PolicyTest 에서 시드 재현 가능하도록.
        // 랭크 다양성을 위해 손패 size 1/3, 2/3 인덱스 + 가장 작은 1장을 보낸다.
        Card toLeft = hand.get(0);
        Card toPartner = hand.get(hand.size() / 2);
        Card toRight = hand.get(hand.size() - 1);
        if (toLeft.equals(toPartner) || toLeft.equals(toRight) || toPartner.equals(toRight)) {
            // 같은 카드 충돌 — fallback: 손에서 처음 3장.
            toLeft = hand.get(0);
            toPartner = hand.get(1);
            toRight = hand.get(2);
        }
        return List.of(new TichuAction.PassCards(toLeft, toPartner, toRight));
    }

    private static List<TichuAction> playingCandidates(TichuState.Playing pl, int seat) {
        TrickState trick = pl.trick();
        PlayerState me = pl.players().get(seat);

        // Dragon trick 양도가 본인에게 미뤄져 있는 경우 — 다른 액션 불가, GiveDragonTrick 만.
        if (isDragonGivePending(trick, seat)) {
            List<TichuAction> giveCandidates = new ArrayList<>();
            for (int s = 0; s < 4; s++) {
                if (Team.ofSeat(s) != Team.ofSeat(seat)) {
                    giveCandidates.add(new TichuAction.GiveDragonTrick(s));
                }
            }
            return giveCandidates;
        }

        // 내 차례가 아니면 — 폭탄 인터럽트도 1차에선 시도 안 함 (단순 봇).
        if (trick.currentTurnSeat() != seat) return List.of();

        if (me.isFinished() || me.hand().isEmpty()) return List.of();

        List<TichuAction> result = new ArrayList<>();

        // 1장 단일 플레이.
        for (Card c : me.hand()) {
            result.add(new TichuAction.PlayCard(List.of(c)));
        }

        // 동일 rank 페어 / 트리플 — 손패가 14장 이내라 O(n^3) 무시 가능.
        // 페어 / 트리플은 wish 강제 상황에서 PassTrick 이 막힐 때 합법 출구가 필요해서 포함.
        for (int i = 0; i < me.hand().size(); i++) {
            for (int j = i + 1; j < me.hand().size(); j++) {
                Card a = me.hand().get(i);
                Card b = me.hand().get(j);
                if (a.isNormal() && b.isNormal() && a.rank() == b.rank()) {
                    result.add(new TichuAction.PlayCard(List.of(a, b)));
                    for (int k = j + 1; k < me.hand().size(); k++) {
                        Card c = me.hand().get(k);
                        if (c.isNormal() && c.rank() == a.rank()) {
                            result.add(new TichuAction.PlayCard(List.of(a, b, c)));
                        }
                    }
                }
            }
        }

        // PassTrick (리드 트릭이면 ActionValidator 가 reject).
        result.add(new TichuAction.PassTrick());

        return result;
    }

    private static boolean isDragonGivePending(TrickState trick, int seat) {
        if (trick.currentTop() == null) return false;
        var top = trick.currentTop().cards();
        if (top.size() != 1) return false;
        return top.get(0).is(Special.DRAGON) && trick.currentTopSeat() == seat;
    }
}
