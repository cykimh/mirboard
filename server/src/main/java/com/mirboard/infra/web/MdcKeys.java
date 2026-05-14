package com.mirboard.infra.web;

import org.slf4j.MDC;

/**
 * MDC 키 표준화. 로그 패턴 {@code %X{userId} %X{roomId} %X{eventId}} 와 짝.
 *
 * <p>요청/메시지 처리 진입에서 {@link #scope} 로 try-with-resources 블록을 열고,
 * 블록 종료 시 자동으로 클리어된다. 가상 스레드 / 풀 재사용 환경에서 MDC 누수를
 * 막기 위해 명시 클리어가 필수.
 */
public final class MdcKeys {

    public static final String USER_ID = "userId";
    public static final String ROOM_ID = "roomId";
    public static final String EVENT_ID = "eventId";

    private MdcKeys() {}

    /** try-with-resources 로 닫히면 MDC 의 해당 키들을 모두 제거한다. */
    public static MdcScope scope() {
        return new MdcScope();
    }

    public static final class MdcScope implements AutoCloseable {
        private MdcScope() {}

        public MdcScope userId(Long userId) {
            if (userId != null) MDC.put(USER_ID, userId.toString());
            return this;
        }

        public MdcScope roomId(String roomId) {
            if (roomId != null) MDC.put(ROOM_ID, roomId);
            return this;
        }

        public MdcScope eventId(String eventId) {
            if (eventId != null) MDC.put(EVENT_ID, eventId);
            return this;
        }

        @Override
        public void close() {
            MDC.remove(USER_ID);
            MDC.remove(ROOM_ID);
            MDC.remove(EVENT_ID);
        }
    }
}
