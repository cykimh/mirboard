package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import ch.qos.logback.classic.Level;
import com.mirboard.testsupport.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    /**
     * FR-4 — 빌드 도구(HTML 압축 플러그인 등)나 {@code index.html} 정리가 표식 주석을 지우면 렌더러는 조용히 틀을 그대로 돌려줘 모든 방이
     * 공통 미리보기가 된다. 시작할 때 한 번 경고를 남겨 배포 로그에서 알 수 있게 한다.
     */
    @Test
    @DisplayName("표식이 순서대로 한 쌍이면 표식 있음")
    void markers_in_order_once_are_present() {
        assertThat(IndexHtmlTemplate.hasPreviewMarkers("<head><!--og:start--><title>x</title><!--og:end--></head>")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<head><title>x</title></head>",
            "<head><!--og:start--><title>x</title></head>",
            "<head><title>x</title><!--og:end--></head>",
            "<head><!--og:end--><title>x</title><!--og:start--></head>",
            "<head><!--og:start--><!--og:end--><!--og:start--><!--og:end--></head>",
    })
    @DisplayName("표식이 없거나·한쪽뿐이거나·순서가 어긋나거나·두 쌍이면 표식 없음")
    void broken_markers_are_not_present(String html) {
        assertThat(IndexHtmlTemplate.hasPreviewMarkers(html)).isFalse();
    }

    @Test
    @DisplayName("읽은 틀에 표식이 한 쌍이 아니면 시작할 때 WARN — 모든 방이 공통 미리보기가 된다")
    void warns_when_template_has_no_markers() {
        String html = "<html><head><title>Mirboard</title></head></html>";

        try (LogCapture logs = LogCapture.of(IndexHtmlTemplate.class)) {
            IndexHtmlTemplate template = new IndexHtmlTemplate(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));

            assertThat(template.html()).contains(html);
            assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("표식"));
        }
    }

    @Test
    @DisplayName("표식이 맞으면·틀이 아예 없으면(번들 미동봉) 경고하지 않는다")
    void no_warning_for_good_or_missing_template() {
        String html = "<html><head><!--og:start--><title>Mirboard</title><!--og:end--></head></html>";

        try (LogCapture logs = LogCapture.of(IndexHtmlTemplate.class)) {
            new IndexHtmlTemplate(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));
            new IndexHtmlTemplate(new ClassPathResource("static/no-such-index.html"));

            assertThat(logs.messages(Level.WARN)).isEmpty();
        }
    }
}
