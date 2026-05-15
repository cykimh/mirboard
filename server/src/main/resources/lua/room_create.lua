-- room_create.lua
-- Atomically materialize a new room: meta hash + players list + rooms:open ZSET.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = rooms:open
-- ARGV    = roomId, hostId, name, gameType, capacity, createdAt, teamPolicy, fillWithBots
--
-- Returns 1 on success. Caller generates a fresh UUID for roomId so duplicates
-- should not occur; we still refuse to clobber an existing room key.

local roomId       = ARGV[1]
local hostId       = ARGV[2]
local name         = ARGV[3]
local gameType     = ARGV[4]
local capacity     = ARGV[5]
local createdAt    = ARGV[6]
local teamPolicy   = ARGV[7]
local fillWithBots = ARGV[8]  -- "true" / "false"
local ttl          = 21600  -- 6h

if redis.call('EXISTS', KEYS[1]) == 1 then
    return -10  -- ROOM_ID_COLLISION (shouldn't happen with UUIDs)
end

redis.call('HSET', KEYS[1],
    'hostId',       hostId,
    'name',         name,
    'gameType',     gameType,
    'status',       'WAITING',
    'capacity',     capacity,
    'createdAt',    createdAt,
    'updatedAt',    createdAt,
    'teamPolicy',   teamPolicy,
    'fillWithBots', fillWithBots)
redis.call('EXPIRE', KEYS[1], ttl)

redis.call('RPUSH', KEYS[2], hostId)
redis.call('EXPIRE', KEYS[2], ttl)

redis.call('ZADD', KEYS[3], createdAt, roomId)

return 1
