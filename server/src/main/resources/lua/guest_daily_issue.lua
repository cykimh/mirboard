-- guest_daily_issue.lua
-- D-117 — 게스트 일일 전역 상한 카운터: INCR + (첫 발급에) EXPIRE 를 한 번에.
--
-- KEYS[1] = guest:issued:{UTC yyyy-MM-dd}
-- ARGV[1] = ttl 초 (48h — 자정 경계의 늦은 INCR 까지 덮도록 이틀)
--
-- 반환: 오늘의 발급 순번(1부터). 상한 비교·경보는 호출부(GuestAccountService)가 한다.
--
-- 왜 Lua 인가: INCR 과 EXPIRE 를 왕복 두 번으로 나누면 그 사이 EXPIRE 가 실패하거나
-- 프로세스가 죽을 때 TTL 없는 날짜 키가 영구히 남는다 — 이후 요청은 순번이 2 이상이라
-- 다시 EXPIRE 를 걸지 않는다. 원자로 묶으면 "카운터가 있으면 TTL 도 있다"가 항상 참이다
-- (rate_limit_fixed_window.lua 와 같은 패턴).

local n = redis.call('INCR', KEYS[1])
if n == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return n
