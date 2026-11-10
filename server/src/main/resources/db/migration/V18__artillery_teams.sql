-- 씨앗포 S5 (D-225) — 2:2 팀전. 참가자에 팀(좌석 홀짝 0·1)을 더한다. 개인전 판은 NULL.
-- 상대 전적(ArtilleryHeadToHead)이 같은 팀원을 세지 않도록 쓴다. 팀전 무승부·이긴 팀은 results.payload_json(MatchResult)에 있다.
--
-- PRIVACY POLICY 재확인 (D-02): 팀 번호는 게임 결과일 뿐 식별/연락 정보가 아니다. users 컬럼 화이트리스트는 건드리지 않는다.
-- 번호는 파일 이름에만 둔다 — 병렬 게임 작업과 겹치면 관제가 병합 순서대로 파일 이름만 고친다. 이 파일은 씨앗포 테이블만 바꾼다.

ALTER TABLE artillery_match_participants ADD COLUMN team INT;
