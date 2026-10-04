package com.mirboard.domain.game.onecard.card;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

/**
 * 원카드 카드 한 장 (`docs/rules-onecard.md` §1). 무늬 카드는 {@code suit}·{@code rank} 를, 조커는
 * {@code joker} 만 채운다 — 스컬킹 {@code SkullCard} 와 같은 단일 레코드라 JSON 으로 그대로 오간다.
 *
 * <p>{@code isGetterVisibility=NONE}: {@code isJoker()} 같은 판정 메서드가 직렬화 프로퍼티로 잡히면
 * 컴포넌트 {@code joker} 와 섞인다(D-102 의 스컬킹 사례). 판정 메서드에 {@code @JsonIgnore} 를 붙이면
 * 같은 이름의 컴포넌트까지 사라지므로 클래스 단위로 끈다.
 *
 * @param suit  무늬 카드의 무늬. 조커면 null
 * @param rank  A=1, 2~10, J=11, Q=12, K=13. 조커면 0
 * @param joker 조커 종류. 무늬 카드면 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
public record PlayingCard(Suit suit, int rank, Joker joker) {

    public static final int ACE = 1;
    public static final int JACK = 11;
    public static final int QUEEN = 12;
    public static final int KING = 13;

    public PlayingCard {
        if (joker == null) {
            if (suit == null || rank < ACE || rank > KING) {
                throw new IllegalArgumentException("invalid suit card: " + suit + " " + rank);
            }
        } else if (suit != null || rank != 0) {
            throw new IllegalArgumentException("a joker carries no suit or rank: " + joker);
        }
    }

    public static PlayingCard of(Suit suit, int rank) {
        return new PlayingCard(suit, rank, null);
    }

    public static PlayingCard joker(Joker joker) {
        return new PlayingCard(null, 0, joker);
    }

    public boolean isJoker() {
        return joker != null;
    }

    /** 공격 카드 — 2·A·조커 (§1). */
    public boolean isAttack() {
        return attackValue() > 0;
    }

    /** 먹일 장수 — 2 는 2, A 는 3, 흑백 조커 5, 컬러 조커 7, 그 밖은 0 (§1). */
    public int attackValue() {
        if (joker == Joker.BLACK) {
            return 5;
        }
        if (joker == Joker.COLOR) {
            return 7;
        }
        if (rank == 2) {
            return 2;
        }
        return rank == ACE ? 3 : 0;
    }

    /** 반격 세기 — 2 &lt; A &lt; 흑백 조커 &lt; 컬러 조커 (§6.2). 공격 카드가 아니면 0. */
    public int attackStrength() {
        if (joker == Joker.COLOR) {
            return 4;
        }
        if (joker == Joker.BLACK) {
            return 3;
        }
        if (rank == ACE) {
            return 2;
        }
        return rank == 2 ? 1 : 0;
    }

    /** J — 다음 사람 건너뛰기 (§8.1). */
    public boolean isSkip() {
        return joker == null && rank == JACK;
    }

    /** Q — 방향 반전 (§8.1). */
    public boolean isReverse() {
        return joker == null && rank == QUEEN;
    }

    /** K — 한 번 더 (§8.1). */
    public boolean isExtraTurn() {
        return joker == null && rank == KING;
    }

    /** 7 — 무늬 지정 (§8.1). */
    public boolean isSuitChange() {
        return joker == null && rank == 7;
    }

    /** 일반 카드 — 효과 없는 3·4·5·6·8·9·10 (§1). 시작 카드 조건이다 (§3-4). */
    public boolean isNormal() {
        return joker == null && rank >= 3 && rank <= 10 && rank != 7;
    }
}
