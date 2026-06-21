package com.mirboard.domain.admin;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * D-86 — 채팅 금칙어 마스킹. 금칙어를 같은 길이의 '*' 로 치환(대소문자 무시). 한국어 자모
 * 분리 우회 등 고급 회피 대응은 범위 밖(D-86) — 기본 마스킹만.
 */
@Service
public class ChatModerationService {

    private final List<Pattern> patterns;

    public ChatModerationService(ChatModerationProperties props) {
        this.patterns = props.bannedWords().stream()
                .filter(w -> w != null && !w.isBlank())
                .map(w -> Pattern.compile(Pattern.quote(w), Pattern.CASE_INSENSITIVE))
                .toList();
    }

    public String mask(String message) {
        if (message == null || patterns.isEmpty()) {
            return message;
        }
        String result = message;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("*".repeat(m.group().length())));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }
}
