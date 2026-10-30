package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** D-149 — 아이디 대신 별명: 한글·영문·숫자 2~10자(띄어쓰기·특수문자 불가). */
class UsernamePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"철수", "영희Kim", "민지1", "ab", "가나다라마바사아자차", "Player1234"})
    void accepts_korean_english_digits_two_to_ten(String name) {
        assertThatCode(() -> UsernamePolicy.validate(name)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "a", "가나다라마바사아자차카", "철 수", "alice_01", "guest-ab12", "ㄱㄴ", "철수!", ""})
    void rejects_everything_else(String name) {
        assertThatThrownBy(() -> UsernamePolicy.validate(name)).isInstanceOf(InvalidUsernameException.class);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> UsernamePolicy.validate(null)).isInstanceOf(InvalidUsernameException.class);
    }

    @Test
    void normalizes_decomposed_hangul_to_composed() {
        String nfd = Normalizer.normalize("철수", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("철수");
        assertThat(UsernamePolicy.normalize(nfd)).isEqualTo("철수");
        assertThat(UsernamePolicy.normalize(null)).isNull();
    }
}
