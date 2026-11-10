package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.action.SkullKingAction.PlaceBid;
import com.mirboard.domain.game.skullking.action.SkullKingAction.PlayCard;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * D-230 — 스컬킹 봇: 상대 모델 위의 결정화 몬테카를로 (결정적, 전함수).
 *
 * <p>공개 정보에 맞게 남의 손패를 여러 번 뽑고(빈 수트 반영), 뽑은 패마다 후보 수를 하나씩
 * 두고 남은 라운드를 {@link RoundSim} 의 굴리기 정책(= 상대 모델)으로 끝까지 굴려 <b>내 라운드
 * 점수(보너스 포함)의 평균</b>이 가장 높은 후보를 낸다. 모든 후보가 같은 표본을 쓴다(공통 난수
 * — 후보 사이 차이만 남는다).
 * <ul>
 *   <li><b>입찰</b>: 남의 예측은 비공개라 뽑은 손패의 어림 + 그 좌석의 예측 버릇으로 정한다
 *       ({@link OpponentModel}). 후보 = 합법 예측 전부.</li>
 *   <li><b>플레이</b>: 남의 예측·승수는 공개돼 있다. 표본마다 "그 손패로 그 예측을 했을 법한
 *       정도"로 무게를 준다.</li>
 * </ul>
 *
 * <p><b>결정적이다.</b> 표본 난수 시드는 뷰 내용(카드 코드·예측·좌석)에서만 만든다 — 시간·외부
 * 난수 없음, enum 의 identity hashCode 없음. 예산은 시간이 아니라 시뮬레이션한 카드 수
 * ({@link #BUDGET})라 서버 부하와 무관하게 같은 뷰면 같은 수다.
 *
 * <p><b>전함수다.</b> 합법수가 비면 null, 1개면 그것. 반환값은 언제나 {@code legal} 의 원소이고
 * 동률은 합법수 순서상 앞선 것. 그래도 정책이 실패하면 어댑터가 {@code timeoutAction} 으로
 * 떨어진다 ({@code SkullKingGameEngine#chooseBotAction}).
 */
public final class SkullKingBotPolicy {

    /** 한 수 결정에 시뮬레이션할 카드 수 상한 — 한 수 20ms 이하(D-230 실측)의 근거. */
    static final int BUDGET = 30_000;
    static final int MIN_WORLDS = 8;
    static final int MAX_WORLDS = 256;
    /** 플레이 중 표본 무게 — 공개 예측과 어림의 차이에 대한 정규분포 폭. */
    static final double BID_SIGMA = 1.0;
    /** 빈 수트를 지키며 패를 채우지 못하면 이만큼 다시 섞는다 — 그래도 안 되면 빈 수트를 무시. */
    private static final int DEAL_RETRIES = 4;

    private SkullKingBotPolicy() {
    }

    /**
     * @param view  정보 경계 — 공개된 정보만 담긴 뷰
     * @param legal 엔진이 계산한 합법수 (follow 의무 반영)
     * @return {@code legal} 의 원소. {@code legal} 이 비면 null
     */
    public static SkullKingAction choose(SkullKingBotView view, List<SkullKingAction> legal) {
        if (legal.isEmpty()) {
            return null;
        }
        if (legal.size() == 1) {
            return legal.get(0);
        }
        return new Search(view).best(legal);
    }

    /** 한 번의 결정 — 표본·후보 점수 계산 상태. */
    private static final class Search {

        private final SkullKingBotView view;
        private final int n;
        private final int me;
        private final int[] myHand;
        /** 미공개 풀 (덱 순서). */
        private final int[] pool;
        /** [좌석][수트] — 그 수트가 없다고 공개적으로 드러났는가. */
        private final boolean[][] void_;
        /** 좌석별 이번 라운드에 이미 낸 카드 (손패 코드 — 티그리스는 59). */
        private final int[][] playedBy;
        private final int[] playedCount;
        private final int[] handSizes;
        private final double[] habit;
        private final boolean[] ghost;
        private final SplittableRandom random;

        Search(SkullKingBotView view) {
            this.view = view;
            this.n = view.seatCount();
            this.me = view.seat();
            this.myHand = codes(view.hand());
            this.pool = codes(view.unseenCards());
            this.void_ = new boolean[n][4];
            this.playedBy = new int[n][view.hand().size() + view.completedTricks().size() + 1];
            this.playedCount = new int[n];
            for (TrickResult trick : view.completedTricks()) {
                observe(trick.cards());
            }
            observe(view.currentTrick());
            this.handSizes = new int[n];
            boolean[] playedNow = new boolean[n];
            view.currentTrick().forEach(pc -> playedNow[pc.seat()] = true);
            for (int s = 0; s < n; s++) {
                handSizes[s] = myHand.length - (playedNow[s] && s != me ? 1 : 0);
            }
            this.habit = OpponentModel.habits(view.pastBids(), n);
            this.ghost = new boolean[n];
            view.deserted().forEach(s -> ghost[s] = true);
            this.random = new SplittableRandom(seed(view, myHand));
        }

        /** 트릭 하나의 제출 순서로 빈 수트와 낸 카드를 기록한다. */
        private void observe(List<PlayedCard> cards) {
            int[] codes = new int[cards.size()];
            for (int i = 0; i < cards.size(); i++) {
                PlayedCard pc = cards.get(i);
                int lead = CardCode.leadSuit(codes, i);
                codes[i] = CardCode.played(pc);
                int card = CardCode.of(pc.card());
                if (lead >= 0 && CardCode.isSuit(card) && CardCode.suit(card) != lead) {
                    void_[pc.seat()][lead] = true;
                }
                playedBy[pc.seat()][playedCount[pc.seat()]++] = card;
            }
        }

        SkullKingAction best(List<SkullKingAction> legal) {
            int candidates = legal.size();
            RoundSim world = baseWorld();
            RoundSim sim = new RoundSim(n, view.roundNumber(), myHand.length);
            int[] index = new int[candidates];
            int[] played = new int[candidates];
            int[] bids = new int[candidates];
            for (int c = 0; c < candidates; c++) {
                switch (legal.get(c)) {
                    case PlaceBid bid -> bids[c] = bid.bid();
                    case PlayCard play -> {
                        index[c] = indexOf(play.card());
                        played[c] = play.declaredAs() == null
                                ? CardCode.of(play.card())
                                : (play.declaredAs() == TigressMode.PIRATE
                                        ? CardCode.PIRATE : CardCode.ESCAPE);
                    }
                }
            }

            int perWorld = Math.max(1, candidates * world.remaining);
            int worlds = Math.clamp(BUDGET / perWorld, MIN_WORLDS, MAX_WORLDS);
            double[] total = new double[candidates];
            for (int w = 0; w < worlds; w++) {
                double weight = deal(world);
                if (weight <= 0) {
                    continue;
                }
                for (int c = 0; c < candidates; c++) {
                    sim.copyFrom(world);
                    if (view.bidding()) {
                        sim.bid[me] = bids[c];
                    } else {
                        sim.play(me, index[c], played[c]);
                    }
                    sim.rollout();
                    total[c] += weight * sim.score(me);
                }
            }
            int pick = 0;
            for (int c = 1; c < candidates; c++) {
                if (total[c] > total[pick]) {
                    pick = c;
                }
            }
            return legal.get(pick);
        }

        private int indexOf(SkullCard card) {
            int code = CardCode.of(card);
            for (int i = 0; i < myHand.length; i++) {
                if (myHand[i] == code) {
                    return i;
                }
            }
            throw new IllegalArgumentException("legal action not in hand: " + card);
        }

        /** 공개된 부분 — 내 손패·예측·승수·지금까지의 보너스·진행 중 트릭. 남의 손패는 비어 있다. */
        private RoundSim baseWorld() {
            RoundSim world = new RoundSim(n, view.roundNumber(), myHand.length);
            System.arraycopy(myHand, 0, world.hand[me], 0, myHand.length);
            int remaining = 0;
            for (int s = 0; s < n; s++) {
                world.handLen[s] = s == me ? myHand.length : 0;
                remaining += handSizes[s];
                world.ghost[s] = ghost[s];
                world.won[s] = view.tricksWon().get(s);
                world.bid[s] = view.bidding() ? 0 : view.bids().get(s);
            }
            for (TrickResult trick : view.completedTricks()) {
                int[] codes = new int[trick.cards().size()];
                for (int i = 0; i < codes.length; i++) {
                    codes[i] = CardCode.played(trick.cards().get(i));
                }
                world.bonus[trick.winnerSeat()] +=
                        CardCode.bonus(codes, codes.length, CardCode.winner(codes, codes.length));
            }
            for (PlayedCard pc : view.currentTrick()) {
                world.trick[world.trickLen] = CardCode.played(pc);
                world.trickSeat[world.trickLen] = pc.seat();
                world.trickLen++;
            }
            world.leader = view.leadSeat();
            world.remaining = remaining;
            return world;
        }

        /**
         * 남의 손패를 새로 뽑아 {@code world} 에 채운다. 입찰 중이면 남의 예측도 어림해 넣는다.
         *
         * @return 표본 무게 — 플레이 중엔 공개 예측과의 부합도, 입찰 중엔 1
         */
        private double deal(RoundSim world) {
            int[] shuffled = pool.clone();
            for (int i = shuffled.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int t = shuffled[i];
                shuffled[i] = shuffled[j];
                shuffled[j] = t;
            }
            boolean[] taken = new boolean[shuffled.length];
            boolean ok = false;
            for (int attempt = 0; attempt <= DEAL_RETRIES && !ok; attempt++) {
                ok = fill(world, shuffled, taken, attempt < DEAL_RETRIES);
                if (!ok && attempt < DEAL_RETRIES) {
                    Arrays.fill(taken, false);
                    for (int i = shuffled.length - 1; i > 0; i--) {
                        int j = random.nextInt(i + 1);
                        int t = shuffled[i];
                        shuffled[i] = shuffled[j];
                        shuffled[j] = t;
                    }
                }
            }

            double weight = 1.0;
            int[] full = new int[myHand.length + playedBy[0].length];
            for (int s = 0; s < n; s++) {
                if (s == me) {
                    continue;
                }
                if (view.bidding()) {
                    world.bid[s] = ghost[s] ? 0 : OpponentModel.predictBid(
                            n, world.hand[s], world.handLen[s], habit[s], handSizes[s]);
                } else if (!ghost[s]) {
                    int len = world.handLen[s];
                    System.arraycopy(world.hand[s], 0, full, 0, len);
                    System.arraycopy(playedBy[s], 0, full, len, playedCount[s]);
                    double gap = view.bids().get(s)
                            - (OpponentModel.handValue(n, full, len + playedCount[s]) + habit[s]);
                    weight *= StrictMath.exp(-gap * gap / (2 * BID_SIGMA * BID_SIGMA));
                }
            }
            return weight;
        }

        /** 빈 수트가 많은 좌석부터 섞인 풀을 앞에서 훑어 채운다. 못 채우면 false. */
        private boolean fill(RoundSim world, int[] shuffled, boolean[] taken, boolean respectVoids) {
            for (int s : fillOrder()) {
                int need = handSizes[s];
                int len = 0;
                for (int p = 0; p < shuffled.length && len < need; p++) {
                    int card = shuffled[p];
                    if (taken[p] || (respectVoids && CardCode.isSuit(card)
                            && void_[s][CardCode.suit(card)])) {
                        continue;
                    }
                    taken[p] = true;
                    world.hand[s][len++] = card;
                }
                world.handLen[s] = len;
                if (len < need) {
                    return false;
                }
            }
            return true;
        }

        private int[] fillOrder() {
            int[] order = new int[n - 1];
            int k = 0;
            for (int s = 0; s < n; s++) {
                if (s != me) {
                    order[k++] = s;
                }
            }
            // 삽입 정렬 — 빈 수트 수 내림차순, 같으면 좌석 순 (결정적).
            for (int i = 1; i < order.length; i++) {
                int x = order[i];
                int j = i - 1;
                while (j >= 0 && voids(order[j]) < voids(x)) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = x;
            }
            return order;
        }

        private int voids(int seat) {
            int count = 0;
            for (boolean v : void_[seat]) {
                count += v ? 1 : 0;
            }
            return count;
        }
    }

    private static int[] codes(List<SkullCard> cards) {
        int[] codes = new int[cards.size()];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = CardCode.of(cards.get(i));
        }
        return codes;
    }

    /** 뷰 내용만으로 만든 64비트 시드 (SplitMix64 섞기). */
    private static long seed(SkullKingBotView view, int[] hand) {
        long h = 0x5EEDL;
        h = mix(h, view.seat());
        h = mix(h, view.seatCount());
        h = mix(h, view.roundNumber());
        h = mix(h, view.bidding() ? 1 : 0);
        h = mix(h, view.leadSeat());
        for (int c : hand) {
            h = mix(h, c);
        }
        for (TrickResult trick : view.completedTricks()) {
            for (PlayedCard pc : trick.cards()) {
                h = mix(h, pc.seat() * 64L + CardCode.played(pc));
            }
        }
        for (PlayedCard pc : view.currentTrick()) {
            h = mix(h, 1000 + pc.seat() * 64L + CardCode.played(pc));
        }
        for (int b : view.bids()) {
            h = mix(h, b);
        }
        for (List<Integer> round : view.pastBids()) {
            for (int b : round) {
                h = mix(h, 77 + b);
            }
        }
        return h;
    }

    private static long mix(long h, long value) {
        long z = h + value * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
