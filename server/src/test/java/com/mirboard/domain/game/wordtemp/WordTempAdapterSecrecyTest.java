package com.mirboard.domain.game.wordtemp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.AttemptResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Guess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.GuessEntry;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 은닉(룰 §12)을 <b>포트 어댑터가 내보내는 JSON</b>으로 확인한다 — 인프라는 {@code publicView}·{@code privateView}·이벤트를 Jackson 으로
 * 그대로 직렬화해 공개 토픽·본인 큐에 싣는다. 판을 끝까지 몰며 전이마다:
 * <ul>
 *   <li>공개 뷰·공개 이벤트 JSON 에 아직 공개되지 않은 낱말(후보·진행 중인 정답·공개 전 추리·가려진 답)이 <b>문자열로도 없다</b>.</li>
 *   <li>좌석마다의 비공개 뷰 JSON 에 <b>남의</b> 비밀이 없다(출제자도 남의 추리를 못 보고, 추리자는 정답을 못 본다).</li>
 *   <li>비공개 이벤트는 살아 있는 사람 한 좌석에게만 가고 순번을 쓰지 않는다(D-126). 공개 이벤트는 순번을 쓴다.</li>
 * </ul>
 * 룰 v2(D-320): <b>힌트와 온도는 비밀이 아니다</b> — 낼 때마다 공개 이벤트로 나가고 공개 뷰의 {@code hints} 에 있다. 그래서 힌트 낱말이
 * 누군가의 후보와 같은 글자열이면(고정 표의 강아지 등) 공개 JSON 에 나올 수 있다 — 그 낱말은 "알려진 낱말"로 친다.
 */
class WordTempAdapterSecrecyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private int checks;
    private int hintsSeen;

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 지금 모두가 알아도 되는 낱말 — 끝난 차례의 정답·차수 요약, 이번 차례의 힌트 전부(D-320), 공개된 추리(가려진 것 제외), 차례 끝 동안의 정답. */
    private static Set<String> publicWords(WordTempState s) {
        Set<String> words = new LinkedHashSet<>();
        for (TurnSummary turn : s.history()) {
            words.add(turn.answer());
            turn.revealed().forEach(r -> words.add(r.text()));
        }
        for (Revealed r : s.revealed()) {
            words.add(r.text());
        }
        for (AttemptResult a : s.attempts()) {
            for (GuessEntry g : a.guesses()) {
                if (!g.pass() && !g.masked()) {
                    words.add(g.text());
                }
            }
        }
        s.hints().forEach(h -> words.add(h.text())); // 채점 중·무효였던 것도 이벤트로 이미 나갔다
        boolean turnOpen = s.phase() == Phase.HINT || s.phase() == Phase.GUESS || s.phase() == Phase.ATTEMPT_RESULT;
        if (!turnOpen && s.answer() != null) {
            words.add(s.answer().word());
        }
        return words;
    }

    /** 내 후보의 범주 이름(자원의 글자열 — "물건" 등)은 낱말이 아니다. 한 글자 힌트("물")와 겹쳐 보이지 않게 뺀다. */
    private static String withoutCategories(String json) {
        return json.replaceAll("\"category\":\"[^\"]*\"", "\"category\":\"\"");
    }

    private static boolean covered(Set<String> known, String secret) {
        return secret == null || secret.isEmpty() || known.stream().anyMatch(k -> k != null && k.contains(secret));
    }

    private void check(WordTempAdapterHarness h) {
        checks++;
        WordTempState s = h.state();
        Set<String> known = publicWords(s);
        boolean turnOpen = s.phase() == Phase.HINT || s.phase() == Phase.GUESS || s.phase() == Phase.ATTEMPT_RESULT;

        // 모두에게 비밀인 것
        Set<String> secrets = new LinkedHashSet<>();
        for (int seat = 0; seat < s.seatCount(); seat++) {
            for (AnswerEntry c : s.seat(seat).candidates()) {
                secrets.add(c.word());
                secrets.addAll(c.aliases());
            }
        }
        for (Guess g : s.guesses()) {
            secrets.add(g.text());
        }
        for (AttemptResult a : s.attempts()) {
            a.guesses().stream().filter(GuessEntry::masked).forEach(g -> secrets.add(g.text()));
        }

        StringBuilder pub = new StringBuilder(json(h.adapter.publicView(s)));
        for (GameEvent e : h.events) {
            if (e.isPrivate()) {
                assertThat(e.sequenced()).as("비공개 이벤트는 순번을 쓰지 않는다: " + e.envelopeType()).isFalse();
                assertThat(h.config.bot(e.privateSeat())).as("봇에게 비공개 이벤트 없음").isFalse();
                assertThat(e.privateSeat()).isBetween(0, s.seatCount() - 1);
                assertThat(e).as("비공개는 후보와 내 추리뿐").isInstanceOfAny(WordTempEvent.WordCandidates.class,
                        WordTempEvent.GuessAccepted.class);
            } else {
                assertThat(e.sequenced()).isTrue();
                pub.append(json(e));
            }
        }
        // D-320 — 진행 중인 차례의 힌트(확정·채점 중)는 공개 뷰에 낱말·온도 그대로 있다
        if (turnOpen) {
            String view = json(h.adapter.publicView(s));
            for (Hint hint : s.hints()) {
                if (hint.status() != HintStatus.VOID) {
                    hintsSeen++;
                    assertThat(view).as("공개 뷰에 힌트 '%s'", hint.text())
                            .contains("\"text\":\"" + hint.text() + "\",\"attempt\":" + hint.attempt());
                }
            }
        }
        for (String secret : secrets) {
            if (!covered(known, secret)) {
                assertThat(pub.toString()).as("공개 JSON 에 비밀 '%s' (단계 %s)", secret, s.phase()).doesNotContain(secret);
            }
        }

        // 좌석마다 — 남의 비밀이 내 비공개 뷰에 없다
        for (int viewer = -1; viewer < s.seatCount(); viewer++) {
            var view = h.adapter.privateView(s, viewer);
            if (viewer < 0 || h.config.bot(viewer) || !s.alive(viewer)) {
                assertThat(view).as("관전자·봇·빠진 좌석은 비공개 뷰 없음").isEmpty();
                continue;
            }
            String mine = withoutCategories(json(view.orElseThrow()));
            Set<String> ok = new HashSet<>(known);
            for (AnswerEntry c : s.seat(viewer).candidates()) {
                ok.add(c.word());
            }
            assertThat(mine).as("비공개 뷰에는 힌트 목록이 없다").doesNotContain("\"hints\"");
            Guess own = s.guessOf(viewer);
            if (own != null) {
                ok.add(own.text());
            }
            for (String secret : secrets) {
                if (!covered(ok, secret)) {
                    assertThat(mine).as("좌석 %d 의 비공개 뷰에 남의 비밀 '%s' (단계 %s)", viewer, secret, s.phase())
                            .doesNotContain(secret);
                }
            }
            // 본인에게 가는 비공개 이벤트에도 남의 비밀이 없다
            for (GameEvent e : h.events) {
                if (e.isPrivate() && e.privateSeat() == viewer) {
                    String body = withoutCategories(json(e));
                    for (String secret : secrets) {
                        if (!covered(ok, secret)) {
                            assertThat(body).as("좌석 %d 의 %s 에 남의 비밀 '%s'", viewer, e.envelopeType(), secret)
                                    .doesNotContain(secret);
                        }
                    }
                }
            }
        }
    }

    @Test
    void a_scripted_turn_with_live_scoring_masked_guesses_and_a_solve_leaks_nothing() {
        WordTempAdapterHarness h = new WordTempAdapterHarness(4, Set.of(), 1, WordTempFixtures.table(), null);
        h.start(0);
        check(h);
        for (int seat = 0; seat < 4; seat++) {
            h.act(seat, new WordTempAction.SelectWord(1, 0));
            check(h);
        }
        // 1차 힌트(기준 70°) — 기준 아래 표 둘 + 채점 중 하나. 낼 때마다 공개 뷰에 보인다.
        h.hint("여름");
        check(h);
        h.hint("소금");
        check(h);
        h.hint("짠맛");
        check(h);
        assertThat(json(h.adapter.publicView(h.state()))).contains("\"hintCount\":3").contains("여름").contains("소금")
                .contains("짠맛").contains("PENDING").doesNotContain("바다");
        h.scorer.deliver("바다", "짠맛", new LiveScore.Scored(64));
        h.fire();
        check(h);
        h.endHints();
        check(h);
        assertThat(json(h.adapter.publicView(h.state()))).contains("여름").contains("소금").contains("짠맛")
                .contains("\"floorMet\":false").doesNotContain("바다");

        // 1차 추리 — 낸 사람만 표시되고 내용은 없다. 금칙어를 품은 답은 공개 뒤에도 가려진다.
        h.guess(1, "항구");
        check(h);
        assertThat(json(h.adapter.publicView(h.state()))).contains("\"guessedSeats\":[1]").doesNotContain("항구");
        h.guess(2, "바보야");
        check(h);
        h.guess(3, "");
        check(h);
        assertThat(h.state().phase()).isEqualTo(Phase.ATTEMPT_RESULT);
        String revealed = json(h.event(WordTempEvent.GuessesRevealed.class));
        assertThat(revealed).contains("항구").contains("···").doesNotContain("바보");

        // 2차 — 맞힌다
        h.fire();
        check(h);
        h.hint("파도"); // 93 ≥ 75 — 그 자리에서 추리로
        check(h);
        assertThat(h.state().phase()).isEqualTo(Phase.GUESS);
        h.guess(1, "바다요").guess(2, "소금").guess(3, "바다");
        check(h);
        h.fire();
        check(h);
        assertThat(h.state().phase()).isEqualTo(Phase.TURN_END);
        // 차례 끝 — 정답과 힌트 전체가 공개된다. 안 쓴 후보는 여전히 없다.
        assertThat(json(h.adapter.publicView(h.state()))).contains("바다").contains("여름").contains("짠맛")
                .doesNotContain("사과").doesNotContain("휴대폰");

        // 나머지 차례 — 출제자가 손을 놓는다(힌트 0개로 5차까지 → 차례 끝). 끝까지.
        while (!h.state().ended()) {
            h.fire();
            check(h);
        }
        String end = json(h.adapter.publicView(h.state())) + json(h.event(WordTempEvent.MatchEnded.class));
        assertThat(end).as("안 쓴 후보는 판이 끝나도 공개하지 않는다").doesNotContain("사과").doesNotContain("눈사람")
                .doesNotContain("기차").doesNotContain("축구");
        assertThat(checks).isGreaterThan(20);
        assertThat(hintsSeen).isPositive();
    }

    @Test
    void a_match_played_by_bots_around_one_human_leaks_nothing_at_any_transition() {
        for (int seats = 3; seats <= 6; seats++) {
            Set<Integer> bots = new HashSet<>();
            for (int seat = 1; seat < seats; seat++) {
                bots.add(seat);
            }
            WordTempAdapterHarness h = new WordTempAdapterHarness(seats, bots, 2, WordTempFixtures.table(), null);
            h.start(seats - 1);
            check(h);
            int guard = 0;
            while (!h.state().ended() && guard++ < 5_000) {
                WordTempState s = h.state();
                // 사람(좌석 0)은 추리 단계에 가끔 답을 낸다 — 공개 전 추리가 새지 않는지 본다
                if (s.phase() == Phase.GUESS && s.setterSeat() != 0 && s.guessOf(0) == null && guard % 2 == 0) {
                    h.guess(0, "고래");
                } else {
                    h.fire();
                }
                check(h);
            }
            assertThat(h.state().ended()).isTrue();
        }
        assertThat(checks).isGreaterThan(200);
        assertThat(hintsSeen).as("봇의 힌트도 공개 뷰에 있었다").isGreaterThan(50);
    }
}
