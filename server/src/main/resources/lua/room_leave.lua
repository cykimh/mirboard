-- room_leave.lua
-- Atomically remove player from room. Promote new host if needed. Clean up if empty.
-- D-122: a FINISHED room keeps its seat list (see below).
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = rooms:open
-- KEYS[4] = room:{roomId}:ready
-- KEYS[5] = room:{roomId}:spectators
-- ARGV    = userId, roomId
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_IN_ROOM
--    0 = room destroyed (last player left)
--    N (>= 1) = remaining player count (FINISHED: unchanged seat count — the seat is kept)

local userId = ARGV[1]
local roomId = ARGV[2]

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

-- D-122: seat index = position in the players list, and a started game's seats are
-- immutable. LREM on a FINISHED room shifted the remaining players' indexOf onto other
-- seats (wrong names on the result screen, and resync handing out another seat's
-- private view). So a FINISHED leave only drops the leaver's membership flags: no LREM,
-- no host promotion, no destroy-by-empty. The room fades with the 600s TTL that
-- room_finish.lua set. WAITING / IN_GAME behaviour below is unchanged.
if redis.call('HGET', KEYS[1], 'status') == 'FINISHED' then
    local players = redis.call('LRANGE', KEYS[2], 0, -1)
    for i = 1, #players do
        if players[i] == userId then
            redis.call('SREM', KEYS[4], userId)
            redis.call('SREM', KEYS[5], userId)
            return #players
        end
    end
    return -2
end

local removed = redis.call('LREM', KEYS[2], 0, userId)
if removed == 0 then
    return -2
end

local remaining = redis.call('LLEN', KEYS[2])
if remaining == 0 then
    -- D-74: ready SET, D-75: spectators SET 도 함께 삭제해 고아 키 방지.
    redis.call('DEL', KEYS[1], KEYS[2], KEYS[4], KEYS[5])
    redis.call('ZREM', KEYS[3], roomId)
    return 0
end

local hostId = redis.call('HGET', KEYS[1], 'hostId')
if hostId == userId then
    local newHost = redis.call('LINDEX', KEYS[2], 0)
    redis.call('HSET', KEYS[1], 'hostId', newHost)
end

return remaining
