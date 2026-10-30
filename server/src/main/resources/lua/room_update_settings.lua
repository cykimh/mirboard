-- room_update_settings.lua  (D-161)
-- 대기실에서 방장이 게임 설정을 바꾼다. 상태 확인과 쓰기를 한 번에 — 확인한 뒤 시작된 방의 설정이 바뀌지 않게.
-- 방장·값 검증은 서비스가 먼저 한다.
--
-- KEYS[1] = room:{roomId}
-- ARGV    = field1, value1, field2, value2, ...
--
-- Return codes:
--   -1 = ROOM_NOT_FOUND
--   -2 = NOT_WAITING (이미 시작/종료)
--    1 = updated

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
    return -2
end
if #ARGV > 0 then
    redis.call('HSET', KEYS[1], unpack(ARGV))
end
return 1
