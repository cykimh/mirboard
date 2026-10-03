package com.mirboard.domain.game.tichu.bot;

import static com.mirboard.domain.game.tichu.bot.HandPlanner.DOG;
import static com.mirboard.domain.game.tichu.bot.HandPlanner.DRAGON;
import static com.mirboard.domain.game.tichu.bot.HandPlanner.MAHJONG;
import static com.mirboard.domain.game.tichu.bot.HandPlanner.PHOENIX;
import static com.mirboard.domain.game.tichu.bot.HandPlanner.count;

import com.mirboard.domain.game.tichu.action.ActionValidator;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.action.TichuActionRejectedException;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.hand.HandType;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-118 — 티츄 봇 휴리스틱. 공개 정보({@link BotView})만 보고 {@link
 * LegalActionEnumerator#enumerateFull} 후보 중에서 고르는 <b>순수·결정적</b> 정책이다.
 * Random·시간·해시 순회를 쓰지 않는다 — 같은 상태면 언제나 같은 수를 둔다.
 *
 * <p><b>선택 의미론</b>: 모든 규칙은 "legal ∩ 규칙 술어"에서만 고르고, 비면 다음 규칙으로
 * 넘어간다. 그래서 소원 강제 중(소원 랭크를 쥔 리드)에는 개·최저 단일 같은 규칙이 저절로
 * 건너뛰어진다. 예외는 둘 — PassCards 는 직접 구성하고, 마작 플레이의 소원은 나중에 붙인다.
 * 둘 다 {@link #decide} 가 {@link ActionValidator} 로 다시 확인하고, 실패하면
 * {@link TimeoutActionPolicy} 로 폴백한다(fellBack).
 *
 * <p><b>결정 지점</b> (규칙 표는 {@code docs/rules-tichu.md} §16):
 * <ul>
 *   <li>선언 — 그랜드(보이는 8장), 티츄(패스 전은 매우 강할 때만, 보통은 패스 후 첫 차례)</li>
 *   <li>패스 — 파트너 몫을 먼저, 상대 둘에게는 손이 가장 덜 나빠지는 2장(약한 쪽이 왼쪽)</li>
 *   <li>리드 L1~L7, 팔로우 F0~F6, 폭탄, 소원, 용 양도, 봉황</li>
 * </ul>
 *
 * <p><b>엔진 현황 전제</b> (바뀌면 {@link #isBoss}·F3·F4 문턱을 다시 보정): (a) 용이 top 이 되면
 * 즉시 양도 대기라 아무도 응수 못 함 → 용 단독은 불패, (b) 용을 양도하면 활성 소원이 사라짐,
 * (c) 한 번 패스하면 그 트릭에 다시 못 들어옴 → 팔로우는 "이번이 마지막 기회"라는 전제,
 * (d) 용으로 라운드가 끝나면 양도 없이 용 주인이 가져감.
 */
public final class HeuristicBotPolicy {

    private static final Logger log = LoggerFactory.getLogger(HeuristicBotPolicy.class);

    // ---------- 튜닝 상수 (보정 근거: docs/plans/tichu-bot-heuristic.md) ----------

    /** 그랜드티츄: 보이는 8장의 power(용 + 봉황 + A 수 + 2×포카드) 문턱. 용 또는 봉황 필수. */
    static final int GRAND_POWER = 4;
    /** 패스 전(Dealing 14) 티츄: controls ≥ losers + 2 ∧ losers ≤ 2. */
    static final int EARLY_TICHU_MARGIN = 2;
    static final int EARLY_TICHU_MAX_LOSERS = 2;
    /** 패스 후(Playing 첫 차례) 티츄: controls ≥ losers + 1 ∧ losers ≤ 3. 상대 선언 시 각각 1 보수적으로. */
    static final int TICHU_MARGIN = 1;
    static final int TICHU_MAX_LOSERS = 3;
    /** 패스 후 티츄는 모든 상대가 이 장수 이상일 때만. */
    static final int TICHU_MIN_OPPONENT_CARDS = 10;
    /**
     * 자가대전 보정(D-118)으로 더한 보수 조건: 티츄는 컨트롤 3개 이상, 패스 후 선언은 비컨트롤
     * 묶음(groups − controls) 3개 이하. 초기 문턱만으로는 성공률 0.62 로 목표 0.70 미달.
     */
    static final int TICHU_MIN_CONTROLS = 3;
    static final int TICHU_MAX_OPEN_GROUPS = 3;
    /** 위협: 활성 상대가 이 장수 이하. */
    static final int THREAT_CARDS = 2;
    /** 팔로우에서 컨트롤을 쓸 만한 트릭 점수. */
    static final int CONTROL_POINTS = 15;
    /** 조합을 깨서라도 받을 만한 트릭 점수. */
    static final int BREAK_POINTS = 10;
    /** 폭탄을 쓸 만한 트릭 점수. */
    static final int BOMB_POINTS = 25;
    /** 폭탄: top 을 낸 상대가 이 장수 이하. */
    static final int BOMB_TOP_CARDS = 3;

    /** 기능 끄기 비교(평가 전용)를 위한 스위치. 운영은 항상 {@link #DEFAULT}. */
    record Tuning(boolean declarations, boolean partnerGuard, boolean bombs, boolean passing) {
        static final Tuning DEFAULT = new Tuning(true, true, true, true);
    }

    /** @param fellBack 제안이 검증을 통과하지 못해 타임아웃 정책으로 대신했는지 */
    record Decision(TichuAction action, boolean fellBack) {
    }

    private static final HeuristicBotPolicy DEFAULT = new HeuristicBotPolicy(Tuning.DEFAULT);
    private static final TichuAction PASS = new TichuAction.PassTrick();

    private final Tuning tuning;

    HeuristicBotPolicy(Tuning tuning) {
        this.tuning = tuning;
    }

    /** @return null 이면 그 좌석은 지금 할 게 없다 (다른 좌석 차례 / RoundEnd). */
    public static TichuAction choose(TichuState state, int seat) {
        return DEFAULT.decide(state, seat).action();
    }

    Decision decide(TichuState state, int seat) {
        List<TichuAction> legal = LegalActionEnumerator.enumerateFull(state, seat);
        if (legal.isEmpty()) return new Decision(null, false);
        TichuAction proposal = propose(BotView.of(state, seat), legal);
        if (proposal == null) return new Decision(null, false);
        try {
            ActionValidator.validate(state, seat, proposal);
            return new Decision(proposal, false);
        } catch (TichuActionRejectedException e) {
            log.warn("Heuristic bot proposal rejected, falling back: seat={} action={} reason={}",
                    seat, proposal, e.reason());
            return new Decision(TimeoutActionPolicy.choose(state, seat), true);
        }
    }

    /** 공개 정보와 합법 후보만으로 고른다. legal 의 순서와 무관하다. */
    TichuAction propose(BotView v, List<TichuAction> legal) {
        TichuAction give = giveDragon(v, legal);
        if (give != null) return give;
        return switch (v.phase()) {
            case DEALING -> dealing(v, legal);
            case PASSING -> passing(v, legal);
            case PLAYING -> playing(v, legal);
            case ROUND_END -> null;
        };
    }

    // ---------- 용 양도 (F0) ----------

    /**
     * 활성 상대 중 장수가 많은 쪽 — 4등이 될 가능성이 커서 그 트릭 점수가 1등 팀으로 넘어갈
     * 수 있다(ScoreCalculator). 동률이면 비선언자, 그다음 좌석 오름차순.
     */
    private static TichuAction giveDragon(BotView v, List<TichuAction> legal) {
        if (!v.dragonGiveMine()) return null;
        List<TichuAction> gives = filter(legal, a -> a instanceof TichuAction.GiveDragonTrick);
        if (gives.isEmpty()) return null;
        int a = Math.min(v.leftOpponent(), v.rightOpponent());
        int b = Math.max(v.leftOpponent(), v.rightOpponent());
        int to;
        if (v.active(a) != v.active(b)) {
            to = v.active(a) ? a : b;
        } else if (v.size(a) != v.size(b)) {
            to = v.size(a) > v.size(b) ? a : b;
        } else if (v.declared(a) != v.declared(b)) {
            to = v.declared(a) ? b : a;
        } else {
            to = a;
        }
        TichuAction choice = new TichuAction.GiveDragonTrick(to);
        if (gives.contains(choice)) return choice;
        return gives.stream()
                .min(Comparator.comparingInt(g -> ((TichuAction.GiveDragonTrick) g).toSeat()))
                .orElseThrow();
    }

    // ---------- 선언 (Dealing) ----------

    private TichuAction dealing(BotView v, List<TichuAction> legal) {
        boolean partnerQuiet = !v.declared(v.partner());
        if (tuning.declarations() && partnerQuiet) {
            if (v.dealingCardCount() == 8 && has(legal, TichuAction.DeclareGrandTichu.class)
                    && grandWorthy(v.hand())) {
                return new TichuAction.DeclareGrandTichu();
            }
            if (v.dealingCardCount() == 14 && has(legal, TichuAction.DeclareTichu.class)) {
                HandPlanner.Plan plan = HandPlanner.plan(v.hand());
                if (plan.controls() >= plan.losers() + EARLY_TICHU_MARGIN
                        && plan.losers() <= EARLY_TICHU_MAX_LOSERS
                        && plan.controls() >= TICHU_MIN_CONTROLS) {
                    return new TichuAction.DeclareTichu();
                }
            }
        }
        return has(legal, TichuAction.Ready.class) ? new TichuAction.Ready() : null;
    }

    /** power8 = 용 + 봉황 + A 수 + 2×포카드 ≥ {@link #GRAND_POWER} ∧ 용 또는 봉황. */
    static boolean grandWorthy(long hand) {
        boolean special = (hand & (DRAGON | PHOENIX)) != 0;
        int power = Long.bitCount(hand & (DRAGON | PHOENIX)) + count(hand, 14);
        for (int r = 2; r <= 14; r++) {
            if (count(hand, r) == 4) power += 2;
        }
        return special && power >= GRAND_POWER;
    }

    /** 패스 후 첫 차례 티츄 기준 (패스 단계에서 "선언할 손인가" 판단에도 쓴다). */
    private boolean tichuWorthy(BotView v, HandPlanner.Plan plan) {
        int bump = 0;
        for (int o : opponents(v)) {
            if (v.declared(o)) bump = 1;
        }
        return plan.controls() >= plan.losers() + TICHU_MARGIN + bump
                && plan.losers() <= TICHU_MAX_LOSERS - bump
                && plan.controls() >= TICHU_MIN_CONTROLS
                && plan.size() - plan.controls() <= TICHU_MAX_OPEN_GROUPS;
    }

    // ---------- 패스 ----------

    private TichuAction passing(BotView v, List<TichuAction> legal) {
        TichuAction fallback = legal.stream()
                .filter(a -> a instanceof TichuAction.PassCards)
                .findFirst().orElse(null);
        if (fallback == null) return null;
        long hand = v.hand();
        if (!tuning.passing() || Long.bitCount(hand) < 3) return fallback;

        HandPlanner.Plan plan = HandPlanner.plan(hand);
        long bombCards = bombCards(plan);
        long toPartner = partnerGift(v, plan, bombCards);
        long rest = hand & ~toPartner;
        long first = cheapestGift(v, plan, rest, bombCards);
        long second = cheapestGift(v, plan, rest & ~first, bombCards);
        // 더 약한 카드가 왼쪽(s+1) — TichuEngine.swapAndStartPlaying 의 toLeft 수신자.
        boolean firstWeaker = valueKey(first) != valueKey(second)
                ? valueKey(first) < valueKey(second)
                : HandPlanner.compareCanonical(first, second) < 0;
        long left = firstWeaker ? first : second;
        long right = firstWeaker ? second : first;
        return new TichuAction.PassCards(card(left), card(toPartner), card(right));
    }

    private long partnerGift(BotView v, HandPlanner.Plan plan, long bombCards) {
        long hand = v.hand();
        long pool = hand & ~bombCards;
        if (pool == 0) pool = hand;
        if (v.declared(v.partner())) return strongest(pool);
        boolean planningTichu = v.declared(v.seat())
                || (tuning.declarations() && tichuWorthy(v, plan));
        if (planningTichu) {
            long loser = 0;
            for (HandPlanner.Group g : plan.groups()) {
                if (g.type() == HandType.SINGLE && g.loser()
                        && (loser == 0 || valueKey(g.mask()) < valueKey(loser))) {
                    loser = g.mask();
                }
            }
            if (loser != 0) return loser;
        } else if (plan.controls() <= 1) {
            return strongest(pool);
        } else {
            long best = 0;
            for (HandPlanner.Group g : plan.groups()) {
                if (g.type() == HandType.SINGLE && !g.control() && g.mask() != DOG
                        && (best == 0 || valueKey(g.mask()) > valueKey(best))) {
                    best = g.mask();
                }
            }
            if (best != 0) return best;
        }
        return cheapestGift(v, plan, hand, bombCards);
    }

    /**
     * 상대에게 줄 1장: (cost(손−c), 점수카드 여부, 랭크) 최소. 용·봉황·폭탄 구성원·마작은 맨
     * 뒤로 미루고, 개는 파트너가 선언하지 않았고 내 controls ≥ 2 일 때만 준다.
     */
    private static long cheapestGift(BotView v, HandPlanner.Plan plan, long rest, long bombCards) {
        boolean dogOk = !v.declared(v.partner()) && plan.controls() >= 2;
        long deferred = DRAGON | PHOENIX | MAHJONG | bombCards | (dogOk ? 0 : DOG);
        long[] tiers = {rest & ~deferred, rest & DOG, rest & MAHJONG, rest & bombCards,
                rest & PHOENIX, rest & DRAGON};
        for (long tier : tiers) {
            long best = 0;
            int bestCost = 0;
            long m = tier;
            while (m != 0) {
                long c = m & -m;
                m &= m - 1;
                int cost = HandPlanner.cost(rest & ~c);
                if (best == 0 || compareGift(cost, c, bestCost, best) < 0) {
                    best = c;
                    bestCost = cost;
                }
            }
            if (best != 0) return best;
        }
        throw new IllegalStateException("no card to pass");
    }

    private static int compareGift(int costA, long a, int costB, long b) {
        if (costA != costB) return Integer.compare(costA, costB);
        boolean pa = card(a).points() != 0;
        boolean pb = card(b).points() != 0;
        if (pa != pb) return pa ? 1 : -1;
        if (valueKey(a) != valueKey(b)) return Integer.compare(valueKey(a), valueKey(b));
        return HandPlanner.compareCanonical(a, b);
    }

    private static long strongest(long pool) {
        long best = 0;
        long m = pool;
        while (m != 0) {
            long c = m & -m;
            m &= m - 1;
            if (best == 0 || valueKey(c) >= valueKey(best)) best = c;
        }
        return best;
    }

    private static long bombCards(HandPlanner.Plan plan) {
        long m = 0;
        for (HandPlanner.Group g : plan.groups()) {
            if (g.type().isBomb()) m |= g.mask();
        }
        return m;
    }

    // ---------- Playing ----------

    private TichuAction playing(BotView v, List<TichuAction> legal) {
        if (v.turnSeat() != v.seat()) return null;   // 용 양도는 위에서 처리했다.
        if (declareNow(v, legal)) return new TichuAction.DeclareTichu();
        Ctx c = new Ctx(v, legal, partnerHoldBack(v));
        return v.isLead() ? lead(c) : follow(c);
    }

    /**
     * 패스 후 첫 차례 선언. 선언은 차례를 넘기지 않으므로 스케줄러가 같은 좌석을 다시 불러
     * 이어서 낸다.
     */
    private boolean declareNow(BotView v, List<TichuAction> legal) {
        if (!tuning.declarations() || !has(legal, TichuAction.DeclareTichu.class)) return false;
        if (v.declared(v.partner()) || v.anyFinished()) return false;
        for (int o : opponents(v)) {
            if (v.size(o) < TICHU_MIN_OPPONENT_CARDS) return false;
        }
        return tichuWorthy(v, HandPlanner.plan(v.hand()));
    }

    // ---------- 리드 L1~L7 ----------

    private TichuAction lead(Ctx c) {
        BotView v = c.v;
        List<Candidate> pool = c.plays;
        if (c.guard) {
            List<Candidate> keep = filter(pool, x -> !x.empties);
            if (!keep.isEmpty()) pool = keep;   // 리드 후보가 전부 완주면 예외.
        }
        // 폭탄을 깨는 비폭탄 후보, 파트너가 나간 뒤의 개(리드가 상대에게 간다)는 뺀다.
        boolean partnerOut = v.finished(v.partner());
        List<Candidate> safe = filter(pool, x -> x.empties
                || (!c.breaksBomb(x) && !(partnerOut && x.mask == DOG)));
        if (safe.isEmpty()) safe = pool;

        // L1 손패 전체가 한 조합.
        if (!c.guard) {
            Candidate out = first(safe, x -> x.empties);
            if (out != null) return c.play(out);
        }
        // L2 개로 파트너에게 리드를 넘긴다.
        int partner = v.partner();
        if (v.active(partner)) {
            Candidate dog = first(safe, x -> x.mask == DOG);
            boolean handOff = v.declared(partner) || v.size(partner) <= THREAT_CARDS
                    || (c.plan.controls() == 0 && v.size(partner) <= v.handSize()) || c.guard;
            if (dog != null && handOff) return c.play(dog);
        }
        // L3 두 묶음 중 하나가 불패면 그것부터.
        if (!c.guard && c.plan.size() == 2) {
            Candidate boss = first(safe,
                    x -> c.isBoss(x) && HandPlanner.groupCount(v.hand() & ~x.mask) <= 1);
            if (boss != null) return c.play(boss);
        }
        // L4 활성 상대가 1장이면 단일을 피한다.
        if (anyOpponent(v, o -> v.size(o) == 1)) {
            Candidate multi = min(filter(safe, x -> x.size > 1 && !x.bomb),
                    Comparator.comparingInt(c::costAfter));
            if (multi != null) return c.play(multi);
            Candidate high = min(filter(safe, x -> x.size == 1 && x.mask != DOG),
                    Comparator.comparingInt(x -> -leadKey(x.mask)));
            if (high != null) return c.play(high);
        }
        // L5 파트너가 1장이면 최저 단일.
        if (v.active(partner) && v.size(partner) == 1) {
            Candidate low = min(filter(safe, x -> x.size == 1 && x.mask != DOG),
                    Comparator.comparingInt(x -> valueKey(x.mask)));
            if (low != null) return c.play(low);
        }
        // L6 가드: 비컨트롤 최저 단일로 파트너에게 리드를 넘긴다.
        if (c.guard) {
            Candidate low = min(filter(safe,
                    x -> x.size == 1 && x.mask != DOG && !x.control && !x.empties),
                    Comparator.comparingInt(x -> valueKey(x.mask)));
            if (low != null) return c.play(low);
        }
        // L7 기본: 손이 가장 좋아지는 비컨트롤 → 가장 약한 컨트롤 → 가장 약한 폭탄.
        Candidate best = min(filter(safe, x -> !x.bomb && !x.usesControl),
                Comparator.<Candidate>comparingInt(c::costAfter)
                        .thenComparingInt(x -> x.hand.rank())
                        .thenComparingInt(x -> -x.size));
        if (best == null) {
            best = min(filter(safe, x -> !x.bomb), Comparator.comparingInt(x -> valueKey(x)));
        }
        if (best == null) best = min(safe, BOMB_STRENGTH);
        return c.play(best);
    }

    // ---------- 팔로우 F1~F6 ----------

    private TichuAction follow(Ctx c) {
        BotView v = c.v;
        List<Candidate> allowed = c.guard ? filter(c.plays, x -> !x.empties) : c.plays;

        // F1 손패를 비우는 합법 조합.
        if (!c.guard) {
            Candidate out = first(c.plays, x -> x.empties);
            if (out != null) return c.play(out);
        }
        // F2 소원 자발 준수 (서버는 PassTrick 을 막지 않는다 — rules-tichu §9).
        int wish = v.wishRank();
        if (wish > 0 && count(v.hand(), wish) > 0) {
            List<Candidate> ws = filter(allowed, x -> count(x.mask, wish) > 0);
            Candidate best = min(filter(ws, x -> !x.bomb), c.f4Order());
            if (best == null) best = min(ws, BOMB_STRENGTH);
            if (best != null) return c.play(best);
        }
        // F3 파트너가 top 이면 패스. 단, 마지막 응수자 s+1 이 거의 나갔으면 불패로 막는다.
        if (v.topSeat() == v.partner()) {
            int next = v.leftOpponent();
            if (v.active(next) && !v.passed(next) && v.size(next) <= THREAT_CARDS
                    && !c.isBossTop()) {
                Candidate block = min(filter(allowed, x -> !x.bomb && c.isBoss(x)), c.f4Order());
                if (block != null) return c.play(block);
            }
            return c.pass();
        }
        // F4 비폭탄 중 (Δcost, 컨트롤 사용, 랭크) 최소 — 컨트롤·조합 깨기는 조건부.
        Candidate best = min(filter(allowed,
                x -> !x.bomb && (x.empties || !c.breaksBomb(x)) && c.f4Allowed(x)), c.f4Order());
        if (best != null) return c.play(best);
        // F5 폭탄.
        if (tuning.bombs() && v.isOpponent(v.topSeat())) {
            Candidate bomb = min(filter(allowed, x -> x.bomb), BOMB_STRENGTH);
            if (bomb != null && (v.size(v.topSeat()) <= BOMB_TOP_CARDS
                    || anyOpponent(v, v::declared) || c.guard || v.trickPoints() >= BOMB_POINTS
                    || HandPlanner.groupCount(v.hand() & ~bomb.mask) <= 1)) {
                return c.play(bomb);
            }
        }
        // F6
        return c.pass();
    }

    // ---------- 공통 술어 (엔진을 고치면 여기만 다시 보정) ----------

    /**
     * 파트너가 선언 중이면 내가 먼저 나가지 않는다. 파트너 미완주 ∧ 완주자 없음 ∧ 내 선언 없음
     * ∧ 상대 위협 없음(활성 상대 중 장수 ≤2 이면서 파트너 장수 이하인 좌석이 없음).
     */
    private boolean partnerHoldBack(BotView v) {
        if (!tuning.partnerGuard()) return false;
        int partner = v.partner();
        if (!v.declared(partner) || v.finished(partner) || v.anyFinished()
                || v.declared(v.seat())) {
            return false;
        }
        return !anyOpponent(v, o -> v.size(o) <= THREAT_CARDS && v.size(o) <= v.size(partner));
    }

    /** danger: 활성 상대 ≤2장 ∨ 활성 상대가 선언자 ∨ 파트너 가드. */
    private static boolean danger(BotView v, boolean guard) {
        return guard || anyOpponent(v, o -> v.size(o) <= THREAT_CARDS || v.declared(o));
    }

    /**
     * 같은 타입·길이에서 미공개 카드(56 − 내 손 − 공개 카드)로 이길 수 없는 묶음. 남의 폭탄은
     * 보지 않는다. 용 단독은 엔진 현황 (a) 때문에 불패로 친다.
     *
     * @param rank 비교 대표값. 봉황 단독이면 실효 rank(리드 1, 팔로우는 이전 top rank).
     */
    static boolean isBoss(HandType type, int rank, int length, long mask, long unseen) {
        boolean p = (unseen & PHOENIX) != 0;
        switch (type) {
            case SINGLE -> {
                if (mask == DRAGON) return true;
                if ((unseen & DRAGON) != 0) return false;
                if (mask != PHOENIX && p) return false;
                for (int r = Math.max(rank + 1, 2); r <= 14; r++) {
                    if (count(unseen, r) > 0) return false;
                }
                return true;
            }
            case PAIR, TRIPLE -> {
                int need = type == HandType.PAIR ? 2 : 3;
                for (int q = rank + 1; q <= 14; q++) {
                    int cnt = count(unseen, q);
                    if (cnt >= need || (p && cnt >= need - 1)) return false;
                }
                return true;
            }
            case FULL_HOUSE -> {
                for (int q = rank + 1; q <= 14; q++) {
                    int cnt = count(unseen, q);
                    boolean tripleNeedsP = cnt < 3;
                    if (cnt < 2 || (tripleNeedsP && !p)) continue;
                    for (int o = 2; o <= 14; o++) {
                        if (o == q) continue;
                        int co = count(unseen, o);
                        if (co >= 2 || (co == 1 && p && !tripleNeedsP)) return false;
                    }
                }
                return true;
            }
            case STRAIGHT -> {
                for (int hi = rank + 1; hi <= 14; hi++) {
                    if (runAvailable(unseen, hi - length + 1, hi, 1, p, 1)) return false;
                }
                return true;
            }
            case CONSECUTIVE_PAIRS -> {
                int k = length / 2;
                for (int hi = rank + 1; hi <= 14; hi++) {
                    if (runAvailable(unseen, hi - k + 1, hi, 2, p, 2)) return false;
                }
                return true;
            }
            case BOMB -> {
                for (int q = rank + 1; q <= 14; q++) {
                    if (count(unseen, q) == 4) return false;
                }
                return HandPlanner.straightFlushMembers(unseen) == 0;
            }
            case STRAIGHT_FLUSH_BOMB -> {
                return !longerOrHigherFlush(unseen, length, rank);
            }
        }
        return false;
    }

    /** [lo..hi] 의 각 랭크가 need 장씩 있고, 모자란 랭크 하나는 봉황으로 채울 수 있는가. */
    private static boolean runAvailable(long unseen, int lo, int hi, int need, boolean p,
                                        int minRank) {
        if (lo < minRank) return false;
        boolean usedP = false;
        for (int r = lo; r <= hi; r++) {
            int cnt = count(unseen, r);
            if (cnt >= need) continue;
            if (cnt == need - 1 && p && !usedP && r >= 2) {
                usedP = true;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean longerOrHigherFlush(long unseen, int length, int rank) {
        for (int s = 0; s < 4; s++) {
            int runStart = -1;
            for (int r = 2; r <= 15; r++) {
                boolean has = r <= 14 && (unseen & HandPlanner.bit(r, s)) != 0;
                if (has) {
                    if (runStart < 0) runStart = r;
                    continue;
                }
                int len = runStart < 0 ? 0 : r - runStart;
                if (len >= 5 && (len > length || (len == length && r - 1 > rank))) return true;
                runStart = -1;
            }
        }
        return false;
    }

    // ---------- 후보 ----------

    /** 합법 PlayCard 하나(카드 집합 단위로 중복 제거). */
    private static final class Candidate {
        final long mask;
        final Hand hand;
        final int size;
        final boolean empties;
        final boolean bomb;
        final boolean control;
        final boolean usesControl;
        private int costAfter = Integer.MIN_VALUE;

        Candidate(long mask, Hand hand, long myHand) {
            this.mask = mask;
            this.hand = hand;
            this.size = Long.bitCount(mask);
            this.empties = mask == myHand;
            this.bomb = hand.isBomb();
            this.control = HandPlanner.isControl(hand.type(), hand.rank(), mask);
            this.usesControl = control || (mask & (PHOENIX | DRAGON)) != 0;
        }
    }

    /** 한 번의 Playing 결정에 필요한 계산을 모은다 — 같은 값을 규칙마다 다시 구하지 않게. */
    private final class Ctx {
        final BotView v;
        final List<TichuAction> legal;
        final boolean guard;
        final boolean danger;
        final HandPlanner.Plan plan;
        final int costNow;
        final long unseen;
        final List<Candidate> plays;

        Ctx(BotView v, List<TichuAction> legal, boolean guard) {
            this.v = v;
            this.legal = legal;
            this.guard = guard;
            this.danger = danger(v, guard);
            this.plan = HandPlanner.plan(v.hand());
            this.costNow = plan.cost();
            this.unseen = v.unseen();
            this.plays = candidates(v, legal);
        }

        int costAfter(Candidate x) {
            if (x.costAfter == Integer.MIN_VALUE) x.costAfter = HandPlanner.cost(v.hand() & ~x.mask);
            return x.costAfter;
        }

        int delta(Candidate x) {
            return costAfter(x) - costNow;
        }

        boolean breaksBomb(Candidate x) {
            if (x.bomb) return false;
            for (HandPlanner.Group g : plan.groups()) {
                if (g.type().isBomb() && (g.mask() & x.mask) != 0) return true;
            }
            return false;
        }

        boolean breaksCombo(Candidate x) {
            for (HandPlanner.Group g : plan.groups()) {
                if ((g.mask() & x.mask) != 0 && (g.mask() & ~x.mask) != 0) return true;
            }
            return false;
        }

        /**
         * F4 허용: 컨트롤은 트릭 점수 ≥15 ∨ danger ∨ 내가 선언자 ∨ 사용 후 묶음 ≤2 일 때만.
         * 비컨트롤은 손이 나빠지지 않을 때(Δcost ≤ 0, 조합 안 깸), 조합을 깨야 하면 트릭 점수
         * ≥10 ∨ danger 일 때만.
         */
        boolean f4Allowed(Candidate x) {
            if (x.usesControl) {
                return v.trickPoints() >= CONTROL_POINTS || danger || v.declared(v.seat())
                        || HandPlanner.groupCount(v.hand() & ~x.mask) <= 2;
            }
            return (delta(x) <= 0 && !breaksCombo(x))
                    || v.trickPoints() >= BREAK_POINTS || danger;
        }

        Comparator<Candidate> f4Order() {
            return Comparator.<Candidate>comparingInt(this::delta)
                    .thenComparing(x -> x.usesControl)
                    .thenComparingInt(HeuristicBotPolicy::valueKey);
        }

        boolean isBoss(Candidate x) {
            int rank = x.hand.phoenixSingle() ? (v.isLead() ? 1 : v.top().rank()) : x.hand.rank();
            return HeuristicBotPolicy.isBoss(x.hand.type(), rank, x.hand.length(), x.mask, unseen);
        }

        boolean isBossTop() {
            Hand top = v.top();
            return HeuristicBotPolicy.isBoss(top.type(), top.rank(), top.length(),
                    HandPlanner.mask(top.cards()), unseen);
        }

        TichuAction play(Candidate x) {
            Integer wish = (x.mask & MAHJONG) != 0 ? wishFor(v) : null;
            return new TichuAction.PlayCard(HandPlanner.cards(x.mask), wish);
        }

        TichuAction pass() {
            if (has(legal, TichuAction.PassTrick.class)) return PASS;
            Candidate any = min(plays, Comparator.comparingInt(this::costAfter));
            return any == null ? null : play(any);
        }
    }

    /** legal 의 PlayCard 를 카드 집합으로 중복 제거하고 정규 순서로 정렬한다. */
    private static List<Candidate> candidates(BotView v, List<TichuAction> legal) {
        long[] masks = new long[legal.size()];
        int n = 0;
        for (TichuAction a : legal) {
            if (a instanceof TichuAction.PlayCard pc) masks[n++] = HandPlanner.mask(pc.cards());
        }
        Long[] boxed = new Long[n];
        for (int i = 0; i < n; i++) boxed[i] = masks[i];
        Arrays.sort(boxed, HandPlanner::compareCanonical);
        List<Candidate> out = new ArrayList<>(n);
        long prev = 0;
        for (Long m : boxed) {
            if (m == prev) continue;
            prev = m;
            HandDetector.detect(HandPlanner.cards(m))
                    .ifPresent(h -> out.add(new Candidate(m, h, v.hand())));
        }
        return out;
    }

    /** 마작을 낼 때: 14→2 순으로 내 손에 없고 4장이 다 나오지 않은 최고 랭크. 없으면 null. */
    private static Integer wishFor(BotView v) {
        for (int r = 14; r >= 2; r--) {
            if (count(v.hand(), r) == 0 && count(v.played(), r) < 4) return r;
        }
        return null;
    }

    // ---------- 정렬 키 · 도우미 ----------

    /** 남겨 둘 가치(낮을수록 먼저 내도 되는 카드): 봉황은 A 위, 용은 최상. */
    private static int valueKey(long cardOrGroup) {
        if (cardOrGroup == DRAGON) return 1000;
        if (cardOrGroup == PHOENIX) return 29;
        if (cardOrGroup == DOG) return 0;
        if (cardOrGroup == MAHJONG) return 2;
        int top = 0;
        for (int r = 14; r >= 2; r--) {
            if (count(cardOrGroup, r) > 0) {
                top = r;
                break;
            }
        }
        return top * 2;
    }

    private static int valueKey(Candidate x) {
        if (x.size == 1) return valueKey(x.mask);
        return x.hand.rank() * 2;
    }

    /** 리드 실효 세기: 봉황 리드는 1.5. */
    private static int leadKey(long single) {
        if (single == PHOENIX) return 3;
        return valueKey(single);
    }

    /** 폭탄 세기: SF > 포카드, SF 끼리는 길이 → rank. */
    private static final Comparator<Candidate> BOMB_STRENGTH = Comparator
            .<Candidate>comparingInt(x -> x.hand.type() == HandType.STRAIGHT_FLUSH_BOMB ? 1 : 0)
            .thenComparingInt(x -> x.hand.length())
            .thenComparingInt(x -> x.hand.rank());

    private static Card card(long single) {
        return HandPlanner.card(Long.numberOfTrailingZeros(single));
    }

    private static int[] opponents(BotView v) {
        return new int[] {v.leftOpponent(), v.rightOpponent()};
    }

    private static boolean anyOpponent(BotView v, IntPredicate p) {
        for (int o : opponents(v)) {
            if (v.active(o) && p.test(o)) return true;
        }
        return false;
    }

    private static boolean has(List<TichuAction> legal, Class<? extends TichuAction> type) {
        for (TichuAction a : legal) {
            if (type.isInstance(a)) return true;
        }
        return false;
    }

    private static <T> List<T> filter(List<T> xs, Predicate<T> p) {
        List<T> out = new ArrayList<>();
        for (T x : xs) {
            if (p.test(x)) out.add(x);
        }
        return out;
    }

    private static Candidate first(List<Candidate> xs, Predicate<Candidate> p) {
        for (Candidate x : xs) {
            if (p.test(x)) return x;
        }
        return null;
    }

    /** 총순서 min — 마지막 키는 정규 카드 순서(후보 리스트가 이미 그 순서로 정렬돼 있다). */
    private static Candidate min(List<Candidate> xs, Comparator<Candidate> order) {
        Candidate best = null;
        for (Candidate x : xs) {
            if (best == null || order.compare(x, best) < 0) best = x;
        }
        return best;
    }
}
