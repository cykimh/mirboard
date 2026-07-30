-- D-93 — 채팅 신고 적재. D-86 후속.
--
-- 상시 채팅 로그를 영속화하는 테이블이 **아니다**. 채팅 원문은 Redis 링버퍼
-- (`chatlog:{scope}`, TTL 2h)에만 휘발로 남고, 신고된 메시지만 여기로 승격된다.
-- users 화이트리스트는 불변(D-02) — 별도 테이블 선례는 V5(아바타)·V8(admin_roles).
--
-- 컬럼은 신고 판단에 필요한 최소만 둔다. IP·세션·User-Agent 등 식별 정보 금지.
CREATE TABLE chat_reports (
    id                BIGSERIAL    PRIMARY KEY,
    -- 신고 대상 메시지의 STOMP envelope eventId. 서버가 이 값으로 링버퍼를 조회해
    -- 원문/작성자를 확정한다(클라가 본문을 제출하지 않음 = 무고 방지).
    event_id          VARCHAR(36)  NOT NULL,
    -- 'LOBBY' | 'ROOM'. ROOM 이면 room_id 가 채워진다.
    scope             VARCHAR(8)   NOT NULL,
    room_id           VARCHAR(36),
    -- 메시지 작성자(피신고자)와 신고자. users(id) FK — 계정 삭제 기능이 없어 고아 방지용.
    reported_user_id  BIGINT       NOT NULL,
    reporter_user_id  BIGINT       NOT NULL,
    -- 신고 시점의 메시지 스냅샷(마스킹 적용된 broadcast 본문 그대로).
    message           VARCHAR(500) NOT NULL,
    -- 원 메시지 발생 시각(envelope ts)과 신고 접수 시각.
    message_at        TIMESTAMP    NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_reports_reported FOREIGN KEY (reported_user_id) REFERENCES users (id),
    CONSTRAINT fk_chat_reports_reporter FOREIGN KEY (reporter_user_id) REFERENCES users (id),
    -- 같은 사람이 같은 메시지를 반복 신고하지 못하게(남용 방지).
    CONSTRAINT uq_chat_reports_event_reporter UNIQUE (event_id, reporter_user_id)
);

-- 어드민 목록은 최신순 조회가 기본.
CREATE INDEX idx_chat_reports_created_at ON chat_reports (created_at DESC);
-- 특정 유저의 누적 신고 확인용.
CREATE INDEX idx_chat_reports_reported_user ON chat_reports (reported_user_id);
