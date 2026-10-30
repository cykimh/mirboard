-- D-149 — 회원 초기화(운영). 사람 계정과 그에 딸린 기록을 모두 지우고 시드 봇(is_bot=true)만 남긴다.
-- 되돌릴 수 없다. 사용자가 직접 실행한다(docs/deploy.md "회원 초기화").
--   flyctl postgres connect -a mirboard-db -d mirboard   → 이 파일 내용을 붙여 넣는다.
-- 매치 기록은 참가자가 users 를 가리켜(ON DELETE 없음) 회원보다 먼저 지운다. 봇 매치도 함께 지운다(전적·랭킹 전부 초기화).
BEGIN;
DELETE FROM chat_reports;
DELETE FROM admin_roles;
DELETE FROM tichu_match_participants;
DELETE FROM tichu_match_results;
DELETE FROM skullking_match_participants;
DELETE FROM skullking_match_results;
DELETE FROM onecard_match_participants;
DELETE FROM onecard_match_results;
DELETE FROM user_game_stats;
DELETE FROM user_avatars;
DELETE FROM users WHERE is_bot = false;
SELECT count(*) AS remaining_users, count(*) FILTER (WHERE is_bot) AS bots FROM users;
COMMIT;
