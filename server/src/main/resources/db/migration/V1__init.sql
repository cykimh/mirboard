-- Mirboard initial schema.
--
-- PRIVACY POLICY (enforced at schema level):
--   The `users` table MUST NOT store email, phone, real name, birth date,
--   address, or any other contact / identifying information. Only the bare
--   minimum needed to authenticate and aggregate match results is kept.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    win_count     INT          NOT NULL DEFAULT 0,
    lose_count    INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tichu_match_results (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    room_id      VARCHAR(36)  NOT NULL,
    finished_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    team_a_score INT          NOT NULL,
    team_b_score INT          NOT NULL,
    payload_json JSON         NOT NULL,
    PRIMARY KEY (id),
    KEY idx_match_room (room_id),
    KEY idx_match_finished_at (finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tichu_match_participants (
    match_id BIGINT  NOT NULL,
    user_id  BIGINT  NOT NULL,
    team     CHAR(1) NOT NULL,
    is_win   BOOLEAN NOT NULL,
    PRIMARY KEY (match_id, user_id),
    KEY idx_participant_user (user_id),
    CONSTRAINT fk_participant_match FOREIGN KEY (match_id) REFERENCES tichu_match_results(id),
    CONSTRAINT fk_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id),
    CONSTRAINT chk_participant_team CHECK (team IN ('A','B'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
