package com.mirboard.infra.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * D-133 — 방 미리보기의 화면 틀. 빌드된 클라 {@code index.html}(Dockerfile 이 {@code classpath:static/} 으로 복사 — D-39)을
 * <b>시작할 때 한 번</b> 읽어 둔다. 요청마다 파일을 읽지 않는다 — 번들은 배포 단위라 실행 중에 바뀌지 않는다.
 *
 * <p>개발·테스트처럼 클라 번들을 동봉하지 않은 빌드에는 틀이 없다({@link Optional#empty()}). 그때 미리보기 컨트롤러는 기존 리소스
 * 처리와 같게 404 를 준다. 읽기 실패도 기동을 막지 않고 빈 틀로 둔다(경고 로그). 읽은 틀에 미리보기 표식이 순서대로 한 쌍이 아니면
 * 기동은 그대로 하되 경고를 남긴다({@link #hasPreviewMarkers}).
 */
@Component
public class IndexHtmlTemplate {

    private static final Logger log = LoggerFactory.getLogger(IndexHtmlTemplate.class);

    static final String LOCATION = "static/index.html";

    private final Optional<String> html;

    @Autowired
    public IndexHtmlTemplate() {
        this(new ClassPathResource(LOCATION));
    }

    IndexHtmlTemplate(Resource resource) {
        this.html = load(resource);
    }

    /** 빌드된 {@code index.html} 원문. 없으면 비어 있다. */
    public Optional<String> html() {
        return html;
    }

    /**
     * 틀에 미리보기 표식이 순서대로 한 쌍({@code <!--og:start-->} 하나 뒤에 {@code <!--og:end-->} 하나) 있는가. 없거나 어긋나면
     * 렌더러가 조용히 틀을 그대로 돌려줘 모든 방이 공통 미리보기가 된다 — 빌드 도구(HTML 압축 등)나 {@code index.html} 정리가
     * 주석을 지운 경우다(최종 리뷰 FR-4).
     */
    static boolean hasPreviewMarkers(String html) {
        int start = html.indexOf(RoomPreviewRenderer.START);
        int end = html.indexOf(RoomPreviewRenderer.END);
        return start >= 0
                && end >= start + RoomPreviewRenderer.START.length()
                && html.indexOf(RoomPreviewRenderer.START, start + 1) < 0
                && html.indexOf(RoomPreviewRenderer.END, end + 1) < 0;
    }

    private static Optional<String> load(Resource resource) {
        if (!resource.exists()) {
            log.info("방 미리보기 화면 틀 없음 — 클라 번들 미동봉 빌드: {}", resource.getDescription());
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!hasPreviewMarkers(html)) {
                log.warn("방 미리보기 화면 틀에 표식({}…{})이 순서대로 한 쌍이 아님 — 모든 방이 공통 미리보기가 된다: {}",
                        RoomPreviewRenderer.START, RoomPreviewRenderer.END, resource.getDescription());
            }
            return Optional.of(html);
        } catch (IOException e) {
            log.warn("방 미리보기 화면 틀을 읽지 못함 — 미리보기 없이 기동: {}", resource.getDescription(), e);
            return Optional.empty();
        }
    }
}
