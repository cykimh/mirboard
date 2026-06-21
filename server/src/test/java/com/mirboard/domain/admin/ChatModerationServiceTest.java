package com.mirboard.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatModerationServiceTest {

    private final ChatModerationService svc =
            new ChatModerationService(new ChatModerationProperties(List.of("badword", "욕설")));

    @Test
    void masks_banned_word_with_asterisks_of_same_length() {
        assertThat(svc.mask("you badword here")).isEqualTo("you ******* here");
    }

    @Test
    void masks_korean_banned_word() {
        assertThat(svc.mask("이건 욕설 이다")).isEqualTo("이건 ** 이다");
    }

    @Test
    void masking_is_case_insensitive() {
        assertThat(svc.mask("BADWORD caps")).isEqualTo("******* caps");
    }

    @Test
    void leaves_clean_text_untouched() {
        assertThat(svc.mask("clean text 안녕")).isEqualTo("clean text 안녕");
    }

    @Test
    void handles_empty_banlist_gracefully() {
        var none = new ChatModerationService(new ChatModerationProperties(List.of()));
        assertThat(none.mask("badword stays")).isEqualTo("badword stays");
    }
}
