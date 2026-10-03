package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * D-117 — 게스트는 컬럼이 아니라 <b>규약</b>(username 접두 `guest-` + 로그인 불가 해시)으로
 * 식별한다. 규약이 무너지는 가장 쉬운 길은 가입으로 `guest-` 이름을 만드는 것이므로,
 * {@link UsernamePolicy} 가 그 이름을 거부한다는 사실을 여기서 고정한다 — 누가 정책에
 * 하이픈을 허용하는 순간 이 테스트가 깨져야 한다.
 */
class GuestPolicyTest {

    private static final SplittableRandom RNG = new SplittableRandom(117);

    @Test
    void new_username_is_prefix_plus_eight_unambiguous_chars_within_column_length() {
        for (int i = 0; i < 500; i++) {
            String name = GuestPolicy.newUsername(RNG);
            assertThat(name).matches("guest-[a-hjkmnp-z2-9]{8}");
            // users.username VARCHAR(20)
            assertThat(name.length()).isLessThanOrEqualTo(20);
        }
    }

    @Test
    void new_usernames_do_not_repeat_in_practice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            seen.add(GuestPolicy.newUsername(RNG));
        }
        assertThat(seen).hasSize(2_000);
    }

    @Test
    void guest_names_cannot_be_registered_so_they_cannot_be_forged() {
        String name = GuestPolicy.newUsername(RNG);
        assertThatThrownBy(() -> UsernamePolicy.validate(name))
                .isInstanceOf(InvalidUsernameException.class);
    }

    @Test
    void is_guest_only_for_the_hyphen_prefix() {
        assertThat(GuestPolicy.isGuest("guest-abcd2345")).isTrue();
        assertThat(GuestPolicy.isGuest("guest_abc")).isFalse();
        assertThat(GuestPolicy.isGuest("bot_north")).isFalse();
        assertThat(GuestPolicy.isGuest("alice_01")).isFalse();
        assertThat(GuestPolicy.isGuest(null)).isFalse();
    }

    @Test
    void require_not_guest_blocks_only_guests() {
        assertThatThrownBy(() -> GuestPolicy.requireNotGuest(new AuthPrincipal(1L, "guest-abcd2345")))
                .isInstanceOf(GuestForbiddenException.class);
        assertThatCode(() -> GuestPolicy.requireNotGuest(new AuthPrincipal(2L, "alice_01")))
                .doesNotThrowAnyException();
    }

    @Test
    void like_pattern_and_prefix_agree() {
        // 정리·랭킹 SQL 은 LIKE 패턴을, ELO 판정은 StartingWith(접두)를 쓴다 — 둘이 갈리면 안 된다.
        assertThat(GuestPolicy.LIKE_PATTERN).isEqualTo(GuestPolicy.PREFIX + "%");
    }
}
