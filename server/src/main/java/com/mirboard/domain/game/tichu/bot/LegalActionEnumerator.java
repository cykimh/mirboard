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
 * <p>두 진입점의 역할 분담 (D-118):
 * <ul>
 *   <li>{@link #enumerate} — 포트 {@code legalActions}·{@link TimeoutActionPolicy}·
 *       {@link RandomBotPolicy} 가 쓰는 <b>좁은</b> 후보. PlayCard 는 손패 단일 카드(1장) +
 *       동일 rank 페어(2장) + 트리플(3장)뿐이고, Dealing 은 Ready 하나다. 이 계약(후보 분포·
 *       타임아웃 자동 플레이)은 그대로 둔다.</li>
 *   <li>{@link #enumerateFull} — {@link HeuristicBotPolicy} 전용 <b>넓은</b> 후보. 위 후보에
 *       {@link ComboFinder} 조합(봉황 페어·트리플, 풀하우스, 스트레이트, 연속페어, 폭탄)과
 *       선언(그랜드티츄·티츄)을 더해 같은 {@link ActionValidator} 로 거른다.</li>
 * </ul>
 * ActionValidator 가 canBeat 검사로 부적합 카드를 자동 제외.
 */
public final class LegalActionEnumerator {

    private LegalActionEnumerator() {
    }

    public static List<TichuAction> enumerate(TichuState state, int seat) {
        return validOnly(state, seat, candidates(state, seat));
    }

    /**
     * D-118 — 휴리스틱 봇용 넓은 후보. 용 양도가 보류 중이면 (완주한 좌석이어도) 양도
     * 후보만 돌려준다. 같은 카드 집합이 중복되지 않도록 {@link ComboFinder} 에서는
     * {@link #candidates} 가 만들지 않는 묶음(4장 이상, 봉황이 낀 페어·트리플)만 더한다.
     * 마작 소원 변형은 기존 후보의 단독 마작에만 붙는다 — 조합 속 마작의 소원은 봇이 직접
     * 붙이고 검증한다.
     */
    static List<TichuAction> enumerateFull(TichuState state, int seat) {
        List<TichuAction> raw = new ArrayList<>(candidates(state, seat));
        switch (state) {
            case TichuState.Dealing d -> {
                if (d.phaseCardCount() == 8) raw.add(new TichuAction.DeclareGrandTichu());
                if (d.phaseCardCount() == 14) raw.add(new TichuAction.DeclareTichu());
            }
            case TichuState.Playing pl -> {
                if (isDragonGivePending(pl.trick(), seat)) break;   // 양도만.
                PlayerState me = pl.players().get(seat);
                if (pl.trick().currentTurnSeat() == seat && !me.isFinished()) {
                    long hand = HandPlanner.mask(me.hand());
                    long[] combos = pl.trick().isLead()
                            ? ComboFinder.leadMasks(hand)
                            : ComboFinder.followMasks(hand, pl.trick().currentTop());
                    for (long m : combos) {
                        int size = Long.bitCount(m);
                        if (size >= 4 || (size >= 2 && (m & HandPlanner.PHOENIX) != 0)) {
                            raw.add(new TichuAction.PlayCard(HandPlanner.cards(m)));
                        }
                    }
                }
                raw.add(new TichuAction.DeclareTichu());
            }
            case TichuState.Passing __ -> { }
            case TichuState.RoundEnd __ -> { }
        }
        return validOnly(state, seat, raw);
    }

    private static List<TichuAction> validOnly(TichuState state, int seat, List<TichuAction> raw) {
        List<TichuAction> legal = new ArrayList<>(raw.size());
        for (TichuAction action : raw) {
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
            // 소원 없는 변형을 항상 **먼저** 넣는다 — TimeoutActionPolicy 가 동률에서
            // Stream.min 의 "먼저 온 것 유지" 성질로 고르므로, 이 순서라야 타임아웃
            // 자동 플레이가 D-109 이전과 똑같이 "소원 없이 마작" 으로 남는다.
            result.add(new TichuAction.PlayCard(List.of(c)));
            if (c.is(Special.MAHJONG)) {
                // 마작을 내는 액션에 소원을 동봉할 수 있다 (D-109). 봇은 휴리스틱 없이
                // 균등 후보 — RandomBotPolicy 가 이 중에서 고른다.
                for (int r = 2; r <= 14; r++) {
                    result.add(new TichuAction.PlayCard(List.of(c), r));
                }
            }
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
