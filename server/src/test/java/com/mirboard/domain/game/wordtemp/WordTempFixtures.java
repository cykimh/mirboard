package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.action.WordTempActionRejectedException;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.invariant.WordTempInvariantChecker;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 낱말 온도 엔진 테스트의 고정 표·고정 후보와 작은 진행 도우미. 표는 룰 §14 의 예(정답 바다)를 그대로 담는다 —
 * {@code 짠맛} 은 일부러 표에 없다(실시간 채점 경로).
 */
public final class WordTempFixtures {

    private WordTempFixtures() {
    }

    /** 좌석 0~5 의 후보(좌석마다 4개 — 라운드 수에 맞춰 앞에서 잘라 쓴다). 첫 후보가 기본 정답. */
    public static final List<List<AnswerEntry>> CANDIDATES = List.of(
            List.of(AnswerEntry.of("바다", "자연"), AnswerEntry.of("사과", "과일"),
                    AnswerEntry.of("휴대폰", "물건", "핸드폰"), AnswerEntry.of("우산", "생활")),
            List.of(AnswerEntry.of("케이크", "음식", "케익"), AnswerEntry.of("눈사람", "겨울"),
                    AnswerEntry.of("수영", "운동"), AnswerEntry.of("시계", "물건")),
            List.of(AnswerEntry.of("피아노", "악기"), AnswerEntry.of("기차", "탈것"),
                    AnswerEntry.of("학교", "장소"), AnswerEntry.of("연필", "문구")),
            List.of(AnswerEntry.of("고양이", "동물"), AnswerEntry.of("축구", "운동"),
                    AnswerEntry.of("라면", "음식"), AnswerEntry.of("안경", "물건")),
            List.of(AnswerEntry.of("자전거", "탈것"), AnswerEntry.of("도서관", "장소"),
                    AnswerEntry.of("딸기", "과일"), AnswerEntry.of("장갑", "겨울")),
            List.of(AnswerEntry.of("기타", "악기"), AnswerEntry.of("강아지", "동물"),
                    AnswerEntry.of("김밥", "음식"), AnswerEntry.of("지우개", "문구")));

    public static InMemorySimilarityTable table() {
        InMemorySimilarityTable t = new InMemorySimilarityTable();
        t.put("바다", "여름", 58).put("바다", "갈매기", 71).put("바다", "여행", 44).put("바다", "해변", 88)
                .put("바다", "파도", 93).put("바다", "해양", 97).put("바다", "소금", 62).put("바다", "항구", 80)
                .put("바다", "모래", 75).put("바다", "하늘", 31).put("바다", "물", 66).put("바다", "고래", 78)
                .put("바다", "섬", 72).put("바다", "배", 70).put("바다", "수영", 64);
        t.put("케이크", "생일", 84).put("케이크", "빵", 79).put("케이크", "촛불", 73).put("케이크", "설탕", 61)
                .put("케이크", "파티", 67).put("케이크", "접시", 42).put("케이크", "우유", 48)
                .put("케이크", "디저트", 91).put("케이크", "구름", 18);
        t.put("피아노", "건반", 92).put("피아노", "악기", 86).put("피아노", "음악", 77).put("피아노", "연주", 74)
                .put("피아노", "소리", 55).put("피아노", "손가락", 51).put("피아노", "나무", 33).put("피아노", "학원", 63);
        t.put("고양이", "야옹", 90).put("고양이", "수염", 76).put("고양이", "강아지", 70).put("고양이", "동물", 68)
                .put("고양이", "생선", 64).put("고양이", "털", 59).put("고양이", "집", 41).put("고양이", "발톱", 72);
        t.put("자전거", "바퀴", 82).put("자전거", "페달", 89).put("자전거", "헬멧", 66).put("자전거", "도로", 57)
                .put("자전거", "운동", 52).put("자전거", "공원", 45);
        t.put("기타", "줄", 78).put("기타", "악기", 85).put("기타", "노래", 69).put("기타", "음악", 74)
                .put("기타", "연주", 73).put("기타", "나무", 35);
        for (List<AnswerEntry> hand : CANDIDATES) {
            for (AnswerEntry e : hand) {
                t.answer(e.word());
            }
        }
        t.put("사과", "과일", 88).put("사과", "빨강", 70).put("사과", "나무", 62).put("사과", "씨", 55);
        t.put("눈사람", "겨울", 80).put("눈사람", "눈", 90).put("눈사람", "당근", 60).put("눈사람", "장갑", 52);
        t.put("수영", "물", 82).put("수영", "수영복", 90).put("수영", "여름", 60).put("수영", "운동", 70);
        return t;
    }

    /** 좌석 {@code first} 가 첫 출제자가 되는 씨앗. */
    public static long seedStartingAt(int seats, int first) {
        for (long seed = 1; ; seed++) {
            if (new SplittableRandom(seed).nextInt(seats) == first) {
                return seed;
            }
        }
    }

    public static List<List<AnswerEntry>> candidates(int seats, int rounds) {
        int per = Math.max(3, rounds + 1);
        List<List<AnswerEntry>> hands = new ArrayList<>();
        for (int s = 0; s < seats; s++) {
            hands.add(CANDIDATES.get(s).subList(0, per));
        }
        return hands;
    }

    /** 사람만 4명·1라운드·힌트 90초, 좌석 0 부터 출제 — 룰 §14 의 판. */
    public static Game game() {
        return game(4, Set.of(), 1);
    }

    public static Game game(int seats, Set<Integer> bots, int rounds) {
        return new Game(WordTempConfig.of(seats, bots, rounds, 90, WordTempBotLevel.NORMAL), 0);
    }

    /**
     * 한 판을 손으로 몰아가는 도우미 — 시각을 들고 다니며 전이마다 불변식을 확인한다. 마지막 전이의 이벤트는 {@link #events}.
     */
    public static final class Game {
        public final WordTempEngine engine;
        public WordTempState state;
        public long now = 1_000_000L;
        public List<WordTempEvent> events;
        public boolean live;

        public Game(WordTempConfig config, int firstSetter) {
            this.engine = new WordTempEngine(config, table());
            var r = engine.start(candidates(config.seatCount(), config.rounds()),
                    seedStartingAt(config.seatCount(), firstSetter), now);
            take(r);
        }

        private void take(WordTempEngine.Result r) {
            state = r.newState();
            events = r.events();
            WordTempInvariantChecker.check(state, engine.config());
        }

        public Game after(long ms) {
            now += ms;
            return this;
        }

        public Game act(int seat, WordTempAction action) {
            take(engine.apply(state, seat, action, now, live));
            return this;
        }

        public RejectionReason rejected(int seat, WordTempAction action) {
            WordTempState before = state;
            try {
                engine.apply(state, seat, action, now, live);
            } catch (WordTempActionRejectedException e) {
                if (state != before) {
                    throw new AssertionError("rejection changed the state");
                }
                return e.reason();
            }
            throw new AssertionError("expected a rejection: " + action);
        }

        /** 살아 있는 사람 모두가 {@code index} 번 후보를 고른다 → 첫 출제 차례의 힌트 단계. */
        public Game selectAll(int index) {
            for (int seat : engine.pendingSeats(state)) {
                if (state.phase() == Phase.WORD_SELECT) {
                    act(seat, new WordTempAction.SelectWord(state.round(), index));
                }
            }
            return this;
        }

        public Game hint(String text) {
            return act(state.setterSeat(), new WordTempAction.SubmitHint(state.turnNo(), state.attempt(), text));
        }

        public RejectionReason hintRejected(String text) {
            return rejected(state.setterSeat(),
                    new WordTempAction.SubmitHint(state.turnNo(), state.attempt(), text));
        }

        public Game endHints() {
            return act(state.setterSeat(), new WordTempAction.EndHints(state.turnNo(), state.attempt()));
        }

        public Game guess(int seat, String text) {
            return act(seat, new WordTempAction.SubmitGuess(state.turnNo(), state.attempt(), text));
        }

        /** 남은 사람 추리자가 모두 패스한다. */
        public Game passAll() {
            for (int seat : engine.pendingSeats(state)) {
                if (state.phase() == Phase.GUESS) {
                    guess(seat, "");
                }
            }
            return this;
        }

        /** 다음 타이머 시각까지 흘려 보내고 {@code onTimer}. */
        public Game timer() {
            now += engine.timer(state, now).orElseThrow();
            take(engine.onTimer(state, now));
            return this;
        }

        /** 단계가 {@code phase} 가 될 때까지 타이머를 돌린다. */
        public Game until(Phase phase) {
            for (int i = 0; i < 200 && state.phase() != phase; i++) {
                timer();
            }
            if (state.phase() != phase) {
                throw new AssertionError("never reached " + phase + ": " + state);
            }
            return this;
        }

        public Game score(String hint, LiveScore score) {
            take(engine.applyScore(state, hint, score, now));
            return this;
        }

        public Game desert(int seat) {
            var d = engine.desert(state, seat, now);
            state = d.newState();
            events = d.events();
            WordTempInvariantChecker.check(state, engine.config());
            return this;
        }

        public <T extends WordTempEvent> T event(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).findFirst()
                    .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in " + events));
        }

        public boolean has(Class<? extends WordTempEvent> type) {
            return events.stream().anyMatch(type::isInstance);
        }

        public int total(int seat) {
            return state.seat(seat).total();
        }
    }
}
