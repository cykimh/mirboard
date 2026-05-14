package com.mirboard.domain.game.tichu.card;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;

/**
 * 티츄 카드. 일반 카드는 {@code suit + rank(2~14)}, 특수 카드는 {@code special} 로
 * 표현된다 (다른 한쪽은 null). 인스턴스는 정적 팩토리로 생성.
 *
 * <p>특수 카드의 rank 는 정렬/비교를 단순화하기 위한 내부 값:
 * <ul>
 *   <li>{@code DOG = 0} — 비교에서 가장 낮음 (실제 룰에서는 단독 플레이)</li>
 *   <li>{@code MAHJONG = 1} — 일반 2~14 보다 낮음 (스트레이트 최저 시작 가능)</li>
 *   <li>{@code PHOENIX = 0} — 와일드, 컨텍스트에 따라 별도 처리</li>
 *   <li>{@code DRAGON = 100} — 항상 최강 싱글</li>
 * </ul>
 *
 * <p>{@code isGetterVisibility=NONE}: {@code isSpecial()} / {@code isNormal()} 가
 * record 컴포넌트 {@code special} 과 충돌하지 않도록 Jackson 의 is-getter 자동 발견을
 * 끈다. 컴포넌트는 canonical accessor (suit/rank/special) 로만 직렬화된다.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
public record Card(Suit suit, int rank, Special special) {

    public Card {
        boolean isSpecial = special != null;
        if (isSpecial) {
            if (suit != null) {
                throw new IllegalArgumentException("Special card must not have a suit: " + special);
            }
        } else {
            Objects.requireNonNull(suit, "Normal card requires a suit");
            if (rank < 2 || rank > 14) {
                throw new IllegalArgumentException("Rank must be in [2, 14]: " + rank);
            }
        }
    }

    public static Card normal(Suit suit, int rank) {
        return new Card(suit, rank, null);
    }

    public static Card mahjong() {
        return new Card(null, 1, Special.MAHJONG);
    }

    public static Card dog() {
        return new Card(null, 0, Special.DOG);
    }

    public static Card phoenix() {
        return new Card(null, 0, Special.PHOENIX);
    }

    public static Card dragon() {
        return new Card(null, 100, Special.DRAGON);
    }

    public boolean isSpecial() {
        return special != null;
    }

    public boolean isNormal() {
        return special == null;
    }

    public boolean is(Special target) {
        return special == target;
    }

    /** 라운드 종료 시 트릭 점수 계산에 쓰이는 카드 자체 점수. */
    @JsonIgnore
    public int points() {
        if (special == Special.DRAGON) return 25;
        if (special == Special.PHOENIX) return -25;
        if (special != null) return 0;          // Mahjong, Dog
        if (rank == 5) return 5;
        if (rank == 10 || rank == 13) return 10; // 10 or K
        return 0;
    }
}
