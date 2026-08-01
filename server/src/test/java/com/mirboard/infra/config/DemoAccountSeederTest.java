package com.mirboard.infra.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.auth.AuthService;
import com.mirboard.domain.lobby.auth.InvalidPasswordException;
import com.mirboard.domain.lobby.auth.InvalidUsernameException;
import com.mirboard.domain.lobby.auth.UsernameTakenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * D-105 — 데모 시더의 계약은 하나다: <b>무슨 일이 있어도 기동을 막지 않는다.</b>
 *
 * <p>{@link ApplicationRunner} 에서 예외가 새 나가면 Spring 이 컨텍스트를 닫고 프로세스가
 * 죽는다. 데모 계정은 쇼케이스용 부가 기능이라, 설정 오타 하나로 서비스 전체가 크래시
 * 루프에 빠지면 안 된다. 이 테스트가 없으면 catch 절이 다음 리팩터에서 조용히 좁아진다.
 */
class DemoAccountSeederTest {

    private static final String PW = "demo-password-1234";

    private ApplicationRunner runnerFor(AuthService authService, String username, String password) {
        return new DemoAccountSeeder().seedDemoAccount(authService, username, password);
    }

    @Test
    @DisplayName("비밀번호가 비어 있으면 register 를 부르지 않고 조용히 건너뛴다")
    void skipsWhenPasswordBlank() throws Exception {
        var authService = mock(AuthService.class);

        runnerFor(authService, "demo", "   ").run(null);

        verify(authService, never()).register(anyString(), anyString());
    }

    @Test
    @DisplayName("이미 있는 계정이면 건너뛴다 (재기동 안전)")
    void survivesUsernameTaken() {
        var authService = mock(AuthService.class);
        when(authService.register(any(), any())).thenThrow(new UsernameTakenException("taken"));

        assertThatCode(() -> runnerFor(authService, "demo", PW).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정책 위반 비밀번호(8자 미만)로도 기동을 막지 않는다")
    void survivesInvalidPassword() {
        var authService = mock(AuthService.class);
        when(authService.register(any(), any())).thenThrow(new InvalidPasswordException());

        assertThatCode(() -> runnerFor(authService, "demo", "short").run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정책 위반 사용자명(하이픈 등)으로도 기동을 막지 않는다")
    void survivesInvalidUsername() {
        var authService = mock(AuthService.class);
        when(authService.register(any(), any())).thenThrow(new InvalidUsernameException("bad"));

        assertThatCode(() -> runnerFor(authService, "demo-user", PW).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("두 인스턴스 동시 최초 부팅(유니크 제약 충돌)에도 기동을 막지 않는다")
    void survivesUniqueConstraintRace() {
        var authService = mock(AuthService.class);
        when(authService.register(any(), any()))
                .thenThrow(new DataIntegrityViolationException("uk_users_username"));

        assertThatCode(() -> runnerFor(authService, "demo", PW).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("예상 못 한 예외(DB 다운 등)에도 기동을 막지 않는다")
    void survivesUnexpectedFailure() {
        var authService = mock(AuthService.class);
        when(authService.register(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> runnerFor(authService, "demo", PW).run(null)).doesNotThrowAnyException();
    }
}
