-- rate_limit_fixed_window.lua
-- D-84 — 고정 윈도 레이트리밋. INCR + (첫 요청에) EXPIRE 를 원자적으로 처리.
--
-- KEYS[1] = 카운터 키 (예: ratelimit:auth:ip:{ip})
-- ARGV[1] = limit (윈도당 최대 요청 수)
-- ARGV[2] = window 초
--
-- 반환: 1 = 허용, 0 = 한도 초과(거부)

local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
if count > tonumber(ARGV[1]) then
    return 0
end
return 1
