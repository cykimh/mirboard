-- D-128 (원카드 S3) — 원카드 매치 결과. 스컬킹 skullking_match_*(V11) 와 같은 2테이블 구조이고,
-- 게임별 전적은 V11 의 user_game_stats(game_type = 'ONE_CARD')에 쌓인다.
--
-- PRIVACY POLICY 재확인 (D-02): 순위·남은 장수는 게임 결과일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- end_reason: FINISHED / LAST_STANDING / NO_HUMANS / STALEMATE (`docs/rules-onecard.md` §11.1).

CREATE TABLE onecard_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_reason    VARCHAR(16)  NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_oc_match_finished_at ON onecard_match_results (finished_at);

-- final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §11.2). cards_left: 살아 있으면 남은 장수,
-- 탈락했으면 탈락 순간의 장수.
CREATE TABLE onecard_match_participants (
    match_id    BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    seat        INT      NOT NULL,
    final_rank  INT      NOT NULL,
    cards_left  INT      NOT NULL,
    is_win      BOOLEAN  NOT NULL,
    deserted    BOOLEAN  NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_oc_participant_match FOREIGN KEY (match_id) REFERENCES onecard_match_results(id),
    CONSTRAINT fk_oc_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_oc_participant_user ON onecard_match_participants (user_id);
