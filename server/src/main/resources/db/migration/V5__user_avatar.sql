-- D-80 — 선택적 코스메틱 아바타. users 화이트리스트(D-02)를 건드리지 않도록
-- 별도 테이블에 저장한다.
--
-- PRIVACY POLICY (D-02 보정, D-80): 아바타는 식별/연락 정보가 아니라 사용자가 임의로
-- 설정·삭제하는 코스메틱이다. users 컬럼은 그대로 두고(절대 금지 컬럼 불변) 본 테이블에
-- 분리 저장한다. 업로드 이미지는 사용자 제공 콘텐츠라 PII 표면이 늘 수 있으므로 기본은
-- 이모지이며 업로드는 본인 선택이다. 이미지는 128x128 PNG 로 서버에서 리사이즈해 저장.

CREATE TABLE user_avatars (
    user_id      BIGINT       NOT NULL,
    image        BYTEA        NOT NULL,
    content_type VARCHAR(32)  NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_avatar_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
