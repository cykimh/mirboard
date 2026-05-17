package com.mirboard.infra.config;

import com.mirboard.infra.web.JsonAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   JsonAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Phase 12A (D-61) — 인증 필요 HTTP 표면은 /api/** 뿐.
                        // STOMP 는 /ws 핸드셰이크 permitAll + STOMP CONNECT 단계에서
                        // 별도 인증 (ChannelInterceptor). 정적 자산(cards/characters/
                        // sfx/board)·SPA 딥링크 경로를 일일이 열거하면 route-drift 로
                        // 401 이 반복 발생 (배포 시연에서 적발) → 비-API default-permit.
                        // ⚠️ 규약: 민감한 HTTP 엔드포인트는 반드시 /api/** 하위에 둘 것.
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/ws/**", "/error", "/actuator/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
