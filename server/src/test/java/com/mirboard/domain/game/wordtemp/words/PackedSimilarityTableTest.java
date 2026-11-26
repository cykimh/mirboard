package com.mirboard.domain.game.wordtemp.words;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.wordtemp.words.SimilarityTable.Neighbor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** D-316 — 정렬 배열 표의 읽기·조회·형식 검사(작은 손 표). 진짜 표는 {@code WordTempRealDataTest}. */
class PackedSimilarityTableTest {

    private static PackedSimilarityTable table(String text) throws IOException {
        return PackedSimilarityTable.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String SMALL = """
            #wordtemp-table\tversion=t1\tpromptVersion=score-v1\tmodel=m\tcreated=2026-11-25\tanswers=2\tpairs=5
            바다\t갈매기:71,물:66,여름:58
            사과\t과일:88,물:20
            """;

    @Test
    void lookups_find_exactly_the_pairs_in_the_file() throws IOException {
        PackedSimilarityTable t = table(SMALL);
        assertThat(t.lookup("바다", "갈매기")).hasValue(71);
        assertThat(t.lookup("바다", "물")).hasValue(66);
        assertThat(t.lookup("사과", "물")).hasValue(20);
        assertThat(t.lookup("사과", "과일")).hasValue(88);
        // 표에 없는 쌍은 "모름"이다 — 낮은 점수가 아니다(어휘에 있는 낱말이어도 그 정답의 이웃이 아니면 없다)
        assertThat(t.lookup("사과", "갈매기")).isEmpty();
        assertThat(t.lookup("바다", "짠맛")).isEmpty();
        assertThat(t.lookup("하늘", "물")).isEmpty();
        assertThat(t.lookup(null, "물")).isEmpty();
        assertThat(t.version()).isEqualTo("t1");
        assertThat(t.promptVersion()).isEqualTo("score-v1");
        assertThat(t.pairCount()).isEqualTo(5);
        assertThat(t.vocabularySize()).isEqualTo(4);
    }

    @Test
    void neighbors_and_answers_keep_the_file_order() throws IOException {
        PackedSimilarityTable t = table(SMALL);
        assertThat(t.answers()).containsExactly("바다", "사과");
        assertThat(t.neighbors("바다")).containsExactly(new Neighbor("갈매기", 71), new Neighbor("물", 66),
                new Neighbor("여름", 58));
        assertThat(t.neighbors("하늘")).isEmpty();
    }

    @Test
    void a_malformed_or_unsorted_file_does_not_load() {
        assertThatThrownBy(() -> table("바다\t물:66\n")).hasMessageContaining("header");
        assertThatThrownBy(() -> table("#wordtemp-table\tanswers=1\tpairs=2\n바다\t물:66,갈매기:71\n"))
                .hasMessageContaining("ascending");
        assertThatThrownBy(() -> table("#wordtemp-table\n사과\t물:20\n바다\t물:66\n")).hasMessageContaining("ascending");
        assertThatThrownBy(() -> table("#wordtemp-table\n바다\t물:101\n")).hasMessageContaining("range");
        assertThatThrownBy(() -> table("#wordtemp-table\tanswers=2\tpairs=1\n바다\t물:66\n"))
                .hasMessageContaining("answers=2");
    }
}
