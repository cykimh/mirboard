package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;

/**
 * D-230 — 롤아웃 전용 <b>정수 규칙 거울</b>. 표본 하나에 라운드 끝까지 수백 장을 내 보므로
 * 객체·스트림을 쓰는 {@code TrickResolver} 는 느리다. 카드를 0~60 정수로 바꿔 트릭 승자·리드
 * 수트·follow 의무·보너스만 배열로 계산한다.
 *
 * <p><b>정본이 아니다.</b> 판정 정본은 여전히 {@code TrickResolver}·{@code LeadSuitResolver}·
 * {@code BonusCalculator}·엔진 합법수이고, 이 거울이 그것과 같다는 것을 무작위 대조 테스트
 * ({@code CardCodeTest})가 고정한다. 거울이 틀려도 봇의 추정이 흐려질 뿐 — 최종 수는 언제나
 * 엔진 합법수의 원소다.
 *
 * <pre>
 *   색상 카드  suit.ordinal()·14 + (rank − 1)   0 ~ 55   (검정 42 ~ 55)
 *   해적 56 · 인어 57 · 스컬킹 58 · 티그리스 59 · 탈출 60
 * </pre>
 *
 * 낸 카드는 "판정 코드"로 적는다 — 티그리스는 선언에 따라 해적(56)·탈출(60)이 된다. 판정·
 * 보너스가 모두 선언값만 보므로(§13-②⑩) 판정 코드 하나로 충분하다.
 */
final class CardCode {

    static final int SUIT_CARDS = 56;
    static final int PIRATE = 56;
    static final int MERMAID = 57;
    static final int SKULL_KING = 58;
    static final int TIGRESS = 59;
    static final int ESCAPE = 60;
    /** 코드 개수 — 표 크기. */
    static final int CODES = 61;
    static final int BLACK = SkullSuit.BLACK.ordinal();
    private static final int RANKS = SkullSuit.MAX_RANK;

    private CardCode() {
    }

    static int of(SkullCard card) {
        if (card.isSuit()) {
            return card.suit().ordinal() * RANKS + card.rank() - 1;
        }
        return switch (card.special()) {
            case PIRATE -> PIRATE;
            case MERMAID -> MERMAID;
            case SKULL_KING -> SKULL_KING;
            case TIGRESS -> TIGRESS;
            case ESCAPE -> ESCAPE;
        };
    }

    /** 낸 카드의 판정 코드 — 티그리스는 선언값. */
    static int played(PlayedCard pc) {
        if (pc.card().is(SpecialKind.TIGRESS)) {
            return pc.declaredAs() == TigressMode.PIRATE ? PIRATE : ESCAPE;
        }
        return of(pc.card());
    }

    static boolean isSuit(int code) {
        return code < SUIT_CARDS;
    }

    static int suit(int code) {
        return code / RANKS;
    }

    static int rank(int code) {
        return code % RANKS + 1;
    }

    private static boolean isCharacter(int code) {
        return code == PIRATE || code == MERMAID || code == SKULL_KING;
    }

    /** 리드 수트(§6.1) — 없으면 −1. 캐릭터 리드면 끝까지 없고, 탈출 리드면 처음 색상 카드. */
    static int leadSuit(int[] played, int len) {
        if (len == 0 || isCharacter(played[0])) {
            return -1;
        }
        for (int i = 0; i < len; i++) {
            if (played[i] < SUIT_CARDS) {
                return played[i] / RANKS;
            }
        }
        return -1;
    }

    /** 트릭 승자 인덱스(§7 사다리 + §8 먼저 낸 쪽). {@code len ≥ 1}. */
    static int winner(int[] played, int len) {
        int skullKing = -1;
        int mermaid = -1;
        int pirate = -1;
        int lead = -1;
        int bestBlack = -1;
        for (int i = 0; i < len; i++) {
            int c = played[i];
            if (c == SKULL_KING) {
                if (skullKing < 0) {
                    skullKing = i;
                }
            } else if (c == MERMAID) {
                if (mermaid < 0) {
                    mermaid = i;
                }
            } else if (c == PIRATE) {
                if (pirate < 0) {
                    pirate = i;
                }
            } else if (c < SUIT_CARDS) {
                if (lead < 0) {
                    lead = c / RANKS;
                }
                if (c / RANKS == BLACK && (bestBlack < 0 || c > played[bestBlack])) {
                    bestBlack = i;
                }
            }
        }
        if (skullKing >= 0) {
            return mermaid >= 0 ? mermaid : skullKing;
        }
        if (pirate >= 0) {
            return pirate;
        }
        if (mermaid >= 0) {
            return mermaid;
        }
        if (bestBlack >= 0) {
            return bestBlack;
        }
        if (lead < 0) {
            return 0;   // 전원 탈출 — 먼저 낸 탈출
        }
        int best = -1;
        for (int i = 0; i < len; i++) {
            int c = played[i];
            if (c < SUIT_CARDS && c / RANKS == lead && (best < 0 || c > played[best])) {
                best = i;
            }
        }
        return best;
    }

    /** 승자 {@code w} 가 이 트릭으로 얻는 보너스(§11) — 예측 적중 여부는 호출자가 본다. */
    static int bonus(int[] played, int len, int w) {
        int total = 0;
        int mermaids = 0;
        int pirates = 0;
        boolean skullKing = false;
        for (int i = 0; i < len; i++) {
            int c = played[i];
            if (c < SUIT_CARDS && c % RANKS == RANKS - 1) {
                total += c / RANKS == BLACK ? 20 : 10;
            }
            if (i != w) {
                if (c == MERMAID) {
                    mermaids++;
                } else if (c == PIRATE) {
                    pirates++;
                } else if (c == SKULL_KING) {
                    skullKing = true;
                }
            }
        }
        return total + switch (played[w]) {
            case PIRATE -> 20 * mermaids;
            case SKULL_KING -> 30 * pirates;
            case MERMAID -> skullKing ? 40 : 0;
            default -> 0;
        };
    }

    /**
     * follow 의무(§6.2) — 리드 수트가 정해졌고 그 색을 들고 있으면 그 색 또는 특수 카드만.
     *
     * @param lead {@link #leadSuit} 결과
     */
    static boolean mustFollow(int[] hand, int handLen, int lead) {
        if (lead < 0) {
            return false;
        }
        for (int i = 0; i < handLen; i++) {
            if (hand[i] < SUIT_CARDS && hand[i] / RANKS == lead) {
                return true;
            }
        }
        return false;
    }

    static boolean playable(int card, boolean mustFollow, int lead) {
        return !mustFollow || card >= SUIT_CARDS || card / RANKS == lead;
    }

    /**
     * 이기는 데 쓰기 아까운 정도 — D-119 {@code spendCost} 와 같은 서열.
     *
     * @param handCard 손패 코드 (티그리스 구별용)
     * @param played   판정 코드
     */
    static int spendCost(int handCard, int played) {
        if (played == ESCAPE) {
            return 0;
        }
        if (played < SUIT_CARDS) {
            return (played / RANKS == BLACK ? 200 : 100) + played % RANKS + 1;
        }
        return switch (played) {
            case MERMAID -> 300;
            case PIRATE -> handCard == TIGRESS ? 450 : 400;
            default -> 500;   // 스컬킹
        };
    }
}
