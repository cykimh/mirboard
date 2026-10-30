package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** D-156 — 비밀번호는 4~64자면 무엇이든(문자 종류 제한 없음, 사용자 결정). */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"1234", "aaaa", "가나다라", "    ", "a!@#"})
    void four_or_more_of_anything_is_fine(String pw) {
        assertThatCode(() -> PasswordPolicy.validate(pw)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "abc"})
    void fewer_than_four_is_rejected(String pw) {
        assertThatThrownBy(() -> PasswordPolicy.validate(pw)).isInstanceOf(InvalidPasswordException.class);
    }

    @Test
    void null_and_too_long_are_rejected() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(InvalidPasswordException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("x".repeat(65))).isInstanceOf(InvalidPasswordException.class);
    }
}
