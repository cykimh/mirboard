-- 요트 개인 최고 점수 (D-201) — 혼자 끝까지 친 판(end_reason FINISHED)의 총점. 1인 판은 승패·전적이 없어(D-181)
-- yacht_match_results/participants 에는 쓰지 않고 여기에만 쓴다. "내 최고 점수"는 이 표와 여럿 판 참가자 표
-- (탈주하지 않고 FINISHED 로 끝난 판의 total_score) 중 최고다.
--
-- PRIVACY POLICY 재확인 (D-02): 총점은 게임 결과일 뿐 식별/연락 정보가 아니다. users 컬럼 화이트리스트는 건드리지 않는다.
-- 게스트·봇은 쓰지 않는다(D-201). 그래도 게스트 정리(GuestAccountSweeper)가 이 표 때문에 막히지 않게 ON DELETE CASCADE.

CREATE TABLE yacht_solo_scores (
    id           BIGINT     GENERATED ALWAYS AS IDENTITY,
    user_id      BIGINT     NOT NULL,
    room_id      VARCHAR(36) NOT NULL,
    total_score  INT        NOT NULL,
    finished_at  TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_yt_solo_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_yt_solo_user_score ON yacht_solo_scores (user_id, total_score DESC);
