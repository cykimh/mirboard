package com.mirboard.domain.lobby.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * D-117 — 방문자별 일회용 게스트 계정 생성.
 *
 * <p>공유 데모 계정(D-105)은 "userId 하나 = 사람 한 명" 전제를 깨서 좌석 탈취·손패 유출이
 * 생긴다. 그래서 방문자마다 {@code users} 행을 하나 만들고 JWT 를 바로 준다. 게스트 식별은
 * {@link GuestPolicy} 규약이다.
 *
 * <p>순서: 킬스위치 → Redis 일일 전역 슬롯 예약(INCR 원자, 판정 불가면 fail-closed) → INSERT
 * (username 유니크 충돌이면 새 이름으로 최대 3회) → 커밋 뒤 정리({@link GuestAccountSweeper})
 * 기회 실행. IP 당 한도는 여기가 아니라 레이트리밋 필터(`guest` 버킷)가 맡는다.
 *
 * <p><b>⚠️ 이 클래스에 {@code @Transactional} 을 붙이지 말 것.</b> ① 유니크 위반이 난
 * 트랜잭션은 rollback-only 라 같은 트랜잭션에서 재시도할 수 없다 — INSERT 는
 * {@code saveAndFlush} 의 자체 트랜잭션으로 한 번씩 커밋/롤백돼야 한다. ② 정리 쪽 오류가
 * 바깥 트랜잭션을 rollback-only 로 만들면 방금 만든 게스트까지 사라진다.
 * (이 계약을 강제하는 테스트는 없다 — 붙이면 재시도가 rollback-only 트랜잭션에 갇히니 고칠 때 주의.)
 *
 * <p>로그에 IP 를 남기지 않는다(로그인/가입 경로와 같은 원칙).
 */
@Service
public class GuestAccountService {

    private static final Logger log = LoggerFactory.getLogger(GuestAccountService.class);

    static final String ISSUED_KEY_PREFIX = "guest:issued:";
    /** 날짜 키는 하루만 쓰이지만 자정 경계의 늦은 INCR 까지 덮도록 이틀. */
    static final Duration ISSUED_KEY_TTL = Duration.ofHours(48);
    private static final int MAX_NAME_ATTEMPTS = 3;

    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final RedisScript<Long> issueScript;
    private final GuestProperties props;
    private final GuestAccountSweeper sweeper;
    private final Clock clock;
    private final RandomGenerator rng;

    @Autowired
    public GuestAccountService(UserRepository users, StringRedisTemplate redis,
                               @Qualifier("guestDailyIssueScript") RedisScript<Long> issueScript,
                               GuestProperties props, GuestAccountSweeper sweeper, Clock clock) {
        this(users, redis, issueScript, props, sweeper, clock, new SecureRandom());
    }

    GuestAccountService(UserRepository users, StringRedisTemplate redis, RedisScript<Long> issueScript,
                        GuestProperties props, GuestAccountSweeper sweeper, Clock clock,
                        RandomGenerator rng) {
        this.users = users;
        this.redis = redis;
        this.issueScript = issueScript;
        this.props = props;
        this.sweeper = sweeper;
        this.clock = clock;
        this.rng = rng;
    }

    public record CreatedGuest(long userId, String username) {
    }

    public CreatedGuest createGuest() {
        if (!props.enabled()) {
            throw new GuestDisabledException();
        }
        reserveDailySlot();
        User saved = insertWithFreshName();
        try {
            sweeper.sweepIfDue();
        } catch (RuntimeException e) {
            // 정리는 부가 작업 — 실패해도 방금 만든 게스트는 정상 반환.
            log.warn("Guest sweep failed (guest creation unaffected): {}", e.toString());
        }
        log.info("Guest created: userId={} username={}", saved.getId(), saved.getUsername());
        return new CreatedGuest(saved.getId(), saved.getUsername());
    }

    /**
     * UTC 하루 전역 상한. INCR 이 원자라 다중 인스턴스에서도 상한을 넘겨 발급하지 않는다.
     * 첫 EXPIRE 도 같은 스크립트(`guest_daily_issue.lua`)라 TTL 없는 날짜 키가 남지 않는다.
     * Redis 가 응답하지 않으면 만들지 않는다 — 어차피 Redis 없이는 방이 동작하지 않는다.
     */
    private void reserveDailySlot() {
        Instant now = clock.instant();
        String key = ISSUED_KEY_PREFIX + LocalDate.ofInstant(now, ZoneOffset.UTC);
        long issued;
        try {
            Long n = redis.execute(issueScript, List.of(key),
                    Long.toString(ISSUED_KEY_TTL.toSeconds()));
            if (n == null) {
                throw new GuestUnavailableException(secondsUntilNextUtcMidnight(now));
            }
            issued = n;
        } catch (DataAccessException e) {
            log.warn("Guest daily cap check failed (failing closed): {}", e.toString());
            throw new GuestUnavailableException(secondsUntilNextUtcMidnight(now));
        }

        int cap = props.dailyCap();
        if (issued == (long) Math.ceil(cap * 0.7)) {
            log.warn("Guest daily cap 70% reached: issued={} cap={}", issued, cap);
        }
        if (issued > cap) {
            if (issued == cap + 1L) {
                // ERROR 는 Sentry 이벤트가 된다(D-107) — 그날 첫 거절에서 한 번만.
                log.error("Guest daily cap exhausted: cap={} — new guests refused until UTC midnight",
                        cap);
            }
            throw new GuestUnavailableException(secondsUntilNextUtcMidnight(now));
        }
    }

    private User insertWithFreshName() {
        for (int attempt = 1; ; attempt++) {
            String username = GuestPolicy.newUsername(rng);
            try {
                return users.saveAndFlush(User.create(username, GuestPolicy.NO_LOGIN_HASH, clock));
            } catch (DataIntegrityViolationException e) {
                if (attempt >= MAX_NAME_ATTEMPTS) {
                    throw e;
                }
                log.debug("Guest username collision, retrying: attempt={}", attempt);
            }
        }
    }

    private static long secondsUntilNextUtcMidnight(Instant now) {
        Instant midnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return Math.max(1L, Duration.between(now, midnight).toSeconds());
    }
}
