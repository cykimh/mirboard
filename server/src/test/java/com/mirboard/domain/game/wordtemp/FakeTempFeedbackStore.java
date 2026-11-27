package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.wordtemp.feedback.TempFeedbackStore;
import java.util.ArrayList;
import java.util.List;

/** 테스트용 신고 저장소(D-323) — 받은 것을 `정답/힌트/방향/온도/버전` 글로 적어 둔다. {@link #failing} 이면 던진다. */
public final class FakeTempFeedbackStore implements TempFeedbackStore {

    public final List<String> records = new ArrayList<>();
    public boolean failing;

    @Override
    public void record(String answer, String hint, String direction, int temp, String version) {
        if (failing) {
            throw new IllegalStateException("feedback store down");
        }
        records.add(answer + "/" + hint + "/" + direction + "/" + temp + "/" + version);
    }

    @Override
    public List<Row> top(int limit) {
        return List.of();
    }
}
