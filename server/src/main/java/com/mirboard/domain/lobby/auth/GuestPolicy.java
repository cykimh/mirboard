package com.mirboard.domain.lobby.auth;

import java.util.random.RandomGenerator;

/**
 * D-117 — 게스트 계정 규약의 단일 진실원.
 *
 * <p>게스트는 {@code users} 에 컬럼을 더하지 않고(D-02 화이트리스트 불변) <b>규약</b>으로
 * 식별한다: username 이 {@value #PREFIX} 로 시작하고, password_hash 가 로그인 불가
 * sentinel {@value #NO_LOGIN_HASH} 이며, is_bot=false.
 *
 * <p>위조 불가의 근거는 하이픈이다 — {@link UsernamePolicy}({@code [A-Za-z0-9_]})가 하이픈을
 * 거부하므로 가입으로는 {@code guest-} 이름을 만들 수 없다. <b>UsernamePolicy 가 하이픈을
 * 허용하게 바뀌면 이 규약이 무너진다</b>(GuestPolicyTest 가 고정). sentinel 은 BCrypt 형식이
 * 아니라서 어떤 비밀번호로도 {@code matches} 가 참이 될 수 없다.
 */
public final class GuestPolicy {

    public static final String PREFIX = "guest-";

    /** SQL LIKE 용 — 정리(sweep)·랭킹 제외 쿼리가 쓴다. {@link #PREFIX} 와 갈리면 안 된다. */
    public static final String LIKE_PATTERN = PREFIX + "%";

    /** 로그인 불가 해시. BCrypt 가 아니므로 {@code PasswordEncoder.matches} 가 항상 거짓. */
    public static final String NO_LOGIN_HASH = "__guest_no_login__";

    /** 소문자+숫자에서 혼동 문자(0/o, 1/l/i)를 뺀 31자. 8자리 → 약 8.5×10^11 가지. */
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int SUFFIX_LENGTH = 8;

    private GuestPolicy() {
    }

    /** {@code guest-} + 8자. 전체 14자로 users.username VARCHAR(20) 안. */
    public static String newUsername(RandomGenerator rng) {
        StringBuilder sb = new StringBuilder(PREFIX.length() + SUFFIX_LENGTH).append(PREFIX);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static boolean isGuest(String username) {
        return username != null && username.startsWith(PREFIX);
    }

    /**
     * 게스트에게 금지된 행위(비밀번호 변경·아바타) 앞에서 호출. JWT 의 username 클레임으로
     * 판정하므로 DB 조회가 없다(username 은 바뀌지 않는다).
     */
    public static void requireNotGuest(AuthPrincipal principal) {
        if (principal != null && isGuest(principal.username())) {
            throw new GuestForbiddenException();
        }
    }
}
