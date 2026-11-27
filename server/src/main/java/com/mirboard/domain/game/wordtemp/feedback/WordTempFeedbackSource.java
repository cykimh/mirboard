package com.mirboard.domain.game.wordtemp.feedback;

import com.mirboard.domain.game.core.GameFeedbackSource;
import com.mirboard.domain.game.wordtemp.WordTempGameDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 낱말 온도의 운영자용 신고 목록(D-323) — 게임 중립 어드민 경로({@link GameFeedbackSource})에 꽂힌다. */
@Component
public class WordTempFeedbackSource implements GameFeedbackSource {

    private final TempFeedbackStore store;

    public WordTempFeedbackSource(TempFeedbackStore store) {
        this.store = store;
    }

    @Override
    public String gameType() {
        return WordTempGameDefinition.ID;
    }

    @Override
    public List<Map<String, Object>> top(int limit) {
        return store.top(limit).stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("answer", r.answer());
            row.put("hint", r.hint());
            row.put("direction", r.direction());
            row.put("temp", r.temp());
            row.put("version", r.version());
            row.put("count", r.count());
            row.put("firstSeen", r.firstSeen().toEpochMilli());
            row.put("lastSeen", r.lastSeen().toEpochMilli());
            return row;
        }).toList();
    }
}
