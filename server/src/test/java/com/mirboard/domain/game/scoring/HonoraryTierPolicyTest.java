package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.lobby.auth.User;
import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** D-198 — 명예 계급: 설정에 적힌 계정만 별왕관, NFC 로 비교, 게스트·봇은 대상 아님. */
class HonoraryTierPolicyTest {

    private static final String NFC = "창엽2";
    private static final String NFD = Normalizer.normalize(NFC, Normalizer.Form.NFD);

    private static User user(String name, boolean bot) {
        User u = User.create(name, "x", Clock.systemUTC());
        if (bot) {
            ReflectionTestUtils.setField(u, "isBot", true);
        }
        return u;
    }

    @Test
    @DisplayName("설정에 적힌 계정은 별왕관, 다른 계정은 받은 계급 그대로")
    void only_listed_users_wear_the_star_crown() {
        HonoraryTierPolicy policy = new HonoraryTierPolicy(List.of(NFC));

        assertThat(policy.apply(NFC, Tier.SKULL.name())).isEqualTo("GOLD_MEDAL");
        assertThat(policy.apply(user(NFC, false), Tier.MISSILE.name())).isEqualTo("GOLD_MEDAL");
        assertThat(policy.apply("창엽", Tier.MISSILE.name())).isEqualTo("MISSILE");
        assertThat(policy.apply("창엽3", Tier.GOLD_CROWN.name())).isEqualTo("GOLD_CROWN");
        assertThat(policy.apply((String) null, Tier.MISSILE.name())).isEqualTo("MISSILE");
    }

    @Test
    @DisplayName("NFC 로 비교한다 — 자모가 풀린 이름·설정값도 같은 계정")
    void compares_after_nfc() {
        assertThat(NFD).isNotEqualTo(NFC);

        assertThat(new HonoraryTierPolicy(List.of(NFC)).isHonorary(NFD)).isTrue();
        assertThat(new HonoraryTierPolicy(List.of(NFD)).isHonorary(NFC)).isTrue();
        assertThat(new HonoraryTierPolicy(List.of(" " + NFD + " ")).isHonorary(NFC)).isTrue();
    }

    @Test
    @DisplayName("게스트·봇은 설정에 적혀도 대상이 아니다")
    void guests_and_bots_are_excluded() {
        HonoraryTierPolicy policy = new HonoraryTierPolicy(List.of(NFC, "guest-abcd2345", "bot_alpha"));

        assertThat(policy.isHonorary("guest-abcd2345")).isFalse();
        assertThat(policy.isHonorary(user("bot_alpha", true))).isFalse();
        assertThat(policy.isHonorary(user(NFC, true))).isFalse();
        assertThat(policy.isHonorary(user(NFC, false))).isTrue();
    }

    @Test
    @DisplayName("설정이 비면 아무도 대상이 아니다")
    void empty_config_means_nobody() {
        assertThat(new HonoraryTierPolicy(List.of()).isHonorary(NFC)).isFalse();
        assertThat(new HonoraryTierPolicy(List.of("")).isHonorary("")).isFalse();
        assertThat(new HonoraryTierPolicy(null).isHonorary(NFC)).isFalse();
    }
}
