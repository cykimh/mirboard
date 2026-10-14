package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mirboard.infra.web.RoomPreviewRenderer.RoomPreview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-133 — 방 미리보기 렌더러(순수). 빌드된 {@code index.html} 의 {@code <!--og:start-->}…{@code <!--og:end-->} 구간을 그 방의
 * Open Graph 로 바꾼다. 카톡 스크래퍼는 자바스크립트를 실행하지 않으므로 이 HTML 이 곧 말풍선 미리보기다.
 *
 * <p>사용자 입력(방 이름·방장 이름)은 메타 속성 안에 들어가므로 HTML 이스케이프가 핵심이다 — 따옴표 하나로 속성이 끊기면
 * 미리보기가 깨지고, 꺾쇠는 마크업 주입이 된다. 현재 인원은 넣지 않는다(카톡이 미리보기를 캐시해 낡은 숫자가 보인다).
 */
class RoomPreviewRendererTest {

    static final String TEMPLATE = """
            <!doctype html>
            <html lang="ko">
              <head>
                <meta charset="UTF-8" />
                <!--og:start-->
                <title>Mirboard</title>
                <meta property="og:title" content="미르보드카페 — 공통" />
                <!--og:end-->
              </head>
              <body><div id="root"></div></body>
            </html>
            """;

    private static RoomPreview room(String roomName, String host) {
        return new RoomPreview("원카드", roomName, host, 4, "https://mirboard.test/rooms/r1");
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("구간을 그 방의 미리보기로 바꾼다 — 제목·설명·주소·사이트 이름·로케일·카드 종류·<title>")
    void replaces_block_with_room_preview() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("금요일 한 판", "alice"));

        assertThat(html)
                .contains("<title>원카드 · 금요일 한 판 — 미르보드카페</title>")
                .contains("<meta property=\"og:title\" content=\"원카드 · 금요일 한 판\" />")
                .contains("<meta property=\"og:description\" content=\"alice님이 초대했어요 · 4인 방 · 누르면 바로 입장\" />")
                .contains("<meta name=\"description\" content=\"alice님이 초대했어요 · 4인 방 · 누르면 바로 입장\" />")
                .contains("<meta property=\"og:url\" content=\"https://mirboard.test/rooms/r1\" />")
                .contains("<meta property=\"og:type\" content=\"website\" />")
                .contains("<meta property=\"og:site_name\" content=\"미르보드카페\" />")
                .contains("<meta property=\"og:locale\" content=\"ko_KR\" />")
                .contains("<meta name=\"twitter:card\" content=\"summary_large_image\" />")
                .doesNotContain("미르보드카페 — 공통")
                .doesNotContain("<title>Mirboard</title>");
        assertThat(count(html, "<title>")).isEqualTo(1);
    }

    /** D-138 — 카톡 큰 미리보기 이미지. 스크래퍼는 상대 주소를 풀지 못하므로 방 주소의 출처(scheme://host)를 붙인 절대 주소다. */
    @Test
    @DisplayName("미리보기 이미지 — 방 주소의 출처 + /og-image.jpg, 크기 1200×630, 큰 카드")
    void adds_preview_image_on_the_same_origin() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("금요일 한 판", "alice"));

        assertThat(html)
                .contains("<meta property=\"og:image\" content=\"https://mirboard.test/og-image.jpg\" />")
                .contains("<meta property=\"og:image:width\" content=\"1200\" />")
                .contains("<meta property=\"og:image:height\" content=\"630\" />")
                .contains("<meta property=\"og:image:alt\" content=\"카드 테이블 — 미르보드카페\" />");
    }

    @Test
    @DisplayName("방 주소가 이상하면 이미지 없이 — 작은 카드")
    void no_image_without_a_usable_url() {
        String html = RoomPreviewRenderer.render(TEMPLATE, new RoomPreview("원카드", "방", "alice", 4, "not a url"));

        assertThat(html)
                .doesNotContain("og:image")
                .contains("<meta name=\"twitter:card\" content=\"summary\" />");
    }

    @Test
    @DisplayName("구간 밖(문서 앞뒤)은 한 글자도 바꾸지 않고 표식은 남긴다")
    void keeps_everything_outside_the_block() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("방", "alice"));

        String before = TEMPLATE.substring(0, TEMPLATE.indexOf(RoomPreviewRenderer.START));
        String after = TEMPLATE.substring(TEMPLATE.indexOf(RoomPreviewRenderer.END));
        assertThat(html).startsWith(before + RoomPreviewRenderer.START).endsWith(after);
    }

    @Test
    @DisplayName("방 이름의 따옴표·꺾쇠·앰퍼샌드는 이스케이프돼 메타 속성이 깨지지 않는다")
    void escapes_room_name() {
        String html = RoomPreviewRenderer.render(TEMPLATE,
                room("\"><script>alert('x')</script> & 친구들", "alice"));

        assertThat(html)
                .contains("content=\"원카드 · &quot;&gt;&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; 친구들\"")
                .doesNotContain("<script>")
                .doesNotContain("\"><script");
    }

    @Test
    @DisplayName("방 이름에 표식을 넣어도 구간이 늘지 않는다 — 꺾쇠가 이스케이프된다")
    void marker_in_room_name_is_inert() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("<!--og:end--><!--og:start-->", "alice"));

        assertThat(count(html, RoomPreviewRenderer.START)).isEqualTo(1);
        assertThat(count(html, RoomPreviewRenderer.END)).isEqualTo(1);
    }

    @Test
    @DisplayName("방장 이름도 이스케이프한다")
    void escapes_host_name() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("방", "a\"b<c>"));

        assertThat(html).contains("a&quot;b&lt;c&gt;님이 초대했어요").doesNotContain("a\"b");
    }

    @Test
    @DisplayName("이모지는 그대로 둔다(UTF-8) — 숫자 문자 참조로 바꾸지 않는다")
    void keeps_emoji() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("🃏 한 판 🎉", "alice"));

        assertThat(html).contains("원카드 · 🃏 한 판 🎉");
    }

    @Test
    @DisplayName("방 이름이 40글자를 넘으면 40글자에서 자르고 말줄임표 — 이모지(서로게이트 쌍)를 반으로 가르지 않는다")
    void clips_long_room_name_keeping_surrogate_pairs() {
        String longName = "🎲".repeat(39) + "가나다라";
        String html = RoomPreviewRenderer.render(TEMPLATE, room(longName, "alice"));

        String expected = "🎲".repeat(39) + "가…";
        assertThat(html).contains("<meta property=\"og:title\" content=\"원카드 · " + expected + "\" />");
        assertThat(html).doesNotContain("가나");
        // 반쪽 서로게이트가 섞이면 UTF-8 로 인코딩할 때 '?' 가 된다.
        assertThat(new String(html.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(html);
    }

    @Test
    @DisplayName("딱 40글자인 방 이름은 자르지 않는다")
    void keeps_room_name_of_exactly_forty() {
        String name = "가".repeat(40);
        String html = RoomPreviewRenderer.render(TEMPLATE, room(name, "alice"));

        assertThat(html).contains("원카드 · " + name + "\" />").doesNotContain("…");
    }

    /**
     * FR-3 — 길이는 사람이 보는 글자(확장 그래핌 묶음) 수다. 코드 포인트로 세면 ZWJ 가족 이모지(코드 포인트 5개)가 경계에 걸릴 때
     * 낱개 사람 이모지만 남고, 딱 40글자인 이름도 잘린다.
     */
    @Test
    @DisplayName("ZWJ 가족 이모지가 경계에 걸려도 통째로 두고 그 뒤에서 자른다")
    void clips_by_grapheme_keeping_zwj_sequence() {
        String family = "👨‍👩‍👧";
        String html = RoomPreviewRenderer.render(TEMPLATE, room("가".repeat(39) + family + "끝", "alice"));

        assertThat(html).contains("<meta property=\"og:title\" content=\"원카드 · " + "가".repeat(39) + family + "…\" />");
        assertThat(html).doesNotContain("끝");
    }

    @Test
    @DisplayName("ZWJ 가족 이모지로 끝나는 딱 40글자 이름은 자르지 않는다(코드 포인트로는 44개)")
    void keeps_forty_graphemes_with_zwj_sequence() {
        String name = "가".repeat(39) + "👨‍👩‍👧";
        String html = RoomPreviewRenderer.render(TEMPLATE, room(name, "alice"));

        assertThat(html).contains("원카드 · " + name + "\" />").doesNotContain("…");
    }

    @Test
    @DisplayName("NFD 한글(초성+중성 자모)은 한 글자로 센다 — 자모 가운데서 자르지 않는다")
    void counts_nfd_hangul_as_one_grapheme() {
        String ga = Normalizer.normalize("가", Normalizer.Form.NFD);
        assertThat(ga.codePointCount(0, ga.length())).isEqualTo(2);

        String thirty = RoomPreviewRenderer.render(TEMPLATE, room(ga.repeat(30), "alice"));
        String fortyOne = RoomPreviewRenderer.render(TEMPLATE, room(ga.repeat(41), "alice"));

        assertThat(thirty).contains("원카드 · " + ga.repeat(30) + "\" />").doesNotContain("…");
        assertThat(fortyOne).contains("원카드 · " + ga.repeat(40) + "…\" />");
    }

    @Test
    @DisplayName("방장 이름도 20글자(그래핌)에서 자른다 — 가족 이모지를 가르지 않는다")
    void clips_host_name_by_grapheme() {
        String family = "👨‍👩‍👧";
        String html = RoomPreviewRenderer.render(TEMPLATE, room("방", "a".repeat(19) + family + "b"));

        assertThat(html).contains("content=\"" + "a".repeat(19) + family + "…님이 초대했어요");
    }

    @Test
    @DisplayName("방 이름의 줄바꿈·연속 공백은 공백 하나로 접는다")
    void collapses_whitespace() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("  금요일\n\t한   판 ", "alice"));

        assertThat(html).contains("content=\"원카드 · 금요일 한 판\"");
    }

    @Test
    @DisplayName("방 이름이 비면 \"{방장}님의 방\"")
    void blank_room_name_falls_back_to_host() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("   ", "guest-abcd2345"));

        assertThat(html).contains("<meta property=\"og:title\" content=\"원카드 · guest-abcd2345님의 방\" />");
    }

    @Test
    @DisplayName("방장을 모르면(계정 정리 등) 초대한 사람 없이 정원만 — 방 이름이 비면 \"초대받은 방\"")
    void unknown_host() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("", null));

        assertThat(html)
                .contains("<meta property=\"og:title\" content=\"원카드 · 초대받은 방\" />")
                .contains("<meta property=\"og:description\" content=\"4인 방 · 누르면 바로 입장\" />")
                .doesNotContain("null");
    }

    @Test
    @DisplayName("현재 인원은 넣지 않는다 — 렌더러 입력에 인원이 없고 결과에 \"명\"·\"/\" 인원 표기가 없다")
    void excludes_current_player_count() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("방", "alice"));

        assertThat(RoomPreview.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("gameName", "roomName", "hostName", "capacity", "url");
        assertThat(html).doesNotContain("명").doesNotContain("1/4").doesNotContain("참가");
    }

    @Test
    @DisplayName("표식이 없으면 틀 그대로")
    void no_markers_returns_template() {
        String plain = "<html><head><title>Mirboard</title></head></html>";

        assertThat(RoomPreviewRenderer.render(plain, room("방", "alice"))).isSameAs(plain);
    }

    @Test
    @DisplayName("표식 순서가 어긋나거나 한쪽만 있으면 틀 그대로")
    void broken_markers_return_template() {
        String reversed = "<head><!--og:end--><title>x</title><!--og:start--></head>";
        String onlyStart = "<head><!--og:start--><title>x</title></head>";

        assertThat(RoomPreviewRenderer.render(reversed, room("방", "alice"))).isSameAs(reversed);
        assertThat(RoomPreviewRenderer.render(onlyStart, room("방", "alice"))).isSameAs(onlyStart);
    }

    @Test
    @DisplayName("실제 client/index.html 의 표식과 맞물린다 — 공통 제목이 방 제목으로 바뀌고 <title> 은 하나")
    void works_with_real_client_index_html() throws IOException {
        Path index = Path.of("../client/index.html");
        assumeTrue(Files.exists(index), "저장소 밖에서 돌면 건너뛴다(클라 원문 없음)");
        String template = Files.readString(index, StandardCharsets.UTF_8);
        // 시작할 때 경고(FR-4)가 실제 틀에는 뜨지 않는다 — 표식이 순서대로 한 쌍.
        assertThat(IndexHtmlTemplate.hasPreviewMarkers(template)).isTrue();

        String html = RoomPreviewRenderer.render(template, room("금요일 한 판", "alice"));

        assertThat(html).isNotEqualTo(template)
                .contains("<meta property=\"og:title\" content=\"원카드 · 금요일 한 판\" />")
                .doesNotContain("미르보드카페 — 티츄·스컬킹·원카드\"")
                .contains("<div id=\"root\"></div>");
        assertThat(count(html, "<title>")).isEqualTo(1);
    }
}
