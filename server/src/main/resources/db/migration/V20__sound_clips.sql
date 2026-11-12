-- D-265 — 게임 음성 클립. 운영자(admin_roles)가 올리는 짧은 음성 파일("풀하우스!"·"원카드!" 등)을 키별로 한 개씩 둔다.
-- users 는 건드리지 않는다(D-02 화이트리스트 불변). 키는 서버 허용 목록(SoundClipKey)에 있는 것만 들어온다.
-- 파일은 500KB 이하(서버 검증)라 BYTEA 한 칸에 둔다 — 없으면 클라가 브라우저 읽어 주기로 대신한다.
CREATE TABLE sound_clips (
    clip_key     VARCHAR(64)  NOT NULL,
    content_type VARCHAR(32)  NOT NULL,
    data         BYTEA        NOT NULL,
    byte_size    INTEGER      NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (clip_key)
);
