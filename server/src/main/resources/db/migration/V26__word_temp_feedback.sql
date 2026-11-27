-- D-323 — 낱말 온도 "온도가 이상해요" 신고. 낱말만 남긴다: (정답, 힌트, 방향, 그때 온도, 표·채점 버전)과 횟수.
-- 사용자·방·좌석 식별자는 두지 않는다(개인정보 최소화 — 누가 냈는지는 어디에도 남지 않는다). 다음 표 갱신·채점 기준문 검수의 재료다.
-- direction: LOW(너무 낮다) · HIGH(너무 높다) · UNSURE(방향 없음). table_version: 표 힌트는 표 버전(v1 …), 실시간 채점 힌트는 'live'.
CREATE TABLE word_temp_feedback (
    answer         VARCHAR(16)  NOT NULL,
    hint           VARCHAR(16)  NOT NULL,
    direction      VARCHAR(8)   NOT NULL,
    temp           INT          NOT NULL,
    table_version  VARCHAR(32)  NOT NULL,
    count          INT          NOT NULL DEFAULT 1,
    first_seen     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (answer, hint, direction, table_version),
    CONSTRAINT ck_wt_feedback_temp CHECK (temp BETWEEN 0 AND 100),
    CONSTRAINT ck_wt_feedback_direction CHECK (direction IN ('LOW', 'HIGH', 'UNSURE'))
);
-- 어드민 목록: 많이 신고된 쌍부터.
CREATE INDEX idx_wt_feedback_count ON word_temp_feedback (count DESC, last_seen DESC);
