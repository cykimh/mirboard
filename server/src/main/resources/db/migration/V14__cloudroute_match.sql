-- D-212 (구름항로 S2) — 구름항로 매치 결과. 원카드 onecard_match_* 와 같은 2테이블 구조이고,
-- 게임별 전적은 user_game_stats(game_type = 'CLOUD_ROUTE')에 쌓인다.
-- 이 파일은 구름항로 테이블만 새로 만든다(다른 마이그레이션의 객체를 바꾸지 않음 — FK 대상 users 는 V1 부터 있다).
-- 버전 번호는 관제가 병합 순서에 맞춰 바꿀 수 있다(파일 이름 밖에서 번호를 참조하지 말 것).
--
-- PRIVACY POLICY 재확인 (D-02): 순위·총자산은 게임 결과(게임 돈 G — 계정 돈이 아니다, D-82)일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- end_reason: LAST_STANDING / NO_HUMANS / ROUND_LIMIT (`docs/rules-cloudroute.md` §12.1).

CREATE TABLE cloudroute_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_reason    VARCHAR(16)  NOT NULL,
    rounds        INT          NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_cr_match_finished_at ON cloudroute_match_results (finished_at);

-- final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §12.2). total_assets: 살아 있으면 현금 + 땅 정가, 탈락했으면 0.
-- status: ALIVE / BANKRUPT / DESERTED.
CREATE TABLE cloudroute_match_participants (
    match_id      BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    seat          INT          NOT NULL,
    final_rank    INT          NOT NULL,
    total_assets  INT          NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    is_win        BOOLEAN      NOT NULL,
    deserted      BOOLEAN      NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_cr_participant_match FOREIGN KEY (match_id) REFERENCES cloudroute_match_results(id),
    CONSTRAINT fk_cr_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_cr_participant_user ON cloudroute_match_participants (user_id);
