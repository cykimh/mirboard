-- D-102 (M5/T7 S5) — 봇 풀 4 → 8 확장.
--
-- 시드 봇 4명(V3)으로는 스컬킹 8인 방(호스트 1 + 봇 7)을 채울 수 없다 — D-99 에서 발견,
-- 티츄(4인 고정)에서는 도달 불가였던 한계. V3 과 같은 형식으로 4명을 추가한다.
-- password_hash 는 BCrypt 형식이 아니므로 로그인 절대 불가 (V3 과 동일한 봉인).
--
-- PRIVACY POLICY 재확인 (D-02): 봇은 게임 시스템 NPC 계정 — 개인정보 아님.

INSERT INTO users (username, password_hash, is_bot)
VALUES
    ('bot_northeast', '__bot_no_login__', TRUE),
    ('bot_southeast', '__bot_no_login__', TRUE),
    ('bot_southwest', '__bot_no_login__', TRUE),
    ('bot_northwest', '__bot_no_login__', TRUE);
