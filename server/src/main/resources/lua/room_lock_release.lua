-- room_lock_release.lua  (D-140)
-- 방 액션 락을 내가 잡았을 때만 지운다. TTL(2초)이 지나 다른 경로가 새로 잡은 락을 늦게 돌아온 스레드가 지우면, 세 번째 경로가
-- 같은 방에 동시에 들어올 수 있었다(F-server-2).
--
-- KEYS[1] = room:{roomId}:lock
-- ARGV[1] = 획득 때 넣은 토큰
--
-- Return: 1 = 지움, 0 = 내 락이 아님(만료·남의 락) — 아무것도 안 함

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
