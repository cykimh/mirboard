package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.wordtemp.words.SimilarityTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * 테스트용 메모리 유사도 표 — 넣은 순서를 지킨다(봇의 결정이 재현되게). 운영 구현(정렬 배열)은 S1 데이터 작업·S2 의 몫.
 */
public final class InMemorySimilarityTable implements SimilarityTable {

    private final Map<String, Map<String, Integer>> rows = new LinkedHashMap<>();
    private final Map<String, List<Neighbor>> neighbors = new LinkedHashMap<>();

    public InMemorySimilarityTable put(String answer, String hint, int temp) {
        Integer old = rows.computeIfAbsent(answer, a -> new LinkedHashMap<>()).put(hint, temp);
        List<Neighbor> list = neighbors.computeIfAbsent(answer, a -> new ArrayList<>());
        if (old != null) {
            list.removeIf(n -> n.hint().equals(hint));
        }
        list.add(new Neighbor(hint, temp));
        return this;
    }

    /** 이웃 없이 정답만 올린다(봇의 "아는 은행"에 들어가게). */
    public InMemorySimilarityTable answer(String answer) {
        rows.computeIfAbsent(answer, a -> new LinkedHashMap<>());
        neighbors.computeIfAbsent(answer, a -> new ArrayList<>());
        return this;
    }

    @Override
    public OptionalInt lookup(String answer, String hint) {
        Map<String, Integer> row = rows.get(answer);
        Integer temp = row == null ? null : row.get(hint);
        return temp == null ? OptionalInt.empty() : OptionalInt.of(temp);
    }

    @Override
    public List<Neighbor> neighbors(String answer) {
        List<Neighbor> list = neighbors.get(answer);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public List<String> answers() {
        return List.copyOf(rows.keySet());
    }
}
