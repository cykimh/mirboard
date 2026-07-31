package com.mirboard.domain.game.skullking.card;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;

/**
 * 스컬킹 카드 (`docs/rules-skullking.md` §1). 색상 카드는 {@code suit + rank(1~14)},
 * 특수 카드는 {@code special} 로 표현된다 (다른 한쪽은 null).
 *
 * <p><b>중복 카드를 구분하지 않는다 (설계 판단, D-101).</b> 덱에는 같은 해적이 5장,
 * 인어가 2장, 탈출이 5장 들어 있고 이들은 게임상 완전히 교환 가능하다. 물리적 개체를
 * 구분하려고 copy 인덱스를 달면 클라가 "몇 번째 해적을 내는지"까지 왕복시켜야 하고,
 * 같은 값의 다른 인덱스를 보냈다는 이유로 정당한 플레이가 거절되는 함정이 생긴다.
 * 대신 카드 보존 불변식을 multiset 으로 검사한다 ({@code SkullKingInvariantChecker}) —
 * 색상 카드는 덱에 1장뿐이라 종류별 상한 검사만으로 중복이 잡히고, 특수 카드끼리의
 * 교환은 애초에 구별 불가능한 상태라 위반이 아니다.
 *
 * <p>{@code isGetterVisibility=NONE}: {@link #isSuit()} 가 record 컴포넌트
 * {@code suit} 과 충돌하지 않도록 Jackson 의 is-getter 자동 발견을 끈다 (티츄 {@code Card}
 * 와 같은 이유).
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
public record SkullCard(SkullSuit suit, int rank, SpecialKind special) {

    public SkullCard {
        if (special != null) {
            if (suit != null) {
                throw new IllegalArgumentException("Special card must not have a suit: " + special);
            }
            if (rank != 0) {
                throw new IllegalArgumentException("Special card must have rank 0: " + special);
            }
        } else {
            Objects.requireNonNull(suit, "Suit card requires a suit");
            if (rank < SkullSuit.MIN_RANK || rank > SkullSuit.MAX_RANK) {
                throw new IllegalArgumentException(
                        "Rank must be in [" + SkullSuit.MIN_RANK + ", " + SkullSuit.MAX_RANK
                                + "]: " + rank);
            }
        }
    }

    public static SkullCard of(SkullSuit suit, int rank) {
        return new SkullCard(suit, rank, null);
    }

    public static SkullCard special(SpecialKind kind) {
        return new SkullCard(null, 0, Objects.requireNonNull(kind, "kind"));
    }

    public static SkullCard pirate() {
        return special(SpecialKind.PIRATE);
    }

    public static SkullCard mermaid() {
        return special(SpecialKind.MERMAID);
    }

    public static SkullCard skullKing() {
        return special(SpecialKind.SKULL_KING);
    }

    public static SkullCard tigress() {
        return special(SpecialKind.TIGRESS);
    }

    public static SkullCard escape() {
        return special(SpecialKind.ESCAPE);
    }

    @JsonIgnore
    public boolean isSuit() {
        return special == null;
    }

    @JsonIgnore
    public boolean isSpecial() {
        return special != null;
    }

    public boolean is(SpecialKind target) {
        return special == target;
    }

    /** 검정(으뜸패) 색상 카드인가. 오프수트여도 3색을 이긴다 (§7.1). */
    @JsonIgnore
    public boolean isTrump() {
        return suit != null && suit.isTrump();
    }

    /** 14 카드 보너스 대상인가 (§11). 검정 14 는 20점, 나머지 3색 14 는 10점. */
    @JsonIgnore
    public boolean isBonusFourteen() {
        return isSuit() && rank == SkullSuit.MAX_RANK;
    }
}
