-- presence_join.lua
-- D-96 보정 — 방 프레즌스 등록. **한 세션은 정확히 한 번만** 카운터를 올린다.
--
-- 리스너(`WsSessionLifecycleListener`)는 방 토픽 **구독마다** 이 경로를 타는데, 실제
-- 클라는 한 세션에서 방 토픽을 3개(`/topic/room/{id}`, `/chat`, `/reaction`) 구독한다.
-- 반면 DISCONNECT 는 세션당 한 번뿐이라, 등록이 멱등하지 않으면 끊긴 뒤에도 잔여
-- 카운터가 남아 `hasLiveSession` 이 영영 참이 된다 — 즉 **탈주가 확정되지 않는다**.
--
-- 세션 키를 `SET NX` 로 잡아 "이 세션을 이미 셌는가"의 판정과 카운터 증가를 한 원자
-- 단위로 묶는다. SUBSCRIBE 프레임 3개가 동시에 처리돼도 증가는 한 번뿐이다.
--
-- KEYS[1] = presence:room:{roomId}       (HASH: userId -> 세션 수)
-- KEYS[2] = presence:session:{sessionId} (STRING: "{userId}:{roomId}")
-- ARGV[1] = userId
-- ARGV[2] = roomId
-- ARGV[3] = TTL 초
--
-- 반환: 이 호출로 카운터가 올라갔으면 1, 이미 등록된 세션이라 건너뛰었으면 0

local value = ARGV[1] .. ':' .. ARGV[2]
local counted = 0

if redis.call('SET', KEYS[2], value, 'EX', ARGV[3], 'NX') then
    redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
    counted = 1
else
    -- 같은 세션의 추가 구독 — 카운터는 그대로 두고 TTL 만 늘린다.
    redis.call('EXPIRE', KEYS[2], ARGV[3])
end

redis.call('EXPIRE', KEYS[1], ARGV[3])
return counted
