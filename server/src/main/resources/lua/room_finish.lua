-- room_finish.lua
-- Mark room as FINISHED and remove from open rooms ZSET. State/hand keys are
-- cleaned up by the caller (game-specific cleanup).
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = rooms:open
-- KEYS[3] = room:{roomId}:players
-- KEYS[4] = room:{roomId}:ready
-- KEYS[5] = room:{roomId}:spectators
-- ARGV    = roomId, now
--
-- Returns 1 on success, -1 if room missing.

local roomId = ARGV[1]
local now    = ARGV[2]
local ttl    = 600

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

redis.call('HSET', KEYS[1], 'status', 'FINISHED', 'updatedAt', now)
redis.call('ZREM', KEYS[2], roomId)
-- Trim TTL so the room metadata fades after the result screen has time to display.
redis.call('EXPIRE', KEYS[1], ttl)
-- D-122: a FINISHED room is never destroyed by its last leave (seats are kept), so its
-- membership keys fade together with the metadata instead of lingering for 6h.
-- EXPIRE on a missing key is a no-op.
redis.call('EXPIRE', KEYS[3], ttl)
redis.call('EXPIRE', KEYS[4], ttl)
redis.call('EXPIRE', KEYS[5], ttl)
return 1
