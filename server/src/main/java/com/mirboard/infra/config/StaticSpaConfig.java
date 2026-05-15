package com.mirboard.infra.config;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * React SPA 정적 서빙 + 클라이언트 라우터 fallback.
 *
 * Phase 7-3 (D-39): React 번들은 빌드 시 {@code server/src/main/resources/static/}
 * 으로 복사된다 (Dockerfile stage 2). 이 핸들러가 모든 경로에 대해 우선 정적 파일을
 * 찾고, 없으면 {@code /index.html} 로 fallback 해 SPA 의 history-routing 을 지원한다.
 *
 * REST(/api/**), WebSocket(/ws/**), Actuator(/actuator/**) 는 우선순위가 높은
 * Spring 의 controller / endpoint mapping 이 먼저 매칭되어 가로채므로 이 fallback
 * 의 영향을 받지 않는다.
 */
@Component
public class StaticSpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {
        private static final Resource INDEX = new ClassPathResource("/static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            return INDEX.exists() ? INDEX : null;
        }
    }
}
