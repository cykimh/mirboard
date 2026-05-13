-- room_leave.lua
-- Atomically remove player from room. Promote new host if needed. Clean up if empty.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = rooms:open
-- ARGV    = userId, roomId
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_IN_ROOM
--    0 = room destroyed (last player left)
--    N (>= 1) = remaining player count

local userId = ARGV[1]
local roomId = ARGV[2]

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

local removed = redis.call('LREM', KEYS[2], 0, userId)
if removed == 0 then
    return -2
end

local remaining = redis.call('LLEN', KEYS[2])
if remaining == 0 then
    redis.call('DEL', KEYS[1], KEYS[2])
    redis.call('ZREM', KEYS[3], roomId)
    return 0
end

local hostId = redis.call('HGET', KEYS[1], 'hostId')
if hostId == userId then
    local newHost = redis.call('LINDEX', KEYS[2], 0)
    redis.call('HSET', KEYS[1], 'hostId', newHost)
end

return remaining
