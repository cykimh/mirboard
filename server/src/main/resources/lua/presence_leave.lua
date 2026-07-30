-- presence_leave.lua
-- D-96 — 방 프레즌스 세션 카운터 감소. 0 이하면 필드를 삭제해 "접속 없음"으로 만든다.
--
-- 한 유저가 탭을 여러 개 열 수 있으므로 boolean 이 아니라 카운터다. 하나만 닫혔을 때
-- 여전히 접속 중으로 보여야 탈주 오판을 막는다.
--
-- KEYS[1] = presence:room:{roomId}   (HASH: userId -> 세션 수)
-- ARGV[1] = userId
-- ARGV[2] = TTL 초 (고아 키 정리)
--
-- 반환: 감소 후 남은 세션 수 (0 이면 이 유저는 해당 방에서 완전히 끊김)

local remaining = redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
if remaining <= 0 then
    redis.call('HDEL', KEYS[1], ARGV[1])
    remaining = 0
end

-- 방에 아무도 없으면 키 자체를 지우고, 있으면 TTL 만 갱신.
if redis.call('HLEN', KEYS[1]) == 0 then
    redis.call('DEL', KEYS[1])
else
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end

return remaining
