package com.mirboard.infra.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> roomCreateScript() {
        return scriptOf("lua/room_create.lua");
    }

    @Bean
    public RedisScript<Long> roomJoinScript() {
        return scriptOf("lua/room_join.lua");
    }

    @Bean
    public RedisScript<Long> roomLeaveScript() {
        return scriptOf("lua/room_leave.lua");
    }

    @Bean
    public RedisScript<Long> roomReadyScript() {
        return scriptOf("lua/room_ready.lua");
    }

    @Bean
    public RedisScript<Long> roomFinishScript() {
        return scriptOf("lua/room_finish.lua");
    }

    /** Phase 19(#1, D-75) — 플레이어/관전자 0 인 방 무조건 소멸. */
    @Bean
    public RedisScript<Long> roomDeleteScript() {
        return scriptOf("lua/room_delete.lua");
    }

    /** D-84 — 인증 IP 고정 윈도 레이트리밋 (INCR+EXPIRE 원자). */
    @Bean
    public RedisScript<Long> rateLimitFixedWindowScript() {
        return scriptOf("lua/rate_limit_fixed_window.lua");
    }

    /** D-96 — 방 프레즌스 세션 카운터 감소(0 이면 필드 삭제). */
    @Bean
    public RedisScript<Long> presenceLeaveScript() {
        return scriptOf("lua/presence_leave.lua");
    }

    /**
     * D-96 — 만료 데드라인 원자 pop. 모든 인스턴스가 같은 ZSET 을 폴링하므로
     * ZRANGEBYSCORE+ZREM 이 한 덩어리여야 한 항목이 한 인스턴스에만 간다.
     * 반환이 배열이라 결과 타입이 List 다.
     */
    @Bean
    public RedisScript<List> deadlinePollScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/deadline_poll.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static RedisScript<Long> scriptOf(String classpath) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpath));
        script.setResultType(Long.class);
        return script;
    }
}
