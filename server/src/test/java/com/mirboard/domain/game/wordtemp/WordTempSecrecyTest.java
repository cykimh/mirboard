package com.mirboard.domain.game.wordtemp;

import static com.mirboard.domain.game.wordtemp.WordTempFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.WordTempFixtures.Game;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempViews;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.PrivateView;
import com.mirboard.domain.game.wordtemp.state.WordTempVisibility;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-wordtemp.md` §12 — 누가 언제 무엇을 보나. 판정은 {@link WordTempVisibility} 한 곳이고, 공개 뷰·공개 이벤트 JSON 에는
 * 후보·진행 중인 정답·미공개 힌트·공개 전 추리가 <b>문자열로도 없다</b>. 상태는 Redis 에 JSON 으로 저장되고(S2) 액션은 클라 JSON 에서 읽힌다.
 */
class WordTempSecrecyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String publicJson(Game g) {
        return json(WordTempViews.publicView(g.state, g.engine.config(), g.now));
    }

    private String publicEventsJson(Game g) {
        // 이벤트는 하나씩 직렬화한다(인프라가 envelope 마다 하나씩 싣는 것과 같다)
        StringBuilder out = new StringBuilder();
        g.events.stream().filter(e -> !e.isPrivate()).forEach(e -> out.append(json(e)));
        return out.toString();
    }

    /** 사람 넷, 좌석 0 이 출제(정답 바다) — 힌트 셋(하나는 채점 중)까지 낸 힌트 단계. */
    private static Game hinting() {
        Game g = game();
        g.live = true;
        g.act(0, new WordTempAction.SelectWord(1, 0)).act(1, new WordTempAction.SelectWord(1, 1))
                .act(2, new WordTempAction.SelectWord(1, 2)).act(3, new WordTempAction.SelectWord(1, 0));
        return g.hint("여름").hint("갈매기").hint("짠맛");
    }

    private static Game guessing() {
        Game g = hinting().score("짠맛", new LiveScore.Scored(64)).endHints();
        return g.guess(1, "항구");
    }

    private static List<String> allCandidateWords(WordTempState s) {
        List<String> words = new ArrayList<>();
        s.seats().forEach(seat -> seat.candidates().forEach(c -> words.add(c.word())));
        return words;
    }

    // ---- 공개 뷰·공개 이벤트에 비밀이 없다 ----

    @Test
    void the_word_select_phase_shows_nobodys_candidates() {
        Game g = game(4, Set.of(3), 1);
        g.act(0, new WordTempAction.SelectWord(1, 0));
        String view = publicJson(g);
        assertThat(view).contains("\"chosen\":true");
        for (String word : allCandidateWords(g.state)) {
            assertThat(view).doesNotContain(word);
            assertThat(publicEventsJson(g)).doesNotContain(word);
        }
        assertThat(publicEventsJson(g)).isEqualTo("{\"@event\":\"WORD_SELECTED\",\"seat\":0}");
    }

    @Test
    void the_hint_phase_shows_only_the_count() {
        Game g = hinting();
        String view = publicJson(g);
        assertThat(view).contains("\"hintCount\":3").contains("\"maxHints\":5").contains("\"phase\":\"HINT\"");
        for (String secret : List.of("바다", "여름", "갈매기", "짠맛", "answer\":\"")) {
            assertThat(view).doesNotContain(secret);
        }
        assertThat(publicEventsJson(g)).isEqualTo("{\"@event\":\"HINT_COUNT\",\"count\":3,\"max\":5}");
        for (String word : allCandidateWords(g.state)) {
            assertThat(view).doesNotContain(word);
        }
    }

    @Test
    void the_guess_phase_shows_the_revealed_hint_and_who_answered_but_not_what() {
        Game g = guessing();
        String view = publicJson(g);
        assertThat(view).contains("갈매기").contains("\"guessedSeats\":[1]");
        for (String secret : List.of("바다", "여름", "짠맛", "항구")) {
            assertThat(view).doesNotContain(secret);
        }
        assertThat(publicEventsJson(g)).isEqualTo("{\"@event\":\"GUESS_SUBMITTED\",\"seat\":1}");
    }

    @Test
    void wrong_answers_are_shown_after_the_reveal_but_the_answer_and_other_hints_stay_hidden() {
        Game g = guessing().guess(2, "소금").guess(3, "");
        assertThat(g.state.phase()).isEqualTo(Phase.ATTEMPT_RESULT);
        String view = publicJson(g);
        assertThat(view).contains("항구").contains("소금").contains("갈매기");
        for (String secret : List.of("바다", "여름", "짠맛")) {
            assertThat(view).doesNotContain(secret);
            assertThat(publicEventsJson(g)).doesNotContain(secret);
        }
    }

    @Test
    void the_turn_end_reveals_the_answer_and_every_scored_hint_but_never_an_unused_candidate() {
        Game g = guessing().guess(2, "소금").guess(3, "").until(Phase.HINT);
        g.timer(); // 2차 힌트 0개 → 차례 끝
        assertThat(g.state.phase()).isEqualTo(Phase.TURN_END);
        String view = publicJson(g);
        String events = publicEventsJson(g);
        for (String shown : List.of("바다", "여름", "갈매기", "짠맛")) {
            assertThat(view).contains(shown);
            assertThat(events).contains(shown);
        }
        for (String unused : List.of("사과", "휴대폰", "케이크", "눈사람", "수영", "피아노", "기차", "학교", "고양이",
                "축구", "라면")) {
            assertThat(view).doesNotContain(unused);
        }
        // 다음 차례가 시작되면 방금 정답은 요약(history)에만 남고 새 정답은 다시 숨는다
        g.timer();
        String next = publicJson(g);
        assertThat(next).contains("바다").doesNotContain("\"turnReveal\":{").doesNotContain("눈사람");
        assertThat(next).doesNotContain("여름"); // 공개되지 않았던 힌트는 요약에 없다
    }

    @Test
    void unused_candidates_stay_private_even_after_the_match_ends() {
        Game g = game(3, Set.of(), 1).selectAll(0);
        for (int i = 0; i < 3; i++) {
            g.until(Phase.HINT).timer().timer();
        }
        assertThat(g.state.ended()).isTrue();
        String all = publicJson(g) + publicEventsJson(g);
        assertThat(all).contains("MATCH_ENDED");
        for (String unused : List.of("사과", "휴대폰", "눈사람", "수영", "기차", "학교")) {
            assertThat(all).doesNotContain(unused);
        }
    }

    // ---- 비공개 뷰 ----

    @Test
    void the_setter_sees_the_answer_and_every_hint_with_its_status() {
        Game g = hinting();
        PrivateView setter = WordTempViews.privateView(g.state, g.engine.config(), 0);
        assertThat(setter.answer()).isEqualTo("바다");
        assertThat(setter.hints()).extracting(WordTempViews.OwnHint::text).containsExactly("여름", "갈매기", "짠맛");
        assertThat(setter.hints()).extracting(WordTempViews.OwnHint::status)
                .containsExactly(HintStatus.SCORED, HintStatus.SCORED, HintStatus.PENDING);
        assertThat(setter.hints()).extracting(WordTempViews.OwnHint::temp).containsExactly(58, 71, -1);
        assertThat(setter.candidates()).extracting(WordTempEvent.CandidateInfo::word)
                .containsExactly("바다", "사과", "휴대폰");
        assertThat(setter.chosen()).isZero();
    }

    @Test
    void a_guesser_sees_their_own_candidates_and_guess_and_nothing_of_the_setter() {
        Game g = guessing();
        PrivateView one = WordTempViews.privateView(g.state, g.engine.config(), 1);
        assertThat(one.answer()).isNull();
        assertThat(one.hints()).isEmpty();
        assertThat(one.myGuess()).isEqualTo("항구");
        assertThat(one.candidates()).extracting(WordTempEvent.CandidateInfo::word)
                .containsExactly("케이크", "눈사람", "수영");
        PrivateView two = WordTempViews.privateView(g.state, g.engine.config(), 2);
        assertThat(two.myGuess()).isNull();
        assertThat(json(two)).doesNotContain("항구").doesNotContain("바다").doesNotContain("여름");
        // 출제자도 남의 답을 못 본다
        assertThat(json(WordTempViews.privateView(g.state, g.engine.config(), 0))).doesNotContain("항구");
    }

    @Test
    void bots_out_seats_and_spectators_get_no_private_view() {
        Game g = game(4, Set.of(3), 1).selectAll(0);
        assertThat(WordTempViews.privateView(g.state, g.engine.config(), 0)).isNotNull();
        assertThat(WordTempViews.privateView(g.state, g.engine.config(), 3)).isNull(); // 봇
        assertThat(WordTempViews.privateView(g.state, g.engine.config(), -1)).isNull(); // 관전자
        assertThat(WordTempViews.privateView(g.state, g.engine.config(), 7)).isNull();
        g.desert(2);
        assertThat(WordTempViews.privateView(g.state, g.engine.config(), 2)).isNull();
        assertThat(WordTempVisibility.privateRecipients(g.state, g.engine.config().bots())).containsExactly(0, 1);
    }

    @Test
    void visibility_is_decided_in_one_place() {
        Game g = hinting();
        assertThat(WordTempVisibility.seesAnswer(g.state, 0)).isTrue();
        assertThat(WordTempVisibility.seesHints(g.state, 0)).isTrue();
        for (int viewer : new int[] {-1, 1, 2, 3, 9}) {
            assertThat(WordTempVisibility.seesAnswer(g.state, viewer)).isFalse();
            assertThat(WordTempVisibility.seesHints(g.state, viewer)).isFalse();
        }
        assertThat(WordTempVisibility.seesCandidates(g.state, 1, 1)).isTrue();
        assertThat(WordTempVisibility.seesCandidates(g.state, 1, 2)).isFalse();
        assertThat(WordTempVisibility.seesCandidates(g.state, -1, -1)).isFalse();
        assertThat(WordTempVisibility.seesOwnGuess(g.state, 1)).isFalse(); // 아직 힌트 단계
        assertThat(WordTempVisibility.turnSecretsPublic(g.state)).isFalse();

        Game guessing = guessing();
        assertThat(WordTempVisibility.seesOwnGuess(guessing.state, 1)).isTrue();
        assertThat(WordTempVisibility.seesOwnGuess(guessing.state, 0)).isFalse(); // 출제자
        assertThat(WordTempVisibility.seesOwnGuess(guessing.state, -1)).isFalse();

        assertThat(WordTempVisibility.masked("왕바보", Set.of("바보"))).isTrue();
        assertThat(WordTempVisibility.masked("바다", Set.of("바보"))).isFalse();
        assertThat(WordTempVisibility.masked("", Set.of("바보"))).isFalse();
    }

    @Test
    void private_events_go_only_to_living_humans_and_use_no_sequence_number() {
        Game g = hinting();
        List<WordTempEvent> seen = new ArrayList<>(g.events);
        g.score("짠맛", new LiveScore.NotANoun());
        seen.addAll(g.events);
        g.endHints().guess(1, "항구");
        seen.addAll(g.events);
        assertThat(seen).anyMatch(WordTempEvent.HintPending.class::isInstance)
                .anyMatch(WordTempEvent.HintVoid.class::isInstance)
                .anyMatch(WordTempEvent.GuessAccepted.class::isInstance);
        for (WordTempEvent e : seen) {
            if (e instanceof WordTempEvent.Private p) {
                assertThat(p.isPrivate()).isTrue();
                assertThat(p.sequenced()).isFalse();
                assertThat(p.privateSeat()).isIn(0, 1);
            } else {
                assertThat(e.isPrivate()).isFalse();
                assertThat(e.sequenced()).isTrue();
            }
        }
        assertThat(json(new WordTempEvent.GuessSubmitted(1))).doesNotContain("private").doesNotContain("sequenced");
    }

    // ---- JSON ----

    @Test
    void actions_read_the_documented_json() throws Exception {
        assertThat(mapper.readValue("{\"@action\":\"SELECT_WORD\",\"round\":1,\"index\":2}", WordTempAction.class))
                .isEqualTo(new WordTempAction.SelectWord(1, 2));
        assertThat(mapper.readValue("{\"@action\":\"SUBMIT_HINT\",\"turnNo\":3,\"attempt\":2,\"text\":\"여름\"}",
                WordTempAction.class)).isEqualTo(new WordTempAction.SubmitHint(3, 2, "여름"));
        assertThat(mapper.readValue("{\"@action\":\"END_HINTS\",\"turnNo\":3,\"attempt\":2}", WordTempAction.class))
                .isEqualTo(new WordTempAction.EndHints(3, 2));
        assertThat(mapper.readValue("{\"@action\":\"SUBMIT_GUESS\",\"turnNo\":3,\"attempt\":2,\"text\":\"\"}",
                WordTempAction.class)).isEqualTo(new WordTempAction.SubmitGuess(3, 2, ""));
        assertThat(mapper.readValue("{\"@action\":\"TICK\",\"turnNo\":3,\"attempt\":2,\"phase\":\"GUESS\"}",
                WordTempAction.class)).isEqualTo(new WordTempAction.Tick(3, 2, Phase.GUESS));
    }

    @Test
    void states_round_trip_through_json_in_every_phase() throws Exception {
        List<Game> games = List.of(game(4, Set.of(3), 2), hinting(), guessing(), guessing().guess(2, "바다"),
                guessing().guess(2, "소금").until(Phase.HINT).timer(), hinting().desert(0).desert(1).desert(2));
        for (Game g : games) {
            String stored = mapper.writeValueAsString(g.state);
            assertThat(mapper.readValue(stored, WordTempState.class)).isEqualTo(g.state);
        }
        assertThat(games.get(5).state.ended()).isTrue();
        // 모르는 필드는 무시하고 없는 목록은 빈 목록으로 읽는다(구 JSON 호환)
        WordTempState s = games.get(0).state;
        String trimmed = mapper.writeValueAsString(s).replace("\"hints\":[],", "\"futureField\":1,");
        assertThat(mapper.readValue(trimmed, WordTempState.class)).isEqualTo(s);
    }

    @Test
    void events_carry_their_envelope_type() throws Exception {
        Game g = guessing().guess(2, "바다").guess(3, "");
        for (WordTempEvent e : g.events) {
            assertThat(mapper.writeValueAsString(e)).contains("\"@event\":\"" + e.envelopeType() + "\"");
        }
        assertThat(g.event(WordTempEvent.GuessesRevealed.class).envelopeType()).isEqualTo("GUESSES_REVEALED");
        assertThat(new WordTempEvent.WordCandidates(0, 1, List.of()).envelopeType()).isEqualTo("WORD_CANDIDATES");
        assertThat(AnswerEntry.of("바다", "자연").aliases()).isEmpty();
    }

    // ---- D-319: 화면이 쓰는 값은 서버가 싣는다 ----

    @Test
    void the_public_view_carries_the_attempt_floors() {
        WordTempFixtures.Game g = WordTempFixtures.game();
        assertThat(WordTempViews.publicView(g.state, g.engine.config(), g.now).floors())
                .isEqualTo(g.engine.config().tuning().floors())
                .containsExactly(50, 60, 70);
    }

    @Test
    void candidates_carry_a_korean_category_label_and_the_gloss_only_to_their_owner() {
        var entry = new com.mirboard.domain.game.wordtemp.words.AnswerEntry("강아지", "animal", "개의 새끼", java.util.List.of());
        var seat = new com.mirboard.domain.game.wordtemp.state.WordTempState.Seat(true, 0, 0,
                java.util.List.of(entry, com.mirboard.domain.game.wordtemp.words.AnswerEntry.of("우산", "생활")),
                java.util.List.of(), -1);
        var infos = WordTempViews.candidateInfos(seat);
        assertThat(infos.get(0).category()).isEqualTo("동물");
        assertThat(infos.get(0).gloss()).isEqualTo("개의 새끼");
        // 모르는 범주 코드는 그대로, 뜻풀이가 없으면 빈 문자열
        assertThat(infos.get(1).category()).isEqualTo("생활");
        assertThat(infos.get(1).gloss()).isEmpty();
        // 공개 뷰 타입에는 후보가 없다 — 뜻풀이가 새 길이 없다
        WordTempFixtures.Game g = WordTempFixtures.game();
        assertThat(json(WordTempViews.publicView(g.state, g.engine.config(), g.now))).doesNotContain("gloss").doesNotContain("candidates");
    }
}
