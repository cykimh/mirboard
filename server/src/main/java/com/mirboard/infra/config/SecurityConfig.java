package com.mirboard.infra.config;

import com.mirboard.infra.ratelimit.HttpRateLimitFilter;
import com.mirboard.infra.web.JsonAuthenticationEntryPoint;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   HttpRateLimitFilter rateLimitFilter,
                                                   JsonAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // D-83 — CORS 는 corsConfigurationSource 빈(화이트리스트)으로. 전면 개방 폐지.
                .cors(Customizer.withDefaults())
                // D-83 — 보안 헤더. HSTS 는 스프링 기본 동작상 HTTPS 요청에서만 송출돼
                // dev http 에 무해(프로필 분기 불필요). CSP 는 정적 자산이 /api 밖(규칙#8)
                // 이라 잘못 좁히면 데모가 깨질 위험 → report-only 도입은 M2 로 보류.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000L)))
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
                // D-90 — 레이트리밋은 JwtAuthFilter **뒤**여야 한다: 그래야 인증된 요청이
                // IP 가 아니라 userId 키를 쓴다(NAT 뒤 지인들이 서로 할당량을 안 깎음).
                .addFilterAfter(rateLimitFilter, JwtAuthFilter.class)
                .build();
    }

    /** D-83 — 환경별 origin 화이트리스트. JWT 는 Authorization 헤더라 쿠키 자격증명 불필요. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
