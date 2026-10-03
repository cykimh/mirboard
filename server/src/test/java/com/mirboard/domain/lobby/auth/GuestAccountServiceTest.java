package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * D-117 — 게스트 생성 오케스트레이션의 실패 경로. 정상 경로는 GuestAuthIntegrationTest 가
 * 실제 PG/Redis 로 본다. 여기서는 "상한을 판정할 수 없으면 만들지 않는다(fail-closed)",
 * "이름 충돌은 새 이름으로 재시도", "정리 실패는 생성을 망치지 않는다"를 고정한다.
 */
class GuestAccountServiceTest {

    /** 2026-10-03 22:00 UTC — 다음 UTC 자정까지 2시간. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T22:00:00Z"), ZoneOffset.UTC);
    private static final String TODAY_KEY = "guest:issued:2026-10-03";

    private UserRepository users;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private GuestAccountSweeper sweeper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        users = mock(UserRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sweeper = mock(GuestAccountSweeper.class);
        when(redis.opsForValue()).thenReturn(values);
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0), 42L));
    }

    private GuestAccountService service(boolean enabled, int dailyCap) {
        var props = new GuestProperties(enabled, dailyCap, Duration.ofHours(48), 100);
        return new GuestAccountService(users, redis, props, sweeper, CLOCK, new SplittableRandom(7));
    }

    @Test
    void disabled_kill_switch_creates_nothing() {
        assertThatThrownBy(() -> service(false, 200).createGuest())
                .isInstanceOf(GuestDisabledException.class);
        verify(values, never()).increment(anyString());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void creates_a_guest_row_with_the_no_login_hash() {
        when(values.increment(TODAY_KEY)).thenReturn(5L);

        var created = service(true, 200).createGuest();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).startsWith(GuestPolicy.PREFIX);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo(GuestPolicy.NO_LOGIN_HASH);
        assertThat(saved.getValue().isBot()).isFalse();
        assertThat(created.userId()).isEqualTo(42L);
        assertThat(created.username()).isEqualTo(saved.getValue().getUsername());
    }

    @Test
    void first_issue_of_the_day_sets_the_counter_ttl() {
        when(values.increment(TODAY_KEY)).thenReturn(1L);

        service(true, 200).createGuest();

        verify(redis).expire(TODAY_KEY, Duration.ofHours(48));
    }

    @Test
    void over_the_daily_cap_is_unavailable_until_utc_midnight() {
        when(values.increment(TODAY_KEY)).thenReturn(201L);

        assertThatThrownBy(() -> service(true, 200).createGuest())
                .isInstanceOfSatisfying(GuestUnavailableException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(7_200L));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void redis_failure_fails_closed() {
        when(values.increment(TODAY_KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service(true, 200).createGuest())
                .isInstanceOf(GuestUnavailableException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void username_collision_retries_with_a_new_name() {
        when(values.increment(TODAY_KEY)).thenReturn(3L);
        when(users.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_username"))
                .thenAnswer(inv -> withId(inv.getArgument(0), 43L));

        var created = service(true, 200).createGuest();

        ArgumentCaptor<User> attempts = ArgumentCaptor.forClass(User.class);
        verify(users, times(2)).saveAndFlush(attempts.capture());
        assertThat(attempts.getAllValues().get(0).getUsername())
                .isNotEqualTo(attempts.getAllValues().get(1).getUsername());
        assertThat(created.userId()).isEqualTo(43L);
    }

    @Test
    void persistent_collisions_give_up_after_three_attempts() {
        when(values.increment(TODAY_KEY)).thenReturn(3L);
        when(users.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_username"));

        assertThatThrownBy(() -> service(true, 200).createGuest())
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(users, times(3)).saveAndFlush(any());
    }

    @Test
    void sweeper_failure_does_not_affect_the_created_guest() {
        when(values.increment(TODAY_KEY)).thenReturn(3L);
        doThrow(new IllegalStateException("sweep boom")).when(sweeper).sweepIfDue();

        var created = service(true, 200).createGuest();

        assertThat(created.userId()).isEqualTo(42L);
        verify(sweeper).sweepIfDue();
    }

    @Test
    void sweep_runs_only_after_a_successful_insert() {
        when(values.increment(eq(TODAY_KEY))).thenReturn(500L);

        assertThatThrownBy(() -> service(true, 200).createGuest())
                .isInstanceOf(GuestUnavailableException.class);
        verify(sweeper, never()).sweepIfDue();
    }

    private static User withId(User user, long id) throws ReflectiveOperationException {
        Field f = User.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(user, id);
        return user;
    }
}
