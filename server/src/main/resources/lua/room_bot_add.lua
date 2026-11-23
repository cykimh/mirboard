-- room_bot_add.lua  (D-290)
-- 대기실에서 방장이 봇을 앉힌다 — 한 명씩(count = 1) 또는 정원까지(count = -1). 방장·WAITING·판돈 0·정원 확인과 앉히기를 한 번에
-- 한다(room_join.lua 와 같은 키를 원자적으로 다뤄 확인과 앉히기 사이에 누가 들어오거나 판이 시작되는 일이 없다).
-- 봇은 준비 표시를 하지 않는다(준비는 사람만 센다, D-161).
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- ARGV    = hostId, now, count(-1 = 정원까지), botId1, botId2, ...  (앉힐 후보 — 이미 앉은 봇은 건너뛴다)
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_WAITING (이미 시작/종료)
--   -3 = NOT_HOST
--   -4 = STAKED_ROOM (판돈 방에는 봇을 앉히지 않는다, D-81)
--    n (>= 0) = 앉힌 봇 수 (0 = 빈자리 또는 남은 봇이 없다)

local hostId = ARGV[1]
local now    = ARGV[2]
local count  = tonumber(ARGV[3])
local ttl    = 21600

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
    return -2
end
if redis.call('HGET', KEYS[1], 'hostId') ~= hostId then
    return -3
end
local stake = tonumber(redis.call('HGET', KEYS[1], 'stake') or '0')
if stake and stake > 0 then
    return -4
end

local capacity = tonumber(redis.call('HGET', KEYS[1], 'capacity'))
local players = redis.call('LRANGE', KEYS[2], 0, -1)
local seatedIds = {}
for i = 1, #players do
    seatedIds[players[i]] = true
end

local seated = #players
local added = 0
for i = 4, #ARGV do
    if seated >= capacity then
        break
    end
    if count >= 0 and added >= count then
        break
    end
    local botId = ARGV[i]
    if not seatedIds[botId] then
        redis.call('RPUSH', KEYS[2], botId)
        seatedIds[botId] = true
        seated = seated + 1
        added = added + 1
    end
end

if added > 0 then
    redis.call('HSET', KEYS[1], 'updatedAt', now)
    redis.call('EXPIRE', KEYS[1], ttl)
    redis.call('EXPIRE', KEYS[2], ttl)
end
return added
