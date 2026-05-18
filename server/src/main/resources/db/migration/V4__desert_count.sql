-- Phase 19(#3) — users.desert_count 컬럼 추가. IN_GAME 탈주(명시 '나가기' /
-- WS 끊김 후 유예시간 내 미복귀) 누적 횟수. 매치 탈주 확정 시 +1.
--
-- PRIVACY POLICY 재확인 (D-02): desert_count 는 게임 행동 집계용 derived 값이지
-- 식별/연락 정보가 아니므로 users 컬럼 화이트리스트에 추가 가능 (V2 rating·
-- V3 is_bot 선례 동일). CLAUDE.md 의 "개인정보 최소화" 원칙 위반 아님.

ALTER TABLE users
    ADD COLUMN desert_count INT NOT NULL DEFAULT 0;
