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
 * 처리와 같게 404 를 준다. 읽기 실패도 기동을 막지 않고 빈 틀로 둔다(경고 로그).
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

    private static Optional<String> load(Resource resource) {
        if (!resource.exists()) {
            log.info("방 미리보기 화면 틀 없음 — 클라 번들 미동봉 빌드: {}", resource.getDescription());
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("방 미리보기 화면 틀을 읽지 못함 — 미리보기 없이 기동: {}", resource.getDescription(), e);
            return Optional.empty();
        }
    }
}
