-- room_start_now.lua  (D-136)
-- 친구와 하기 대기실의 "지금 N명으로 시작". 정원을 현재 인원으로 줄이고 WAITING -> IN_GAME 을 한 번에 한다
-- (room_join.lua 와 같은 키를 원자적으로 다뤄 확인과 시작 사이에 누가 들어오는 일이 없다). 방장 확인은 호출측(자바)이 먼저 한다.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = room:{roomId}:ready
-- KEYS[4] = rooms:open
-- ARGV    = hostId, minPlayers, roomId
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_WAITING (already started / finished)
--   -3 = NOT_MEMBER (host is not a seated player)
--   -4 = NOT_ENOUGH_PLAYERS (#players < minPlayers)
--   -5 = PLAYERS_NOT_READY (someone other than the host is not ready)
--   -6 = ROOM_ALREADY_FULL (#players >= capacity — start by ready instead)
--    1 = started (capacity := #players, WAITING -> IN_GAME)

local hostId     = ARGV[1]
local minPlayers = tonumber(ARGV[2])
local roomId     = ARGV[3]
local ttl        = 21600

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
    return -2
end

local players = redis.call('LRANGE', KEYS[2], 0, -1)
local isMember = false
for i = 1, #players do
    if players[i] == hostId then
        isMember = true
        break
    end
end
if not isMember then
    return -3
end

if #players < minPlayers then
    return -4
end

local capacity = tonumber(redis.call('HGET', KEYS[1], 'capacity'))
if #players >= capacity then
    return -6
end

for i = 1, #players do
    if players[i] ~= hostId and redis.call('SISMEMBER', KEYS[3], players[i]) == 0 then
        return -5
    end
end

redis.call('SADD', KEYS[3], hostId)
redis.call('EXPIRE', KEYS[3], ttl)
redis.call('HSET', KEYS[1], 'capacity', #players, 'status', 'IN_GAME')
redis.call('ZREM', KEYS[4], roomId)
return 1
