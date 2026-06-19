-- D-81 — 가상 칩 내기 모드. 칩은 게임 내 가상 재화(현금 입출금·환전 없음)라
-- rating/desert_count 와 동일한 derived 게임 값으로 취급, users 화이트리스트(D-02)에
-- 추가 허용한다(식별/연락 정보 아님). 기존 유저·봇 포함 기본 1000칩.
ALTER TABLE users
    ADD COLUMN chip_balance BIGINT NOT NULL DEFAULT 1000;
