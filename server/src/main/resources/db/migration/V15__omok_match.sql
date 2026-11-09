-- D-175 (오목 S3b) — 오목 매치 결과. 원카드 onecard_match_* 와 같은 2테이블 구조이고,
-- 게임별 전적은 user_game_stats(game_type = 'OMOK')에 쌓인다. 이 파일은 오목 테이블만 새로 만든다.
--
-- PRIVACY POLICY 재확인 (D-02): 색·팀·승패는 게임 결과일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- mode: SOLO / TEAMS_2V2, rule: RENJU / DOUBLE_THREE_ONLY / FREESTYLE (`docs/rules-omok.md` §2·§5).
-- end_reason: FIVE / RESIGN / TIMEOUT / DESERTED / BOARD_FULL / NO_LEGAL_MOVE (§10.1).
-- winner_color: BLACK / WHITE, 무승부면 NULL. payload_json 은 수순(기보)과 결과 — 다시 보기를 서버 변경 없이 붙이려고.

CREATE TABLE omok_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mode          VARCHAR(16)  NOT NULL,
    rule          VARCHAR(24)  NOT NULL,
    end_reason    VARCHAR(16)  NOT NULL,
    winner_color  VARCHAR(8),
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_omok_match_finished_at ON omok_match_results (finished_at);

-- team: seat % 2 (1:1 은 좌석 하나가 한 편). color: BLACK / WHITE.
CREATE TABLE omok_match_participants (
    match_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    seat        INT          NOT NULL,
    team        INT          NOT NULL,
    color       VARCHAR(8)   NOT NULL,
    is_win      BOOLEAN      NOT NULL,
    is_draw     BOOLEAN      NOT NULL,
    deserted    BOOLEAN      NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_omok_participant_match FOREIGN KEY (match_id) REFERENCES omok_match_results(id),
    CONSTRAINT fk_omok_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_omok_participant_user ON omok_match_participants (user_id);
