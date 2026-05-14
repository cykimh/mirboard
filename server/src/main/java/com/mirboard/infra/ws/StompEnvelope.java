package com.mirboard.infra.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 공통 STOMP 메시지 봉투. `docs/stomp-protocol.md` 참조.
 * 방 단위 단조 카운터(`seq`) 가 필요 없는 메시지에서는 null 로 직렬화 시 제외.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StompEnvelope<T>(String eventId, String type, long ts, Long seq, T payload) {

    public static <T> StompEnvelope<T> of(String type, T payload, Clock clock) {
        return new StompEnvelope<>(
                UUID.randomUUID().toString(),
                type,
                Instant.now(clock).toEpochMilli(),
                null,
                payload);
    }
}
