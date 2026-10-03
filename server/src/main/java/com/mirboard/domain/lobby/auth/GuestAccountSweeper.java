package com.mirboard.domain.lobby.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D-117 — 한 판도 끝내지 않은 채 보존 기간(기본 48h)이 지난 게스트 행 정리.
 *
 * <p>별도 스케줄러 없이 <b>게스트 생성 경로에서</b> 기회적으로 돈다({@link #sweepIfDue}).
 * Redis {@code SET NX EX 600} 락을 잡은 인스턴스만 실행하므로 다중 인스턴스에서도 10분에 1회다.
 * 게스트가 안 생기는 날엔 정리도 안 돌지만, 그날은 쌓이는 행도 없다.
 *
 * <p>"매치를 끝낸 적 없음"의 근거는 {@code user_game_stats} 행 부재다 — D-115 의 "비봇
 * 참가자마다 user_game_stats 행" 계약에 기댄다(docs/game-port.md). 끝낸 게스트는 참가자 FK
 * 때문에 지울 수 없고(전적 보존), 신고·어드민 역할에 연루된 행도 남긴다.
 *
 * <p>삭제는 id 하나당 {@code REQUIRES_NEW} 트랜잭션이다. 계약을 어긴 행(참가 기록은 있는데
 * stats 가 없음)은 FK 위반으로 그 id 만 건너뛰고 warn 을 남긴다 — 독 행 하나가 나머지 정리를
 * 막지 않게. 커서({@code guest:sweep:cursor})로 다음 회차는 그 뒤부터 보므로, 독 행이 배치
 * 크기 이상 쌓여도 정리가 같은 자리에서 멈추지 않는다.
 */
@Component
public class GuestAccountSweeper {

    private static final Logger log = LoggerFactory.getLogger(GuestAccountSweeper.class);

    static final String LOCK_KEY = "guest:sweep:lock";
    static final String CURSOR_KEY = "guest:sweep:cursor";
    static final Duration LOCK_TTL = Duration.ofMinutes(10);
    static final Duration CURSOR_TTL = Duration.ofDays(7);

    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final GuestProperties props;
    private final TransactionTemplate perRowTx;
    private final Clock clock;

    public GuestAccountSweeper(UserRepository users, StringRedisTemplate redis, GuestProperties props,
                               PlatformTransactionManager txManager, Clock clock) {
        this.users = users;
        this.redis = redis;
        this.props = props;
        this.perRowTx = new TransactionTemplate(txManager);
        this.perRowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public record SweepResult(int deleted, int skipped) {
    }

    /** 락을 잡았을 때만 1회 정리. 10분 안의 두 번째 호출은 아무것도 안 한다. */
    public void sweepIfDue() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            sweepOnce();
        }
    }

    /** 커서 뒤에서 배치 하나를 정리한다. 락 없이 부르므로 테스트·운영 수동 실행용. */
    public SweepResult sweepOnce() {
        long afterId = readCursor();
        // created_at 은 TIMESTAMP(타임존 없음)에 UTC 벽시계로 저장된다(hibernate.jdbc.time_zone: UTC).
        // 같은 기준의 LocalDateTime 으로 비교해 DB 세션 시간대의 영향을 받지 않게 한다.
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minus(props.retention()), ZoneOffset.UTC);
        int batch = props.sweepBatch();
        List<Long> candidates = users.findExpiredGuestIds(
                afterId, cutoff, GuestPolicy.LIKE_PATTERN, GuestPolicy.NO_LOGIN_HASH, batch);

        int deleted = 0;
        int skipped = 0;
        for (Long id : candidates) {
            try {
                Integer n = perRowTx.execute(status ->
                        users.deleteGuestById(id, GuestPolicy.LIKE_PATTERN, GuestPolicy.NO_LOGIN_HASH));
                deleted += n == null ? 0 : n;
            } catch (DataIntegrityViolationException e) {
                // 계약 위반(참가 기록만 있고 user_game_stats 없음) 또는 users 를 참조하는 새 FK
                // 테이블 — 정리 쿼리를 같이 고쳐야 한다는 신호.
                skipped++;
                log.warn("Guest sweep skipped userId={} (still referenced): {}", id,
                        e.getMostSpecificCause().getMessage());
            }
        }
        writeCursor(candidates.size() < batch ? 0L : candidates.getLast());
        if (deleted > 0 || skipped > 0) {
            log.info("Guest sweep: deleted={} skipped={} afterId={}", deleted, skipped, afterId);
        }
        return new SweepResult(deleted, skipped);
    }

    private long readCursor() {
        String raw = redis.opsForValue().get(CURSOR_KEY);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void writeCursor(long id) {
        redis.opsForValue().set(CURSOR_KEY, Long.toString(id), CURSOR_TTL);
    }
}
