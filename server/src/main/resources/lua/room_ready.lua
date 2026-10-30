-- room_ready.lua  (Phase 16 #2)
-- Atomically toggle a player's ready flag. When every seat is filled AND
-- every player is ready, transition WAITING -> IN_GAME (start the game).
-- D-161 — 그 자동 시작은 봇과 연습 방(fillWithBots)만이다. 친구 방의 준비는 표시만 하고 시작은 늘 방장(room_start_now.lua).
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = room:{roomId}:ready
-- KEYS[4] = rooms:open
-- ARGV    = userId, ready('1'|'0'), roomId
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_WAITING (already started / finished)
--   -3 = NOT_MEMBER (not a seated player)
--    0 = ready toggled, not started
--    1 = started (WAITING -> IN_GAME)

local userId = ARGV[1]
local ready  = ARGV[2]
local roomId = ARGV[3]
local ttl    = 21600

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
    return -2
end

local players = redis.call('LRANGE', KEYS[2], 0, -1)
local isMember = false
for i = 1, #players do
    if players[i] == userId then
        isMember = true
        break
    end
end
if not isMember then
    return -3
end

if ready == '1' then
    redis.call('SADD', KEYS[3], userId)
else
    redis.call('SREM', KEYS[3], userId)
end
redis.call('EXPIRE', KEYS[3], ttl)

if redis.call('HGET', KEYS[1], 'fillWithBots') ~= 'true' then
    return 0
end

local capacity = tonumber(redis.call('HGET', KEYS[1], 'capacity'))
if #players < capacity then
    return 0
end
-- BY-3 — 준비 집합 크기(SCARD)가 아니라 앉은 사람마다 준비를 확인한다. 좌석에 없는 준비가 남아도 조기 시작하지 않는다.
for i = 1, #players do
    if redis.call('SISMEMBER', KEYS[3], players[i]) == 0 then
        return 0
    end
end
redis.call('HSET', KEYS[1], 'status', 'IN_GAME')
redis.call('ZREM', KEYS[4], roomId)
return 1
