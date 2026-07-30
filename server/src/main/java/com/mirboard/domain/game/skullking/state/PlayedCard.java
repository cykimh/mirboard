package com.mirboard.domain.game.skullking.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.card.TigressMode;
import java.util.Objects;

/**
 * 트릭에 제출된 카드 한 장 + 누가 냈는가. 트릭 안에서의 순서는 리스트 인덱스가 갖는다
 * (§8 "먼저 낸 사람" 동점 규칙이 인덱스로 풀린다).
 *
 * @param seat       제출한 좌석
 * @param card       제출한 카드
 * @param declaredAs 티그리스일 때의 선언 (해적/탈출). 그 외 카드는 반드시 null
 */
public record PlayedCard(int seat, SkullCard card, TigressMode declaredAs) {

    public PlayedCard {
        Objects.requireNonNull(card, "card");
        boolean isTigress = card.is(SpecialKind.TIGRESS);
        if (isTigress && declaredAs == null) {
            throw new IllegalArgumentException("Tigress must be declared as PIRATE or ESCAPE");
        }
        if (!isTigress && declaredAs != null) {
            throw new IllegalArgumentException(
                    "Only Tigress carries a declaration, got " + declaredAs + " on " + card);
        }
    }

    public static PlayedCard of(int seat, SkullCard card) {
        return new PlayedCard(seat, card, null);
    }

    public static PlayedCard tigress(int seat, TigressMode mode) {
        return new PlayedCard(seat, SkullCard.tigress(), mode);
    }

    /**
     * 판정에 쓰이는 단일 정체성 (§13-②③⑩). 강약·동점·보너스가 모두 이 값만 본다 —
     * 티그리스 분기가 여기 한 곳에만 존재하는 것이 설계 요점이다.
     */
    @JsonIgnore
    public EffectiveKind kind() {
        if (card.isSuit()) {
            return EffectiveKind.SUIT;
        }
        return switch (card.special()) {
            case PIRATE -> EffectiveKind.PIRATE;
            case MERMAID -> EffectiveKind.MERMAID;
            case SKULL_KING -> EffectiveKind.SKULL_KING;
            case ESCAPE -> EffectiveKind.ESCAPE;
            case TIGRESS -> declaredAs == TigressMode.PIRATE
                    ? EffectiveKind.PIRATE
                    : EffectiveKind.ESCAPE;
        };
    }

    @JsonIgnore
    public boolean isKind(EffectiveKind target) {
        return kind() == target;
    }
}
