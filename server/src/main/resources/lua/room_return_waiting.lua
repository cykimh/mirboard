-- room_return_waiting.lua  (D-153)
-- 정상 종료한 친구 방을 대기실로 돌린다: IN_GAME -> WAITING, 정원 := 게임 최대 인원(D-143 시작이 줄였다), 준비 비우기,
-- 관전자를 들어온 순서(spectators_since 점수)대로 빈자리에 앉히기, 방 목록에 다시 올리기. 한 번에 원자적으로 한다.
-- 관전 순서 ZSET 에 남은 지난 관전자(이미 나간 사람)는 SET 과 맞춰 걸러낸다.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- KEYS[3] = room:{roomId}:ready
-- KEYS[4] = room:{roomId}:spectators
-- KEYS[5] = room:{roomId}:spectators_since
-- KEYS[6] = rooms:open
-- ARGV    = maxPlayers, roomId, now
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_IN_GAME (그대로 둔다)
--    n = 돌아간 뒤 좌석 수

local maxPlayers = tonumber(ARGV[1])
local roomId     = ARGV[2]
local now        = ARGV[3]
local ttl        = 21600

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
if redis.call('HGET', KEYS[1], 'status') ~= 'IN_GAME' then
    return -2
end

redis.call('DEL', KEYS[3])

local seated = redis.call('LLEN', KEYS[2])
local ordered = redis.call('ZRANGE', KEYS[5], 0, -1)
local queue = {}
local queued = {}
for i = 1, #ordered do
    if redis.call('SISMEMBER', KEYS[4], ordered[i]) == 1 then
        queue[#queue + 1] = ordered[i]
        queued[ordered[i]] = true
    end
end
-- 순서 기록이 없는 관전자(이 스크립트 이전에 들어온 사람)는 뒤에 붙인다.
local rest = redis.call('SMEMBERS', KEYS[4])
table.sort(rest)
for i = 1, #rest do
    if not queued[rest[i]] then
        queue[#queue + 1] = rest[i]
    end
end

for i = 1, #queue do
    if seated >= maxPlayers then
        break
    end
    redis.call('RPUSH', KEYS[2], queue[i])
    redis.call('SREM', KEYS[4], queue[i])
    redis.call('ZREM', KEYS[5], queue[i])
    seated = seated + 1
end

redis.call('HSET', KEYS[1], 'status', 'WAITING', 'capacity', maxPlayers, 'updatedAt', now)
redis.call('ZADD', KEYS[6], now, roomId)
redis.call('EXPIRE', KEYS[1], ttl)
redis.call('EXPIRE', KEYS[2], ttl)
return seated
