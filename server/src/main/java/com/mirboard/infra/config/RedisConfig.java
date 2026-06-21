package com.mirboard.infra.config;

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

    private static RedisScript<Long> scriptOf(String classpath) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpath));
        script.setResultType(Long.class);
        return script;
    }
}
