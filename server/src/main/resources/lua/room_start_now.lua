-- room_start_now.lua  (D-136, D-143)
-- 친구와 하기 대기실의 방장 시작. 정원을 현재 인원으로 줄이고 WAITING -> IN_GAME 을 한 번에 한다
-- (room_join.lua 와 같은 키를 원자적으로 다뤄 확인과 시작 사이에 누가 들어오는 일이 없다). 방장 확인은 호출측(자바)이 먼저 한다.
-- D-143 — 준비는 시작 조건이 아니고(방장이 누르면 시작), 정원이 다 찼어도 이 길로 시작한다(자동 시작 없음).
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

-- 준비 집합은 시작 조건이 아니지만 좌석과 맞춰 둔다(게임 시작 뒤 읽는 곳이 없어도 상태가 어긋나 보이지 않게).
for i = 1, #players do
    redis.call('SADD', KEYS[3], players[i])
end
redis.call('EXPIRE', KEYS[3], ttl)
redis.call('HSET', KEYS[1], 'capacity', #players, 'status', 'IN_GAME')
redis.call('ZREM', KEYS[4], roomId)
return 1
