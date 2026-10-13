package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.infra.scheduling.DeadlineHandler;
import com.mirboard.infra.scheduling.DeadlineQueue;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * D-134 — 대기실(WAITING) 플레이어 끊김 뒤 유예 시간 안에 돌아오지 않으면 내보낸다.
 *
 * <p>D-75 는 대기실 끊김을 즉시 leave 했다. 초대 링크(D-133)로 방장이 휴대폰 공유창에서 카톡으로 나가 친구를 기다리는 것이 주
 * 시나리오가 되자, 백그라운드 10~15초면 소켓이 죽는 휴대폰(D-79)에서 혼자 만든 방이 친구가 오기 전에 사라졌다. 그래서 끊김 처리
 * ({@link RoomDisconnectHandler})가 그 사람의 준비를 풀고 이 유예를 건다. 방 토픽을 다시 구독하면(돌아옴) 취소된다.
 *
 * <p>모양은 {@link DesertionGraceScheduler} 와 같다 — 만료 시각이 Redis 데드라인 큐({@code deadlines:waiting-leave}, D-96)에 있어
 * 어느 인스턴스든 처리한다. 그래서 만료 시점에 방을 다시 읽어 <b>아직 WAITING 이고 그 사람이 플레이어이며 라이브 세션이 없을 때만</b>
 * 내보낸다(마지막 사람이면 방 소멸 — 기존 leave 그대로). 방이 없어졌거나·시작됐거나·이미 나갔거나·돌아왔으면 아무것도 안 한다.
 */
@Component
public class WaitingLeaveGraceScheduler implements DeadlineHandler {

    private static final Logger log = LoggerFactory.getLogger(WaitingLeaveGraceScheduler.class);

    /** `deadlines:waiting-leave` 큐. */
    public static final String KIND = "waiting-leave";

    private final RoomService roomService;
    private final RoomPresence presence;
    private final DeadlineQueue deadlines;
    private final long graceSeconds;

    public WaitingLeaveGraceScheduler(
            RoomService roomService,
            RoomPresence presence,
            DeadlineQueue deadlines,
            @Value("${mirboard.waiting.grace-seconds:120}") long graceSeconds) {
        this.roomService = roomService;
        this.presence = presence;
        this.deadlines = deadlines;
        this.graceSeconds = graceSeconds;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** 대기실 끊김 시 호출. 같은 (방, 사람) 재등록은 ZADD 의미로 기존 유예를 대체한다. */
    public void scheduleGrace(String roomId, long userId) {
        deadlines.schedule(KIND, member(roomId, userId), Duration.ofSeconds(graceSeconds));
        log.info("Waiting leave grace scheduled: roomId={} userId={} graceSec={}", roomId, userId, graceSeconds);
    }

    /** 폴러가 만료분을 넘겨준다. */
    @Override
    public void handle(String member) {
        int sep = member.lastIndexOf(':');
        if (sep < 0) {
            log.warn("대기실 유예 member 형식 오류: {}", member);
            return;
        }
        String roomId = member.substring(0, sep);
        long userId;
        try {
            userId = Long.parseLong(member.substring(sep + 1));
        } catch (NumberFormatException e) {
            log.warn("대기실 유예 userId 파싱 실패: {}", member);
            return;
        }

        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            return; // 이미 소멸.
        }
        if (room.status() != RoomStatus.WAITING || !room.playerIds().contains(userId)) {
            return; // 시작됐거나(좌석 불변 — D-122) 이미 나갔다.
        }
        // 재접속 판정은 Redis 프레즌스 — 다른 인스턴스로 돌아왔어도 보인다.
        if (presence.hasLiveSession(userId, roomId)) {
            log.info("Waiting leave grace: 재접속 확인 — 자리 유지. roomId={} userId={}", roomId, userId);
            return;
        }
        try {
            roomService.leaveRoom(roomId, userId);
            log.info("Waiting leave grace expired → leave: roomId={} userId={}", roomId, userId);
        } catch (RoomNotFoundException | NotInRoomException e) {
            // 다시 읽은 뒤 내보내기 전에 방이 사라졌거나 스스로 나갔다 — 할 일이 없다.
            log.info("Waiting leave grace: 이미 정리됨. roomId={} userId={} reason={}", roomId, userId, e.toString());
        }
    }

    /**
     * 방 토픽 재구독(돌아옴) 시 호출 — 대기 중인 대기실 유예가 있으면 취소하고 {@code true}. 풀었던 준비는 되돌리지 않는다
     * (돌아온 사람이 다시 누른다).
     */
    public boolean cancelIfPending(String roomId, long userId) {
        boolean removed = deadlines.cancelExisting(KIND, member(roomId, userId));
        if (removed) {
            log.info("Waiting leave grace cancelled (reconnect): roomId={} userId={}", roomId, userId);
        }
        return removed;
    }

    /** 데드라인 member = `{roomId}:{userId}`. */
    static String member(String roomId, long userId) {
        return roomId + ":" + userId;
    }
}
