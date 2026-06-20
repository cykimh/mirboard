-- D-82 — 내기 칩을 계정 영속 지갑에서 방 단위 테이블 칩으로 전환. 칩은 계정에 두지
-- 않고 Redis(room:{id}:chips)에만 둔다. D-81 의 V6 컬럼을 제거한다(D-02 화이트리스트
-- 원복: 게임 재화 컬럼도 계정에 안 둠).
ALTER TABLE users
    DROP COLUMN chip_balance;
