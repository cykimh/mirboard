-- room_join.lua
-- Atomically validate + push player into room:{id}:players.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = rooms:open
-- KEYS[4] = room:{roomId}:spectators  (D-137 — 앉으면 관전자 목록에서 뺀다)
-- KEYS[5] = room:{roomId}:kicked:{userId}  (D-290 — 방장이 방금 내보낸 사람의 재입장 금지 키)
-- ARGV    = userId, now, roomId
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = GAME_ALREADY_STARTED
--   -3 = ROOM_FULL
--   -4 = ALREADY_IN_ROOM
--   -5 = KICKED (D-290 — 방장이 내보낸 직후, 금지 키가 살아 있는 동안)
--    N (>= 1) = new player count after the join

local userId = ARGV[1]
local now    = ARGV[2]
local roomId = ARGV[3]
local ttl    = 21600

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

local status = redis.call('HGET', KEYS[1], 'status')
if status ~= 'WAITING' then
    return -2
end

local players = redis.call('LRANGE', KEYS[2], 0, -1)
for i = 1, #players do
    if players[i] == userId then
        return -4
    end
end

-- D-290 — 방장이 방금 내보낸 사람은 금지 키가 만료될 때까지 다시 앉지 못한다(이미 앉은 사람의 재요청은 위에서 -4 로 끝난다).
if redis.call('EXISTS', KEYS[5]) == 1 then
    return -5
end

local capacity = tonumber(redis.call('HGET', KEYS[1], 'capacity'))
local current = #players
if current >= capacity then
    return -3
end

redis.call('RPUSH', KEYS[2], userId)
-- D-137 — 관전자가 자리에 앉으면(같은 원자 구간) 관전자 목록에서 빠진다. 예전엔 플레이어·관전자 양쪽에 남았다(BY-5).
redis.call('SREM', KEYS[4], userId)
redis.call('HSET', KEYS[1], 'updatedAt', now)
redis.call('EXPIRE', KEYS[1], ttl)
redis.call('EXPIRE', KEYS[2], ttl)

-- Phase 16(#2) — capacity 도달 자동시작 폐기. 시작은 room_ready.lua 가
-- 전원 ready 시에만 WAITING→IN_GAME 전이. join 은 정원만 막고 끝.
return current + 1
