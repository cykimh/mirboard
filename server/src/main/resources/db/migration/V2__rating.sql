-- Phase 8D — users.rating 컬럼 추가. ELO 점수 (기본 1000) 로 매치 결과에 따라 갱신.
--
-- PRIVACY POLICY 재확인 (D-02): rating 은 게임 성적 집계용 derived 값이지 식별
-- 정보가 아니므로 users 컬럼 화이트리스트에 추가 가능. tier 는 DB 컬럼이 아니라
-- 조회 시 rating 구간에서 계산 (derived).

ALTER TABLE users
    ADD COLUMN rating INT NOT NULL DEFAULT 1000;
