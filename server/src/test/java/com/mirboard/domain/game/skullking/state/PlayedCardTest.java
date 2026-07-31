package com.mirboard.domain.game.skullking.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.TigressMode;
import org.junit.jupiter.api.Test;

/** 티그리스 정체성 해소 (§13-②③⑩) — 강약·동점·보너스가 공유하는 단일 출처. */
class PlayedCardTest {

    @Test
    void suit_card_resolves_to_suit_kind() {
        assertThat(PlayedCard.of(0, SkullCard.of(SkullSuit.BLACK, 14)).kind())
                .isEqualTo(EffectiveKind.SUIT);
    }

    @Test
    void plain_specials_resolve_to_their_own_kind() {
        assertThat(PlayedCard.of(0, SkullCard.pirate()).kind()).isEqualTo(EffectiveKind.PIRATE);
        assertThat(PlayedCard.of(0, SkullCard.mermaid()).kind()).isEqualTo(EffectiveKind.MERMAID);
        assertThat(PlayedCard.of(0, SkullCard.skullKing()).kind())
                .isEqualTo(EffectiveKind.SKULL_KING);
        assertThat(PlayedCard.of(0, SkullCard.escape()).kind()).isEqualTo(EffectiveKind.ESCAPE);
    }

    @Test
    void tigress_resolves_to_its_declaration() {
        assertThat(PlayedCard.tigress(0, TigressMode.PIRATE).kind())
                .isEqualTo(EffectiveKind.PIRATE);
        assertThat(PlayedCard.tigress(0, TigressMode.ESCAPE).kind())
                .isEqualTo(EffectiveKind.ESCAPE);
    }

    @Test
    void tigress_without_a_declaration_is_rejected() {
        assertThatThrownBy(() -> new PlayedCard(0, SkullCard.tigress(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tigress must be declared");
    }

    @Test
    void declaration_on_a_non_tigress_card_is_rejected() {
        assertThatThrownBy(() -> new PlayedCard(0, SkullCard.pirate(), TigressMode.PIRATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only Tigress carries a declaration");
        assertThatThrownBy(
                () -> new PlayedCard(0, SkullCard.of(SkullSuit.GREEN, 3), TigressMode.ESCAPE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void character_classification_excludes_suit_and_escape() {
        assertThat(EffectiveKind.PIRATE.isCharacter()).isTrue();
        assertThat(EffectiveKind.MERMAID.isCharacter()).isTrue();
        assertThat(EffectiveKind.SKULL_KING.isCharacter()).isTrue();
        assertThat(EffectiveKind.ESCAPE.isCharacter()).isFalse();
        assertThat(EffectiveKind.SUIT.isCharacter()).isFalse();
    }
}
