-- 낱말 온도 S2 (D-310·D-316·D-317) — 매치 결과 2테이블 + 실시간 점수 캐시. 매치 테이블은 별자리 수판 star_square_match_*(V22)와 같은
-- 구조이고, 게임별 전적은 V11 의 user_game_stats(game_type = 'WORD_TEMP')에 쌓인다.
--
-- PRIVACY POLICY 재확인 (D-02): 순위·점수는 게임 결과일 뿐 식별/연락 정보가 아니다. users 컬럼 화이트리스트는 건드리지 않는다.
-- payload_json 에는 공개된 것만 남긴다 — 출제 차례별 정답·공개 힌트·온도·맞힌 좌석, 표 버전. 오답 원문·안 쓴 후보는 없다(룰 §13).
--
-- end_reason: FINISHED / LAST_SEAT / NO_HUMANS (`docs/rules-wordtemp.md` §8).
-- 번호는 파일 이름에만 둔다 — 병렬 작업이 먼저 V25 를 쓰면 관제가 순서대로 고친다. 이 파일은 낱말 온도 테이블만 만든다.

CREATE TABLE word_temp_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_reason    VARCHAR(16)  NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_wt_match_finished_at ON word_temp_match_results (finished_at);

-- final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §8), 빠진 사람은 모두 같은 최하위.
-- total_points = setter_points(출제 점수) + guess_points(추리 점수).
CREATE TABLE word_temp_match_participants (
    match_id       BIGINT   NOT NULL,
    user_id        BIGINT   NOT NULL,
    seat           INT      NOT NULL,
    final_rank     INT      NOT NULL,
    total_points   INT      NOT NULL,
    setter_points  INT      NOT NULL,
    guess_points   INT      NOT NULL,
    is_win         BOOLEAN  NOT NULL,
    deserted       BOOLEAN  NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_wt_participant_match FOREIGN KEY (match_id) REFERENCES word_temp_match_results(id),
    CONSTRAINT fk_wt_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_wt_participant_user ON word_temp_match_participants (user_id);

-- 실시간 점수 캐시 겸 놓친 낱말 수집(D-312). 표 밖 (정답, 힌트) 쌍을 모델이 처음 매긴 값 — 처음 쓴 값이 정본이다(INSERT … ON CONFLICT DO NOTHING).
-- 키에 모델·채점 기준문 버전이 들어간다(바꾸면 새로 매긴다). hits = 같은 쌍이 나온 횟수(다음 표 갱신의 후보 재료).
-- **낱말과 횟수뿐이다** — 사용자·방·좌석 식별자, 시각별 기록은 없다(users FK 없음). 낱말은 형식 검사를 통과한 한글 1~10자다.
-- valid = false 는 "쓸 수 있는 명사가 아님"(고유명사·비하 표현·명사 아닌 말)이고 그때 score 는 0 이다.
CREATE TABLE word_temp_live_scores (
    answer          VARCHAR(16)  NOT NULL,
    hint            VARCHAR(16)  NOT NULL,
    model           VARCHAR(64)  NOT NULL,
    prompt_version  VARCHAR(32)  NOT NULL,
    valid           BOOLEAN      NOT NULL,
    score           INT          NOT NULL,
    hits            INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (answer, hint, model, prompt_version),
    CONSTRAINT ck_wt_live_score CHECK (score BETWEEN 0 AND 100)
);
-- 하루 호출 상한을 재시작 뒤에도 잇는다(오늘 새로 쓴 행 수).
CREATE INDEX idx_wt_live_created_at ON word_temp_live_scores (created_at);
