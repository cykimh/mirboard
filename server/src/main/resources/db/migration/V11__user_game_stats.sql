-- D-115 (M6-1) — 게임별 전적·레이팅. 스컬킹 매치 영속(D-102 보류 ①)의 선행.
--
-- PRIVACY POLICY 재확인 (D-02): 게임 활동 집계(rating·승패·탈주 수)는 식별/연락 정보가
-- 아니다. D-80 user_avatars 와 같이 users 밖의 별도 테이블에 두어 users 컬럼 화이트리스트를
-- 불변으로 지킨다.
--
-- 행이 없으면 "아직 안 해 본 게임" — 앱이 기본값(1000/0/0/0)으로 읽는다.

CREATE TABLE user_game_stats (
    user_id      BIGINT       NOT NULL,
    game_type    VARCHAR(32)  NOT NULL,
    rating       INT          NOT NULL DEFAULT 1000,
    win_count    INT          NOT NULL DEFAULT 0,
    lose_count   INT          NOT NULL DEFAULT 0,
    desert_count INT          NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, game_type),
    CONSTRAINT fk_game_stats_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
-- 랭킹 조회: 게임별 rating 내림차순.
CREATE INDEX idx_game_stats_ranking ON user_game_stats (game_type, rating DESC);

-- 티츄 기존 전적 이관. users 의 네 컬럼은 이번엔 남겨 두고 쓰기만 멈춘다 —
-- 운영에서 이관을 확인한 뒤 별도 마이그레이션으로 DROP 한다(D-115).
-- 활동이 없던 사람(기본값 그대로)은 행을 만들지 않는다. 봇은 레이팅 대상이 아니다(D-71).
INSERT INTO user_game_stats (user_id, game_type, rating, win_count, lose_count, desert_count)
SELECT id, 'TICHU', rating, win_count, lose_count, desert_count
FROM users
WHERE is_bot = FALSE
  AND (win_count > 0 OR lose_count > 0 OR desert_count > 0 OR rating <> 1000);

-- 스컬킹 매치 결과 (티츄 tichu_match_* 와 같은 2테이블 구조).
CREATE TABLE skullking_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rounds_played INT          NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_sk_match_finished_at ON skullking_match_results (finished_at);

CREATE TABLE skullking_match_participants (
    match_id    BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    seat        INT      NOT NULL,
    final_score INT      NOT NULL,
    is_win      BOOLEAN  NOT NULL,
    deserted    BOOLEAN  NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_sk_participant_match FOREIGN KEY (match_id) REFERENCES skullking_match_results(id),
    CONSTRAINT fk_sk_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_sk_participant_user ON skullking_match_participants (user_id);
