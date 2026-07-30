package com.mirboard.infra.ws;

import com.mirboard.infra.scheduling.DeadlineHandler;
import com.mirboard.infra.scheduling.DeadlineQueue;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 19(#3, D-75) — IN_GAME 끊김 후 유예 시간 내 미복귀면 탈주 확정.
 *
 * <p>D-96 — in-memory {@code ScheduledFuture} 맵에서 <b>Redis 데드라인 큐</b>로 옮겼다.
 * 구 구조는 유예를 건 인스턴스가 죽으면 <b>탈주가 영원히 확정되지 않았다</b>(타이머가
 * 프로세스와 함께 사라짐). 이제 만료 시각이 Redis 에 있으므로 어느 인스턴스든 인계한다.
 *
 * <p>재접속 판정도 {@link RoomPresence}(Redis) 로 옮겨, A 인스턴스에 붙은 재접속을
 * B 인스턴스의 유예 만료가 볼 수 있다 — 구 in-memory 레지스트리에서는 이게 불가능해
 * <b>재접속한 사람을 탈주 처리</b>했다.
 */
@Component
public class DesertionGraceScheduler implements DeadlineHandler {

    private static final Logger log = LoggerFactory.getLogger(DesertionGraceScheduler.class);

    /** `deadlines:desertion` 큐. */
    public static final String KIND = "desertion";

    private final RoomPresence presence;
    private final DesertionService desertion;
    private final DeadlineQueue deadlines;
    private final long graceSeconds;

    public DesertionGraceScheduler(
            RoomPresence presence,
            DesertionService desertion,
            DeadlineQueue deadlines,
            @Value("${mirboard.desertion.grace-seconds:120}") long graceSeconds) {
        this.presence = presence;
        this.desertion = desertion;
        this.deadlines = deadlines;
        this.graceSeconds = graceSeconds;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** IN_GAME 끊김 시 호출. 같은 (room,user) 재등록은 ZADD 의미로 기존 유예를 대체한다. */
    public void scheduleGrace(String roomId, long userId) {
        deadlines.schedule(KIND, member(roomId, userId), Duration.ofSeconds(graceSeconds));
        log.info("Desertion grace scheduled: roomId={} userId={} graceSec={}",
                roomId, userId, graceSeconds);
    }

    /** 폴러가 만료분을 넘겨준다. */
    @Override
    public void handle(String member) {
        int sep = member.lastIndexOf(':');
        if (sep < 0) {
            log.warn("탈주 유예 member 형식 오류: {}", member);
            return;
        }
        String roomId = member.substring(0, sep);
        long userId;
        try {
            userId = Long.parseLong(member.substring(sep + 1));
        } catch (NumberFormatException e) {
            log.warn("탈주 유예 userId 파싱 실패: {}", member);
            return;
        }

        // 재접속 판정은 Redis 프레즌스 — 다른 인스턴스로 돌아왔어도 보인다.
        if (presence.hasLiveSession(userId, roomId)) {
            log.info("Desertion grace: 재접속 확인 — abort. roomId={} userId={}", roomId, userId);
            return;
        }
        desertion.processDesertion(roomId, userId);
    }

    /**
     * 재접속 시 호출 — 대기 중인 탈주 유예가 있으면 취소하고 {@code true}.
     * 호출자는 true 일 때만 RECONNECTED 알림을 보낸다.
     *
     * <p>ZSET {@code ZREM} 의 반환값이 곧 "정말 대기 중이었는가"라 in-memory 시절의
     * future null 체크와 의미가 같다.
     */
    public boolean cancelIfPending(String roomId, long userId) {
        boolean removed = deadlines.cancelExisting(KIND, member(roomId, userId));
        if (removed) {
            log.info("Desertion grace cancelled (reconnect): roomId={} userId={}", roomId, userId);
        }
        return removed;
    }

    /** 데드라인 member = `{roomId}:{userId}`. */
    static String member(String roomId, long userId) {
        return roomId + ":" + userId;
    }
}
