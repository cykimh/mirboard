package com.mirboard.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4.0 부터 Jackson autoconfigure 가 별도 모듈 (spring-boot-jackson) 로
 * 분리되었고, 단순 starter-jackson 추가만으론 ObjectMapper bean 이 자동 등록되지
 * 않는 환경이 발견되어 본 설정에서 명시적으로 노출한다. RedisTemplate / WS broker /
 * 게임 상태 직렬화 모두 동일한 인스턴스를 사용.
 *
 * <p>{@code findAndRegisterModules()} 가 classpath 의 모듈 (parameter-names, jdk8,
 * jsr310 등) 을 자동 등록해 record 컴포넌트 이름 보존과 Optional/Instant 직렬화를
 * 정상화한다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }
}
