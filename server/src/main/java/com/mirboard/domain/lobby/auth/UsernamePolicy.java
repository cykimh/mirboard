package com.mirboard.domain.lobby.auth;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * D-149 — 아이디 대신 <b>별명</b>: 한글(완성형)·영문·숫자 2~10자. 띄어쓰기·특수문자는 받지 않는다 — 하이픈이 밖이라
 * 게스트 이름(`guest-…`, D-117)을 가입으로 만들 수 없다. 별명은 NFC 로 맞춰 저장·조회한다(자모가 풀린 입력도 같은 별명).
 */
public final class UsernamePolicy {

    public static final Pattern PATTERN = Pattern.compile("^[가-힣A-Za-z0-9]{2,10}$");

    private UsernamePolicy() {
    }

    /** 완성형 한글로 맞춘다(NFC). 가입·로그인 모두 이 값으로 다룬다. */
    public static String normalize(String username) {
        return username == null ? null : Normalizer.normalize(username, Normalizer.Form.NFC);
    }

    public static void validate(String username) {
        if (username == null || !PATTERN.matcher(username).matches()) {
            throw new InvalidUsernameException(username);
        }
    }
}
