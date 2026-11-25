package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.scoring.LiveHintScorer;
import com.mirboard.domain.game.wordtemp.words.WordTempWords;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 낱말 온도의 카탈로그 메타데이터 + 엔진 팩토리(S2, D-310·D-316). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정 없이
 * 이 Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.wordtemp.status} = 환경 변수 {@code MIRBOARD_WORD_TEMP_STATUS}, 기본
 * {@code DISABLED} — S4 공개 전까지 카탈로그에도 없고 새 방을 만들 수 없다). 공개는 이 기본값과 {@code application.yml} 의 기본값을 같이
 * {@code AVAILABLE} 로 바꾼다({@code WordTempGameDefinitionTest}). 공개 뒤에는 {@code COMING_SOON}(새 방만 막음, 진행 중인 판은 끝까지)이
 * 운영의 되돌리기 손잡이다({@code docs/deploy.md}).
 *
 * <p>방 옵션: 판 길이(짧게 1 · 보통 2 · 길게 3라운드)·봇 강도 — 둘 다 기존 상수다. 턴 제한은 <b>힌트 제한</b>(출제자의 힌트 단계 시간)이고
 * 끔이 없다 — 60·90·120·180초, 기본 90.
 */
@Component
public final class WordTempGameDefinition implements GameDefinition {

    public static final String ID = "WORD_TEMP";

    private final WordTempStateStore stateStore;
    private final WordTempWords words;
    private final LiveHintScorer scorer;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;
    private final int perMatchLiveCap;
    private final double timeScale;

    public WordTempGameDefinition(WordTempStateStore stateStore,
                                  WordTempWords words,
                                  LiveHintScorer scorer,
                                  Clock clock,
                                  ApplicationEventPublisher publisher,
                                  @Value("${mirboard.wordtemp.status:DISABLED}") GameStatus status,
                                  @Value("${mirboard.wordtemp.live-scoring.per-match-cap:60}") int perMatchLiveCap,
                                  @Value("${mirboard.wordtemp.time-scale:1.0}") double timeScale) {
        this.stateStore = stateStore;
        this.words = words;
        this.scorer = scorer;
        this.clock = clock;
        this.publisher = publisher;
        this.status = Objects.requireNonNull(status,
                "mirboard.wordtemp.status(환경 변수 MIRBOARD_WORD_TEMP_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
        this.perMatchLiveCap = Math.max(0, perMatchLiveCap);
        this.timeScale = timeScale;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "낱말 온도";
    }

    @Override
    public String shortDescription() {
        return "3~6인 낱말 맞히기. 출제자의 힌트가 정답과 얼마나 가까운지 온도로 보고 정답 낱말을 맞힌다.";
    }

    @Override
    public int minPlayers() {
        return WordTempConfig.MIN_SEATS;
    }

    @Override
    public int maxPlayers() {
        return WordTempConfig.MAX_SEATS;
    }

    @Override
    public int defaultPlayers() {
        return 4;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    /** 힌트 제한 기본 90초(룰 §2). */
    @Override
    public int defaultTurnSeconds() {
        return WordTempConfig.DEFAULT_HINT_SECONDS;
    }

    /** 힌트 제한: 60·90·120·180초 — 끔 없음. */
    @Override
    public List<Integer> turnSecondsChoices() {
        return List.of(60, 90, 120, 180);
    }

    /** D-286 — 시간은 한 사람의 차례가 아니라 출제자의 힌트 단계에 걸린다. */
    @Override
    public String turnLimitLabel() {
        return "힌트 제한";
    }

    @Override
    public Set<RoomOption> supportedRoomOptions() {
        return EnumSet.of(RoomOption.MATCH_LENGTH, RoomOption.BOT_LEVEL);
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new WordTempGameEngine(ctx, config(ctx), words.table(), words.table().version(), scorer, stateStore,
                clock, publisher);
    }

    /** 이 방의 판 설정 — 어댑터와 라운드 스타터가 같이 쓴다. */
    public WordTempConfig config(GameContext ctx) {
        return WordTempSettings.config(ctx, perMatchLiveCap, words.blockedWords(), timeScale);
    }

    public double timeScale() {
        return timeScale;
    }

    public int perMatchLiveCap() {
        return perMatchLiveCap;
    }
}
