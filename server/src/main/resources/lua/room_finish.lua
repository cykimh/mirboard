-- room_finish.lua
-- Mark room as FINISHED and remove from open rooms ZSET. State/hand keys are
-- cleaned up by the caller (game-specific cleanup).
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = rooms:open
-- ARGV    = roomId, now
--
-- Returns 1 on success, -1 if room missing.

local roomId = ARGV[1]
local now    = ARGV[2]

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

redis.call('HSET', KEYS[1], 'status', 'FINISHED', 'updatedAt', now)
redis.call('ZREM', KEYS[2], roomId)
-- Trim TTL so the room metadata fades after the result screen has time to display.
redis.call('EXPIRE', KEYS[1], 600)
return 1
