-- D-305 — 통합 레이팅. 계급은 게임별 레이팅이 아니라 사람마다 하나인 통합 레이팅에서 나온다.
--
-- 저장: user_game_stats 의 game_type = '*' 행(이번 달 전적 user_monthly_stats 의 '*' 와 같은 방식 — '*' 는 게임 id 로 쓸 수
--       없는 글자). rating = 통합 레이팅, win/lose/desert_count = 모든 게임 합. 새 테이블·컬럼 없음(users 화이트리스트 불변, D-02).
-- 시작값: 게임별 레이팅을 판 수(승+패)로 가중 평균해 반올림. 승·패가 한 판도 없는 사람(무승부만 한 사람)은 가진 행의 단순 평균.
--         전적 행이 없는 사람은 행을 만들지 않는다 — 앱이 1000·0판으로 읽는다.
-- 대상: 사람 계정만(봇 is_bot · 게스트 'guest-' 접두 제외 — 게스트·봇의 레이팅은 저장하지 않는다, D-299).
-- 스키마 이름을 적지 않는다 — Flyway 기본 스키마(운영 MIRBOARD_DB_SCHEMA, D-169)를 따른다.
--
-- 다시 돌려도 안전: 백업·통합 행 모두 ON CONFLICT DO NOTHING — 이미 있는 통합 레이팅(그 뒤 판으로 움직인 값)을 덮지 않는다.
--
-- 되돌리기(운영자가 손으로 — docs/deploy.md "통합 레이팅"):
--   (가) 통합 레이팅을 시작값으로 다시 맞추기: 통합 행을 지우고 이 파일의 두 번째 INSERT 를 다시 돌린다
--        (지금의 게임별 레이팅으로 다시 평균한다).
--          DELETE FROM user_game_stats WHERE game_type = '*';
--   (나) 게임별 계급으로 돌아가기(코드도 D-305 이전으로 배포): 통합 행을 지우고 게임별 레이팅을 백업 시점 값으로.
--          DELETE FROM user_game_stats WHERE game_type = '*';
--          UPDATE user_game_stats s SET rating = b.rating
--            FROM user_game_stats_backup_d305 b
--           WHERE b.user_id = s.user_id AND b.game_type = s.game_type AND b.game_type <> '*';
--   확인이 끝나 백업이 필요 없으면: DROP TABLE user_game_stats_backup_d305;

CREATE TABLE IF NOT EXISTS user_game_stats_backup_d305 (
    user_id      BIGINT       NOT NULL,
    game_type    VARCHAR(32)  NOT NULL,            -- 게임 id, 또는 '*' = 이 마이그레이션이 만든 통합 시작값
    rating       INT          NOT NULL,
    backed_up_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, game_type)
);

-- 1) 지금의 게임별 레이팅을 그대로 남긴다(봇·게스트 행 포함 — 있는 그대로).
INSERT INTO user_game_stats_backup_d305 (user_id, game_type, rating)
SELECT s.user_id, s.game_type, s.rating
  FROM user_game_stats s
 WHERE s.game_type <> '*'
ON CONFLICT (user_id, game_type) DO NOTHING;

-- 2) 통합 행. 판 수 가중 평균(판이 없으면 단순 평균), 승·패·탈주는 합.
-- 3) 만든 시작값도 백업에 남긴다(나중에 "처음 얼마였나"를 보려고).
WITH fresh AS (
    INSERT INTO user_game_stats (user_id, game_type, rating, win_count, lose_count, desert_count)
    SELECT s.user_id,
           '*',
           CASE WHEN SUM(s.win_count + s.lose_count) > 0
                THEN ROUND(SUM(s.rating::NUMERIC * (s.win_count + s.lose_count))
                           / SUM(s.win_count + s.lose_count))
                ELSE ROUND(AVG(s.rating::NUMERIC))
           END::INT,
           SUM(s.win_count)::INT,
           SUM(s.lose_count)::INT,
           SUM(s.desert_count)::INT
      FROM user_game_stats s
      JOIN users u ON u.id = s.user_id
     WHERE s.game_type <> '*'
       AND u.is_bot = FALSE
       AND u.username NOT LIKE 'guest-%'
     GROUP BY s.user_id
    ON CONFLICT (user_id, game_type) DO NOTHING
    RETURNING user_id, game_type, rating
)
INSERT INTO user_game_stats_backup_d305 (user_id, game_type, rating)
SELECT user_id, game_type, rating FROM fresh
ON CONFLICT (user_id, game_type) DO NOTHING;
