-- Mirboard initial schema (PostgreSQL).
--
-- PRIVACY POLICY (enforced at schema level):
--   The `users` table MUST NOT store email, phone, real name, birth date,
--   address, or any other contact / identifying information. Only the bare
--   minimum needed to authenticate and aggregate match results is kept.

CREATE TABLE users (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    username      VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    win_count     INT          NOT NULL DEFAULT 0,
    lose_count    INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE tichu_match_results (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id      VARCHAR(36)  NOT NULL,
    finished_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    team_a_score INT          NOT NULL,
    team_b_score INT          NOT NULL,
    payload_json TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_match_room        ON tichu_match_results (room_id);
CREATE INDEX idx_match_finished_at ON tichu_match_results (finished_at);

CREATE TABLE tichu_match_participants (
    match_id BIGINT  NOT NULL,
    user_id  BIGINT  NOT NULL,
    team     VARCHAR(1) NOT NULL,
    is_win   BOOLEAN NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_participant_match FOREIGN KEY (match_id) REFERENCES tichu_match_results(id),
    CONSTRAINT fk_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id),
    CONSTRAINT chk_participant_team CHECK (team IN ('A','B'))
);
CREATE INDEX idx_participant_user ON tichu_match_participants (user_id);
