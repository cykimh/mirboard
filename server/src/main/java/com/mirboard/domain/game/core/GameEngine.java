package com.mirboard.domain.game.core;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * 인게임 진행을 위해 인프라가 게임에게 요구하는 전부 (D-97 설계 → D-98 구현). 방 하나에
 * 대응하는 <b>per-room</b> 인스턴스이며 {@link GameDefinition#newEngine(GameContext)} 로만
 * 만들어진다 — 인프라는 게임 이름을 직접 쓰지 않는다.
 *
 * <p>표면은 티츄 구현에서 역산했다. 여섯 가지 책임이 있다: ① 액션 적용 ② 상태 직렬화
 * ③ 공개/비공개 뷰 ④ 단계 이름 ⑤ 라운드·매치 진행 ⑥ 봇·타임아웃용 합법 액션. 여기에 기본값이 있는
 * 선택형 확장이 하나 붙었다: ⑦ 엔진 타이머(D-128 — 시간이 지나면 저절로 일어나는 전이, 구현하지 않으면 없음).
 * 자세한 근거와 "요트가 깨는 지점"은 {@code docs/game-port.md}.
 *
 * <p><b>포트에 없는 것</b>(의도적): 팀, 칩/판돈, ELO, 좌석 수 4 고정, 트릭·리드수트.
 * 넣으면 모든 게임이 티츄 모양을 강요당한다 (`docs/game-port.md` §2).
 *
 * <p>구현은 순수 룰 엔진을 감싸는 어댑터를 권장한다 — 룰 단위 테스트가 Redis 를 끌고
 * 오지 않도록 (티츄: 순수 {@code TichuEngine} + 어댑터 {@code TichuGameEngine}).
 */
public interface GameEngine {

    /** 이 엔진이 담당하는 방 컨텍스트. */
    GameContext context();

    // ---------- ② 상태 직렬화 ----------

    /** 현재 라운드 상태. 게임이 아직 시작되지 않았으면 empty. */
    Optional<GameState> loadState();

    void saveState(GameState state);

    // ---------- ① 액션 적용 ----------

    /**
     * 클라 JSON 을 역직렬화할 이 게임의 액션 타입. 목적지가 게임별로 갈리지 않으므로
     * (`/app/room/{roomId}/action` 하나) 컨트롤러가 방 → gameType → 본 타입으로 분기한다.
     */
    Class<? extends GameAction> actionType();

    /** 액션 적용. 룰 위반은 {@link GameActionRejectedException}. */
    Result apply(GameState state, int seat, GameAction action);

    // ---------- ④ 단계 이름 / 진행 질의 ----------

    /** 클라 분기용 단계 이름 (티츄: DEALING/PASSING/PLAYING/ROUND_END). */
    String phaseName(GameState state);

    /**
     * 지금 행동을 기다리는 좌석들 — 오름차순, 비면 대기 없음.
     *
     * <p>D-98: 복수인 이유는 티츄의 Dealing/Passing 이 <b>동시 대기</b>라서다. 단수로
     * 두면 좌석 3 의 봇이 좌석 1 사람의 선언을 기다리며 멈춘다.
     */
    List<Integer> pendingSeats(GameState state);

    /** 타임아웃 타이머가 겨눌 좌석 (대기 중 첫 좌석). 없으면 -1. */
    default int pendingSeat(GameState state) {
        List<Integer> seats = pendingSeats(state);
        return seats.isEmpty() ? -1 : seats.get(0);
    }

    /** 라운드가 끝난 상태인가 — 더 이상 액션을 받지 않는다. */
    boolean isRoundOver(GameState state);

    /**
     * 매치가 끝났는가. 종료 조건은 게임마다 다르다 (티츄=목표점수, 스컬킹=10라운드,
     * 요트=12칸) — 인프라가 알면 안 된다 (D-97 판단 3).
     *
     * <p>인자가 없는 것은 의도적: 매치 누적 상태(티츄는 팀 점수 + MVP 기여도)를 게임
     * 중립 타입으로 손실 없이 표현할 수 없어 <b>엔진이 소유</b>한다.
     */
    boolean isMatchOver();

    // ---------- ③ 뷰 ----------

    /** 공개 뷰 — 모든 참가자·관전자가 본다. 손패 카드는 절대 포함하지 않는다. */
    Object publicView(GameState state);

    /** 본인 전용 뷰. 비공개 상태가 없는 게임(요트: 점수판이 공개)은 empty. */
    Optional<Object> privateView(GameState state, int seat);

    // ---------- ⑥ 봇 / 타임아웃 ----------

    /** 해당 좌석이 지금 취할 수 있는 액션들. 비어 있으면 할 수 있는 게 없다. */
    List<GameAction> legalActions(GameState state, int seat);

    /**
     * 봇이 고를 액션. 기본은 합법 액션 균등 분포 — 게임별 휴리스틱이 있으면 override.
     *
     * @return null 이면 그 좌석은 지금 행동할 게 없다.
     */
    default GameAction botAction(GameState state, int seat, Random random) {
        List<GameAction> legal = legalActions(state, seat);
        return legal.isEmpty() ? null : legal.get(random.nextInt(legal.size()));
    }

    /** 턴 제한 초과 시 적용할 안전 액션 (결정적). null 이면 아무것도 안 한다. */
    GameAction timeoutAction(GameState state, int seat);

    // ---------- ⑦ 엔진 타이머 (D-128) ----------

    /**
     * D-128 — 이 상태에 <b>시간이 지나면 저절로 일어나는 전이</b>가 있으면 지금부터 남은 시간.
     * 기본은 없음(티츄·스컬킹).
     *
     * <p>턴 제한과는 별개다 — 방의 턴 제한이 꺼져 있어도 걸린다. 인프라는 진행 직후마다
     * ({@code TurnTimeoutScheduler.onTurnAdvanced}) 이 값을 물어 턴 데드라인과 같은 세대 번호로
     * 데드라인을 걸고, 만료되면 {@link #onTimer} 를 부른다. "남은 시간"을 돌려주는 것은 재무장해도
     * 처음부터 다시 세지 않게 하려는 것이다 — 게임은 시작 시각을 상태에 두고 자기 시계로 계산한다.
     *
     * <p>인프라는 <b>발화 때도</b> 락 안에서 이 값을 다시 묻는다 — 비어 있으면 낡은 발화로 보고 아무것도
     * 하지 않고, 0 보다 크면 그 시간 뒤로 다시 걸며, 0 이하(이미 지남 포함)일 때만 {@link #onTimer} 를
     * 부른다. 그러니 같은 상태에서 시계가 흐르면 이 값은 줄어 결국 0 이하가 돼야 하고, 0 이하라고 답한
     * 상태에서는 {@code onTimer} 가 전이를 돌려줘야 한다(비어 있으면 인프라는 WARN 만 남기고 타이머를 버린다).
     */
    default Optional<Duration> timer(GameState state) {
        return Optional.empty();
    }

    /**
     * D-128 — {@link #timer} 가 만료됐을 때의 전이. 비어 있으면 아무 일도 없다.
     *
     * <p>인프라는 방 액션 락을 쥔 채 {@code timer(state)} 를 다시 물어 <b>0 이하일 때만</b> 부른다. 세대 번호는
     * 대부분의 낡은 발화를 거르지만, 진행 경로가 락을 푼 <em>뒤에</em> 세대를 올리므로 락 해제와 세대 상승 사이
     * 틈이 있어 그 틈에 만기된 옛 타이머는 세대 검사를 통과할 수 있다 — 이 재확인이 마지막 방어선이다. 그래서
     * 불린 시점의 상태는 만기이고, 구현이 시각을 다시 볼 필요는 없다. 시스템 전이를 {@link GameAction} 으로 두지
     * 않은 것은 의도적이다: 액션은 클라 JSON 에서 역직렬화되므로 그 계층에 두면 클라가 위조해 보낼 수 있다.
     */
    default Optional<Result> onTimer(GameState state) {
        return Optional.empty();
    }

    // ---------- ⑤ 라운드 · 매치 진행 ----------

    /**
     * 액션 적용 직후 호출. 라운드가 끝났으면 점수를 누적하고 <b>매치 종료</b> 또는
     * <b>다음 라운드 시작</b>으로 분기하며, 그때 발행할 이벤트를 {@code outbound} 에
     * in-place append 한다 (호출자가 한 번에 브로드캐스트하도록).
     *
     * <p>호출자는 방 액션 락을 이미 보유하고 있어야 한다.
     */
    Advance advance(GameState newState, List<GameEvent> outbound);

    /**
     * 게임중 탈주 처리 (D-75). 발행할 이벤트를 {@code outbound} 에 append.
     *
     * <p>"한 명이 빠지면 어떻게 되는가"는 게임 규칙이다 — 티츄는 2:2 라 상대팀 승리로
     * 매치를 끝내고({@link DesertOutcome#MATCH_ENDED}), 스컬킹 같은 개인전은 남은
     * 사람끼리 계속한다({@link DesertOutcome#MATCH_CONTINUES}, D-104). 이미 끝난 매치 등
     * 해당 없으면 {@link DesertOutcome#NOT_APPLICABLE}.
     *
     * <p>D-102: 원래 boolean("매치를 강제 종료했는가")이었으나 티츄 전제가 계약에 박혀
     * 인프라가 무조건 방을 FINISHED 로 만들었다 — "계속"을 표현할 수 있게 3치로 바꿨다.
     */
    DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound);

    /** {@link #desert} 결과 — 인프라는 이걸로 방 상태(유지/종료)만 결정한다. */
    enum DesertOutcome {
        /** 탈주로 보지 않음 (이미 끝난 매치 등) — 상태 무변경. */
        NOT_APPLICABLE,
        /** 매치 계속 — 방을 IN_GAME 으로 유지하고 봇/타임아웃을 재무장한다. */
        MATCH_CONTINUES,
        /** 매치 종료 — 방을 FINISHED 로 전이한다. */
        MATCH_ENDED
    }

    /** 액션 적용 결과 — 새 상태 + 발행할 이벤트. */
    record Result(GameState newState, List<GameEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    /** {@link #advance} 결과. 인프라는 이걸로 메트릭/방 상태만 처리한다. */
    record Advance(boolean roundCompleted, boolean matchCompleted) {

        /** 라운드가 안 끝났음 — 아무 진행 없음. */
        public static final Advance NONE = new Advance(false, false);
    }
}
