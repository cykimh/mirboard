-- D-209 — 이번 달 전적(랭킹용). 매달 1일 0시(Asia/Seoul)에 "초기화"된다 — 지우는 것이 아니라 새 달의 행이 새로 쌓인다.
-- 누적 전적·레이팅(user_game_stats, D-115)은 그대로이고 계급도 거기서 나온다.
--
-- PRIVACY POLICY 재확인 (D-02): 게임 활동 집계(승·패·탈주 수, 최근 결과)는 식별/연락 정보가 아니다.
-- user_game_stats 와 같이 users 밖의 별도 테이블에 두어 users 컬럼 화이트리스트를 불변으로 지킨다.
--
-- game_type '*' = 모든 게임 합(랭킹 "전체"). 게임 행과 같은 트랜잭션에서 같이 쌓는다 — 합을 조회 때 묶지 않으려는 것이고,
-- 게임을 넘나드는 "최근 5경기" 순서를 한 행에 담으려는 것이다('*' 는 게임 id 로 쓸 수 없는 글자).
-- stat_month = 그 달의 1일(Asia/Seoul 달력).
-- recent = 이번 달 최근 결과, 오래된 것 → 최근 순으로 최대 5글자(W 승 · L 패 · D 무).

CREATE TABLE user_monthly_stats (
    user_id      BIGINT       NOT NULL,
    game_type    VARCHAR(32)  NOT NULL,
    stat_month   DATE         NOT NULL,
    win_count    INT          NOT NULL DEFAULT 0,
    lose_count   INT          NOT NULL DEFAULT 0,
    desert_count INT          NOT NULL DEFAULT 0,
    recent       VARCHAR(5)   NOT NULL DEFAULT '',
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, game_type, stat_month),
    CONSTRAINT fk_monthly_stats_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
-- 랭킹 조회: 그 달·그 게임의 승수 내림차순.
CREATE INDEX idx_monthly_stats_ranking ON user_monthly_stats (stat_month, game_type, win_count DESC);
