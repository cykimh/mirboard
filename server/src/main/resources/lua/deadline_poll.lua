-- deadline_poll.lua
-- D-96 — 만료된 데드라인을 **원자적으로 pop**. 모든 인스턴스가 같은 ZSET 을 폴링하므로
-- pop 이 원자적이지 않으면 두 인스턴스가 같은 타이머를 동시에 발화한다.
--
-- ZRANGEBYSCORE + ZREM 을 Lua 한 덩어리로 묶어, 한 항목은 정확히 한 인스턴스에만 간다.
-- (리더 선출을 쓰지 않는 이유: 리더가 죽으면 모든 타이머가 멈추고, 선출 자체가 새로운
--  장애 모드다. ZSET 폴링은 인스턴스가 죽어도 남은 인스턴스가 그대로 인계한다.)
--
-- KEYS[1] = deadlines:{kind}      (ZSET: member=페이로드, score=만료 epochMillis)
-- ARGV[1] = 현재 epochMillis
-- ARGV[2] = 한 번에 가져올 최대 개수 (폭주 방지)
--
-- 반환: 만료된 member 배열 (없으면 빈 배열)

local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
if #due == 0 then
    return {}
end
-- 가져온 것만 정확히 제거 — 그 사이 새로 들어온 항목은 건드리지 않는다.
redis.call('ZREM', KEYS[1], unpack(due))
return due
