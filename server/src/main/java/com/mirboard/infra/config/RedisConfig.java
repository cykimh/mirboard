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
    public RedisScript<Long> roomFinishScript() {
        return scriptOf("lua/room_finish.lua");
    }

    private static RedisScript<Long> scriptOf(String classpath) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpath));
        script.setResultType(Long.class);
        return script;
    }
}
