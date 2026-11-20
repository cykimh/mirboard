-- D-278 (담바둑 S2) — 담바둑 매치 결과. 구름항로 cloudroute_match_* 와 같은 2테이블 구조이고,
-- 게임별 전적은 user_game_stats(game_type = 'DAMBADUK')에 쌓인다.
-- 이 파일은 담바둑 테이블만 새로 만든다(다른 마이그레이션의 객체를 바꾸지 않음 — FK 대상 users 는 V1 부터 있다).
-- 버전 번호는 관제가 병합 순서에 맞춰 바꿀 수 있다(파일 이름 밖에서 번호를 참조하지 말 것).
--
-- PRIVACY POLICY 재확인 (D-02): 순위·집 칸 수·색은 게임 결과일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- end_reason: SEPARATED / FROZEN / LAST_STANDING / TIMEOUT / DESERTED (`docs/rules-dambaduk.md` §6).
-- payload_json 은 끝난 판 상태(돌·담·결과) — 끝난 판 보기를 서버 변경 없이 붙이려고.

CREATE TABLE dambaduk_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    seat_count    INT          NOT NULL,
    end_reason    VARCHAR(16)  NOT NULL,
    moves         INT          NOT NULL,
    is_draw       BOOLEAN      NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_dambaduk_match_finished_at ON dambaduk_match_results (finished_at);

-- color: 0 흑(선) · 1 백 · 2 셋째(§2). final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §7).
-- score·largest_home: 끝난 판의 집 칸 수 합·가장 큰 집. exit_reason: 빠진 까닭(TIMEOUT / DESERTED), 안 빠졌으면 NULL.
CREATE TABLE dambaduk_match_participants (
    match_id       BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    seat           INT          NOT NULL,
    color          INT          NOT NULL,
    final_rank     INT          NOT NULL,
    score          INT          NOT NULL,
    largest_home   INT          NOT NULL,
    exit_reason    VARCHAR(16),
    is_win         BOOLEAN      NOT NULL,
    is_draw        BOOLEAN      NOT NULL,
    deserted       BOOLEAN      NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_dambaduk_participant_match FOREIGN KEY (match_id) REFERENCES dambaduk_match_results(id),
    CONSTRAINT fk_dambaduk_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_dambaduk_participant_user ON dambaduk_match_participants (user_id);
