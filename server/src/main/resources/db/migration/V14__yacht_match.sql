-- 요트 S3 (D-181) — 요트 매치 결과. 원카드 onecard_match_*(V12) 와 같은 2테이블 구조이고,
-- 게임별 전적은 V11 의 user_game_stats(game_type = 'YACHT')에 쌓인다. 1인 매치는 쓰지 않는다(D-181).
--
-- PRIVACY POLICY 재확인 (D-02): 순위·총점은 게임 결과일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- end_reason: FINISHED / NO_HUMANS / ACTION_LIMIT (`docs/rules-yacht.md` §8.1).
-- 번호 주의: 병렬로 기획 중인 다른 게임(오목 등)도 다음 V 를 쓴다 — 먼저 main 에 들어간 쪽이 이 번호를 갖고, 늦은 쪽은 다음 번호로 바꾼다.

CREATE TABLE yacht_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_reason    VARCHAR(16)  NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_yt_match_finished_at ON yacht_match_results (finished_at);

-- final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §8.2), 탈주자는 모두 같은 최하위.
-- total_score: 총점(상단 보너스 포함), 탈주자는 멈춘 총점.
CREATE TABLE yacht_match_participants (
    match_id     BIGINT   NOT NULL,
    user_id      BIGINT   NOT NULL,
    seat         INT      NOT NULL,
    final_rank   INT      NOT NULL,
    total_score  INT      NOT NULL,
    is_win       BOOLEAN  NOT NULL,
    deserted     BOOLEAN  NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_yt_participant_match FOREIGN KEY (match_id) REFERENCES yacht_match_results(id),
    CONSTRAINT fk_yt_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_yt_participant_user ON yacht_match_participants (user_id);
