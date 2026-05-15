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
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/ws/**", "/error").permitAll()
                        // Phase 6A-3 — Actuator (health, prometheus 스크래핑) 은 내부망 노출 가정.
                        // 운영 전에 IP 화이트리스트 또는 별도 management.server.port 분리 필요.
                        .requestMatchers("/actuator/**").permitAll()
                        // Phase 7-3 (D-39) — Spring 이 직접 서빙하는 React SPA 정적 파일.
                        // 정적 파일과 SPA 라우터 경로 (StaticSpaConfig 의 fallback) 는 인증 없이
                        // 응답. 실제 API 호출은 /api/** 와 /ws/** 에서 인증을 강제한다.
                        .requestMatchers(HttpMethod.GET,
                                "/", "/index.html",
                                "/assets/**", "/static/**",
                                "/*.svg", "/*.png", "/*.ico", "/*.txt",
                                "/login", "/register",
                                "/hub", "/hub/**",
                                "/lobby", "/lobby/**",
                                "/room", "/room/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
