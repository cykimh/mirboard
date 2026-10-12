package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

/**
 * D-133 — 방 미리보기의 화면 틀. 빌드된 {@code classpath:static/index.html}(Dockerfile 이 클라 번들을 복사)을 시작할 때 한 번
 * 읽어 둔다. 개발·테스트처럼 틀이 없으면 비어 있고, 컨트롤러는 그때 기존 리소스 처리와 같게 404 를 준다.
 */
class IndexHtmlTemplateTest {

    @Test
    @DisplayName("틀이 있으면 UTF-8 원문을 그대로 들고 있다")
    void loads_template_as_utf8() {
        String html = "<html><head><!--og:start--><title>미르보드카페 🃏</title><!--og:end--></head></html>";

        IndexHtmlTemplate template = new IndexHtmlTemplate(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));

        assertThat(template.html()).contains(html);
    }

    @Test
    @DisplayName("틀이 없으면 비어 있다 — 기동은 막지 않는다")
    void missing_template_is_empty() {
        IndexHtmlTemplate template = new IndexHtmlTemplate(new ClassPathResource("static/no-such-index.html"));

        assertThat(template.html()).isEmpty();
    }

    @Test
    @DisplayName("읽다가 실패해도 비어 있다 — 예외로 기동을 막지 않는다")
    void unreadable_template_is_empty() {
        AbstractResource broken = new AbstractResource() {
            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public String getDescription() {
                return "broken";
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("disk gone");
            }
        };

        assertThat(new IndexHtmlTemplate(broken).html()).isEmpty();
    }

    @Test
    @DisplayName("기본 생성자는 classpath:static/index.html 을 본다 — 클라 번들을 동봉하지 않은 빌드에서는 비어 있다")
    void default_location_is_static_index() {
        assertThat(IndexHtmlTemplate.LOCATION).isEqualTo("static/index.html");
        assertThat(new IndexHtmlTemplate().html().isPresent())
                .isEqualTo(new ClassPathResource(IndexHtmlTemplate.LOCATION).exists());
    }
}
