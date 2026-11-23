-- room_remove_member.lua  (D-290)
-- 대기실에서 방장이 다른 좌석 하나를 비운다 — 봇 빼기와 사람 내보내기(강퇴)가 같이 쓴다. 방장·WAITING 확인과 LREM 을 한 번에 해서,
-- 확인한 뒤 시작된 판의 좌석이 당겨지지 않는다(시작된 게임의 좌석은 불변 — D-122). 방장 자신은 뺄 수 없으므로 방이 비는 일도 없다.
-- 강퇴면(banSeconds > 0) 같은 원자 구간에서 재입장 금지 키를 건다 — room_join.lua 가 그 키를 본다.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = room:{roomId}:ready
-- KEYS[4] = room:{roomId}:kicked:{targetId}
-- ARGV    = hostId, targetId, banSeconds(0 = 금지 안 함)
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_WAITING (이미 시작/종료)
--   -3 = NOT_HOST
--   -4 = NOT_IN_ROOM (대상이 방장 자신이거나 앉아 있지 않다)
--    n (>= 1) = 남은 좌석 수

local hostId     = ARGV[1]
local targetId   = ARGV[2]
local banSeconds = tonumber(ARGV[3])

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
    return -2
end
if redis.call('HGET', KEYS[1], 'hostId') ~= hostId then
    return -3
end
if targetId == hostId then
    return -4
end
if redis.call('LREM', KEYS[2], 0, targetId) == 0 then
    return -4
end
redis.call('SREM', KEYS[3], targetId)
if banSeconds > 0 then
    redis.call('SET', KEYS[4], '1', 'EX', banSeconds)
end
return redis.call('LLEN', KEYS[2])
