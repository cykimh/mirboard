-- D-300 — 레이팅 일괄 +40(별 하나 구간). 데이터 마이그레이션, 한 번만.
--
-- 대상: 사람 계정(봇 is_bot · 게스트 'guest-' 접두 제외 — GuestPolicy 규약)의 user_game_stats 행 중
--       한 판 이상 한 행. "한 판"은 승+패 > 0 이거나, 승패가 0 이어도 그 게임의 이번 달 전적 행
--       (user_monthly_stats — 무승부도 행을 남긴다, D-209)이 있는 것. 이번 달 전적이 생기기 전(V19)에 무승부만 한
--       오목·씨앗포 판은 참가 테이블로 잡는다.
-- user_monthly_stats 는 레이팅 컬럼이 없어(승·패·탈주·최근 결과) 건드리지 않는다.
-- 스키마 이름을 적지 않는다 — Flyway 기본 스키마(운영 MIRBOARD_DB_SCHEMA, D-169)를 따른다.
--
-- 두 번 올리지 않는 장치: 백업 테이블의 PK. 올리는 UPDATE 는 "이번에 백업에 새로 들어간 행"만 받는다
-- (INSERT … ON CONFLICT DO NOTHING RETURNING). 이 파일을 손으로 다시 돌려도 이미 백업된 행은 그대로다.
--
-- 되돌리기(운영자가 손으로 — docs/deploy.md "레이팅 일괄 올리기"):
--   (가) 그 뒤 판의 변화는 지키고 올린 만큼만 뺀다(권장). 두 문장을 한 트랜잭션으로:
--        UPDATE user_game_stats s SET rating = s.rating - b.raised_by
--          FROM user_game_stats_backup_d300 b
--         WHERE b.user_id = s.user_id AND b.game_type = s.game_type AND b.raised_by > 0;
--        UPDATE user_game_stats_backup_d300 SET raised_by = 0 WHERE raised_by > 0;
--   (나) 백업 시점 값으로 통째 복원(그 뒤 판의 레이팅 변화도 사라진다):
--        UPDATE user_game_stats s SET rating = b.rating
--          FROM user_game_stats_backup_d300 b
--         WHERE b.user_id = s.user_id AND b.game_type = s.game_type;
--   확인이 끝나 백업이 필요 없으면: DROP TABLE user_game_stats_backup_d300;
--   (백업을 지운 뒤 이 파일을 손으로 다시 돌리면 또 오른다 — Flyway 는 다시 돌리지 않는다.)

CREATE TABLE IF NOT EXISTS user_game_stats_backup_d300 (
    user_id      BIGINT       NOT NULL,
    game_type    VARCHAR(32)  NOT NULL,
    rating       INT          NOT NULL,            -- 올리기 전 값
    raised_by    INT          NOT NULL DEFAULT 0,  -- 이 마이그레이션이 올린 점수(대상이 아니면 0)
    backed_up_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, game_type)
);

WITH fresh AS (
    INSERT INTO user_game_stats_backup_d300 (user_id, game_type, rating, raised_by)
    SELECT s.user_id, s.game_type, s.rating,
           CASE WHEN u.is_bot = FALSE
                 AND u.username NOT LIKE 'guest-%'
                 AND (s.win_count + s.lose_count > 0
                      OR EXISTS (SELECT 1 FROM user_monthly_stats m
                                  WHERE m.user_id = s.user_id AND m.game_type = s.game_type)
                      -- 이번 달 전적(V19) 이전에 무승부만 한 판(오목·씨앗포)도 한 판으로 친다.
                      OR (s.game_type = 'OMOK' AND EXISTS (SELECT 1 FROM omok_match_participants p
                                                            WHERE p.user_id = s.user_id))
                      OR (s.game_type = 'ARTILLERY' AND EXISTS (SELECT 1 FROM artillery_match_participants p
                                                                 WHERE p.user_id = s.user_id)))
                THEN 40 ELSE 0 END
    FROM user_game_stats s
    JOIN users u ON u.id = s.user_id
    ON CONFLICT (user_id, game_type) DO NOTHING
    RETURNING user_id, game_type, raised_by
)
UPDATE user_game_stats s
   SET rating = s.rating + f.raised_by
  FROM fresh f
 WHERE f.user_id = s.user_id
   AND f.game_type = s.game_type
   AND f.raised_by > 0;
