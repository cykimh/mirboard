-- room_delete.lua  (Phase 19#1, D-75)
-- 방을 무조건 원자 소멸. "플레이어 0 && 관전자 0" 인 방(관전자만 남았다가
-- 마지막 관전자가 나간 경우 등)을 정리할 때 호출. room_leave.lua 와 달리
-- 멤버십 검사 없이 키 전체를 제거한다.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = room:{roomId}:ready
-- KEYS[4] = room:{roomId}:spectators
-- KEYS[5] = rooms:open
-- ARGV    = roomId
--
-- Returns: 1 if room existed and was removed, 0 if it did not exist.

local roomId = ARGV[1]

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end

redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
redis.call('ZREM', KEYS[5], roomId)
return 1
