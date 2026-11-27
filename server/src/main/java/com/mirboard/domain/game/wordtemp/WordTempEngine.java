package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.action.WordTempActionRejectedException;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotPolicy;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent.ScoreChange;
import com.mirboard.domain.game.wordtemp.rules.AnswerJudge;
import com.mirboard.domain.game.wordtemp.rules.CandidateDealer;
import com.mirboard.domain.game.wordtemp.rules.HintRules;
import com.mirboard.domain.game.wordtemp.rules.MatchRanking;
import com.mirboard.domain.game.wordtemp.rules.WordNormalizer;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.AttemptResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.BotMove;
import com.mirboard.domain.game.wordtemp.state.WordTempState.BotMoveKind;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Draft;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Guess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.GuessEntry;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintSource;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import com.mirboard.domain.game.wordtemp.state.WordTempState.VoidReason;
import com.mirboard.domain.game.wordtemp.state.WordTempViews;
import com.mirboard.domain.game.wordtemp.state.WordTempVisibility;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BiConsumer;

/**
 * 낱말 온도 순수 룰 엔진(`docs/rules-wordtemp.md`). 저장소·시계·네트워크가 없다 — 시각은 인자로, 유사도 표는 {@link SimilarityTable} 로
 * 주입받고, 무작위는 전부 상태의 씨앗에서 나온다(같은 입력이면 같은 판). 포트 어댑터(S2)가 이것을 감싸 저장·방송·엔진 타이머·실시간
 * 채점기를 붙인다(D-98 2계층).
 *
 * <p><b>들어오는 길은 넷</b>이다.
 * <ul>
 *   <li>{@link #apply} — 클라 액션(낱말 고르기·힌트·[힌트 끝]·추리)과 서버 타임아웃의 멱등 {@code TICK}. 거절은
 *       {@link WordTempActionRejectedException} 이고 상태를 바꾸지 않는다.</li>
 *   <li>{@link #applyScore} — 잠금 밖에서 끝난 실시간 채점의 결과 반영(§6.2). 엔진은 I/O 를 하지 않는다: 표에 없는 힌트를
 *       <b>채점 중</b>으로 상태에 넣고 비공개 {@code HINT_PENDING} 을 낼 뿐이고, 결과는 어댑터가 가져와 이 함수로 넣는다.</li>
 *   <li>{@link #onTimer} — 시간이 지나면 일어나는 것 전부: 채점 기다림 상한(무효·개수 반환) → 봇 행동 → 단계 마감 순으로 <b>하나만</b>.
 *       {@link #timer} 가 그 가장 이른 시각까지 남은 시간을 돌려준다(포트 ⑦ — 단계 시작 시각이 상태에 있어 재무장해도 처음부터 세지 않는다).
 *       어댑터는 채점 결과 폴링(250ms)을 이 값과 min 으로 합친다(설계서 §4.3).</li>
 *   <li>{@link #desert} — 탈주·강퇴·유예 만료(§10).</li>
 * </ul>
 */
public final class WordTempEngine {

    private final WordTempConfig config;
    private final WordTempTuning tuning;
    private final SimilarityTable table;
    private final WordTempBotPolicy botPolicy;
    private final BiConsumer<String, RuntimeException> botFault;

    public WordTempEngine(WordTempConfig config, SimilarityTable table) {
        this(config, table, (what, fault) -> {
        });
    }

    /**
     * @param botFault 봇 정책이 예외를 냈을 때 불린다(무엇을 하다가, 예외) — 엔진은 그 좌석을 [힌트 끝]·패스로 넘기고 계속한다. 순수 엔진은
     *                 로그를 모르므로 포트 어댑터가 여기에 ERROR 로그를 꽂는다(D-118·D-119).
     */
    public WordTempEngine(WordTempConfig config, SimilarityTable table, BiConsumer<String, RuntimeException> botFault) {
        this.config = config;
        this.tuning = config.tuning();
        this.table = table;
        this.botPolicy = new WordTempBotPolicy(table);
        this.botFault = botFault;
    }

    public WordTempConfig config() {
        return config;
    }

    public record Result(WordTempState newState, List<WordTempEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    public record Desertion(WordTempState newState, Outcome outcome, List<WordTempEvent> events) {

        public enum Outcome {
            /** 이미 빠졌거나 끝난 매치 — 아무것도 바뀌지 않았다. */
            NOT_APPLICABLE,
            MATCH_CONTINUES,
            MATCH_ENDED
        }

        public Desertion {
            events = List.copyOf(events);
        }
    }

    /** 실시간 채점의 결과(§6.2) — 어댑터가 채점기의 답을 이 꼴로 바꿔 {@link #applyScore} 에 넣는다. */
    public sealed interface LiveScore permits LiveScore.Scored, LiveScore.NotANoun, LiveScore.Failed {

        /** 명사이고 점수가 왔다. 0~100 밖은 엔진이 자른다. */
        record Scored(int temp) implements LiveScore {
        }

        /** 명사가 아니다 → 무효. */
        record NotANoun() implements LiveScore {
        }

        /** 실패·거절·형식 밖 응답 → 무효. (시간 초과는 {@link #onTimer} 가 같은 처리를 한다.) */
        record Failed() implements LiveScore {
        }
    }

    // ---- 시작 (§2·§3.1) ----

    /**
     * 1라운드 낱말 고르기로 시작한다. 후보는 부르는 쪽이 나눠 준다({@link CandidateDealer}). 돌려주는 이벤트(첫
     * {@code WORD_SELECT_STARTED} + 각자의 {@code WORD_CANDIDATES})는 스타터가 방송한다 — {@code MATCH_STARTED} 는 없다(시작 상태는 resync 로).
     */
    public Result start(List<List<AnswerEntry>> candidates, long seed, long now) {
        int per = CandidateDealer.perSeat(config.rounds());
        if (candidates == null || candidates.size() != config.seatCount()
                || candidates.stream().anyMatch(c -> c == null || c.size() != per)) {
            throw new IllegalArgumentException("Expected " + config.seatCount() + " seats x " + per + " candidates");
        }
        int n = config.seatCount();
        int first = new SplittableRandom(seed).nextInt(n);
        List<Integer> order = new ArrayList<>(n);
        List<Seat> seats = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add((first + i) % n);
            seats.add(new Seat(true, 0, 0, candidates.get(i), List.of(), -1));
        }
        WordTempState state = new WordTempState(seats, order, 1, 0, -1, 0, Phase.WORD_SELECT, now, null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, seed, null, List.of());
        List<WordTempEvent> events = new ArrayList<>();
        announceSelect(state, events);
        return new Result(state, events);
    }

    private void announceSelect(WordTempState state, List<WordTempEvent> events) {
        events.add(new WordTempEvent.WordSelectStarted(state.round(), tuning.selectMs()));
        for (int seat : WordTempVisibility.privateRecipients(state, config.bots())) {
            events.add(new WordTempEvent.WordCandidates(seat, state.round(),
                    WordTempViews.candidateInfos(state.seat(seat))));
        }
    }

    // ---- 액션 (§12.1) ----

    /** 실시간 채점 없이(표만) — 표 밖 힌트는 {@code UNKNOWN_WORD}. */
    public Result apply(WordTempState state, int seat, WordTempAction action, long now) {
        return apply(state, seat, action, now, false);
    }

    /**
     * @param liveAvailable 지금 실시간 채점을 요청할 수 있는가(켜짐·하루 상한 — 어댑터가 판단). 판 상한은 엔진이 상태로 센다
     */
    public Result apply(WordTempState state, int seat, WordTempAction action, long now, boolean liveAvailable) {
        if (state.ended()) {
            throw reject(RejectionReason.MATCH_OVER);
        }
        if (action == null) {
            throw reject(RejectionReason.INVALID_ACTION);
        }
        Draft d = state.draft();
        List<WordTempEvent> events = new ArrayList<>();
        switch (action) {
            case WordTempAction.SelectWord a -> selectWord(d, seat, a, now, events);
            case WordTempAction.SubmitHint a -> submitHint(d, seat, a, now, liveAvailable, events);
            case WordTempAction.EndHints a -> endHints(d, seat, a, now, events);
            case WordTempAction.SubmitGuess a -> submitGuess(d, seat, a, now, events);
            case WordTempAction.FlagTemp a -> flagTemp(d, seat, a, events);
            case WordTempAction.Tick a -> {
                if (a.phase() != state.phase() || a.turnNo() != state.turnNo() || a.attempt() != state.attempt()
                        || state.pendingHint() != null || now < deadline(state)) {
                    return new Result(state, List.of()); // 낡았거나 아직 마감 전 — 무변경(멱등, §9)
                }
                onDeadline(d, now, events);
            }
        }
        return new Result(d.build(), events);
    }

    private void selectWord(Draft d, int seat, WordTempAction.SelectWord a, long now, List<WordTempEvent> events) {
        if (d.phase != Phase.WORD_SELECT || !human(d, seat) || a.round() != d.round || now >= deadline(d)) {
            throw reject(RejectionReason.NOT_YOUR_PHASE);
        }
        Seat s = d.seats.get(seat);
        if (s.chosen() >= 0) {
            throw reject(RejectionReason.ALREADY_CHOSEN);
        }
        if (!s.available(a.index())) {
            throw reject(RejectionReason.BAD_CHOICE);
        }
        d.seats.set(seat, s.chose(a.index()));
        events.add(new WordTempEvent.WordSelected(seat));
        if (allHumansChose(d)) {
            finishSelect(d, now, events);
        }
    }

    private void submitHint(Draft d, int seat, WordTempAction.SubmitHint a, long now, boolean liveAvailable,
                            List<WordTempEvent> events) {
        requireSetter(d, seat, a.turnNo(), a.attempt(), now);
        WordTempState s = d.peek();
        if (s.pendingHint() != null) {
            throw reject(RejectionReason.SCORING_IN_PROGRESS);
        }
        if (s.hintCount() >= tuning.maxHints()) {
            throw reject(RejectionReason.HINT_LIMIT);
        }
        String text = WordNormalizer.normalize(a.text()).orElseThrow(() -> reject(RejectionReason.HINT_FORMAT));
        // D-320 덧붙임 — 힌트는 채점 중일 때부터 모두에게 공개되므로, 금칙어를 품은 힌트는 받지 않는다(추리 답의 가림과 같은 목록).
        if (WordTempVisibility.masked(text, config.blockedWords())) {
            throw reject(RejectionReason.HINT_BLOCKED);
        }
        HintRules.containment(d.answer, text).ifPresent(reason -> {
            throw reject(reason);
        });
        if (d.hints.stream().anyMatch(h -> h.status() != HintStatus.VOID && h.text().equals(text))) {
            throw reject(RejectionReason.HINT_DUPLICATE);
        }
        OptionalInt temp = table.lookup(d.answer.word(), text);
        if (temp.isPresent()) {
            int t = clamp(temp.getAsInt());
            d.hints.add(new Hint(text, d.attempt, HintStatus.SCORED, t, HintSource.TABLE, 0L));
            int count = d.peek().hintCount();
            events.add(new WordTempEvent.HintScored(seat, text, t, count));
            events.add(new WordTempEvent.HintCount(count, tuning.maxHints()));
            // D-320 — 기준 온도를 넘긴 힌트가 나오면 그 자리에서 추리로("통과"). 10개를 다 써도 추리로 간다.
            if (t >= tuning.floor(d.attempt) || count >= tuning.maxHints()) {
                closeHint(d, now, events);
            }
            return;
        }
        if (!liveAvailable || d.liveRequests >= config.liveCap()) {
            throw reject(RejectionReason.UNKNOWN_WORD);
        }
        d.hints.add(new Hint(text, d.attempt, HintStatus.PENDING, -1, HintSource.LIVE, now));
        d.liveRequests++;
        events.add(new WordTempEvent.HintPending(seat, text));
        events.add(new WordTempEvent.HintCount(d.peek().hintCount(), tuning.maxHints()));
    }

    private void endHints(Draft d, int seat, WordTempAction.EndHints a, long now, List<WordTempEvent> events) {
        requireSetter(d, seat, a.turnNo(), a.attempt(), now);
        WordTempState s = d.peek();
        if (s.pendingHint() != null) {
            throw reject(RejectionReason.SCORING_IN_PROGRESS);
        }
        if (s.hintCount() == 0) {
            throw reject(RejectionReason.NO_HINTS_YET);
        }
        closeHint(d, now, events);
    }

    private void requireSetter(Draft d, int seat, int turnNo, int attempt, long now) {
        if (d.phase != Phase.HINT || seat != d.setterSeat || !human(d, seat) || turnNo != d.turnNo
                || attempt != d.attempt || now >= deadline(d)) {
            throw reject(RejectionReason.NOT_YOUR_PHASE);
        }
    }

    private void submitGuess(Draft d, int seat, WordTempAction.SubmitGuess a, long now, List<WordTempEvent> events) {
        if (d.phase != Phase.GUESS || seat == d.setterSeat || !human(d, seat) || a.turnNo() != d.turnNo
                || a.attempt() != d.attempt || now >= deadline(d)) {
            throw reject(RejectionReason.NOT_YOUR_PHASE);
        }
        if (d.peek().guessOf(seat) != null) {
            throw reject(RejectionReason.ALREADY_GUESSED);
        }
        String text = WordNormalizer.blank(a.text()) ? ""
                : WordNormalizer.normalize(a.text()).orElseThrow(() -> reject(RejectionReason.GUESS_FORMAT));
        d.guesses.add(new Guess(seat, text));
        events.add(new WordTempEvent.GuessAccepted(seat, text));
        events.add(new WordTempEvent.GuessSubmitted(seat));
        settleGuesses(d, now, events);
    }

    // ---- "온도가 이상해요" (D-323) ----

    /** 좌석 하나가 한 판에 보낼 수 있는 온도 신고 횟수. */
    public static final int MAX_FLAGS_PER_SEAT = 10;

    /**
     * 공개된 힌트의 온도가 이상하다는 신고. 판의 진행·점수에는 아무 영향이 없다 — 좌석마다 횟수와 "이미 신고한 힌트"만 상태에 적는다.
     * 받아들이면 낸 사람에게만 {@code FLAG_ACCEPTED}; 저장(낱말만)은 어댑터가 한다.
     */
    private void flagTemp(Draft d, int seat, WordTempAction.FlagTemp a, List<WordTempEvent> events) {
        if (!human(d, seat)) {
            throw reject(RejectionReason.NOT_YOUR_PHASE);
        }
        String text = WordNormalizer.normalize(a.text()).orElseThrow(() -> reject(RejectionReason.FLAG_UNKNOWN_HINT));
        Hint hint = d.hints.stream().filter(h -> h.status() == HintStatus.SCORED && h.text().equals(text)).findFirst()
                .orElseThrow(() -> reject(RejectionReason.FLAG_UNKNOWN_HINT));
        String prefix = seat + ":";
        String key = prefix + d.turnNo + ":" + text;
        if (d.flags.contains(key)) {
            throw reject(RejectionReason.FLAG_DUPLICATE);
        }
        long used = d.flags.stream().filter(f -> f.startsWith(prefix)).count();
        if (used >= MAX_FLAGS_PER_SEAT) {
            throw reject(RejectionReason.FLAG_LIMIT);
        }
        d.flags.add(key);
        events.add(new WordTempEvent.FlagAccepted(seat, text, hint.temp(), a.direction(),
                (int) (MAX_FLAGS_PER_SEAT - used - 1)));
    }

    // ---- 실시간 채점 결과 (§6.2) ----

    /**
     * 채점 중인 힌트 {@code hint} 의 결과를 반영한다. 그 힌트가 지금 채점 중이 아니면(이미 무효·차례가 끝남·다른 힌트) 아무것도 바꾸지 않는다 —
     * 뒤늦은 결과는 캐시에만 남는다(설계서 §4.4).
     */
    public Result applyScore(WordTempState state, String hint, LiveScore score, long now) {
        Hint pending = state.pendingHint();
        if (state.ended() || state.phase() != Phase.HINT || pending == null || !pending.text().equals(hint)) {
            return new Result(state, List.of());
        }
        Draft d = state.draft();
        List<WordTempEvent> events = new ArrayList<>();
        resolvePending(d, score, now, events);
        return new Result(d.build(), events);
    }

    private void resolvePending(Draft d, LiveScore score, long now, List<WordTempEvent> events) {
        int index = -1;
        for (int i = 0; i < d.hints.size(); i++) {
            if (d.hints.get(i).status() == HintStatus.PENDING) {
                index = i;
            }
        }
        Hint pending = d.hints.get(index);
        boolean closing = now >= deadline(d); // 힌트 제한이 끝난 뒤 결과만 기다리던 중(§6.2)
        if (score instanceof LiveScore.Scored scored) {
            int t = clamp(scored.temp());
            d.hints.set(index, pending.scored(t));
            int count = d.peek().hintCount();
            events.add(new WordTempEvent.HintScored(d.setterSeat, pending.text(), t, count));
            if (closing || t >= tuning.floor(d.attempt) || count >= tuning.maxHints()) {
                closeHint(d, now, events);
            }
        } else {
            d.hints.set(index, pending.voided());
            int count = d.peek().hintCount();
            VoidReason reason = score instanceof LiveScore.NotANoun ? VoidReason.NOT_A_NOUN
                    : VoidReason.SCORING_FAILED;
            events.add(new WordTempEvent.HintVoid(d.setterSeat, pending.text(), reason, count));
            events.add(new WordTempEvent.HintCount(count, tuning.maxHints()));
            if (closing) {
                closeHint(d, now, events);
            }
        }
    }

    // ---- 시간 (§9, 포트 ⑦) ----

    /** 지금 단계의 마감 시각. */
    public long deadline(WordTempState state) {
        return state.phaseStartedAt() + config.phaseMs(state.phase());
    }

    private long deadline(Draft d) {
        return d.phaseStartedAt + config.phaseMs(d.phase);
    }

    /**
     * 다음에 시간이 일으킬 일까지 남은 ms(0 이하면 지금 {@link #onTimer}). 끝난 판은 빈 값.
     * 채점 중인 힌트가 있으면 단계 마감 대신 <b>기다림 상한</b>이 끝을 보장한다(힌트 제한이 끝나도 결과를 최대 8초 기다린다).
     */
    public OptionalLong timer(WordTempState state, long now) {
        if (state.ended()) {
            return OptionalLong.empty();
        }
        Hint pending = state.pendingHint();
        long next = pending != null ? pending.requestedAt() + tuning.liveWaitMs() : deadline(state);
        for (BotMove move : state.botPlan()) {
            next = Math.min(next, move.at());
        }
        return OptionalLong.of(Math.max(0L, next - now));
    }

    /** 만기된 것 하나를 처리한다 — 채점 기다림 상한 → 봇 행동 → 단계 마감. 만기된 것이 없으면 무변경. */
    public Result onTimer(WordTempState state, long now) {
        if (state.ended()) {
            return new Result(state, List.of());
        }
        Draft d = state.draft();
        List<WordTempEvent> events = new ArrayList<>();
        Hint pending = state.pendingHint();
        BotMove due = dueMove(state, now);
        if (pending != null && now >= pending.requestedAt() + tuning.liveWaitMs()) {
            resolvePending(d, new LiveScore.Failed(), now, events);
        } else if (due != null) {
            d.botPlan.remove(due);
            if (due.kind() == BotMoveKind.HINT) {
                botHint(d, now, events);
            } else {
                botGuess(d, due.seat(), events);
                settleGuesses(d, now, events);
            }
        } else if (pending == null && now >= deadline(state)) {
            onDeadline(d, now, events);
        } else {
            return new Result(state, List.of());
        }
        return new Result(d.build(), events);
    }

    private static BotMove dueMove(WordTempState state, long now) {
        BotMove due = null;
        for (BotMove move : state.botPlan()) {
            if (move.at() <= now && (due == null || move.at() < due.at()
                    || (move.at() == due.at() && move.seat() < due.seat()))) {
                due = move;
            }
        }
        return due;
    }

    /** 단계 마감 전이 — 엔진 타이머와 {@code TICK} 이 같이 쓴다. */
    private void onDeadline(Draft d, long now, List<WordTempEvent> events) {
        switch (d.phase) {
            case WORD_SELECT -> finishSelect(d, now, events);
            case HINT -> closeHint(d, now, events);
            case GUESS -> {
                // 안 낸 사람은 패스(§3.3). 아직 안 낸 봇은 지금 낸다.
                for (int seat : d.peek().guessers()) {
                    if (config.bot(seat) && d.peek().guessOf(seat) == null) {
                        botGuess(d, seat, events);
                    }
                }
                revealGuesses(d, now, events);
            }
            case ATTEMPT_RESULT -> {
                if (d.peek().solvedThisAttempt()) {
                    endTurn(d, TurnEndReason.SOLVED, now, events);
                } else if (d.attempt >= tuning.maxAttempts()) {
                    endTurn(d, TurnEndReason.ALL_ATTEMPTS, now, events);
                } else {
                    d.attempt++;
                    startHint(d, now, events);
                }
            }
            case TURN_END -> startNextTurn(d, d.order.indexOf(d.setterSeat) + 1, now, events);
            case FINISHED -> {
            }
        }
    }

    // ---- 낱말 고르기 → 출제 차례 ----

    private boolean allHumansChose(Draft d) {
        for (int seat = 0; seat < d.seats.size(); seat++) {
            if (human(d, seat) && d.seats.get(seat).chosen() < 0) {
                return false;
            }
        }
        return true;
    }

    /** 낱말 고르기 끝 — 안 고른 좌석(봇 포함)은 남은 후보에서 무작위(§3.1). 그리고 첫 출제 차례. */
    private void finishSelect(Draft d, long now, List<WordTempEvent> events) {
        for (int seat = 0; seat < d.seats.size(); seat++) {
            Seat s = d.seats.get(seat);
            if (!s.alive() || s.chosen() >= 0) {
                continue;
            }
            List<Integer> open = new ArrayList<>();
            for (int i = 0; i < s.candidates().size(); i++) {
                if (s.available(i)) {
                    open.add(i);
                }
            }
            int pick = open.get(rng(d, seat, 0x51).nextInt(open.size()));
            d.seats.set(seat, s.chose(pick));
            events.add(new WordTempEvent.WordSelected(seat));
        }
        startNextTurn(d, 0, now, events);
    }

    /** 출제 순서의 {@code from} 번째부터 살아 있는 다음 출제자. 없으면 라운드 끝. */
    private void startNextTurn(Draft d, int from, long now, List<WordTempEvent> events) {
        for (int p = from; p < d.order.size(); p++) {
            int seat = d.order.get(p);
            if (d.seats.get(seat).alive()) {
                startTurn(d, seat, now, events);
                return;
            }
        }
        endRound(d, now, events);
    }

    private void startTurn(Draft d, int seat, long now, List<WordTempEvent> events) {
        Seat s = d.seats.get(seat);
        d.turnNo++;
        d.setterSeat = seat;
        d.attempt = 1;
        d.answer = s.candidates().get(s.chosen());
        d.hints.clear();
        d.revealed.clear();
        d.attempts.clear();
        events.add(new WordTempEvent.TurnStarted(d.turnNo, d.round, seat));
        startHint(d, now, events);
    }

    private void endRound(Draft d, long now, List<WordTempEvent> events) {
        if (d.round >= config.rounds()) {
            finish(d, EndReason.FINISHED, now, events);
            return;
        }
        d.round++;
        d.seats.replaceAll(Seat::nextRound);
        d.setterSeat = -1;
        d.attempt = 0;
        d.answer = null;
        d.hints.clear();
        d.revealed.clear();
        d.guesses.clear();
        d.attempts.clear();
        d.botPlan.clear();
        d.phase = Phase.WORD_SELECT;
        d.phaseStartedAt = now;
        announceSelect(d.peek(), events);
    }

    // ---- 힌트 단계 (§3.2) ----

    private void startHint(Draft d, long now, List<WordTempEvent> events) {
        d.phase = Phase.HINT;
        d.phaseStartedAt = now;
        d.guesses.clear();
        d.botPlan.clear();
        events.add(new WordTempEvent.HintPhaseStarted(d.turnNo, d.attempt, config.hintMs()));
        if (config.bot(d.setterSeat)) {
            d.botPlan.add(new BotMove(d.setterSeat, now + hintDelay(d, 0), BotMoveKind.HINT));
        }
    }

    private long hintDelay(Draft d, int nth) {
        return between(rng(d, d.setterSeat, 0x100 + nth), tuning.botHintMinMs(), tuning.botHintMaxMs());
    }

    /**
     * 힌트 단계 끝 → 추리(D-320). 힌트는 낼 때마다 이미 모두에게 공개됐다 — 여기서는 이 차수가 <b>기준 온도를 넘겼는지</b>만 정해 알린다
     * (가장 높은 힌트 ≥ 기준). 못 넘겼으면(시간 초과·10개 소진·힌트 0개) 그래도 추리로 가되, 아무도 못 맞혀도 출제자는 이 차수 점수가 없다.
     */
    private void closeHint(Draft d, long now, List<WordTempEvent> events) {
        d.botPlan.clear();
        Hint best = d.peek().bestHint();
        int floor = tuning.floor(d.attempt);
        boolean floorMet = best != null && best.temp() >= floor;
        String text = best == null ? null : best.text();
        int temp = best == null ? -1 : best.temp();
        d.revealed.add(new Revealed(d.attempt, text, temp, floor, floorMet));
        events.add(new WordTempEvent.HintRevealed(d.attempt, text, temp, floor, floorMet));
        startGuess(d, now, events);
    }

    /** 봇 출제자의 한 수 — 다음 힌트(표 안) 또는 [힌트 끝]. 정책이 예외·비합법 수를 내면 [힌트 끝]으로 넘어간다(§11). */
    private void botHint(Draft d, long now, List<WordTempEvent> events) {
        WordTempState s = d.peek();
        Set<String> usedEarlier = new HashSet<>();
        for (Hint h : d.hints) {
            if (h.attempt() != d.attempt) {
                usedEarlier.add(h.text());
            }
        }
        Optional<String> next;
        try {
            next = botPolicy.nextHint(new WordTempBotPolicy.SetterView(d.answer, tuning.floor(d.attempt),
                    tuning.maxHints(), usedEarlier, s.hintCount(), config.botLevel(),
                    seedOf(d, d.setterSeat, 0x200)));
        } catch (RuntimeException policyFault) {
            botFault.accept("hint seat=" + d.setterSeat + " turnNo=" + d.turnNo + " attempt=" + d.attempt, policyFault);
            next = Optional.empty();
        }
        OptionalInt temp = next.isPresent() && legalBotHint(d, next.get())
                ? table.lookup(d.answer.word(), next.get()) : OptionalInt.empty();
        if (temp.isEmpty() || s.hintCount() >= tuning.maxHints()) {
            closeHint(d, now, events);
            return;
        }
        int t = clamp(temp.getAsInt());
        d.hints.add(new Hint(next.get(), d.attempt, HintStatus.SCORED, t, HintSource.TABLE, 0L));
        int count = d.peek().hintCount();
        events.add(new WordTempEvent.HintCount(count, tuning.maxHints()));
        events.add(new WordTempEvent.HintScored(d.setterSeat, next.get(), t, count));
        if (t >= tuning.floor(d.attempt) || count >= tuning.maxHints()) {
            closeHint(d, now, events);
        } else {
            d.botPlan.add(new BotMove(d.setterSeat, now + hintDelay(d, count), BotMoveKind.HINT));
        }
    }

    private boolean legalBotHint(Draft d, String hint) {
        return WordNormalizer.normalize(hint).filter(hint::equals).isPresent()
                && HintRules.containment(d.answer, hint).isEmpty()
                && d.hints.stream().noneMatch(h -> h.status() != HintStatus.VOID && h.text().equals(hint));
    }

    // ---- 추리 단계 (§3.3·§3.4) ----

    private void startGuess(Draft d, long now, List<WordTempEvent> events) {
        d.phase = Phase.GUESS;
        d.phaseStartedAt = now;
        d.guesses.clear();
        d.botPlan.clear();
        List<Integer> guessers = d.peek().guessers();
        events.add(new WordTempEvent.GuessPhaseStarted(d.attempt, tuning.guessMs(), guessers));
        boolean humanGuesser = guessers.stream().anyMatch(s -> !config.bot(s));
        for (int seat : guessers) {
            if (config.bot(seat)) {
                SplittableRandom r = rng(d, seat, 0x300);
                long delay = humanGuesser ? between(r, tuning.botGuessMinMs(), tuning.botGuessMaxMs())
                        : between(r, Math.min(300L, tuning.botQuickMs()), tuning.botQuickMs());
                d.botPlan.add(new BotMove(seat, now + delay, BotMoveKind.GUESS));
            }
        }
    }

    /** 봇 추리자의 답 — 공개 정보(공개 힌트·온도, 공개된 틀린 답)와 표만 본다. 정책이 예외·비합법 답을 내면 패스(§11). */
    private void botGuess(Draft d, int seat, List<WordTempEvent> events) {
        // D-320 — 힌트는 전부 공개다: 이 출제 차례의 확정 힌트 모두가 단서다.
        List<WordTempBotPolicy.Clue> clues = d.hints.stream().filter(h -> h.status() == HintStatus.SCORED)
                .map(h -> new WordTempBotPolicy.Clue(h.text(), h.temp())).toList();
        Set<String> ruledOut = new LinkedHashSet<>();
        for (AttemptResult a : d.attempts) {
            for (GuessEntry g : a.guesses()) {
                if (!g.pass() && !g.masked()) {
                    ruledOut.add(g.text());
                }
            }
        }
        for (AnswerEntry own : d.seats.get(seat).candidates()) {
            ruledOut.add(own.word());
        }
        String text;
        try {
            text = botPolicy.guess(new WordTempBotPolicy.GuesserView(clues, ruledOut, config.botLevel(),
                    WordTempBotPolicy.mix(d.seed, 0x400 + seat), seedOf(d, seat, 0x500)));
        } catch (RuntimeException policyFault) {
            botFault.accept("guess seat=" + seat + " turnNo=" + d.turnNo + " attempt=" + d.attempt, policyFault);
            text = "";
        }
        if (text == null || (!text.isEmpty() && WordNormalizer.normalize(text).filter(text::equals).isEmpty())) {
            text = "";
        }
        d.botPlan.removeIf(m -> m.seat() == seat);
        d.guesses.add(new Guess(seat, text));
        events.add(new WordTempEvent.GuessSubmitted(seat));
    }

    /** 살아 있는 사람 추리자가 모두 냈으면 봇도 그 자리에서 내고 공개한다(사람을 기다리게 하지 않는다 — §3.3·§11). */
    private void settleGuesses(Draft d, long now, List<WordTempEvent> events) {
        if (d.phase != Phase.GUESS) {
            return;
        }
        List<Integer> guessers = d.peek().guessers();
        for (int seat : guessers) {
            if (!config.bot(seat) && d.peek().guessOf(seat) == null) {
                return;
            }
        }
        for (int seat : guessers) {
            if (config.bot(seat) && d.peek().guessOf(seat) == null) {
                botGuess(d, seat, events);
            }
        }
        revealGuesses(d, now, events);
    }

    /** 동시 공개 + 차수 점수(§3.4). */
    private void revealGuesses(Draft d, long now, List<WordTempEvent> events) {
        WordTempState s = d.peek();
        int r = d.round;
        List<GuessEntry> entries = new ArrayList<>();
        boolean solved = false;
        for (int seat : s.guessers()) {
            Guess g = s.guessOf(seat);
            String text = g == null ? "" : g.text();
            boolean correct = !text.isEmpty() && AnswerJudge.correct(d.answer, text);
            boolean masked = !correct && WordTempVisibility.masked(text, config.blockedWords());
            entries.add(new GuessEntry(seat, text, correct, masked));
            solved |= correct;
        }
        Revealed shown = d.revealed.get(d.revealed.size() - 1);
        int setterGain = !solved && shown.floorMet() ? d.attempt * r : 0;
        int[] delta = new int[d.seats.size()];
        for (GuessEntry e : entries) {
            if (e.correct()) {
                delta[e.seat()] = r;
                d.seats.set(e.seat(), d.seats.get(e.seat()).plus(0, r));
            }
        }
        if (setterGain > 0) {
            delta[d.setterSeat] = setterGain;
            d.seats.set(d.setterSeat, d.seats.get(d.setterSeat).plus(setterGain, 0));
        }
        AttemptResult result = new AttemptResult(d.attempt, entries, setterGain);
        d.attempts.add(result);
        d.guesses.clear();
        d.botPlan.clear();
        d.phase = Phase.ATTEMPT_RESULT;
        d.phaseStartedAt = now;
        List<ScoreChange> scores = new ArrayList<>(d.seats.size());
        for (int seat = 0; seat < d.seats.size(); seat++) {
            scores.add(new ScoreChange(seat, delta[seat], d.seats.get(seat).total()));
        }
        events.add(new WordTempEvent.GuessesRevealed(d.attempt, WordTempViews.revealedGuesses(result), d.setterSeat,
                setterGain, scores, tuning.resultMs()));
    }

    // ---- 출제 차례 끝·판 끝 (§3·§8) ----

    /** 출제 차례를 끝낸다 — 정답과 그 차례의 확정 힌트 전체를 공개한다. 채점 중이던 힌트는 버린다. */
    private void endTurn(Draft d, TurnEndReason reason, long now, List<WordTempEvent> events) {
        d.hints.replaceAll(h -> h.status() == HintStatus.PENDING ? h.voided() : h);
        d.guesses.clear();
        d.botPlan.clear();
        List<Integer> solvers = new ArrayList<>();
        int gain = 0;
        for (AttemptResult a : d.attempts) {
            solvers.addAll(a.solvers());
            gain += a.setterGain();
        }
        d.history.add(new TurnSummary(d.turnNo, d.round, d.setterSeat, d.answer.word(), d.revealed, solvers, gain,
                reason));
        d.phase = Phase.TURN_END;
        d.phaseStartedAt = now;
        WordTempState s = d.peek();
        events.add(new WordTempEvent.TurnEnded(d.turnNo, d.answer.word(), WordTempViews.hintInfos(s), reason,
                s.totals(), tuning.turnEndMs()));
    }

    private void finish(Draft d, EndReason reason, long now, List<WordTempEvent> events) {
        d.hints.replaceAll(h -> h.status() == HintStatus.PENDING ? h.voided() : h);
        d.guesses.clear();
        d.botPlan.clear();
        d.phase = Phase.FINISHED;
        d.phaseStartedAt = now;
        d.result = MatchRanking.result(d.seats, reason);
        events.add(new WordTempEvent.MatchEnded(d.result));
    }

    // ---- 탈주·강퇴 (§10) ----

    public Desertion desert(WordTempState state, int seat, long now) {
        if (state.ended() || !state.alive(seat)) {
            return new Desertion(state, Desertion.Outcome.NOT_APPLICABLE, List.of());
        }
        Draft d = state.draft();
        List<WordTempEvent> events = new ArrayList<>();
        d.seats.set(seat, d.seats.get(seat).out());
        d.botPlan.removeIf(m -> m.seat() == seat);
        events.add(new WordTempEvent.SeatOut(seat));
        List<Integer> alive = d.peek().aliveSeats();
        boolean setterLeft = state.turnInProgress() && seat == state.setterSeat();
        if (setterLeft) {
            // 출제 중 — 그 차례를 그 자리에서 끝낸다(정답 공개). 진행 중이던 차수의 점수는 없고 앞 차수 점수는 유지.
            // 이 탈주로 판까지 끝나더라도 정답은 먼저 공개한다(D-315).
            endTurn(d, TurnEndReason.SETTER_OUT, now, events);
        }
        if (alive.size() <= 1) {
            finish(d, EndReason.LAST_SEAT, now, events);
        } else if (alive.stream().allMatch(config::bot)) {
            finish(d, EndReason.NO_HUMANS, now, events);
        } else if (setterLeft) {
            // 위에서 끝냈다
        } else if (state.phase() == Phase.GUESS) {
            d.guesses.removeIf(g -> g.seat() == seat); // 패스로 친다
            settleGuesses(d, now, events);
        } else if (state.phase() == Phase.WORD_SELECT && allHumansChose(d)) {
            finishSelect(d, now, events);
        }
        WordTempState next = d.build();
        return new Desertion(next, next.ended() ? Desertion.Outcome.MATCH_ENDED : Desertion.Outcome.MATCH_CONTINUES,
                events);
    }

    // ---- 포트 ④ ----

    /** 지금 단계에서 아직 할 일이 남은 살아 있는 <b>사람</b> 좌석. 봇은 엔진 타이머가 움직이므로 넣지 않는다. */
    public List<Integer> pendingSeats(WordTempState state) {
        return switch (state.phase()) {
            case WORD_SELECT -> state.aliveSeats().stream()
                    .filter(s -> !config.bot(s) && state.seat(s).chosen() < 0).toList();
            case HINT -> config.bot(state.setterSeat()) ? List.of() : List.of(state.setterSeat());
            case GUESS -> state.guessers().stream()
                    .filter(s -> !config.bot(s) && state.guessOf(s) == null).toList();
            case ATTEMPT_RESULT, TURN_END, FINISHED -> List.of();
        };
    }

    // ---- 도우미 ----

    private boolean human(Draft d, int seat) {
        return seat >= 0 && seat < d.seats.size() && d.seats.get(seat).alive() && !config.bot(seat);
    }

    private static long seedOf(Draft d, int seat, int salt) {
        return WordTempBotPolicy.mix(WordTempBotPolicy.mix(WordTempBotPolicy.mix(d.seed, d.round * 1_000L + d.turnNo),
                d.attempt * 64L + seat), salt);
    }

    private static SplittableRandom rng(Draft d, int seat, int salt) {
        return new SplittableRandom(seedOf(d, seat, salt));
    }

    private static long between(SplittableRandom rng, long min, long max) {
        return max <= min ? min : min + rng.nextLong(max - min + 1);
    }

    private static int clamp(int temp) {
        return Math.max(0, Math.min(100, temp));
    }

    private static WordTempActionRejectedException reject(RejectionReason reason) {
        return new WordTempActionRejectedException(reason);
    }
}
