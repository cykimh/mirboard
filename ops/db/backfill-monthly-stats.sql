-- D-209 — 이번 달 전적(user_monthly_stats) 다시 채우기. **운영자가 직접 한 번** 돌린다(배포로 자동 실행하지 않는다).
--
-- V19 배포 직후 이번 달 기록은 0 에서 시작한다. 이 스크립트는 게임별 매치 기록 테이블(*_match_results / *_match_participants)에서
-- 이번 달(Asia/Seoul 달력) 판을 다시 세어 채운다. 여러 번 돌려도 같은 결과다 — 매치 기록에는 배포 뒤의 판도 들어 있으므로
-- 더하지 않고 **다시 센 값으로 바꾼다**(배포 뒤에 쌓인 행을 두 번 세지 않는다).
--
-- 쓰는 법 (운영 DB 는 mirboard 스키마 — D-169. 로컬은 public):
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "SET search_path TO mirboard" -f ops/db/backfill-monthly-stats.sql
--   (psql 은 -c 와 -f 를 한 세션에서 차례로 돌린다. 또는 이 파일 맨 위에 SET search_path 한 줄을 넣는다.)
--
-- 앱이 쌓는 값과 다를 수 있는 곳:
--   1. 티츄 탈주 수 — tichu_match_participants 에 탈주 표시가 없어 0 으로 센다(승·패는 맞다). 이미 쌓인 값이 더 크면 그 값을 둔다.
--   2. 씨앗포 무승부 — 무승부 표시 칸이 없어 "이긴 사람이 없는 판"을 무승부로 본다(탈주자는 패).
--   3. finished_at 은 시간대 없는 TIMESTAMP 다 — 서버·DB 가 UTC 라고 보고 KST 로 바꾼다(운영 Fly·Supabase 는 UTC).
--   4. 얏추 1인 판(D-181)은 매치 기록에 없어 여기서도 세지 않는다. 봇은 뺀다. 게스트 행은 앱처럼 쌓되 랭킹 조회가 뺀다.

BEGIN;

WITH bounds AS (
    -- 이번 달 1일 0시(KST 벽시계).
    SELECT date_trunc('month', now() AT TIME ZONE 'Asia/Seoul') AS kst_start
),
plays AS (
    SELECT p.user_id, 'TICHU' AS game_type, r.finished_at, r.id AS match_id,
           CASE WHEN p.is_win THEN 'W' ELSE 'L' END AS result, FALSE AS deserted
    FROM tichu_match_participants p JOIN tichu_match_results r ON r.id = p.match_id
    UNION ALL
    SELECT p.user_id, 'SKULL_KING', r.finished_at, r.id,
           CASE WHEN p.is_win THEN 'W' ELSE 'L' END, p.deserted
    FROM skullking_match_participants p JOIN skullking_match_results r ON r.id = p.match_id
    UNION ALL
    SELECT p.user_id, 'ONE_CARD', r.finished_at, r.id,
           CASE WHEN p.is_win THEN 'W' ELSE 'L' END, p.deserted
    FROM onecard_match_participants p JOIN onecard_match_results r ON r.id = p.match_id
    UNION ALL
    SELECT p.user_id, 'CLOUD_ROUTE', r.finished_at, r.id,
           CASE WHEN p.is_win THEN 'W' ELSE 'L' END, p.deserted
    FROM cloudroute_match_participants p JOIN cloudroute_match_results r ON r.id = p.match_id
    UNION ALL
    SELECT p.user_id, 'YACHT', r.finished_at, r.id,
           CASE WHEN p.is_win THEN 'W' ELSE 'L' END, p.deserted
    FROM yacht_match_participants p JOIN yacht_match_results r ON r.id = p.match_id
    UNION ALL
    SELECT p.user_id, 'OMOK', r.finished_at, r.id,
           CASE WHEN p.is_draw THEN 'D' WHEN p.is_win THEN 'W' ELSE 'L' END, p.deserted
    FROM omok_match_participants p JOIN omok_match_results r ON r.id = p.match_id
    UNION ALL
    SELECT p.user_id, 'ARTILLERY', r.finished_at, r.id,
           CASE WHEN p.is_win THEN 'W'
                WHEN NOT p.deserted AND NOT EXISTS (
                    SELECT 1 FROM artillery_match_participants w WHERE w.match_id = p.match_id AND w.is_win
                ) THEN 'D'
                ELSE 'L' END,
           p.deserted
    FROM artillery_match_participants p JOIN artillery_match_results r ON r.id = p.match_id
),
month_plays AS (
    SELECT pl.*
    FROM plays pl
    JOIN users u ON u.id = pl.user_id
    CROSS JOIN bounds b
    WHERE u.is_bot = FALSE
      AND (pl.finished_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul') >= b.kst_start
),
expanded AS (
    -- 게임 행 + 전체 행('*').
    SELECT user_id, game_type, finished_at, match_id, result, deserted FROM month_plays
    UNION ALL
    SELECT user_id, '*', finished_at, match_id, result, deserted FROM month_plays
),
ranked AS (
    SELECT e.*,
           ROW_NUMBER() OVER (PARTITION BY user_id, game_type ORDER BY finished_at DESC, match_id DESC) AS rn
    FROM expanded e
)
INSERT INTO user_monthly_stats (user_id, game_type, stat_month, win_count, lose_count, desert_count, recent, updated_at)
SELECT user_id,
       game_type,
       (SELECT kst_start::date FROM bounds),
       COUNT(*) FILTER (WHERE result = 'W'),
       COUNT(*) FILTER (WHERE result = 'L'),
       COUNT(*) FILTER (WHERE deserted),
       -- 최근 5판을 오래된 것 → 최근 순으로.
       COALESCE(STRING_AGG(result, '' ORDER BY finished_at, match_id) FILTER (WHERE rn <= 5), ''),
       CURRENT_TIMESTAMP
FROM ranked
GROUP BY user_id, game_type
ON CONFLICT (user_id, game_type, stat_month) DO UPDATE SET
    win_count    = EXCLUDED.win_count,
    lose_count   = EXCLUDED.lose_count,
    desert_count = GREATEST(user_monthly_stats.desert_count, EXCLUDED.desert_count),
    recent       = EXCLUDED.recent,
    updated_at   = CURRENT_TIMESTAMP;

-- 확인: 이번 달 전체 행 상위 20.
SELECT u.username, m.win_count, m.lose_count, m.desert_count, m.recent
FROM user_monthly_stats m
JOIN users u ON u.id = m.user_id
WHERE m.game_type = '*'
  AND m.stat_month = date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')::date
ORDER BY m.win_count DESC, m.lose_count ASC
LIMIT 20;

COMMIT;
