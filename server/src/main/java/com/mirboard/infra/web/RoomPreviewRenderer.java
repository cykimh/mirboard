package com.mirboard.infra.web;

import java.text.BreakIterator;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * D-133 — 방 미리보기(Open Graph) 렌더러. 순수 함수 — 화면 틀(빌드된 {@code index.html}) 문자열과 방 정보로 HTML 을 만든다.
 *
 * <p>틀의 {@code <!--og:start-->}…{@code <!--og:end-->} 구간(사이트 공통 미리보기, 클라 {@code index.html})을 그 방의 미리보기로
 * 바꾸고 구간 밖은 한 글자도 건드리지 않는다. 표식이 없거나 순서가 어긋나면 틀을 그대로 돌려준다.
 *
 * <ul>
 *   <li>제목 "{게임 표시 이름} · {방 이름}"(방 이름이 비면 "{방장}님의 방"), {@code <title>} 은 제목 + " — 미르보드카페".</li>
 *   <li>설명 "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장". <b>현재 인원은 넣지 않는다</b> — 카톡이 미리보기를 한동안
 *       저장해 낡은 숫자가 보인다(입력 {@link RoomPreview} 에 인원이 아예 없다).</li>
 *   <li>사용자 입력(방 이름·방장 이름)은 공백을 접고 길이를 자른 뒤(방 이름 {@value #MAX_ROOM_NAME}글자·방장 {@value #MAX_HOST_NAME}글자,
 *       사람이 보는 글자인 확장 그래핌 묶음 기준 — ZWJ 이모지·NFD 한글 자모를 가르지 않는다) 모든 값을
 *       {@link HtmlUtils#htmlEscape(String, String)} 으로 이스케이프한다(속성 안 따옴표 포함, UTF-8 이라 한글·이모지는 그대로).</li>
 * </ul>
 */
public final class RoomPreviewRenderer {

    static final String START = "<!--og:start-->";
    static final String END = "<!--og:end-->";
    static final String SITE_NAME = "미르보드카페";
    /** 방 이름 길이 상한(글자 — 확장 그래핌 묶음). 카톡 말풍선 제목은 두 줄 남짓에서 어차피 잘린다 — 메타가 끝없이 길어지지 않게만. */
    static final int MAX_ROOM_NAME = 40;
    /** 방장 이름 상한(글자). 가입 규칙(3~20자)·게스트 이름(`guest-` + 8자)이 이미 그 안이라 방어용. */
    static final int MAX_HOST_NAME = 20;

    private static final String INDENT = "    ";

    /**
     * 미리보기에 쓰는 방 정보. {@code hostName} 은 방장 계정을 못 찾으면 {@code null}. {@code url} 은 요청의 절대 주소
     * (프록시 forwarded 헤더 반영, 쿼리 없음).
     */
    public record RoomPreview(String gameName, String roomName, String hostName, int capacity, String url) {
    }

    private RoomPreviewRenderer() {
    }

    /** 틀의 표식 구간을 방 미리보기로 바꾼다. 표식이 없거나 어긋나면 틀 그대로. */
    public static String render(String template, RoomPreview room) {
        int start = template.indexOf(START);
        int end = template.indexOf(END);
        if (start < 0 || end < start + START.length()) {
            return template;
        }
        return template.substring(0, start + START.length()) + "\n"
                + block(room)
                + INDENT + template.substring(end);
    }

    private static String block(RoomPreview room) {
        String host = room.hostName() == null ? null : clip(room.hostName(), MAX_HOST_NAME);
        String title = room.gameName() + " · " + roomLabel(room.roomName(), host);
        String description = (host == null ? "" : host + "님이 초대했어요 · ")
                + room.capacity() + "인 방 · 누르면 바로 입장";
        return line("<title>" + esc(title + " — " + SITE_NAME) + "</title>")
                + meta("name", "description", description)
                + meta("property", "og:type", "website")
                + meta("property", "og:site_name", SITE_NAME)
                + meta("property", "og:title", title)
                + meta("property", "og:description", description)
                + meta("property", "og:url", room.url())
                + meta("property", "og:locale", "ko_KR")
                + meta("name", "twitter:card", "summary");
    }

    private static String roomLabel(String roomName, String host) {
        String name = roomName == null ? "" : clip(roomName, MAX_ROOM_NAME);
        if (!name.isEmpty()) {
            return name;
        }
        return host == null ? "초대받은 방" : host + "님의 방";
    }

    /**
     * 공백(줄바꿈·탭 포함)을 하나로 접고 앞뒤를 다듬은 뒤 글자 {@code max} 개에서 자른다(넘치면 말줄임표). 글자는 확장 그래핌 묶음
     * (JDK 20+ {@link BreakIterator#getCharacterInstance()}) — 코드 포인트로 세면 👨‍👩‍👧(코드 포인트 5개)이 경계에서 낱개
     * 이모지로 갈리고 NFD 한글(자모 2~3개가 한 글자)이 자모 가운데서 잘린다(최종 리뷰 FR-3).
     */
    static String clip(String value, int max) {
        String folded = value.strip().replaceAll("\\s+", " ");
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(folded);
        int cut = graphemes.first();
        for (int i = 0; i < max; i++) {
            int next = graphemes.next();
            if (next == BreakIterator.DONE) {
                return folded; // max 글자 이하.
            }
            cut = next;
        }
        if (graphemes.next() == BreakIterator.DONE) {
            return folded; // 딱 max 글자.
        }
        return folded.substring(0, cut) + "…";
    }

    private static String meta(String attr, String key, String content) {
        return line("<meta " + attr + "=\"" + key + "\" content=\"" + esc(content) + "\" />");
    }

    private static String line(String tag) {
        return INDENT + tag + "\n";
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
