-- room_update_settings.lua  (D-161, D-176)
-- 대기실에서 방장이 게임 설정을 바꾼다. 상태 확인과 쓰기를 한 번에 — 확인한 뒤 시작된 방의 설정이 바뀌지 않게.
-- 방장·값 검증은 서비스가 먼저 한다.
-- D-176 — 정원이 바뀌는 설정(예: 1:1 ↔ 2:2)은 maxSeated 를 넘긴다: 앉은 인원이 그보다 많으면 아무것도 쓰지 않는다.
-- room_join.lua 도 같은 해시의 capacity 를 원자적으로 읽으므로 확인 뒤 누가 앉아 새 정원을 넘는 일이 없다.
--
-- KEYS[1] = room:{roomId}
-- KEYS[2] = room:{roomId}:players
-- ARGV    = maxSeated(-1 = 확인 안 함), field1, value1, field2, value2, ...
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_WAITING (이미 시작/종료)
--   -3 = TOO_MANY_PLAYERS (#players > maxSeated)
--    1 = updated

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
    return -2
end
local maxSeated = tonumber(ARGV[1])
if maxSeated >= 0 and redis.call('LLEN', KEYS[2]) > maxSeated then
    return -3
end
if #ARGV > 1 then
    redis.call('HSET', KEYS[1], unpack(ARGV, 2))
end
return 1
