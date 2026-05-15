-- Phase 9A — 봇 사용자 분류 플래그 + 시드 봇 4명 (솔로 모드, D-49).
--
-- PRIVACY POLICY 재확인 (D-02): `is_bot` 은 게임 시스템이 자동 채우는 NPC 와
-- 사람 사용자를 구분하는 분류 플래그. 식별/연락 정보가 아니므로 users 컬럼
-- 화이트리스트에 추가 가능. CLAUDE.md 의 "개인정보 최소화" 원칙 위반 아님.

ALTER TABLE users
    ADD COLUMN is_bot BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_users_is_bot ON users (is_bot) WHERE is_bot = TRUE;

-- 시드 봇 4명. password_hash 는 의도적으로 bcrypt 형식 ($2$...) 이 아니라
-- 평문 매치가 절대 불가능한 sentinel — 어떤 비밀번호로도 로그인할 수 없다.
INSERT INTO users (username, password_hash, is_bot)
VALUES
    ('bot_north', '__bot_no_login__', TRUE),
    ('bot_east',  '__bot_no_login__', TRUE),
    ('bot_south', '__bot_no_login__', TRUE),
    ('bot_west',  '__bot_no_login__', TRUE);
