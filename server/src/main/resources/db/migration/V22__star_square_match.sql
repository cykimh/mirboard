-- 별자리 수판 S2 (D-285) — 별자리 수판 매치 결과. 얏추 yacht_match_*(V14) 와 같은 2테이블 구조이고,
-- 게임별 전적은 V11 의 user_game_stats(game_type = 'STAR_SQUARE')에 쌓인다. 1인 판은 아무것도 쓰지 않는다(D-181 선례).
--
-- PRIVACY POLICY 재확인 (D-02): 순위·점수는 게임 결과일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- end_reason: FINISHED / NO_HUMANS / LAST_SEAT (`docs/rules-starsquare.md` §8.3).
-- 번호는 파일 이름에만 둔다 — 병렬 게임 작업은 관제가 번호 순서대로 병합하고(담바둑 V21), 순서가 바뀌면 파일 이름만 고친다.
-- 이 파일은 별자리 수판 테이블만 만든다.

CREATE TABLE star_square_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_reason    VARCHAR(16)  NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_sq_match_finished_at ON star_square_match_results (finished_at);

-- final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §8.2), 탈주자는 모두 같은 최하위.
-- total_points: 퍼즐 점수 합(좌석 수 − 퍼즐 순위의 합), firsts: 퍼즐 1등 횟수.
CREATE TABLE star_square_match_participants (
    match_id      BIGINT   NOT NULL,
    user_id       BIGINT   NOT NULL,
    seat          INT      NOT NULL,
    final_rank    INT      NOT NULL,
    total_points  INT      NOT NULL,
    firsts        INT      NOT NULL,
    is_win        BOOLEAN  NOT NULL,
    deserted      BOOLEAN  NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_sq_participant_match FOREIGN KEY (match_id) REFERENCES star_square_match_results(id),
    CONSTRAINT fk_sq_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_sq_participant_user ON star_square_match_participants (user_id);
