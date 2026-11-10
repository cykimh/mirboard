package com.mirboard.domain.game.skullking.bot;

import static com.mirboard.domain.game.skullking.bot.CardCode.ESCAPE;
import static com.mirboard.domain.game.skullking.bot.CardCode.PIRATE;
import static com.mirboard.domain.game.skullking.bot.CardCode.TIGRESS;

import com.mirboard.domain.game.skullking.scoring.RoundScorer;

/**
 * D-230 — 표본 하나(남의 손패를 뽑아 채운 라운드)를 끝까지 굴리는 가변 시뮬레이터. 할당 없이
 * 배열만 고친다 — 한 수 결정에 수만 장을 내 보기 때문이다.
 *
 * <p><b>굴리기 정책 = 상대 모델.</b> 모든 좌석이 공개된 예측을 보고 "더 이겨야 하면 지금
 * 이기는 카드 중 (뒤에 낼 사람이 있으면 버틸 만한 것 중) 가장 싼 것, 채웠으면 지는 카드 중
 * 들고 있기 가장 위험한 것"을 낸다(D-119 플레이 규칙에서 승률을 '지금 트릭에서 이기는가'로
 * 바꾼 것). 탈주 유령은 실제와 같이
 * 최약수를 낸다(D-104).
 */
final class RoundSim {

    /**
     * 이겨야 하는데 내 뒤에 낼 사람이 있으면, 지금 이기는 카드 중 끝까지 버틸 확률 어림
     * ({@link OpponentModel#hold})이 이 이상인 것 중 가장 싼 것을 낸다 — 없으면 지금 이기는
     * 가장 싼 것. 가장 싼 승리 카드만 내면 뒤에서 뒤집혀 굴리기가 상대의 승수를 과소평가한다.
     */
    static final double HOLD = 0.5;

    final int n;
    final int round;
    final int[][] hand;
    final int[] handLen;
    final int[] bid;
    final int[] won;
    /** 좌석별로 지금까지 딴 트릭의 보너스 합 — 적중일 때만 점수에 들어간다. */
    final int[] bonus;
    final boolean[] ghost;
    final int[] trick;
    final int[] trickSeat;
    int trickLen;
    int leader;
    /** 라운드 끝까지 남은 카드 수 (모든 좌석 합). */
    int remaining;

    RoundSim(int n, int round, int maxHand) {
        this.n = n;
        this.round = round;
        this.hand = new int[n][maxHand];
        this.handLen = new int[n];
        this.bid = new int[n];
        this.won = new int[n];
        this.bonus = new int[n];
        this.ghost = new boolean[n];
        this.trick = new int[n];
        this.trickSeat = new int[n];
    }

    void copyFrom(RoundSim o) {
        for (int s = 0; s < n; s++) {
            System.arraycopy(o.hand[s], 0, hand[s], 0, o.handLen[s]);
        }
        System.arraycopy(o.handLen, 0, handLen, 0, n);
        System.arraycopy(o.bid, 0, bid, 0, n);
        System.arraycopy(o.won, 0, won, 0, n);
        System.arraycopy(o.bonus, 0, bonus, 0, n);
        System.arraycopy(o.ghost, 0, ghost, 0, n);
        System.arraycopy(o.trick, 0, trick, 0, o.trickLen);
        System.arraycopy(o.trickSeat, 0, trickSeat, 0, o.trickLen);
        trickLen = o.trickLen;
        leader = o.leader;
        remaining = o.remaining;
    }

    int toAct() {
        return (leader + trickLen) % n;
    }

    /** {@code seat} 이 손패 {@code index} 의 카드를 판정 코드 {@code played} 로 낸다. */
    void play(int seat, int index, int played) {
        int[] h = hand[seat];
        int last = --handLen[seat];
        // 순서를 보존한다 — 동률을 손패 순서로 깨므로 결정성·재현성이 순서에 달려 있다.
        System.arraycopy(h, index + 1, h, index, last - index);
        trick[trickLen] = played;
        trickSeat[trickLen] = seat;
        trickLen++;
        remaining--;
        if (trickLen == n) {
            int w = CardCode.winner(trick, n);
            int winner = trickSeat[w];
            won[winner]++;
            bonus[winner] += CardCode.bonus(trick, n, w);
            leader = winner;
            trickLen = 0;
        }
    }

    /** 남은 라운드를 굴리기 정책으로 끝까지 진행한다. */
    void rollout() {
        while (remaining > 0) {
            int seat = toAct();
            int move = ghost[seat] ? weakest(seat) : greedy(seat);
            play(seat, move >>> 8, move & 0xFF);
        }
    }

    /** 라운드 점수(§10·§11) — 적중이면 보너스 포함. */
    int score(int seat) {
        int base = RoundScorer.baseScore(bid[seat], won[seat], round);
        return bid[seat] == won[seat] ? base + bonus[seat] : base;
    }

    // ---------- 굴리기 정책 ----------

    /** 반환값 = (손패 인덱스 << 8) | 판정 코드. */
    int greedy(int seat) {
        int[] h = hand[seat];
        int len = handLen[seat];
        int lead = CardCode.leadSuit(trick, trickLen);
        boolean follow = CardCode.mustFollow(h, len, lead);
        boolean wantWin = bid[seat] > won[seat];
        int me = trickLen;
        int after = n - trickLen - 1;
        int bestHold = -1;
        int bestHoldKey = Integer.MAX_VALUE;

        int bestWin = -1;
        int bestWinKey = Integer.MAX_VALUE;
        int bestLose = -1;
        int bestLoseKey = Integer.MIN_VALUE;
        int fallback = -1;
        int fallbackKey = Integer.MAX_VALUE;
        for (int i = 0; i < len; i++) {
            int card = h[i];
            if (!CardCode.playable(card, follow, lead)) {
                continue;
            }
            for (int option = 0; option < (card == TIGRESS ? 2 : 1); option++) {
                int played = card == TIGRESS ? (option == 0 ? PIRATE : ESCAPE) : card;
                trick[me] = played;
                boolean wins = CardCode.winner(trick, me + 1) == me;
                int cost = CardCode.spendCost(card, played);
                int move = (i << 8) | played;
                if (wantWin) {
                    if (wins) {
                        if (cost < bestWinKey) {
                            bestWinKey = cost;
                            bestWin = move;
                        }
                        if (after > 0 && cost < bestHoldKey
                                && OpponentModel.hold(after, played) >= HOLD) {
                            bestHoldKey = cost;
                            bestHold = move;
                        }
                    } else {
                        int key = discardKey(card, played, cost);
                        if (key < fallbackKey) {
                            fallbackKey = key;
                            fallback = move;
                        }
                    }
                } else {
                    if (!wins) {
                        int key = card == TIGRESS ? -1 : cost;
                        if (key > bestLoseKey) {
                            bestLoseKey = key;
                            bestLose = move;
                        }
                    } else if (cost < fallbackKey) {
                        fallbackKey = cost;
                        fallback = move;
                    }
                }
            }
        }
        if (wantWin) {
            if (bestHold >= 0) {
                return bestHold;
            }
            return bestWin >= 0 ? bestWin : fallback;
        }
        return bestLose >= 0 ? bestLose : fallback;
    }

    /** 이길 수 없을 때 버리는 순서 — 일반·캐릭터 < 탈출 < 티그리스 (D-119 와 같다). */
    private static int discardKey(int card, int played, int cost) {
        if (card == TIGRESS) {
            return 2000 + (played == ESCAPE ? 0 : 1);
        }
        return played == ESCAPE ? 1000 : cost;
    }

    /** 탈주 유령의 최약수 — 엔진 {@code timeoutAction} 의 서열(탈출·탈출 선언이 가장 약하다). */
    int weakest(int seat) {
        int[] h = hand[seat];
        int len = handLen[seat];
        int lead = CardCode.leadSuit(trick, trickLen);
        boolean follow = CardCode.mustFollow(h, len, lead);
        int best = -1;
        int bestKey = Integer.MAX_VALUE;
        for (int i = 0; i < len; i++) {
            int card = h[i];
            if (!CardCode.playable(card, follow, lead)) {
                continue;
            }
            int played = card == TIGRESS ? ESCAPE : card;
            int key = CardCode.spendCost(card, played);
            if (key < bestKey) {
                bestKey = key;
                best = (i << 8) | played;
            }
        }
        return best;
    }
}
