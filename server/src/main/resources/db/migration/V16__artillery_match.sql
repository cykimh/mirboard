-- 씨앗포 S2 (D-222) — 씨앗포 매치 결과. 요트 yacht_match_*(V14) 와 같은 2테이블 구조이고,
-- 게임별 전적은 V11 의 user_game_stats(game_type = 'ARTILLERY')에 쌓인다.
--
-- PRIVACY POLICY 재확인 (D-02): 순위·남은 체력은 게임 결과일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- end_reason: LAST_STANDING / ALL_DOWN / NO_HUMANS (`docs/rules-artillery.md` §9.3).
-- 번호는 파일 이름에만 둔다 — 병렬 게임 작업은 관제가 번호 순서대로 병합하고, 순서가 바뀌면 파일 이름만 고친다. 이 파일은 씨앗포 테이블만 만든다.

CREATE TABLE artillery_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_reason    VARCHAR(16)  NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_ar_match_finished_at ON artillery_match_results (finished_at);

-- final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §9.3), 탈주자는 모두 같은 최하위.
-- final_hp: 끝날 때 체력(탈락자는 0, 탈주자는 탈주 때 체력).
CREATE TABLE artillery_match_participants (
    match_id     BIGINT   NOT NULL,
    user_id      BIGINT   NOT NULL,
    seat         INT      NOT NULL,
    final_rank   INT      NOT NULL,
    final_hp     INT      NOT NULL,
    is_win       BOOLEAN  NOT NULL,
    deserted     BOOLEAN  NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_ar_participant_match FOREIGN KEY (match_id) REFERENCES artillery_match_results(id),
    CONSTRAINT fk_ar_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_ar_participant_user ON artillery_match_participants (user_id);
