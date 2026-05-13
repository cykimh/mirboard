-- room_join.lua
-- Atomically validate + push player into room:{id}:players.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = rooms:open
-- ARGV    = userId, now, roomId
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = GAME_ALREADY_STARTED
--   -3 = ROOM_FULL
--   -4 = ALREADY_IN_ROOM
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

local capacity = tonumber(redis.call('HGET', KEYS[1], 'capacity'))
local current = #players
if current >= capacity then
    return -3
end

redis.call('RPUSH', KEYS[2], userId)
redis.call('HSET', KEYS[1], 'updatedAt', now)
redis.call('EXPIRE', KEYS[1], ttl)
redis.call('EXPIRE', KEYS[2], ttl)

local newCount = current + 1
if newCount >= capacity then
    -- Auto-transition: WAITING → IN_GAME. Phase 4 will hook GameEngine.start here.
    redis.call('HSET', KEYS[1], 'status', 'IN_GAME')
    redis.call('ZREM', KEYS[3], roomId)
end

return newCount
