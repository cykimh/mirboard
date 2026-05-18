package com.mirboard.infra.ws;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Phase 19(#1, D-75) — STOMP 세션 → (userId, roomId) in-memory 매핑.
 *
 * <p>Spring 의 SUBSCRIBE/DISCONNECT 이벤트만으로는 "어떤 세션이 어느 방을
 * 보고 있는지" 알 수 없어, 게임 토픽 구독 시 등록하고 끊김 시 제거한다.
 *
 * <p><b>트레이드오프</b>: 단일 인스턴스 MVP(D-03) 전제라 in-memory
 * (TurnTimeoutScheduler 의 generation 맵과 동일 가정). 서버 재시작 시
 * 소실되며, 그 경우 호스트 {@code abortGame} 이 백업 탈출구. 다중 인스턴스
 * 전환 시 Redis presence(`presence:room:{id}`)로 교체해야 한다 — 범위 밖.
 */
@Component
public class WsSessionRegistry {

    public record SessionInfo(long userId, String roomId) {
    }

    private final ConcurrentHashMap<String, SessionInfo> bySession = new ConcurrentHashMap<>();

    public void register(String sessionId, long userId, String roomId) {
        bySession.put(sessionId, new SessionInfo(userId, roomId));
    }

    public Optional<SessionInfo> remove(String sessionId) {
        return Optional.ofNullable(bySession.remove(sessionId));
    }

    /**
     * 해당 유저가 지정한 방의 라이브 세션을 (아직) 갖고 있는지 — 끊김 유예
     * 만료 시 "재접속했는가" 판정에 사용. 단일 세션이 끊겨도 새 세션이
     * 같은 방을 구독했으면 true.
     */
    public boolean hasLiveSession(long userId, String roomId) {
        return bySession.values().stream()
                .anyMatch(s -> s.userId() == userId && s.roomId().equals(roomId));
    }
}
