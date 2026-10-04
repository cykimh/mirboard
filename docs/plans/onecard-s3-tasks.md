# 원카드 S3 구현 계획 — 포트 엔진 타이머 + 서버 배선 (D-128)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원카드를 서버에서 끝까지 돌게 한다 — 포트에 엔진 타이머를 더하고, S2 순수 엔진(D-127)에 어댑터·게임 정의·
라운드 시작·휴리스틱 봇·매치 기록(V12)을 붙여 경쟁 창과 봇 풀매치가 실제 서버 경로로 통과하게 한다. 클라 게임판(S4)
전까지 카탈로그 상태는 COMING_SOON 이다.

**Architecture:** 포트(`GameEngine`)에 선택형 기본 메서드 `timer`/`onTimer` 를 더하고, 기존 `TurnTimeoutScheduler.
onTurnAdvanced` 가 턴 데드라인과 같은 세대 번호로 `deadlines:game` 을 걸며 새 `EngineTimerScheduler` 가 같은 가드로
발화한다(호출 지점 변경 0). 원카드 쪽은 스컬킹과 같은 2계층 — 순수 엔진을 `OneCardGameEngine` 어댑터가 감싸 시계·
Redis 저장·뷰·기록 발행을 붙인다. 인프라 코드에는 게임 이름이 들어가지 않는다.

**Tech Stack:** Java 25, Spring Boot 4(빈·`@Value`·`@EventListener`), Redis(`StringRedisTemplate`), PostgreSQL + Flyway,
JUnit 5 + AssertJ + Mockito, Testcontainers(PostgreSQL 16·Redis 7), Awaitility.

**참고:** 설계 `docs/plans/onecard.md` §4.4~§4.10·§6 S3 행 · 룰 정본 `docs/rules-onecard.md`(D-125) · S2 계획
`docs/plans/onecard-s2-tasks.md` · 포트 계약 `docs/game-port.md`. 이 계획의 코드는 검증용 스파이크에서 옮겼고, 스파이크에서
서버 전체(1214건, 실패 0)와 아래 각 단계의 실패·통과·테스트 수를 확인했다.

## Global Constraints

- 응답·주석·문서는 한국어.
- 작업 위치: 브랜치 `feat/onecard-s3`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan`
  (main `a761ae3` 기반). 메인 체크아웃(`/Users/yupchang/Developer/mirboard`)과 다른 워크트리는 건드리지 않는다.
  Bash 는 매 호출 작업 폴더가 바뀔 수 있으니 **명령마다 워크트리 절대경로로 `cd` 하거나 `git -C` 를 쓴다.**
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` — 모델이 무엇이든 이 줄 그대로.
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 컴파일)를 돈다. `--no-verify` 금지.
- **도메인 경계**: 인프라(`server/src/main/java/com/mirboard/infra`)에 게임 이름을 쓰지 않는다 —
  `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra` 가 0건이어야 한다. 인프라 변경은 포트
  확장 1건(엔진 타이머)뿐이다. `domain.game.onecard` 는 `domain.game.core`·Jackson 과, 저장·기록·시작에 필요한
  Spring 빈(스컬킹과 같은 범위)에만 기댄다.
- **사용자 결정(D-128)**: 비공개 이벤트 비순번(설계 §4.5b)은 만들지 않는다 — D-126 이 같은 확장을 넣는다. 창 끝·봇
  시각 직후에 처리된 누름은 인정한다(현행). 봇은 휴리스틱이다.
- 결정 번호: **D-128**. `docs/decisions.md` 의 `## D-127` 바로 위에 넣는다. 착수 시점에 D-128 도 쓰였으면 다음 빈
  번호로 바꾸고 본문의 번호를 모두 고친다.
- **Docker 가 필요한 테스트**(이름이 `IT` 로 끝남)는 OrbStack 소켓을 지정해 돌린다(`scripts/check.sh server` 가 하는
  것과 같다): `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 를 먼저 실행한 셸에서 `./gradlew ...`. 이름이 `Test` 로 끝나는 원카드 테스트는 Docker 없이
  돈다.
- 원카드 도메인 단위 테스트 수(누적, `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`): Task 2 → 140, Task 3 → 155, Task 4 → 159, Task 5 → 173.
  인프라·IT: Task 1 `EngineTimerSchedulerTest` 13건, Task 6 `OneCardMatchRecorderIT` 4건, Task 7 `OneCardRaceIT` 6건,
  Task 8 `OneCardBotMatchSimulationIT` 4건. 서버 전체는 1148 → **1214**(Docker 불필요 965 → **1017**, 84%).
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를 잡는
  문맥이다. 세 경우 모두 첫 글자 하나를 떼고 읽는다(나머지 들여쓰기는 파일 그대로). `+` 뒤가 비어 있으면 빈 줄을
  넣는다. 한 블록은 파일에서 정확히 한 곳과 맞고, 블록은 적힌 순서대로 적용한다. 안에 ``` 가 든 블록은 네 개짜리
  펜스(````)로 감쌌다 — 그 ``` 줄은 파일 내용이다.

## 파일 지도

| 파일 | 태스크 | 책임 |
| --- | --- | --- |
| `server/src/main/java/com/mirboard/domain/game/core/GameEngine.java` | 1 | 포트 — 엔진 타이머 기본 메서드 `timer`/`onTimer` |
| `server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java` | 1 | 진행마다 엔진 타이머 무장(같은 세대), 취소에 함께 포함 |
| `server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java` | 1 | `deadlines:game` 발화 — 턴 타임아웃과 같은 가드로 `onTimer` 적용 |
| `server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardStateStore.java` | 2 | Redis `room:{id}:state` 저장 |
| `server/src/main/java/com/mirboard/domain/game/onecard/state/OneCardStateMapper.java` | 2 | 공개 뷰(장수·창 남은 시간)·비공개 뷰(손패·버전) |
| `server/src/main/java/com/mirboard/domain/game/onecard/state/*.java`(상태 레코드 파일 4개 — 레코드 6종) | 2 | 모르는 필드 무시 — 롤백 안전 |
| `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java` | 3·5 | 포트 어댑터 — 시계·저장·뷰·엔진 타이머·기록 발행(3), 봇(5) |
| `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`, `event/OneCardMatchCompleted.java`, `RaceSettings.java`, `application.yml` | 3 | 카탈로그·설정(공개 상태·창·봇 반응) |
| `server/src/main/java/com/mirboard/domain/game/onecard/lifecycle/OneCardRoundStarter.java` | 4 | 게임 시작 시 분배·저장·봇/타이머 무장 |
| `server/src/main/java/com/mirboard/domain/game/onecard/bot/OneCardBotView.java`, `bot/OneCardBotPolicy.java` | 5 | 휴리스틱 봇(공개 정보 뷰만) |
| `server/src/main/resources/db/migration/V12__onecard_match.sql`, `server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardMatchRecorder.java` | 6 | 매치 기록·게임별 전적 |
| `server/src/test/java/com/mirboard/infra/bot/OneCardRaceIT.java`, `OneCardBotMatchSimulationIT.java` | 7·8 | 서버 경로 검증 |
| `docs/*`, `CLAUDE.md`, `README.md`, `scripts/check.sh` | 1·9 | 포트 계약(1), 원카드 문서·명령·수치(9) |

---
## S3 — 포트 엔진 타이머 + 서버 배선 (D-128)

### Task 1: 포트 확장 — 엔진 타이머

**Files:**
- Modify: `docs/decisions.md`(D-128), `server/src/main/java/com/mirboard/domain/game/core/GameEngine.java`, `server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java`,
  `docs/game-port.md`, `docs/redis-keys.md`(데드라인 종류)
- Create: `server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java`
- Test: `server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java`

**Interfaces:**
- Produces: `GameEngine` 기본 메서드 `Optional<Duration> timer(GameState state)`(지금부터 남은 시간, 기본 empty)·
  `Optional<Result> onTimer(GameState state)`(만료 시 전이, 기본 empty). `EngineTimerScheduler implements DeadlineHandler`
  — `public static final String KIND = "game"`, `handle(String member)`(member `{roomId}#{generation}`).
  `TurnTimeoutScheduler.onTurnAdvanced` 는 IN_GAME 방이면 턴 제한과 무관하게 `engine.timer(state)` 를 물어
  `deadlines:game` 에 같은 세대로 걸고, 세대를 올릴 때·`cancel`·방 정리 때 두 종류를 함께 지운다. 생성자 시그니처는
  그대로다(기존 테스트가 직접 만든다).

- [ ] **Step 1: D-128 결정 기록** — `docs/decisions.md`

`docs/decisions.md`:

```diff
 보류 3건: ① 스컬킹 매치 결과 영속·ELO·desert_count 는 `users.rating` 단일 컬럼(D-02)의
 게임별 분리 결정이 선행이라 별건. ② 봇은 포트 기본(합법 균등 분포)으로 시작 — 휴리스틱은
 후속. ③ 카탈로그에 노출되지만 클라 게임판은 S6(D-103) — 직후 과제.
+
+## D-128 (2026-10-05) — 원카드 S3: 포트 엔진 타이머 + 어댑터·봇·기록 (서버 완성, 클라 전 COMING_SOON)
+
+포트에 선택형 엔진 타이머를 더했다 — `GameEngine.timer(state)`(지금부터 남은 시간)·`onTimer(state)`(만료 시
+전이), 기본은 없음이라 티츄·스컬킹은 그대로다. 무장은 `TurnTimeoutScheduler.onTurnAdvanced` 가 턴 데드라인과
+같은 세대 번호로 `deadlines:game` 에 걸고(호출 지점 변경 0, 턴 제한을 꺼도 걸림) 발화는 새 `EngineTimerScheduler`
+가 턴 타임아웃과 같은 가드로 한다. 원카드는 순수 엔진(D-127)을 감싼 어댑터·정의(`mirboard.onecard.status`, 기본
+COMING_SOON — 클라 게임판 S4 전까지 방 생성 불가)·라운드 시작·휴리스틱 봇·기록기(V12)로 붙었고, 인프라 코드에는
+게임 이름이 없다. 계획 단계에서 사용자가 정한 셋: 비공개 이벤트 비순번(설계 §4.5b)은 같은 확장을 넣는 D-126 에
+맡기고 원카드는 병합 뒤 재정의만 한다(S4 착수 조건), 창 끝·봇 시각 직후에 처리된 누름도 인정한다(서버가 먼저
+처리한 누름이 이김, 룰 §9-4 그대로), 봇은 휴리스틱으로 넣는다(무작위 봇 대비 4인 승률 0.92).
 
 ## D-127 (2026-10-04) — 원카드 순수 룰 엔진 (원카드 S2, 신규 패키지)
 
```

- [ ] **Step 2: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java`:

```java
package com.mirboard.infra.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * D-128 — 엔진 타이머. 무장은 턴 진행({@link TurnTimeoutScheduler#onTurnAdvanced})이 턴 데드라인과
 * 같은 세대 번호로 하고, 발화({@link EngineTimerScheduler})는 턴 타임아웃과 같은 가드를 거쳐
 * {@link GameEngine#onTimer} 를 적용한다. 게임 중립이라 엔진은 모의 객체로 충분하다.
 */
class EngineTimerSchedulerTest {

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final MatchProgressService matchProgress = mock(MatchProgressService.class);
    private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    private final RoomGeneration generations = mock(RoomGeneration.class);
    private final BotScheduler botScheduler = mock(BotScheduler.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);
    private final GameState next = mock(GameState.class);
    private final GameEvent event = mock(GameEvent.class);

    private static Room room(RoomStatus status, int turnSeconds) {
        return new Room("r1", "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, turnSeconds, 0, Set.of());
    }

    private void engineWithState() {
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
    }

    @Nested
    class Arming {

        private final TurnTimeoutScheduler turnTimeout = new TurnTimeoutScheduler(
                roomService, engines, broadcaster, lock, matchProgress, botScheduler,
                deadlines, generations);

        @Test
        void a_declared_timer_is_armed_with_the_turn_generation_even_without_a_turn_limit() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1500)));

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).schedule(EngineTimerScheduler.KIND, "r1#4", Duration.ofMillis(1500));
            verify(deadlines, never()).schedule(eq(TurnTimeoutScheduler.KIND), anyString(), any());
        }

        @Test
        void without_a_declared_timer_only_the_turn_deadline_is_armed() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 30));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).schedule(TurnTimeoutScheduler.KIND, "r1#4", Duration.ofSeconds(30));
            verify(deadlines, never()).schedule(eq(EngineTimerScheduler.KIND), anyString(), any());
        }

        @Test
        void advancing_drops_the_previous_engine_timer_as_well() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).cancel(TurnTimeoutScheduler.KIND, "r1#3");
            verify(deadlines).cancel(EngineTimerScheduler.KIND, "r1#3");
        }

        @Test
        void a_finished_room_arms_no_engine_timer() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED, 0));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            engineWithState();
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1500)));

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines, never()).schedule(anyString(), anyString(), any());
        }

        @Test
        void cancel_drops_the_engine_timer_too() {
            when(generations.current("r1")).thenReturn(7L);
            when(generations.bump("r1")).thenReturn(8L);

            turnTimeout.cancel("r1");

            verify(deadlines).cancel(EngineTimerScheduler.KIND, "r1#7");
        }

        @Test
        void a_failing_engine_lookup_does_not_block_the_turn_deadline() {
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 30));
            when(generations.current("r1")).thenReturn(3L);
            when(generations.bump("r1")).thenReturn(4L);
            when(engines.forRoom(any())).thenThrow(new IllegalStateException("boom"));

            turnTimeout.onTurnAdvanced("r1");

            verify(deadlines).schedule(TurnTimeoutScheduler.KIND, "r1#4", Duration.ofSeconds(30));
        }
    }

    @Nested
    class Firing {

        private final TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);
        private final EngineTimerScheduler scheduler = new EngineTimerScheduler(
                roomService, engines, broadcaster, lock, matchProgress, botScheduler, turnTimeout,
                deadlines, generations);

        @Test
        void a_due_timer_applies_the_engine_transition_and_rearms() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWithState();
            when(engine.onTimer(state)).thenReturn(Optional.of(
                    new GameEngine.Result(next, List.of(event))));

            scheduler.handle("r1#5");

            InOrder order = inOrder(engine, matchProgress, broadcaster, lock, botScheduler, turnTimeout);
            order.verify(engine).saveState(next);
            order.verify(matchProgress).advance(eq(engine), any(), eq(next), eq(List.of(event)));
            order.verify(broadcaster).broadcast("r1", List.of(event), List.of(10L, 20L));
            order.verify(lock).release("r1");
            order.verify(botScheduler).scheduleBots("r1");
            order.verify(turnTimeout).onTurnAdvanced("r1");
        }

        @Test
        void a_stale_generation_touches_nothing() {
            when(generations.current("r1")).thenReturn(6L);

            scheduler.handle("r1#5");

            verify(lock, never()).tryAcquire(anyString());
            verify(engine, never()).onTimer(any());
        }

        @Test
        void a_finished_room_touches_nothing() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.FINISHED, 0));
            engineWithState();

            scheduler.handle("r1#5");

            verify(lock, never()).tryAcquire(anyString());
            verify(engine, never()).onTimer(any());
        }

        @Test
        void a_busy_room_is_retried_shortly_with_the_same_generation() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(false);

            scheduler.handle("r1#5");

            verify(deadlines).schedule(EngineTimerScheduler.KIND, "r1#5", Duration.ofMillis(200));
            verify(lock, never()).release(anyString());
            verify(engine, never()).onTimer(any());
        }

        @Test
        void a_generation_bumped_while_waiting_for_the_lock_is_dropped_inside_the_lock() {
            when(generations.current("r1")).thenReturn(5L, 6L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWithState();

            scheduler.handle("r1#5");

            verify(engine, never()).onTimer(any());
            verify(lock).release("r1");
            verify(botScheduler, never()).scheduleBots(anyString());
        }

        @Test
        void nothing_due_means_nothing_is_saved_broadcast_or_rearmed() {
            when(generations.current("r1")).thenReturn(5L);
            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
            when(lock.tryAcquire("r1")).thenReturn(true);
            engineWithState();
            when(engine.onTimer(state)).thenReturn(Optional.empty());

            scheduler.handle("r1#5");

            verify(engine, never()).saveState(any());
            verify(broadcaster, never()).broadcast(anyString(), any(), any());
            verify(lock).release("r1");
            verify(botScheduler, never()).scheduleBots(anyString());
            verify(turnTimeout, never()).onTurnAdvanced(anyString());
        }

        @Test
        void a_malformed_member_is_ignored() {
            scheduler.handle("no-generation");
            scheduler.handle("r1#not-a-number");

            verify(generations, never()).current(anyString());
        }
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`EngineTimerScheduler`, `timer`, `onTimer` 가 아직 없다).

- [ ] **Step 4: 구현 — 포트**

`server/src/main/java/com/mirboard/domain/game/core/GameEngine.java`:

```diff
 package com.mirboard.domain.game.core;
 
+import java.time.Duration;
 import java.util.List;
 import java.util.Optional;
 import java.util.Random;
```

```diff
     /** 턴 제한 초과 시 적용할 안전 액션 (결정적). null 이면 아무것도 안 한다. */
     GameAction timeoutAction(GameState state, int seat);
 
+    // ---------- ⑦ 엔진 타이머 (D-128) ----------
+
+    /**
+     * D-128 — 이 상태에 <b>시간이 지나면 저절로 일어나는 전이</b>가 있으면 지금부터 남은 시간.
+     * 기본은 없음(티츄·스컬킹).
+     *
+     * <p>턴 제한과는 별개다 — 방의 턴 제한이 꺼져 있어도 걸린다. 인프라는 진행 직후마다
+     * ({@code TurnTimeoutScheduler.onTurnAdvanced}) 이 값을 물어 턴 데드라인과 같은 세대 번호로
+     * 데드라인을 걸고, 만료되면 {@link #onTimer} 를 부른다. "남은 시간"을 돌려주는 것은 재무장해도
+     * 처음부터 다시 세지 않게 하려는 것이다 — 게임은 시작 시각을 상태에 두고 자기 시계로 계산한다.
+     */
+    default Optional<Duration> timer(GameState state) {
+        return Optional.empty();
+    }
+
+    /**
+     * D-128 — {@link #timer} 가 만료됐을 때의 전이. 비어 있으면 아무 일도 없다.
+     *
+     * <p>발화를 믿어도 된다 — 인프라는 세대 번호로 "타이머를 건 뒤 상태가 바뀌지 않았다"를
+     * 확인한 뒤에만, 방 액션 락을 쥐고 부른다. 시스템 전이를 {@link GameAction} 으로 두지 않은
+     * 것은 의도적이다: 액션은 클라 JSON 에서 역직렬화되므로 그 계층에 두면 클라가 위조해 보낼 수
+     * 있다.
+     */
+    default Optional<Result> onTimer(GameState state) {
+        return Optional.empty();
+    }
+
     // ---------- ⑤ 라운드 · 매치 진행 ----------
 
     /**
```

- [ ] **Step 5: 구현 — 무장(턴 타임아웃)**

`server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java`:

```diff
  * 라운드를 끝까지 자동 진행했다. 판정은 게임 중립(방 상태)이다 — 강제 종료는 엔진 상태로는
  * 매치가 안 끝났으므로 {@code isMatchOver()} 로는 못 막는다. 끝내는 쪽은 {@link #cancel} 로
  * 데드라인을 지운다.
+ *
+ * <p>D-128 — 진행 직후 <b>엔진 타이머</b>({@link GameEngine#timer})도 같은 세대 번호로 건다
+ * ({@code deadlines:game}, 발화는 {@link EngineTimerScheduler}). 액션·봇·타임아웃·탈주·라운드
+ * 시작이 이미 이 메서드를 부르므로 호출 지점은 늘지 않고, 턴 제한을 끈 방에서도 걸린다. 무장마다
+ * 상태를 한 번 읽는다(Redis GET) — 타이머가 없는 게임도 같은 비용을 낸다.
  */
 @Component
 public class TurnTimeoutScheduler implements DeadlineHandler {
```

```diff
         // D-122 — 끝난 방에는 다음 턴이 없다(매치를 끝낸 액션 직후의 호출 등). 취소만 한다.
         if (room.status() != RoomStatus.IN_GAME) return;
 
+        armEngineTimer(roomId, room, gen);
+
         int turnSeconds = room.turnSeconds();
         if (turnSeconds <= 0) return;  // 타이머 끔 — 기존 동작 호환.
 
```

```diff
         invalidate(roomId);
     }
 
-    /** generation++ 후 이전 generation 의 데드라인을 지운다. 새 generation 을 반환. */
+    /** generation++ 후 이전 generation 의 데드라인(턴·엔진 타이머)을 지운다. 새 generation 을 반환. */
     private long invalidate(String roomId) {
         long prevGen = generations.current(roomId);
         long gen = generations.bump(roomId);
         deadlines.cancel(KIND, member(roomId, prevGen));
+        deadlines.cancel(EngineTimerScheduler.KIND, member(roomId, prevGen));
         return gen;
+    }
+
+    /**
+     * D-128 — 엔진이 시간 전이를 선언하면 남은 시간 뒤로 엔진 타이머를 건다. 실패해도 턴 타이머는
+     * 막지 않는다(로그만) — 엔진 타이머가 없는 게임의 진행이 이 경로 때문에 멈추면 안 된다.
+     */
+    private void armEngineTimer(String roomId, Room room, long gen) {
+        try {
+            GameEngine engine = engines.forRoom(room);
+            engine.loadState()
+                    .flatMap(engine::timer)
+                    .ifPresent(delay -> deadlines.schedule(
+                            EngineTimerScheduler.KIND, member(roomId, gen), delay));
+        } catch (RuntimeException e) {
+            log.warn("Engine timer arm failed: roomId={} err={}", roomId, e.toString());
+        }
     }
 
     /** 폴러가 만료된 항목을 넘겨준다. 이 인스턴스가 단독 소유한 상태로 들어온다. */
```

```diff
      * 이 호출은 즉시 회수를 위한 것이다.
      */
     private void cleanup(String roomId) {
-        deadlines.cancel(KIND, member(roomId, generations.current(roomId)));
+        long gen = generations.current(roomId);
+        deadlines.cancel(KIND, member(roomId, gen));
+        deadlines.cancel(EngineTimerScheduler.KIND, member(roomId, gen));
         generations.clear(roomId);
     }
 
```

- [ ] **Step 6: 구현 — 발화**

`server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java`:

```java
package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.infra.scheduling.DeadlineHandler;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * D-128 — 엔진 타이머 발화. 엔진이 선언한 "시간이 지나면 저절로 일어나는 전이"
 * ({@link GameEngine#timer}/{@link GameEngine#onTimer}, 예: 실시간 외치기 경쟁 창의 봇 누름·만료)를
 * 진행한다.
 *
 * <p>무장은 {@link TurnTimeoutScheduler#onTurnAdvanced} 가 턴 데드라인과 같은 세대 번호로 한다.
 * 여기는 발화만 맡고, 가드는 턴 타임아웃과 같다: 세대 → IN_GAME → 락(실패 시 짧게 재시도) → 락 안
 * 재확인. 적용 단계만 {@code timeoutAction} 대신 {@code onTimer} 이고, 결과는 다른 진행과 같은 경로를
 * 탄다(저장 → {@code matchProgress.advance} → 브로드캐스트 → 봇·타이머 재무장).
 *
 * <p>게임을 모른다 — 무엇이 언제 일어나는지는 엔진이 답한다.
 */
@Component
public class EngineTimerScheduler implements DeadlineHandler {

    private static final Logger log = LoggerFactory.getLogger(EngineTimerScheduler.class);

    /** `deadlines:game` 큐. member 는 턴 데드라인과 같은 `{roomId}#{generation}`. */
    public static final String KIND = "game";
    /** 락 경합 시 재시도 간격 — 턴 타임아웃과 같다. */
    private static final Duration LOCK_RETRY = Duration.ofMillis(200);

    private final RoomService roomService;
    private final GameEngineProvider engines;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final BotScheduler botScheduler;
    private final TurnTimeoutScheduler turnTimeout;
    private final DeadlineQueue deadlines;
    private final RoomGeneration generations;

    public EngineTimerScheduler(RoomService roomService,
                                GameEngineProvider engines,
                                GameEventBroadcaster broadcaster,
                                RoomActionLock lock,
                                MatchProgressService matchProgress,
                                @Lazy BotScheduler botScheduler,
                                @Lazy TurnTimeoutScheduler turnTimeout,
                                DeadlineQueue deadlines,
                                RoomGeneration generations) {
        this.roomService = roomService;
        this.engines = engines;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
        this.deadlines = deadlines;
        this.generations = generations;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** 폴러가 만료된 항목을 넘겨준다. 이 인스턴스가 단독 소유한 상태로 들어온다. */
    @Override
    public void handle(String member) {
        int sep = member.lastIndexOf('#');
        if (sep < 0) {
            log.warn("엔진 타이머 member 형식 오류: {}", member);
            return;
        }
        String roomId = member.substring(0, sep);
        long gen;
        try {
            gen = Long.parseLong(member.substring(sep + 1));
        } catch (NumberFormatException e) {
            log.warn("엔진 타이머 generation 파싱 실패: {}", member);
            return;
        }
        fire(roomId, gen);
    }

    private void fire(String roomId, long capturedGen) {
        // 그 사이 누가 행동했으면 generation 이 올라가 있다 — 이 타이머가 본 상태는 이미 없다.
        if (generations.current(roomId) != capturedGen) return;
        if (inGameRoom(roomId).isEmpty()) return;

        if (!lock.tryAcquire(roomId)) {
            // 다른 액션 처리 중 — 짧게 뒤로 미뤄 재시도 (gen 재확인은 그때).
            deadlines.schedule(KIND, TurnTimeoutScheduler.member(roomId, capturedGen), LOCK_RETRY);
            return;
        }
        boolean advanced = false;
        try {
            // 락 안에서 gen·방 상태 재확인 — 락 대기 중 누가 행동했거나 매치가 끝났을 수 있다.
            if (generations.current(roomId) != capturedGen) return;
            Optional<Room> current = inGameRoom(roomId);
            if (current.isEmpty()) return;
            Room room = current.get();

            GameEngine engine = engines.forRoom(room);
            GameState state = engine.loadState().orElse(null);
            if (state == null) return;

            Optional<GameEngine.Result> result = engine.onTimer(state);
            if (result.isEmpty()) return;

            GameState newState = result.get().newState();
            engine.saveState(newState);
            List<GameEvent> outbound = new ArrayList<>(result.get().events());
            matchProgress.advance(engine, room, newState, outbound);
            broadcaster.broadcast(roomId, outbound, room.playerIds());
            advanced = true;
            log.info("Engine timer fired: roomId={} phase={} events={}",
                    roomId, engine.phaseName(newState), outbound.size());
        } catch (RuntimeException e) {
            log.error("EngineTimerScheduler error in room {}: {}", roomId, e.getMessage(), e);
        } finally {
            lock.release(roomId);
        }
        if (advanced) {
            // 다음 차례가 봇이면 이어받고, 턴·엔진 타이머를 새 상태로 다시 건다.
            botScheduler.scheduleBots(roomId);
            turnTimeout.onTurnAdvanced(roomId);
        }
    }

    /** 지금 IN_GAME 인 방. 없거나 끝났으면 empty. */
    private Optional<Room> inGameRoom(String roomId) {
        try {
            Room room = roomService.getRoom(roomId);
            return room.status() == RoomStatus.IN_GAME ? Optional.of(room) : Optional.empty();
        } catch (RoomNotFoundException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest" --tests "com.mirboard.infra.bot.FinishedRoomGuardTest"`
Expected: PASS — `EngineTimerSchedulerTest` 13건(무장 6 · 발화 7), 기존 `FinishedRoomGuardTest` 8건 그대로.

- [ ] **Step 8: 턴 타이머 회귀 (Docker)**

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.bot.FinishedRoomStopsProgressIT" --tests "com.mirboard.infra.bot.TurnTimeoutSchedulerIT"`
Expected: PASS — 티츄 턴 타임아웃 자동 진행과 끝난 방의 데드라인 정리가 그대로다(엔진 타이머는 티츄에 없다).

- [ ] **Step 9: 포트 계약 문서**

`docs/game-port.md`:

```diff
 # GameEngine 포트 설계 (D-97 설계 · D-98 구현)
 
-> 상태: **구현 완료** (S1, D-98) · 설계 2026-07-30 · 반영 2026-07-30
+> 상태: **구현 완료** (S1, D-98) · 설계 2026-07-30 · 반영 2026-07-30 · 엔진 타이머 추가 D-128(2026-10-04)
 > 이 문서는 **계약 정본**이다. 포트를 바꾸면 여기를 먼저 고친다.
 > 구현 순서와 세션 분할은 `docs/plans/multi-game-sessions.md`.
 
```

```diff
     Advance advance(GameState newState, List<GameEvent> outbound);
     DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound);
 
+    // ⑦ 엔진 타이머 (D-128) — 기본 없음. 시간이 지나면 저절로 일어나는 전이(§2)
+    default Optional<Duration> timer(GameState state) { return Optional.empty(); }  // 지금부터 남은 시간
+    default Optional<Result> onTimer(GameState state) { return Optional.empty(); }  // 만료 시 전이
+
     record Result(GameState newState, List<GameEvent> events) {}
     record Advance(boolean roundCompleted, boolean matchCompleted) {}
     enum DesertOutcome { NOT_APPLICABLE, MATCH_CONTINUES, MATCH_ENDED }
```

````diff
   리매치를 지원할 때(리매치 대기, IN_GAME 유지).
 - `RoomOption` 이 아니라 메서드인 이유: 방 생성 때 사용자가 고르는 설정이 아니라 게임 구조의
   성질이다. UI 게이팅도 없다(리매치 버튼은 지원 게임의 게임판에만 있다).
+
+### 시간이 지나면 일어나는 전이도 게임이 선언한다 (D-128)
+
+원카드의 "원카드!/잡기!" 경쟁 창은 아무도 행동하지 않아도 상태가 바뀐다 — 추첨된 봇이 누를 시각이 오면
+봇이 누르고, 3초가 지나면 창이 닫힌다. 포트에는 이런 전이가 없었고 시간은 방의 턴 제한에만 묶여 있었다.
+봇 루프·턴 타임아웃을 재활용하면 턴 제한을 끈 방에서 창이 닫히지 않으므로 엔진이 타이머를 선언하는
+선택형 확장을 두었다.
+
+```java
+default Optional<Duration> timer(GameState state) { return Optional.empty(); }
+default Optional<Result> onTimer(GameState state) { return Optional.empty(); }
+```
+
+- **기본 없음(옵트인)** — 티츄·스컬킹은 한 줄도 바꾸지 않았다.
+- **무장**: `TurnTimeoutScheduler.onTurnAdvanced` 가 턴 데드라인과 **같은 세대 번호**로
+  `deadlines:game`(member `{roomId}#{generation}`)에 건다. 액션·봇·타임아웃·탈주·라운드 시작이 이미 이
+  메서드를 부르므로 호출 지점은 늘지 않고, 턴 제한을 끈 방에서도 걸린다. 대가는 진행마다 상태 조회 1회
+  (Redis GET) — 타이머가 없는 게임도 같다.
+- **남은 시간**을 돌려준다. 재무장해도 처음부터 다시 세지 않게, 게임은 시작 시각을 상태에 두고 자기
+  시계로 계산한다(원카드: 창 연 시각 + 가장 빠른 봇 반응 또는 창 길이).
+- **발화**: `EngineTimerScheduler` 가 턴 타임아웃과 같은 가드를 거친다 — 세대 → IN_GAME → 락(실패 시 200ms
+  재시도) → 락 안 재확인. 적용만 `timeoutAction` 대신 `onTimer` 이고, 결과는 다른 진행과 같은 길(저장 →
+  `advance` → 브로드캐스트 → 봇·타이머 재무장)을 탄다. 세대 번호가 "타이머를 건 뒤 상태 무변경"을
+  보장하므로 `onTimer` 는 시각을 다시 보지 않는다.
+- **취소**: 세대가 오르면(누가 행동함) 두 종류가 함께 지워진다. `cancel()`(D-122)도 마찬가지다.
+- **왜 `GameAction` 이 아니라 `Result` 인가**: 액션은 클라 JSON 에서 역직렬화된다. "창 닫기" 같은 시스템
+  전이를 그 계층에 두면 클라가 위조해 보낼 수 있다.
+- 시간 전이가 진행 중인 동안 게임은 `pendingSeats` 를 비워 두면 된다(원카드 경쟁 창) — 봇 루프와 턴
+  타이머가 끼어들지 않고, 시간 진행은 엔진 타이머 하나가 맡는다.
 
 ## 3. 인원 가변 (스컬킹 2~8 결정의 파급) — **구현 완료 (D-99 / S2)**
 
````

`docs/redis-keys.md`:

```diff
 | `room:{roomId}:lock` | STRING | 2s | 액션 직렬화 락 | `SET key NX EX 2` |
 | `presence:room:{roomId}` | HASH | 6h | `userId` → 해당 방을 보고 있는 **세션 수** | D-96(D-111 보정). `RoomPresence`. 방 토픽 SUBSCRIBE 시 `presence_join.lua` 로 **세션당 1회만** `HINCRBY +1`, DISCONNECT 시 `presence_leave.lua` 로 −1(0 이면 `HDEL`, 빈 HASH 면 키 자체 `DEL`). **boolean 이 아니라 카운터** — 탭 두 개 중 하나만 닫아도 접속 중이어야 탈주 오판이 없다. 탈주 유예 만료 시 "재접속했는가"(`hasLiveSession`) 판정의 근거 |
 | `presence:session:{sessionId}` | STRING | 6h | `"{userId}:{roomId}"` | D-96(D-111 보정). `RoomPresence`. 역할 둘: ① DISCONNECT 이벤트는 sessionId 만 주므로 역방향 조회, ② **"이 세션을 이미 셌는가" 표식** — `SET NX` 성공 시에만 카운터를 올려 한 세션의 구독 여러 개가 중복 계수되지 않게 한다. leave 시 `DEL` |
-| `deadlines:{kind}` | ZSET | 12h | member=페이로드, score=만료 `epochMillis` | D-96. `DeadlineQueue`. `kind`=`turn`(member `{roomId}#{generation}`, `TurnTimeoutScheduler`) · `desertion`(member `{roomId}:{userId}`, `DesertionGraceScheduler`). 모든 인스턴스가 폴링(`mirboard.scheduling.poll-interval-millis`, 기본 250ms)하고 만료분 pop 은 `deadline_poll.lua` 로 원자화 — 한 항목은 정확히 한 인스턴스에만 간다. 같은 member 재등록 = score 갱신(= 기존 타이머 취소+재등록). `schedule()` 마다 EXPIRE 갱신 |
+| `deadlines:{kind}` | ZSET | 12h | member=페이로드, score=만료 `epochMillis` | D-96. `DeadlineQueue`. `kind`=`turn`(member `{roomId}#{generation}`, `TurnTimeoutScheduler`) · `game`(D-128 엔진 타이머 — 같은 member 형식·같은 세대 번호, 무장은 `TurnTimeoutScheduler`, 발화는 `EngineTimerScheduler`) · `desertion`(member `{roomId}:{userId}`, `DesertionGraceScheduler`). 모든 인스턴스가 폴링(`mirboard.scheduling.poll-interval-millis`, 기본 250ms)하고 만료분 pop 은 `deadline_poll.lua` 로 원자화 — 한 항목은 정확히 한 인스턴스에만 간다. 같은 member 재등록 = score 갱신(= 기존 타이머 취소+재등록). `schedule()` 마다 EXPIRE 갱신 |
 | `login:fail:{username}` | STRING(INTEGER) | 윈도(기본 15m) | 로그인 연속 실패 횟수 | D-84. `INCR`+첫 실패 시 EXPIRE. 임계 초과 시 lock 설정, 성공 시 DEL |
 | `lock:login:{username}` | STRING | 잠금(기본 15m) | 잠금 마커 | D-84. 존재 시 423 ACCOUNT_LOCKED. users 스키마 비침범(휘발) |
 | `ratelimit:{bucket}:{subject}` | STRING(INTEGER) | 윈도(TTL) | 버킷별 요청 카운터 | D-90(D-84 확장). `bucket`=`auth`·`guest`(D-117, 24h)·`api-default`·`room-create`·`expensive-write`·`game-action`·`chat`·`reaction`·`stomp-default`. `subject`=인증 시 `u:{userId}`, 아니면 `ip:{ip}`(NAT 오탐 회피). **D-117: `/api/auth/**`(`auth`·`guest`)는 Bearer 를 실어도 항상 `ip:` 키**. IP 는 신뢰 헤더 `mirboard.ratelimit.client-ip-header`(운영 `Fly-Client-IP`, 없으면 remoteAddr)에서 읽고 IPv6 는 `x:x:x:x::/64` 로 묶는다(예: `ratelimit:guest:ip:2001:db8:1:2::/64`). `bucket` 은 원본 URI 가 아니라 MVC·Security 가 매칭하는 정규화 경로(디코딩·contextPath/`X-Forwarded-Prefix` 제외)로 고른다 — 인코딩 변형으로 버킷을 갈아타지 못하게(D-117 보정). Lua 원자 고정 윈도(`INCR`+`EXPIRE`). HTTP 초과=429(`Retry-After`=윈도 초), STOMP 초과=드롭(액션만 본인 큐 `ERROR(RATE_LIMITED)`). 클라 IP 는 휘발 카운터 키(영속 로그 아님) |
```

- [ ] **Step 10: 커밋**

```bash
git add docs/decisions.md \
  docs/game-port.md \
  docs/redis-keys.md \
  server/src/main/java/com/mirboard/domain/game/core/GameEngine.java \
  server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java \
  server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java \
  server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java
git commit -m "feat(D-128): 포트 엔진 타이머 — timer/onTimer 와 deadlines:game 무장·발화

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 2: 상태 저장소 + 공개·비공개 뷰 + 롤백 안전 JSON

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardStateStore.java`, `server/src/main/java/com/mirboard/domain/game/onecard/state/OneCardStateMapper.java`
- Modify: `server/src/main/java/com/mirboard/domain/game/onecard/state/OneCardState.java`, `state/RaceWindow.java`, `state/Elimination.java`, `state/MatchResult.java`
- Test: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardStateMapperTest.java`(신규), `server/src/test/java/com/mirboard/domain/game/onecard/OneCardJsonRoundTripTest.java`(1건 추가)

**Interfaces:**
- Consumes: S2 의 `OneCardState`(13 컴포넌트)·`RaceWindow`·`Elimination`·`MatchResult`, 테스트 빌더 `OneCardTables`.
- Produces: `OneCardStateStore` — `save(String roomId, OneCardState)`, `Optional<OneCardState> load(String roomId)`(키
  `room:{id}:state`, TTL 6h). `OneCardStateMapper` — `toTableView(OneCardState, long now)` → `TableView(String phase,
  List<SeatView> seats, PlayingCard topCard, Suit declaredSuit, int attackStack, int direction, int turnSeat,
  int drawPileCount, RaceView race, MatchResult result)`, `SeatView(int seat, int handCount, Elimination.Reason
  eliminated)`, `RaceView(int raceId, int ownerSeat, int slot, int jitterX, int jitterY, long windowMillis, long
  remainingMillis)`; `toPrivateView(OneCardState, int seat)` → `PrivateView(int seat, List<PlayingCard> hand, int
  handVersion)`(handVersion = 상태 버전). 상태 레코드 6종은 `@JsonIgnoreProperties(ignoreUnknown = true)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardStateMapperTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.PrivateView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.RaceView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.SeatView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.TableView;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D-128 — 공개·비공개 뷰와 State Hiding 경계(손패·뽑을 더미 순서·봇 반응 시각). */
class OneCardStateMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OPENED_AT = 10_000L;

    private static OneCardState threeSeats() {
        return seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6)), hand(diamond(4)))
                .top(heart(5)).drawPile(diamond(9), diamond(10)).turn(1).build();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void the_table_view_shows_hand_counts_and_the_pile_count_but_no_cards() throws Exception {
        OneCardState state = threeSeats();

        TableView view = OneCardStateMapper.toTableView(state, OPENED_AT);

        assertThat(view.seats()).containsExactly(
                new SeatView(0, 3, null), new SeatView(1, 2, null), new SeatView(2, 1, null));
        assertThat(view.drawPileCount()).isEqualTo(2);
        assertThat(view.topCard()).isEqualTo(heart(5));
        assertThat(view.turnSeat()).isEqualTo(1);
        assertThat(view.phase()).isEqualTo("PLAYING");
        assertThat(view.race()).isNull();
        JsonNode json = JSON.readTree(JSON.writeValueAsString(view));
        assertThat(fieldNames(json)).doesNotContain("hands", "hand", "drawPile", "discardPile");
        assertThat(fieldNames(json.get("seats").get(0))).containsExactlyInAnyOrder("seat", "handCount", "eliminated");
    }

    @Test
    void an_open_race_exposes_only_the_public_window_and_the_time_left_to_its_end() throws Exception {
        RaceWindow race = new RaceWindow(7, 0, 3, -40, 25, OPENED_AT, 3_000, 1,
                new RaceWindow.BotPress(2, false, 1_200));
        OneCardState state = seats(hand(heart(9)), hand(spade(4), spade(6)), hand(diamond(4), diamond(6)))
                .top(heart(5)).race(race).build();

        TableView view = OneCardStateMapper.toTableView(state, OPENED_AT + 1_000);

        assertThat(view.phase()).isEqualTo("RACE");
        assertThat(view.race()).isEqualTo(new RaceView(7, 0, 3, -40, 25, 3_000, 2_000));
        JsonNode raceJson = JSON.readTree(JSON.writeValueAsString(view)).get("race");
        assertThat(fieldNames(raceJson)).containsExactlyInAnyOrder(
                "raceId", "ownerSeat", "slot", "jitterX", "jitterY", "windowMillis", "remainingMillis");
    }

    @Test
    void the_time_left_never_goes_below_zero() {
        RaceWindow race = new RaceWindow(7, 0, 3, 0, 0, OPENED_AT, 3_000, 1, null);
        OneCardState state = seats(hand(heart(9)), hand(spade(4), spade(6)))
                .top(heart(5)).race(race).build();

        assertThat(OneCardStateMapper.toTableView(state, OPENED_AT + 9_000).race().remainingMillis()).isZero();
    }

    @Test
    void eliminated_seats_show_the_reason() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.DESERTED, 3).top(heart(5)).turn(0).build();

        assertThat(OneCardStateMapper.toTableView(state, 0).seats().get(1))
                .isEqualTo(new SeatView(1, 0, Elimination.Reason.DESERTED));
    }

    @Test
    void the_private_view_carries_the_seats_whole_hand_and_the_state_version() {
        OneCardState state = threeSeats();

        PrivateView view = OneCardStateMapper.toPrivateView(state, 0);

        assertThat(view).isEqualTo(new PrivateView(0, List.of(heart(9), club(3), club(4)), state.version()));
        assertThatThrownBy(() -> OneCardStateMapper.toPrivateView(state, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardJsonRoundTripTest.java`:

```diff
 
 import com.fasterxml.jackson.databind.JsonNode;
 import com.fasterxml.jackson.databind.ObjectMapper;
+import com.fasterxml.jackson.databind.node.ObjectNode;
 import com.mirboard.domain.game.onecard.action.OneCardAction;
 import com.mirboard.domain.game.onecard.card.PlayingCard;
 import com.mirboard.domain.game.onecard.card.Suit;
```

```diff
                         new MatchResult.Standing(1, 2, 2, MatchResult.SeatStatus.ALIVE))));
 
         assertThat(roundTrip(finished, OneCardState.class)).isEqualTo(finished);
+    }
+
+    /**
+     * D-128 — 새 버전이 필드를 더해 저장한 뒤 롤백돼도 진행 중 매치를 읽는다. 상태 안의 레코드(경쟁 창·봇 누름·
+     * 탈락·결과·순위)도 모두 같다.
+     */
+    @Test
+    void a_stored_state_with_unknown_fields_still_loads() throws Exception {
+        OneCardState racing = seats(hand(club(3)), hand(), hand(diamond(4), diamond(6), spade(8)))
+                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5))
+                .race(new RaceWindow(4, 0, 3, -20, 40, 1_000, 3_000, 2, new RaceWindow.BotPress(2, false, 1_500)))
+                .build();
+        ObjectNode racingJson = mapper.valueToTree(racing);
+        racingJson.put("addedLater", 1);
+        ((ObjectNode) racingJson.get("race")).put("addedLater", 1);
+        ((ObjectNode) racingJson.get("race").get("botPress")).put("addedLater", 1);
+        ((ObjectNode) racingJson.get("eliminations").get(0)).put("addedLater", 1);
+
+        OneCardState open = seats(hand(), hand(spade(4), spade(6))).top(heart(2)).turn(-1).build();
+        OneCardState finished = new OneCardState(open.hands(), open.drawPile(), open.discardPile(), -1, 1, null, 2,
+                null, List.of(), 0, 12, 9, new MatchResult(MatchResult.EndReason.FINISHED, List.of(
+                        new MatchResult.Standing(0, 1, 0, MatchResult.SeatStatus.FINISHED),
+                        new MatchResult.Standing(1, 2, 2, MatchResult.SeatStatus.ALIVE))));
+        ObjectNode finishedJson = mapper.valueToTree(finished);
+        ((ObjectNode) finishedJson.get("result")).put("addedLater", 1);
+        ((ObjectNode) finishedJson.get("result").get("standings").get(0)).put("addedLater", 1);
+
+        assertThat(mapper.treeToValue(racingJson, OneCardState.class)).isEqualTo(racing);
+        assertThat(mapper.treeToValue(finishedJson, OneCardState.class)).isEqualTo(finished);
     }
 
     @Test
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`OneCardStateMapper` 가 아직 없다).

- [ ] **Step 3: 구현 — 상태 레코드(모르는 필드 무시)**

`server/src/main/java/com/mirboard/domain/game/onecard/state/OneCardState.java`:

```diff
 
 import com.fasterxml.jackson.annotation.JsonAutoDetect;
 import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
+import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
 import com.mirboard.domain.game.core.GameState;
 import com.mirboard.domain.game.onecard.card.PlayingCard;
 import com.mirboard.domain.game.onecard.card.Suit;
```

```diff
  * <p>단계(진행·경쟁·종료)를 sealed 하위 타입으로 나누지 않은 것은 의도적이다 — 스컬킹은 단계마다 들고
  * 있는 것이 달랐지만, 원카드는 세 단계가 같은 테이블(손패·더미·차례)을 공유하고 경쟁 창과 결과만
  * 붙었다 떨어진다. 그래서 {@code race}·{@code result} 를 nullable 로 두고 단계는 파생한다.
+ *
+ * <p>Redis 에 JSON 으로 저장된다(D-128). 모르는 필드는 무시한다 — 배포 뒤 필드를 늘렸다가 되돌려도 진행 중
+ * 매치를 읽을 수 있게 하려는 것으로, 스컬킹 매치 상태(D-120)와 같은 이유다. 안에 든 레코드도 같다.
  *
  * @param hands        좌석별 손패. 탈락자는 빈 목록
  * @param drawPile     뽑을 더미, 0번이 맨 위
```

```diff
  * @param result       끝났으면 결과, 아니면 null
  */
 @JsonAutoDetect(isGetterVisibility = Visibility.NONE)
+@JsonIgnoreProperties(ignoreUnknown = true)
 public record OneCardState(List<List<PlayingCard>> hands,
                            List<PlayingCard> drawPile,
                            List<PlayingCard> discardPile,
```

`server/src/main/java/com/mirboard/domain/game/onecard/state/RaceWindow.java`:

```diff
 package com.mirboard.domain.game.onecard.state;
+
+import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
 
 /**
  * 열린 외치기 경쟁 창 (`docs/rules-onecard.md` §9).
```

```diff
  * @param botPress     창을 열 때 추첨한 가장 빠른 봇의 누름. 봇이 없거나 창보다 늦으면 null.
  *                     <b>서버 전용</b> — 공개 뷰·이벤트에 싣지 않는다(설계서 §4.9)
  */
+@JsonIgnoreProperties(ignoreUnknown = true)
 public record RaceWindow(int raceId,
                          int ownerSeat,
                          int slot,
```

```diff
      * @param call        true 면 주인의 "원카드!", false 면 다른 봇의 "잡기!"
      * @param delayMillis 창이 열린 뒤 누르기까지의 반응 시간
      */
+    @JsonIgnoreProperties(ignoreUnknown = true)
     public record BotPress(int seat, boolean call, long delayMillis) {
     }
 
```

`server/src/main/java/com/mirboard/domain/game/onecard/state/Elimination.java`:

```diff
 package com.mirboard.domain.game.onecard.state;
+
+import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
 
 /**
  * 탈락 한 건 (`docs/rules-onecard.md` §10). 상태에는 일어난 순서대로 쌓인다 — 파산자 순위가 이 순서를
```

```diff
  *
  * @param cardsHeld 탈락 순간 손에 있던 장수. 손패는 뽑을 더미로 가므로 순위표용으로 따로 남긴다
  */
+@JsonIgnoreProperties(ignoreUnknown = true)
 public record Elimination(int seat, Reason reason, int cardsHeld) {
 
     public enum Reason {
```

`server/src/main/java/com/mirboard/domain/game/onecard/state/MatchResult.java`:

```diff
 package com.mirboard.domain.game.onecard.state;
 
+import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
 import java.util.List;
 
 /**
```

```diff
  *
  * @param standings 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
  */
+@JsonIgnoreProperties(ignoreUnknown = true)
 public record MatchResult(EndReason reason, List<Standing> standings) {
 
     public MatchResult {
```

```diff
      * @param rank      1부터. 동순위 다음은 건너뛴다(1, 1, 3)
      * @param cardsLeft 살아 있으면 남은 장수, 탈락했으면 탈락 순간의 장수
      */
+    @JsonIgnoreProperties(ignoreUnknown = true)
     public record Standing(int seat, int rank, int cardsLeft, SeatStatus status) {
     }
 
```

- [ ] **Step 4: 구현 — 저장소와 뷰**

`server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardStateStore.java`:

```java
package com.mirboard.domain.game.onecard.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 원카드 상태를 Redis 에 영속화한다 (D-128). 키는 티츄·스컬킹과 같은 {@code room:{id}:state} — 방당
 * 게임이 하나라 충돌이 없고, 방 소멸 정리 경로도 공유된다.
 *
 * <p>1판 = 1매치라 스컬킹의 매치 상태 키({@code match:{id}:state})가 없다. 손패도 상태 안에 있고,
 * resync 는 포트의 {@code privateView(state, seat)} 로 꺼낸다.
 */
@Repository
public class OneCardStateStore {

    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public OneCardStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String roomId, OneCardState state) {
        try {
            redis.opsForValue().set(stateKey(roomId), objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OneCardState for room " + roomId, e);
        }
    }

    public Optional<OneCardState> load(String roomId) {
        String json = redis.opsForValue().get(stateKey(roomId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, OneCardState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize OneCardState for room " + roomId, e);
        }
    }

    private static String stateKey(String roomId) {
        return "room:" + roomId + ":state";
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/state/OneCardStateMapper.java`:

```java
package com.mirboard.domain.game.onecard.state;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.ArrayList;
import java.util.List;

/**
 * 서버 상태 → 클라 뷰 변환 (D-128). State Hiding(D-01)의 원카드 경계는 셋이다:
 * <ul>
 *   <li><b>손패</b> — 공개 뷰에는 좌석별 장수만, 카드는 본인 뷰에만</li>
 *   <li><b>뽑을 더미의 순서</b> — 장수만 공개</li>
 *   <li><b>봇 반응 시각</b> — 경쟁 창은 창 끝까지 남은 시간만 싣는다. 봇이 누를 시각
 *       ({@link RaceWindow#botPress()}, {@link RaceWindow#deadline()})은 어디에도 내보내지 않는다</li>
 * </ul>
 * 맨 위 카드·지정 무늬·공격 누적·방향·차례·탈락·결과는 공개 정보다.
 */
public final class OneCardStateMapper {

    private OneCardStateMapper() {
    }

    /** 공개 뷰 — 참가자·관전자 전원이 본다. */
    public record TableView(String phase,
                            List<SeatView> seats,
                            PlayingCard topCard,
                            Suit declaredSuit,
                            int attackStack,
                            int direction,
                            int turnSeat,
                            int drawPileCount,
                            RaceView race,
                            MatchResult result) {
    }

    /** @param eliminated 탈락했으면 사유, 살아 있으면 null */
    public record SeatView(int seat, int handCount, Elimination.Reason eliminated) {
    }

    /**
     * 열린 경쟁 창의 공개 부분.
     *
     * @param remainingMillis 창 끝까지 남은 시간(재접속한 클라가 버튼을 얼마나 보여 줄지). 봇이 누를
     *                        시각이 아니다 — 그건 서버만 안다
     */
    public record RaceView(int raceId, int ownerSeat, int slot, int jitterX, int jitterY,
                           long windowMillis, long remainingMillis) {
    }

    /** 본인 전용 — 손패 전체와 버전({@code HAND_UPDATED} 의 {@code handVersion} 과 같은 축). */
    public record PrivateView(int seat, List<PlayingCard> hand, int handVersion) {
    }

    /** @param now 지금 시각(epoch ms) — 경쟁 창의 남은 시간 계산용 */
    public static TableView toTableView(OneCardState state, long now) {
        List<SeatView> seats = new ArrayList<>();
        for (int seat = 0; seat < state.seatCount(); seat++) {
            seats.add(new SeatView(seat, state.hands().get(seat).size(), eliminationOf(state, seat)));
        }
        return new TableView(state.phaseName(), seats, state.topCard(), state.declaredSuit(),
                state.attackStack(), state.direction(), state.turnSeat(), state.drawPile().size(),
                raceView(state.race(), now), state.result());
    }

    public static PrivateView toPrivateView(OneCardState state, int seat) {
        if (seat < 0 || seat >= state.seatCount()) {
            throw new IllegalArgumentException("no such seat: " + seat);
        }
        return new PrivateView(seat, state.hands().get(seat), state.version());
    }

    private static RaceView raceView(RaceWindow race, long now) {
        if (race == null) {
            return null;
        }
        long remaining = Math.max(0L, race.openedAt() + race.windowMillis() - now);
        return new RaceView(race.raceId(), race.ownerSeat(), race.slot(), race.jitterX(), race.jitterY(),
                race.windowMillis(), remaining);
    }

    private static Elimination.Reason eliminationOf(OneCardState state, int seat) {
        for (Elimination e : state.eliminations()) {
            if (e.seat() == seat) {
                return e.reason();
            }
        }
        return null;
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 원카드 테스트 140건(S2 134 + JSON 1 + 뷰 5).

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardStateStore.java \
  server/src/main/java/com/mirboard/domain/game/onecard/state \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardStateMapperTest.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardJsonRoundTripTest.java
git commit -m "feat(D-128): 원카드 상태 저장소·공개/비공개 뷰 + 롤백 안전 JSON

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: 포트 어댑터 + 게임 정의 + 설정

이 태스크의 어댑터에는 봇이 없다 — `botAction` 은 포트 기본(합법수 무작위)이다. Task 5 가 휴리스틱을 더한다.

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java`, `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`, `server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardMatchCompleted.java`
- Modify: `server/src/main/java/com/mirboard/domain/game/onecard/RaceSettings.java`(오류 메시지), `server/src/main/resources/application.yml`(`mirboard.onecard`)
- Test: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineTest.java`, `server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java`, `server/src/test/java/com/mirboard/domain/game/onecard/RaceSettingsTest.java`(신규),
  `server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java`(카탈로그 3게임)

**Interfaces:**
- Consumes: Task 1 포트 메서드, Task 2 `OneCardStateStore`·`OneCardStateMapper`, S2 `OneCardEngine`
  (`apply(state, seat, action, now)`, `timerDeadline`, `onTimer`, `desert`, `pendingSeats`, `legalActions`,
  `timeoutAction`).
- Produces: `OneCardGameEngine(GameContext, OneCardStateStore, Clock, Random, RaceSettings, ApplicationEventPublisher)
  implements GameEngine` — `apply` 는 `clock.millis()` 를 넘긴다, `timer` = 엔진 마감 − 지금(최소 0), `advance` 는
  나가는 이벤트에 `MATCH_ENDED` 가 있을 때만 `OneCardMatchCompleted` 를 한 번 발행하고 `Advance(true, true)`,
  `desert` 는 3치 매핑(종료면 기록 발행). `OneCardMatchCompleted(String roomId, List<Long> playerIds, MatchResult
  result)`. `OneCardGameDefinition` — `ID = "ONE_CARD"`, 2~6인, `status()` 는 `mirboard.onecard.status`(기본
  `COMING_SOON`), `raceSettings()` 는 `mirboard.onecard.race-window-millis`·`bot-reaction-{owner,catcher}-{min,max}-millis`
  + 슬롯 8.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.PrivateView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.TableView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * D-128 — 포트 어댑터. 시계를 넣는 자리(경쟁 창 여는 시각·엔진 타이머의 남은 시간), 매치 종료 기록 발행,
 * 탈주 3치 매핑. 저장소는 모의 객체라 Docker 없이 돈다.
 */
class OneCardGameEngineTest {

    private static final long NOW = 50_000L;
    /** 봇 반응 고정 — 주인 1.2초, 잡는 쪽 1.5초. */
    private static final RaceSettings FIXED = new RaceSettings(3_000, 1_200, 1_200, 1_500, 1_500, 8);

    private final OneCardStateStore store = mock(OneCardStateStore.class);
    private final List<Object> published = new ArrayList<>();

    private OneCardGameEngine engine(long now, int seatCount, Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seatCount).map(i -> 100 + i).boxed().toList();
        return new OneCardGameEngine(new GameContext("room-1", ids, 0, 0, List.of(botSeats)), store,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), new Random(5), FIXED, published::add);
    }

    /** 좌석 0 이 두 장 중 ♥9 를 내 1장이 되는 3인 테이블. */
    private static OneCardState aboutToGoDownToOne() {
        return seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
    }

    private static OneCardState raced(OneCardGameEngine engine) {
        return (OneCardState) engine.apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9))).newState();
    }

    @Test
    void a_race_opens_at_the_clock_time() {
        GameEngine.Result result = engine(NOW, 3).apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9)));

        OneCardState next = (OneCardState) result.newState();
        assertThat(next.race().openedAt()).isEqualTo(NOW);
        assertThat(result.events()).last().isInstanceOf(OneCardEvent.RaceOpened.class);
    }

    @Test
    void the_timer_is_the_time_left_until_the_window_ends_or_the_fastest_bot_presses() {
        OneCardState noBots = raced(engine(NOW, 3));
        OneCardState withCatcherBot = raced(engine(NOW, 3, 2));

        assertThat(engine(NOW + 1_000, 3).timer(noBots)).contains(Duration.ofMillis(2_000));
        assertThat(engine(NOW + 9_000, 3).timer(noBots)).contains(Duration.ZERO);
        assertThat(engine(NOW + 1_000, 3, 2).timer(withCatcherBot)).contains(Duration.ofMillis(500));
        assertThat(engine(NOW, 3).timer(aboutToGoDownToOne())).isEmpty();
    }

    @Test
    void the_timer_closes_the_race() {
        OneCardState raced = raced(engine(NOW, 3));

        Optional<GameEngine.Result> fired = engine(NOW + 3_000, 3).onTimer(raced);

        assertThat(fired).isPresent();
        assertThat(fired.get().events().getFirst())
                .isEqualTo(new RaceResolved(raced.race().raceId(), RaceOutcome.EXPIRED, -1));
        assertThat(engine(NOW, 3).onTimer(aboutToGoDownToOne())).isEmpty();
    }

    @Test
    void the_transition_that_ends_the_match_is_recorded_exactly_once() {
        OneCardState lastCard = seats(hand(heart(9)), hand(spade(4), spade(6)), hand(diamond(4), diamond(6)))
                .top(heart(5)).turn(0).build();
        OneCardGameEngine adapter = engine(NOW, 3);
        GameEngine.Result result = adapter.apply(lastCard, 0, PlayCard.of(heart(9)));

        GameEngine.Advance advance = adapter.advance(result.newState(), new ArrayList<>(result.events()));

        assertThat(advance).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOfSatisfying(OneCardMatchCompleted.class, done -> {
            assertThat(done.roomId()).isEqualTo("room-1");
            assertThat(done.playerIds()).containsExactly(100L, 101L, 102L);
            assertThat(done.result().reason()).isEqualTo(EndReason.FINISHED);
            assertThat(done.result().winners()).containsExactly(0);
        });
        assertThat(adapter.advance(result.newState(), new ArrayList<>())).isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).hasSize(1);
    }

    @Test
    void nothing_advances_while_the_match_goes_on() {
        OneCardGameEngine adapter = engine(NOW, 3);
        GameEngine.Result result = adapter.apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9)));

        assertThat(adapter.advance(result.newState(), new ArrayList<>(result.events())))
                .isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).isEmpty();
    }

    @Test
    void desertion_maps_to_the_three_port_outcomes() {
        OneCardState threeSeats = aboutToGoDownToOne();
        OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6))).top(heart(5)).turn(0).build();
        OneCardState alreadyOut = seats(hand(heart(9), club(3)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5)).turn(0).build();
        List<GameEvent> outbound = new ArrayList<>();

        when(store.load("room-1")).thenReturn(Optional.empty());
        assertThat(engine(NOW, 3).desert(2, 102L, outbound)).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);

        when(store.load("room-1")).thenReturn(Optional.of(alreadyOut));
        assertThat(engine(NOW, 3).desert(1, 101L, outbound)).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
        verify(store, never()).save(anyString(), any());
        assertThat(outbound).isEmpty();

        when(store.load("room-1")).thenReturn(Optional.of(threeSeats));
        assertThat(engine(NOW, 3).desert(2, 102L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_CONTINUES);
        verify(store).save(eq("room-1"), argThat(saved -> !saved.alive(2) && !saved.ended()));
        assertThat(outbound).isNotEmpty();
        assertThat(published).isEmpty();

        outbound.clear();
        when(store.load("room-1")).thenReturn(Optional.of(twoSeats));
        assertThat(engine(NOW, 2).desert(1, 101L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
        assertThat(outbound).last().isInstanceOf(OneCardEvent.MatchEnded.class);
        assertThat(published).singleElement().isInstanceOfSatisfying(OneCardMatchCompleted.class,
                done -> assertThat(done.result().reason()).isEqualTo(EndReason.LAST_STANDING));
    }

    @Test
    void views_and_queries_read_the_state_with_the_clock() {
        OneCardState raced = raced(engine(NOW, 3));
        OneCardGameEngine later = engine(NOW + 1_000, 3);

        assertThat(later.phaseName(raced)).isEqualTo("RACE");
        assertThat(later.pendingSeats(raced)).isEmpty();
        assertThat(later.isRoundOver(raced)).isFalse();
        assertThat(((TableView) later.publicView(raced)).race().remainingMillis()).isEqualTo(2_000);
        assertThat(later.privateView(raced, 0)).contains(new PrivateView(0, List.of(club(3)), raced.version()));
        assertThat(later.legalActions(raced, 0)).hasSize(1);

        when(store.load("room-1")).thenReturn(Optional.empty());
        assertThat(later.isMatchOver()).isFalse();
    }

    @Test
    void foreign_state_or_action_types_are_rejected() {
        OneCardGameEngine adapter = engine(NOW, 3);

        assertThatThrownBy(() -> adapter.phaseName(mock(GameState.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.apply(aboutToGoDownToOne(), 0, mock(GameAction.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D-128 — 카탈로그 메타데이터, 설정에서 오는 공개 상태·경쟁 창, 엔진 팩토리. */
class OneCardGameDefinitionTest {

    private static OneCardGameDefinition definition(GameStatus status, long window, long ownerMin, long ownerMax,
                                                    long catcherMin, long catcherMax) {
        return new OneCardGameDefinition(mock(OneCardStateStore.class), Clock.systemUTC(), event -> { },
                status, window, ownerMin, ownerMax, catcherMin, catcherMax);
    }

    private static OneCardGameDefinition defaults() {
        return definition(GameStatus.COMING_SOON, 3_000, 1_000, 2_500, 1_000, 2_500);
    }

    @Test
    void the_catalog_entry_is_a_two_to_six_player_game_without_room_options() {
        OneCardGameDefinition def = defaults();

        assertThat(def.id()).isEqualTo("ONE_CARD");
        assertThat(def.displayName()).isEqualTo("원카드");
        assertThat(def.minPlayers()).isEqualTo(2);
        assertThat(def.maxPlayers()).isEqualTo(6);
        assertThat(def.status()).isEqualTo(GameStatus.COMING_SOON);
        assertThat(def.supportedRoomOptions()).isEmpty();
        assertThat(def.supportsRematch()).isFalse();
    }

    @Test
    void status_and_race_timing_come_from_configuration_with_the_protocol_slot_count() {
        OneCardGameDefinition def = definition(GameStatus.AVAILABLE, 2_000, 100, 200, 300, 400);

        assertThat(def.status()).isEqualTo(GameStatus.AVAILABLE);
        assertThat(def.raceSettings()).isEqualTo(new RaceSettings(2_000, 100, 200, 300, 400, 8));
    }

    @Test
    void a_broken_race_configuration_fails_naming_the_condition() {
        assertThatThrownBy(() -> definition(GameStatus.COMING_SOON, 3_000, 2_600, 2_500, 1_000, 2_500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner reaction range");
    }

    @Test
    void the_factory_builds_a_port_adapter_for_the_room() {
        GameEngine engine = defaults().newEngine(new GameContext("room-9", List.of(1L, 2L)));

        assertThat(engine).isInstanceOf(OneCardGameEngine.class);
        assertThat(engine.context().roomId()).isEqualTo("room-9");
        assertThat(engine.actionType()).isEqualTo(OneCardAction.class);
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/RaceSettingsTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** D-128 — 경쟁 창 설정은 운영 설정에서 오므로, 틀린 값은 기동 때 어느 조건인지 말하며 실패해야 한다. */
class RaceSettingsTest {

    @Test
    void the_default_is_a_three_second_window_with_bots_reacting_in_one_to_two_and_a_half_seconds() {
        assertThat(RaceSettings.DEFAULT).isEqualTo(new RaceSettings(3_000, 1_000, 2_500, 1_000, 2_500, 8));
    }

    @Test
    void each_broken_condition_is_named_in_the_error() {
        assertThatThrownBy(() -> new RaceSettings(0, 1_000, 2_500, 1_000, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windowMillis");
        assertThatThrownBy(() -> new RaceSettings(3_000, 1_000, 2_500, 1_000, 2_500, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slotCount");
        assertThatThrownBy(() -> new RaceSettings(3_000, -1, 2_500, 1_000, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner reaction range");
        assertThatThrownBy(() -> new RaceSettings(3_000, 2_600, 2_500, 1_000, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owner reaction range");
        assertThatThrownBy(() -> new RaceSettings(3_000, 1_000, 2_500, -1, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catcher reaction range");
        assertThatThrownBy(() -> new RaceSettings(3_000, 1_000, 2_500, 2_600, 2_500, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catcher reaction range");
    }

    @Test
    void equal_bounds_mean_a_fixed_reaction_time() {
        assertThatNoException().isThrownBy(() -> new RaceSettings(3_000, 1_200, 1_200, 1_500, 1_500, 8));
    }
}
```

`server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java`:

```diff
                 .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
     }
 
-    /** D-102 — 카탈로그 2게임 체제. 정렬은 status → displayName 이라 "스컬킹" 이 먼저다. */
+    /**
+     * D-102 — 정렬은 status → displayName 이라 "스컬킹" 이 먼저다. D-128 — 원카드는 클라 게임판(S4) 전까지
+     * COMING_SOON 이라 AVAILABLE 두 게임 뒤에 온다.
+     */
     @Test
-    void catalog_returns_both_games_when_authenticated() throws Exception {
+    void catalog_lists_every_game_when_authenticated() throws Exception {
         String token = authenticate();
 
         mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.games").isArray())
-                .andExpect(jsonPath("$.games.length()").value(2))
+                .andExpect(jsonPath("$.games.length()").value(3))
                 .andExpect(jsonPath("$.games[0].id").value("SKULL_KING"))
                 .andExpect(jsonPath("$.games[0].displayName").value("스컬킹"))
                 .andExpect(jsonPath("$.games[0].minPlayers").value(2))
```

```diff
                 .andExpect(jsonPath("$.games[1].supportedRoomOptions[0]").value("TARGET_SCORE"))
                 .andExpect(jsonPath("$.games[1].supportedRoomOptions[1]").value("TEAMS"))
                 .andExpect(jsonPath("$.games[1].supportedRoomOptions[2]").value("BETTING"))
-                .andExpect(jsonPath("$.games[1].status").value("AVAILABLE"));
+                .andExpect(jsonPath("$.games[1].status").value("AVAILABLE"))
+                .andExpect(jsonPath("$.games[2].id").value("ONE_CARD"))
+                .andExpect(jsonPath("$.games[2].displayName").value("원카드"))
+                .andExpect(jsonPath("$.games[2].minPlayers").value(2))
+                .andExpect(jsonPath("$.games[2].maxPlayers").value(6))
+                .andExpect(jsonPath("$.games[2].supportedRoomOptions.length()").value(0))
+                .andExpect(jsonPath("$.games[2].status").value("COMING_SOON"));
     }
 
     @Test
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`OneCardGameEngine`·`OneCardGameDefinition`·
`OneCardMatchCompleted` 가 아직 없다).

- [ ] **Step 3: 구현 — 설정과 경쟁 창 설정 검증**

`server/src/main/java/com/mirboard/domain/game/onecard/RaceSettings.java`:

```diff
 package com.mirboard.domain.game.onecard;
 
 /**
- * 외치기 경쟁의 시간 설정 (`docs/rules-onecard.md` §9). 운영값은 S3 에서 설정으로 주입한다.
+ * 외치기 경쟁의 시간 설정 (`docs/rules-onecard.md` §9). 운영값은 {@code mirboard.onecard.*} 설정에서 온다(D-128,
+ * {@code OneCardGameDefinition}) — 잘못된 값이면 기동 때 어느 조건이 틀렸는지 말하며 실패한다.
  *
  * @param windowMillis     창 길이
  * @param ownerMinMillis   1장 남은 봇이 "원카드!" 를 누르는 반응 시간 하한
```

```diff
     public static final RaceSettings DEFAULT = new RaceSettings(3_000, 1_000, 2_500, 1_000, 2_500, 8);
 
     public RaceSettings {
-        if (windowMillis <= 0 || slotCount < 1
-                || ownerMinMillis < 0 || ownerMinMillis > ownerMaxMillis
-                || catcherMinMillis < 0 || catcherMinMillis > catcherMaxMillis) {
-            throw new IllegalArgumentException("invalid race settings");
+        require(windowMillis > 0, "windowMillis must be positive: " + windowMillis);
+        require(slotCount >= 1, "slotCount must be at least 1: " + slotCount);
+        require(ownerMinMillis >= 0 && ownerMinMillis <= ownerMaxMillis,
+                "owner reaction range must be 0 <= min <= max: " + ownerMinMillis + ".." + ownerMaxMillis);
+        require(catcherMinMillis >= 0 && catcherMinMillis <= catcherMaxMillis,
+                "catcher reaction range must be 0 <= min <= max: " + catcherMinMillis + ".." + catcherMaxMillis);
+    }
+
+    private static void require(boolean valid, String message) {
+        if (!valid) {
+            throw new IllegalArgumentException("invalid race settings — " + message);
         }
     }
 }
```

`server/src/main/resources/application.yml`:

```diff
       stomp-default:
         limit: ${MIRBOARD_RATELIMIT_STOMP_LIMIT:60}
         window: 10s
+  onecard:
+    # D-128 — 원카드. 클라 게임판(S4) 전에는 카탈로그에 "준비 중"으로만 보이고 방을 만들 수 없다.
+    # S4 에서 AVAILABLE 로 바꾼다(통합 테스트는 AVAILABLE 로 켜서 방을 만든다).
+    status: ${MIRBOARD_ONECARD_STATUS:COMING_SOON}
+    # "원카드!/잡기!" 경쟁 창 길이와 봇 반응 시간 구간(ms) — 창마다 봇별로 균등 추첨(룰 §9).
+    race-window-millis: 3000
+    bot-reaction-owner-min-millis: 1000
+    bot-reaction-owner-max-millis: 2500
+    bot-reaction-catcher-min-millis: 1000
+    bot-reaction-catcher-max-millis: 2500
   moderation:
     # D-86 — 채팅 금칙어(같은 길이 '*' 마스킹, 대소문자 무시). 운영자가 콤마로 커스터마이즈.
     # 자모 분리 우회 등 고급 회피 대응은 범위 밖. 비우면 마스킹 없음. (예시: 비속어)
```

- [ ] **Step 4: 구현 — 기록 이벤트·어댑터·정의**

`server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardMatchCompleted.java`:

```java
package com.mirboard.domain.game.onecard.event;

import com.mirboard.domain.game.onecard.state.MatchResult;
import java.util.List;

/**
 * D-128 — 원카드 매치가 끝났다(누군가 다 냄·탈락으로 한 명·사람 없음·교착). 매치 기록·게임별 전적
 * ({@code OneCardMatchRecorder})이 듣는다.
 *
 * <p>클라에 나가는 {@link OneCardEvent.MatchEnded} 와 달리 서버 내부 이벤트라 좌석→유저 매핑을 함께 싣는다.
 * 로컬 발행만 한다 — 인스턴스 간 전파를 하면 각 인스턴스가 다시 기록해 같은 매치가 중복으로 남는다.
 *
 * @param playerIds 좌석 순서대로의 유저 id (봇 포함)
 * @param result    종료 사유와 순위표 (`docs/rules-onecard.md` §11)
 */
public record OneCardMatchCompleted(String roomId, List<Long> playerIds, MatchResult result) {

    public OneCardMatchCompleted {
        playerIds = List.copyOf(playerIds);
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java`:

```java
package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-128 — 원카드의 {@link GameEngine} 포트 어댑터. 방 하나에 대응하는 per-room 인스턴스로
 * {@link OneCardGameDefinition#newEngine} 이 만든다. 순수 룰 엔진 {@link OneCardEngine}(D-127)을 감싸 상태
 * I/O·시계·뷰·매치 기록 발행을 붙인다 — 스컬킹과 같은 2계층.
 *
 * <p><b>시계는 여기에만 있다.</b> 순수 엔진은 경쟁 창을 여는 순간의 시각을 인자로 받고 창이 저절로 닫히는
 * 시각을 계산할 뿐이다. 어댑터가 {@link Clock} 으로 그 시각을 넣고, 포트의 엔진 타이머(D-128)에는 "지금부터
 * 남은 시간"으로 바꿔 넘긴다.
 *
 * <p>1판 = 1매치 = 1라운드라 라운드가 끝나면 곧 매치가 끝난다. 본 클래스는 상태를 갖지 않는다(저장소 참조만)
 * — 동시성 직렬화는 호출자의 방 액션 락이 맡는다.
 */
public final class OneCardGameEngine implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(OneCardGameEngine.class);

    private final GameContext context;
    private final OneCardEngine rules;
    private final OneCardStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public OneCardGameEngine(GameContext context,
                             OneCardStateStore stateStore,
                             Clock clock,
                             Random random,
                             RaceSettings raceSettings,
                             ApplicationEventPublisher publisher) {
        this.context = context;
        this.rules = new OneCardEngine(context, random, raceSettings);
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Override
    public GameContext context() {
        return context;
    }

    // ---------- 상태 I/O ----------

    @Override
    public Optional<GameState> loadState() {
        return stateStore.load(context.roomId()).map(GameState.class::cast);
    }

    @Override
    public void saveState(GameState state) {
        stateStore.save(context.roomId(), ocState(state));
    }

    // ---------- 액션 ----------

    @Override
    public Class<? extends GameAction> actionType() {
        return OneCardAction.class;
    }

    /** 사람·봇·타임아웃 공용. 지금 시각은 경쟁 창을 열 때만 쓰인다. */
    @Override
    public Result apply(GameState state, int seat, GameAction action) {
        OneCardEngine.Result result = rules.apply(ocState(state), seat, ocAction(action), clock.millis());
        return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
    }

    // ---------- 단계 / 진행 질의 ----------

    @Override
    public String phaseName(GameState state) {
        return ocState(state).phaseName();
    }

    @Override
    public List<Integer> pendingSeats(GameState state) {
        return rules.pendingSeats(ocState(state));
    }

    /** 1판 = 1라운드 — 라운드가 끝났다는 것은 매치가 끝났다는 것이다. */
    @Override
    public boolean isRoundOver(GameState state) {
        return ocState(state).ended();
    }

    @Override
    public boolean isMatchOver() {
        return stateStore.load(context.roomId()).map(OneCardState::ended).orElse(false);
    }

    // ---------- 뷰 ----------

    @Override
    public Object publicView(GameState state) {
        return OneCardStateMapper.toTableView(ocState(state), clock.millis());
    }

    @Override
    public Optional<Object> privateView(GameState state, int seat) {
        return Optional.of(OneCardStateMapper.toPrivateView(ocState(state), seat));
    }

    // ---------- 봇 / 타임아웃 ----------

    @Override
    public List<GameAction> legalActions(GameState state, int seat) {
        return List.<GameAction>copyOf(rules.legalActions(ocState(state), seat));
    }

    @Override
    public GameAction timeoutAction(GameState state, int seat) {
        return rules.timeoutAction(ocState(state), seat);
    }

    // ---------- 엔진 타이머 (D-128) ----------

    /** 경쟁 창이 저절로 닫히기까지 남은 시간 — 봇 누름 또는 창 끝. 이미 지났으면 0. */
    @Override
    public Optional<Duration> timer(GameState state) {
        var deadline = rules.timerDeadline(ocState(state));
        if (deadline.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.max(0L, deadline.getAsLong() - clock.millis())));
    }

    @Override
    public Optional<Result> onTimer(GameState state) {
        return rules.onTimer(ocState(state))
                .map(result -> new Result(result.newState(), List.<GameEvent>copyOf(result.events())));
    }

    // ---------- 매치 진행 ----------

    /**
     * 이번 전이가 매치를 끝냈으면(나가는 이벤트에 {@code MATCH_ENDED} 가 있으면) 기록 이벤트를 한 번 발행한다.
     * 이미 끝난 상태로 다시 불려도 이벤트가 없으므로 두 번 기록하지 않는다.
     */
    @Override
    public Advance advance(GameState newState, List<GameEvent> outbound) {
        OneCardState state = ocState(newState);
        boolean endedNow = state.ended() && outbound.stream().anyMatch(OneCardEvent.MatchEnded.class::isInstance);
        if (!endedNow) {
            return Advance.NONE;
        }
        record(state.result());
        return new Advance(true, true);
    }

    /**
     * 탈주 (§10) — 파산과 같은 탈락 경로. 순수 엔진의 3치 결과를 포트 3치로 옮긴다. 이미 끝났거나 이미 탈락한
     * 좌석이면 상태 무변경으로 {@code NOT_APPLICABLE}.
     */
    @Override
    public DesertOutcome desert(int seat, long deserterUserId, List<GameEvent> outbound) {
        OneCardState state = stateStore.load(context.roomId()).orElse(null);
        if (state == null) {
            return DesertOutcome.NOT_APPLICABLE;
        }
        OneCardEngine.Desertion desertion = rules.desert(state, seat);
        switch (desertion.outcome()) {
            case NOT_APPLICABLE -> {
                return DesertOutcome.NOT_APPLICABLE;
            }
            case CONTINUED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                log.warn("OneCard desertion continued: room={} seat={} userId={}",
                        context.roomId(), seat, deserterUserId);
                return DesertOutcome.MATCH_CONTINUES;
            }
            case MATCH_ENDED -> {
                stateStore.save(context.roomId(), desertion.newState());
                outbound.addAll(desertion.events());
                record(desertion.newState().result());
                log.warn("OneCard desertion ended match: room={} seat={} userId={} reason={}",
                        context.roomId(), seat, deserterUserId, desertion.newState().result().reason());
                return DesertOutcome.MATCH_ENDED;
            }
        }
        throw new IllegalStateException("Unreachable desert outcome");
    }

    // ---------- internals ----------

    /** 로컬 발행 — 기록기({@code OneCardMatchRecorder})가 듣는다. */
    private void record(MatchResult result) {
        publisher.publishEvent(new OneCardMatchCompleted(context.roomId(), context.playerIds(), result));
        log.info("OneCard match ended: room={} reason={} winners={}",
                context.roomId(), result.reason(), result.winners());
    }

    private static OneCardState ocState(GameState state) {
        if (state instanceof OneCardState oc) {
            return oc;
        }
        throw new IllegalArgumentException("Not a OneCardState: "
                + (state == null ? "null" : state.getClass().getName()));
    }

    private static OneCardAction ocAction(GameAction action) {
        if (action instanceof OneCardAction oc) {
            return oc;
        }
        throw new IllegalArgumentException("Not a OneCardAction: "
                + (action == null ? "null" : action.getClass().getName()));
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`:

```java
package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 원카드의 카탈로그 메타데이터 + 엔진 팩토리 (D-128). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·
 * 디스패치 수정 없이 이 Bean 등록으로 인게임까지 연결된다(D-102 가 실증한 약속).
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.onecard.status}, 기본 {@code COMING_SOON}). 클라 게임판(S4)
 * 전에는 카탈로그에 "준비 중"으로만 보이고 방을 만들 수 없다({@code RoomService} 가 AVAILABLE 만 허용).
 * 통합 테스트는 AVAILABLE 로 켜서 방을 만든다.
 *
 * <p>경쟁 창 길이와 봇 반응 구간도 설정에서 읽는다(룰 §9). 슬롯 수는 클라와 맞춘 프로토콜 상수라 열지 않는다.
 * {@code supportedRoomOptions()} 는 재정의하지 않는다 — 목표 점수·팀·내기를 쓰지 않는 개인전이다(D-106).
 */
@Component
public final class OneCardGameDefinition implements GameDefinition {

    public static final String ID = "ONE_CARD";

    private final OneCardStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;
    private final RaceSettings raceSettings;
    private final SecureRandom random = new SecureRandom();

    public OneCardGameDefinition(
            OneCardStateStore stateStore,
            Clock clock,
            ApplicationEventPublisher publisher,
            @Value("${mirboard.onecard.status:COMING_SOON}") GameStatus status,
            @Value("${mirboard.onecard.race-window-millis:3000}") long raceWindowMillis,
            @Value("${mirboard.onecard.bot-reaction-owner-min-millis:1000}") long ownerMinMillis,
            @Value("${mirboard.onecard.bot-reaction-owner-max-millis:2500}") long ownerMaxMillis,
            @Value("${mirboard.onecard.bot-reaction-catcher-min-millis:1000}") long catcherMinMillis,
            @Value("${mirboard.onecard.bot-reaction-catcher-max-millis:2500}") long catcherMaxMillis) {
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
        this.status = status;
        this.raceSettings = new RaceSettings(raceWindowMillis, ownerMinMillis, ownerMaxMillis,
                catcherMinMillis, catcherMaxMillis, RaceSettings.DEFAULT.slotCount());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "원카드";
    }

    @Override
    public String shortDescription() {
        return "2~6인 손패 털기. 공격을 쌓아 넘기고, 한 장 남으면 누구보다 먼저 \"원카드!\"를 외친다.";
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public int maxPlayers() {
        return 6;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    /** 설정에서 만든 경쟁 창 설정 — 라운드 시작({@code OneCardRoundStarter})도 같은 값을 쓴다. */
    public RaceSettings raceSettings() {
        return raceSettings;
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new OneCardGameEngine(ctx, stateStore, clock, random, raceSettings, publisher);
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 원카드 테스트 155건(어댑터 8 · 정의 4 · 경쟁 창 설정 3 추가).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.rest.games.GameCatalogIntegrationTest"`
Expected: PASS — 4건. 카탈로그가 `SKULL_KING`·`TICHU`(AVAILABLE) 다음에 `ONE_CARD`(COMING_SOON)를 싣는다.

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java \
  server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java \
  server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardMatchCompleted.java \
  server/src/main/java/com/mirboard/domain/game/onecard/RaceSettings.java \
  server/src/main/resources/application.yml \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineTest.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java \
  server/src/test/java/com/mirboard/domain/game/onecard/RaceSettingsTest.java \
  server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java
git commit -m "feat(D-128): 원카드 포트 어댑터·게임 정의(COMING_SOON)·경쟁 창 설정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: 라운드 시작

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/lifecycle/OneCardRoundStarter.java`
- Test: `server/src/test/java/com/mirboard/domain/game/onecard/lifecycle/OneCardRoundStarterTest.java`

**Interfaces:**
- Consumes: `GameStartingEvent(roomId, gameType, playerIds, targetScore)`, `OneCardEngine.startMatch()`, Task 2 저장소,
  `infra.bot.BotScheduler.scheduleBots(roomId)`, `infra.bot.TurnTimeoutScheduler.onTurnAdvanced(roomId)`.
- Produces: `OneCardRoundStarter` `@Component` — `@EventListener onGameStarting(GameStartingEvent)`. `ONE_CARD` 이고 2~6인
  이면 분배해 저장한 뒤 봇 루프·타이머를 건다(시작 이벤트는 브로드캐스트하지 않는다 — 스컬킹과 같다). 테스트용 생성자
  `(OneCardStateStore, Random, BotScheduler, TurnTimeoutScheduler)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/lifecycle/OneCardRoundStarterTest.java`:

```java
package com.mirboard.domain.game.onecard.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.onecard.Dealer;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** D-128 — 방이 게임을 시작하면 원카드 판을 나눠 저장하고 봇 루프·타이머를 건다. */
class OneCardRoundStarterTest {

    private final OneCardStateStore store = mock(OneCardStateStore.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final TurnTimeoutScheduler timers = mock(TurnTimeoutScheduler.class);

    private OneCardRoundStarter starter(long seed) {
        return new OneCardRoundStarter(store, new Random(seed), bots, timers);
    }

    private static GameStartingEvent event(String gameType, int players) {
        List<Long> ids = LongStream.range(0, players).map(i -> 100 + i).boxed().toList();
        return new GameStartingEvent("room-1", gameType, ids, 0);
    }

    @Test
    void a_one_card_game_is_dealt_saved_and_handed_to_the_bots_and_timers() {
        starter(1).onGameStarting(event("ONE_CARD", 4));

        ArgumentCaptor<OneCardState> saved = ArgumentCaptor.forClass(OneCardState.class);
        InOrder order = inOrder(store, bots, timers);
        order.verify(store).save(eq("room-1"), saved.capture());
        order.verify(bots).scheduleBots("room-1");
        order.verify(timers).onTurnAdvanced("room-1");
        OneCardState state = saved.getValue();
        assertThat(state.hands()).hasSize(4).allSatisfy(h -> assertThat(h).hasSize(Dealer.HAND_SIZE));
        assertThat(state.topCard().isNormal()).isTrue();
        assertThat(state.turnSeat()).isBetween(0, 3);
        OneCardInvariantChecker.check(state);
    }

    @Test
    void the_same_seed_deals_the_same_table() {
        ArgumentCaptor<OneCardState> first = ArgumentCaptor.forClass(OneCardState.class);
        ArgumentCaptor<OneCardState> second = ArgumentCaptor.forClass(OneCardState.class);

        starter(9).onGameStarting(event("ONE_CARD", 3));
        verify(store).save(anyString(), first.capture());
        OneCardStateStore other = mock(OneCardStateStore.class);
        new OneCardRoundStarter(other, new Random(9), bots, timers).onGameStarting(event("ONE_CARD", 3));
        verify(other).save(anyString(), second.capture());

        assertThat(second.getValue()).isEqualTo(first.getValue());
    }

    @Test
    void other_games_are_ignored() {
        starter(1).onGameStarting(event("TICHU", 4));

        verifyNoInteractions(store, bots, timers);
    }

    @Test
    void seat_counts_outside_two_to_six_are_skipped() {
        starter(1).onGameStarting(event("ONE_CARD", 7));

        verify(store, never()).save(anyString(), any());
        verifyNoInteractions(bots, timers);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`OneCardRoundStarter`).

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/domain/game/onecard/lifecycle/OneCardRoundStarter.java`:

```java
package com.mirboard.domain.game.onecard.lifecycle;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.onecard.Dealer;
import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.security.SecureRandom;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 원카드 판을 시작한다 (D-128) — 분배·시작 카드·첫 차례(룰 §2·§3). 1판 =
 * 1매치라 이후 라운드가 없다.
 *
 * <p>스컬킹처럼 시작 이벤트는 브로드캐스트하지 않는다 — 클라는 게임판에 들어오며 resync 로 상태를 받는다.
 * 저장 뒤 봇 루프와 턴·엔진 타이머를 건다.
 */
@Component
public class OneCardRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(OneCardRoundStarter.class);

    private final OneCardStateStore stateStore;
    private final Random random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;

    @Autowired
    public OneCardRoundStarter(OneCardStateStore stateStore,
                               @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                               @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this(stateStore, new SecureRandom(), botScheduler, turnTimeout);
    }

    /** 테스트 전용 진입점 (결정적 분배·첫 차례). */
    public OneCardRoundStarter(OneCardStateStore stateStore,
                               Random random,
                               com.mirboard.infra.bot.BotScheduler botScheduler,
                               com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this.stateStore = stateStore;
        this.random = random;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!OneCardGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < Dealer.MIN_SEATS || seatCount > Dealer.MAX_SEATS) {
            log.warn("OneCard needs {}~{} players, got {} — skipping room={}",
                    Dealer.MIN_SEATS, Dealer.MAX_SEATS, seatCount, event.roomId());
            return;
        }

        // 시작에는 경쟁 창이 없어 창 설정이 필요 없다 — 분배와 첫 차례만 난수를 쓴다.
        OneCardEngine engine = new OneCardEngine(new GameContext(event.roomId(), event.playerIds()), random);
        OneCardState state = engine.startMatch().newState();
        stateStore.save(event.roomId(), state);

        log.info("OneCard match started: room={} seats={} firstSeat={} startCard={}",
                event.roomId(), seatCount, state.turnSeat(), state.topCard());
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 원카드 테스트 159건(라운드 시작 4 추가).

- [ ] **Step 5: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/lifecycle/OneCardRoundStarter.java \
  server/src/test/java/com/mirboard/domain/game/onecard/lifecycle/OneCardRoundStarterTest.java
git commit -m "feat(D-128): 원카드 라운드 시작 — 분배·저장·봇/타이머 무장

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 5: 휴리스틱 봇

**Files:**
- Create: `server/src/main/java/com/mirboard/domain/game/onecard/bot/OneCardBotView.java`, `server/src/main/java/com/mirboard/domain/game/onecard/bot/OneCardBotPolicy.java`
- Modify: `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java`(`botAction` + 안전망)
- Test: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardBotPolicyTest.java`, `server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineBotActionTest.java`, `server/src/test/java/com/mirboard/domain/game/onecard/bot/OneCardBotStrengthTest.java`

**Interfaces:**
- Consumes: Task 3 어댑터, S2 `TurnOrder.nextAlive`·`OneCardAction`·`PlayingCard` 판정 메서드.
- Produces: `OneCardBotView(int seat, List<PlayingCard> hand, PlayingCard topCard, Suit baseSuit, int attackStack,
  List<Integer> handCounts, int nextSeat)` + `of(OneCardState, int seat)`·`underAttack()`·`nextHandCount()`.
  `OneCardBotPolicy.choose(OneCardBotView, List<OneCardAction> legal) → OneCardAction`(결정적). 어댑터 `botAction` 은
  정책을 타고(난수 무시), 경쟁 창·남의 차례면 null, 정책 실패·비합법이면 먹기(`timeoutAction`)로 떨어진다 —
  package-private `chooseBotAction(OneCardState, int, BiFunction<…>)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardBotPolicyTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.BLACK_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.COLOR_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.bot.OneCardBotPolicy;
import com.mirboard.domain.game.onecard.bot.OneCardBotView;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** D-128 — 휴리스틱 봇의 수 선택(설계서 §4.6)과 봇이 보는 정보의 경계. */
class OneCardBotPolicyTest {

    private static OneCardAction choose(OneCardState state, int seat) {
        List<Long> ids = LongStream.range(0, state.seatCount()).map(i -> 100 + i).boxed().toList();
        OneCardEngine engine = new OneCardEngine(new GameContext("bot", ids), new Random(1));
        return OneCardBotPolicy.choose(OneCardBotView.of(state, seat), engine.legalActions(state, seat));
    }

    /** 다음 사람(좌석 1)이 넉넉히 든 3인 테이블 — 압박할 이유가 없다. */
    private static OneCardTables calm(PlayingCard... mine) {
        return seats(hand(mine), hand(spade(10), club(10), diamond(10), heart(10)),
                hand(club(4), club(6), club(8)));
    }

    @Test
    void under_attack_the_weakest_counter_is_played() {
        OneCardState state = seats(hand(BLACK_JOKER, spade(2), heart(9)), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(spade(2)));
    }

    @Test
    void under_attack_without_a_counter_the_bot_draws() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)))
                .top(heart(2)).attack(2).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(new Draw());
    }

    @Test
    void a_number_card_goes_before_specials_attacks_and_jokers() {
        OneCardState state = calm(heart(PlayingCard.ACE), heart(PlayingCard.KING), COLOR_JOKER, heart(9))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(heart(9)));
    }

    @Test
    void among_number_cards_the_suit_the_bot_holds_most_goes_first() {
        OneCardState state = calm(heart(9), spade(5), spade(6), spade(8))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(spade(5)));
    }

    @Test
    void a_next_player_close_to_finishing_meets_an_attack_first() {
        OneCardState state = seats(hand(heart(9), heart(PlayingCard.JACK), heart(2)), hand(spade(4)),
                hand(club(4), club(6), club(8)))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(heart(2)));
    }

    @Test
    void without_an_attack_card_a_jack_holds_the_threat_back() {
        OneCardState state = seats(hand(heart(9), heart(PlayingCard.JACK)), hand(spade(4), spade(6)),
                hand(club(4), club(6), club(8)))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(PlayCard.of(heart(PlayingCard.JACK)));
    }

    @Test
    void a_seven_names_the_suit_the_bot_holds_most() {
        OneCardState state = calm(heart(7), spade(4), spade(6), club(3))
                .top(heart(5)).turn(0).build();

        assertThat(choose(state, 0)).isEqualTo(new PlayCard(heart(7), Suit.SPADE));
    }

    @Test
    void the_view_holds_the_bots_own_hand_public_counts_and_the_next_live_seat() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6)), hand(),
                hand(diamond(4), diamond(6), diamond(8)))
                .eliminated(2, Elimination.Reason.BANKRUPT, 20).top(heart(5)).direction(-1).turn(0).build();

        OneCardBotView view = OneCardBotView.of(state, 0);

        assertThat(view.hand()).containsExactly(heart(9), club(3));
        assertThat(view.handCounts()).containsExactly(2, 2, 0, 3);
        assertThat(view.nextSeat()).isEqualTo(3);
        assertThat(view.nextHandCount()).isEqualTo(3);
        assertThat(view.baseSuit()).isEqualTo(Suit.HEART);
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineBotActionTest.java`:

```java
package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.Draw;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.bot.OneCardBotPolicy;
import com.mirboard.domain.game.onecard.bot.OneCardBotView;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-128 — 어댑터의 봇 배선. 봇은 휴리스틱을 타고(난수 무시), 정책이 실패해도 먹기로 떨어져 방이 멈추지
 * 않으며, 경쟁 창·남의 차례에는 두지 않는다. 저장소가 필요 없는 경로라 Docker 없이 돈다.
 */
class OneCardGameEngineBotActionTest {

    private static final GameContext CONTEXT = new GameContext("bot-adapter", List.of(10L, 11L, 12L));

    private final OneCardGameEngine adapter = new OneCardGameEngine(CONTEXT, null, Clock.systemUTC(),
            new Random(3), RaceSettings.DEFAULT, event -> { });
    private final OneCardEngine rules = new OneCardEngine(CONTEXT, new Random(3));

    private static OneCardState onTurn() {
        return seats(hand(heart(9), club(3), spade(5)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
    }

    @Test
    void the_bot_follows_the_heuristic_whatever_the_random() {
        OneCardState state = onTurn();
        OneCardAction expected = OneCardBotPolicy.choose(OneCardBotView.of(state, 0), rules.legalActions(state, 0));

        for (long seed : new long[] {1L, 2L, 3L}) {
            assertThat(adapter.botAction(state, 0, new Random(seed))).as("Random(%d)", seed).isEqualTo(expected);
        }
    }

    @Test
    void a_failing_or_illegal_policy_falls_back_to_drawing() {
        OneCardState state = onTurn();

        assertThat(adapter.chooseBotAction(state, 0, (view, legal) -> {
            throw new IllegalStateException("boom");
        })).isEqualTo(new Draw());
        assertThat(adapter.chooseBotAction(state, 0, (view, legal) -> null)).isEqualTo(new Draw());
        assertThat(adapter.chooseBotAction(state, 0, (view, legal) -> PlayCard.of(spade(4)))).isEqualTo(new Draw());
    }

    @Test
    void the_bot_does_not_move_during_a_race_or_out_of_turn() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
        OneCardState raced = rules.apply(state, 0, PlayCard.of(heart(9)), 0).newState();

        assertThat(adapter.botAction(raced, 0, new Random(1))).isNull();
        assertThat(adapter.botAction(raced, 1, new Random(1))).isNull();
        assertThat(adapter.botAction(onTurn(), 2, new Random(1))).isNull();
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/bot/OneCardBotStrengthTest.java`:

```java
package com.mirboard.domain.game.onecard.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-128 — 봇 강도 평가 (Docker 불필요). 고정 시드 판을 돌려 "휴리스틱이 포트 기본 봇(합법수 균등 무작위)을
 * 확실히 이기는가"를 숫자로 강제한다(스컬킹 D-119 방식).
 *
 * <p>잴 것은 수 선택이라 외치기 경쟁은 늘 주인이 먼저 외친다(벌칙 없음). 초점 좌석은 판마다 돌려 좌석 이점을
 * 지운다. 하한은 실측 − 3·SE 아래로 잡았고, 대조군(초점도 무작위)이 같은 하한을 넘지 못한다는 것으로 하한이
 * 의미 있음을 보인다. 측정값은 INFO 로그로 남는다.
 */
class OneCardBotStrengthTest {

    private static final Logger log = LoggerFactory.getLogger(OneCardBotStrengthTest.class);

    private static final long BASE = 20261004L;
    private static final int GAMES = 1_000;
    /** 차례 상한(600) + 경쟁 창. 넘으면 끝나지 않는 판이다. */
    private static final int STEP_GUARD = 3 * OneCardEngine.TURN_LIMIT;

    /** 휴리스틱 1명 대 무작위 3명 — 기준 0.25. 실측 0.919 (SE 0.009), 대조군 0.245. */
    private static final double MIN_SHARE_FOUR = 0.85;
    /** 휴리스틱 대 무작위 1:1 — 기준 0.5. 실측 0.960 (SE 0.006), 대조군 0.485. */
    private static final double MIN_SHARE_TWO = 0.9;
    /** 휴리스틱 1명 대 탐욕 3명 — 기준 0.25. 실측 0.356 (SE 0.015), 대조군 0.268. */
    private static final double MIN_SHARE_GREEDY = 0.31;

    private enum Policy {
        /** {@link OneCardBotPolicy}. */
        HEURISTIC,
        /** 포트 기본 봇 — 먹기를 포함한 합법수 균등 무작위. */
        RANDOM,
        /** 낼 수 있으면 아무 카드나 무작위로 내고, 못 낼 때만 먹는다. */
        GREEDY
    }

    private static List<Long> ids(int seats) {
        return LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
    }

    private static OneCardAction pick(Policy policy, OneCardState state, int seat, List<OneCardAction> legal,
                                      Random random) {
        return switch (policy) {
            case HEURISTIC -> OneCardBotPolicy.choose(OneCardBotView.of(state, seat), legal);
            case RANDOM -> legal.get(random.nextInt(legal.size()));
            case GREEDY -> {
                List<OneCardAction> plays = legal.stream()
                        .filter(OneCardAction.PlayCard.class::isInstance).toList();
                yield plays.isEmpty() ? new OneCardAction.Draw() : plays.get(random.nextInt(plays.size()));
            }
        };
    }

    /** 초점 좌석이 1등(동순위 포함)인 판의 비율. */
    private static double focalWinShare(int seats, Policy focalPolicy, Policy otherPolicy) {
        int wins = 0;
        for (int game = 0; game < GAMES; game++) {
            int focal = game % seats;
            Random others = new Random(BASE * 31 + game);
            OneCardEngine engine = new OneCardEngine(new GameContext("eval", ids(seats)), new Random(BASE + game));
            OneCardState state = engine.startMatch().newState();
            for (int step = 0; step < STEP_GUARD && !state.ended(); step++) {
                RaceWindow race = state.race();
                if (race != null) {
                    state = engine.apply(state, race.ownerSeat(),
                            new OneCardAction.CallOneCard(race.raceId()), 0).newState();
                    continue;
                }
                int seat = state.turnSeat();
                List<OneCardAction> legal = engine.legalActions(state, seat);
                OneCardAction action = pick(seat == focal ? focalPolicy : otherPolicy, state, seat, legal, others);
                state = engine.apply(state, seat, action, 0).newState();
            }
            assertThat(state.ended()).as("판 %d 이 끝나지 않았다", game).isTrue();
            if (state.result().winners().contains(focal)) {
                wins++;
            }
        }
        double share = (double) wins / GAMES;
        log.info("[D-128] seats={} {}:{} share={} (SE {})", seats, focalPolicy, otherPolicy,
                String.format("%.3f", share), String.format("%.3f", Math.sqrt(share * (1 - share) / GAMES)));
        return share;
    }

    @Test
    void the_heuristic_beats_three_random_bots() {
        assertThat(focalWinShare(4, Policy.HEURISTIC, Policy.RANDOM)).isGreaterThanOrEqualTo(MIN_SHARE_FOUR);
        assertThat(focalWinShare(4, Policy.RANDOM, Policy.RANDOM)).as("대조군").isLessThan(MIN_SHARE_FOUR);
    }

    @Test
    void the_heuristic_beats_a_random_bot_head_to_head() {
        assertThat(focalWinShare(2, Policy.HEURISTIC, Policy.RANDOM)).isGreaterThanOrEqualTo(MIN_SHARE_TWO);
        assertThat(focalWinShare(2, Policy.RANDOM, Policy.RANDOM)).as("대조군").isLessThan(MIN_SHARE_TWO);
    }

    /** "낼 수 있으면 낸다"만으로 얻는 몫을 넘어 규칙(약한 반격·압박·무늬 유지·공격 카드 아끼기)이 값을 하는가. */
    @Test
    void the_heuristic_beats_greedy_bots_that_always_play_when_they_can() {
        assertThat(focalWinShare(4, Policy.HEURISTIC, Policy.GREEDY)).isGreaterThanOrEqualTo(MIN_SHARE_GREEDY);
        assertThat(focalWinShare(4, Policy.GREEDY, Policy.GREEDY)).as("대조군").isLessThan(MIN_SHARE_GREEDY);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol`(`OneCardBotPolicy`·`OneCardBotView`·`chooseBotAction`).

- [ ] **Step 3: 구현 — 뷰와 정책**

`server/src/main/java/com/mirboard/domain/game/onecard/bot/OneCardBotView.java`:

```java
package com.mirboard.domain.game.onecard.bot;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.rules.TurnOrder;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.util.ArrayList;
import java.util.List;

/**
 * 봇이 보는 것 — 자기 손패와 공개 정보뿐이다 (D-128, 스컬킹 D-119 와 같은 원칙). 다른 좌석의 카드·뽑을
 * 더미 순서는 담지 않는다. {@link #of} 가 전체 상태를 읽는 유일한 지점이다.
 *
 * @param handCounts 좌석별 손패 장수(공개 정보). 탈락자는 0
 * @param nextSeat   방향대로 다음 살아 있는 좌석 — 내가 평범하게 내면 차례를 받을 사람
 */
public record OneCardBotView(int seat,
                             List<PlayingCard> hand,
                             PlayingCard topCard,
                             Suit baseSuit,
                             int attackStack,
                             List<Integer> handCounts,
                             int nextSeat) {

    public OneCardBotView {
        hand = List.copyOf(hand);
        handCounts = List.copyOf(handCounts);
    }

    public static OneCardBotView of(OneCardState state, int seat) {
        List<Integer> counts = new ArrayList<>();
        state.hands().forEach(h -> counts.add(h.size()));
        int next = TurnOrder.nextAlive(state.seatCount(), state::alive, seat, state.direction());
        return new OneCardBotView(seat, state.hands().get(seat), state.topCard(), state.baseSuit(),
                state.attackStack(), counts, next);
    }

    public boolean underAttack() {
        return attackStack > 0;
    }

    /** 다음 사람의 손패 장수. */
    public int nextHandCount() {
        return handCounts.get(nextSeat);
    }
}
```

`server/src/main/java/com/mirboard/domain/game/onecard/bot/OneCardBotPolicy.java`:

```java
package com.mirboard.domain.game.onecard.bot;

import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 원카드 휴리스틱 봇 (D-128, 설계서 §4.6). <b>결정적</b>이다 — 같은 뷰·합법수면 같은 수를 낸다.
 *
 * <ul>
 *   <li><b>공격받는 중</b>: 가장 약한 반격 카드를 낸다(센 카드는 다음 반격용으로 아낀다). 없으면 먹는다.</li>
 *   <li><b>다음 사람이 {@value #THREAT_HAND_SIZE}장 이하</b>: 공격 카드(약한 것부터) → J → Q 로 압박·지연한다.</li>
 *   <li><b>평소</b>: 숫자 카드(가장 많이 든 무늬부터, 같으면 높은 숫자) → K → J → Q → 7 → 2·A → 조커. 공격
 *       카드는 반격 수단이라 마지막에 쓴다.</li>
 *   <li><b>7</b> 은 7 을 뺀 손패에서 가장 많이 든 무늬로 지정한다.</li>
 *   <li>낼 수 있으면 먹지 않는다.</li>
 * </ul>
 * 외치기 경쟁은 여기서 다루지 않는다 — 봇의 누름은 경쟁 창을 열 때 추첨한 반응 시간으로 엔진 타이머가
 * 맡는다(룰 §9-6).
 */
public final class OneCardBotPolicy {

    /** 다음 사람의 손패가 이 장수 이하이면 압박한다. */
    static final int THREAT_HAND_SIZE = 2;

    private OneCardBotPolicy() {
    }

    public static OneCardAction choose(OneCardBotView view, List<OneCardAction> legal) {
        List<PlayCard> plays = legal.stream()
                .filter(PlayCard.class::isInstance)
                .map(PlayCard.class::cast)
                .toList();
        if (plays.isEmpty()) {
            return draw(legal);
        }
        Comparator<PlayCard> order;
        if (view.underAttack()) {
            order = Comparator.comparingInt(play -> play.card().attackStrength());
        } else if (threatened(view)) {
            order = Comparator.<PlayCard>comparingInt(play -> pressureRank(play.card()))
                    .thenComparing(calmOrder(view));
        } else {
            order = calmOrder(view);
        }
        // min 은 동률이면 앞의 것을 고른다 — 합법수가 손패 순서라 결정적이다.
        return plays.stream().min(order).orElseThrow();
    }

    private static boolean threatened(OneCardBotView view) {
        return view.nextSeat() != view.seat() && view.nextHandCount() <= THREAT_HAND_SIZE;
    }

    /** 압박 순서 — 공격(약한 것부터) → J → Q, 나머지는 평소 순서로 넘긴다. */
    private static int pressureRank(PlayingCard card) {
        if (card.isAttack()) {
            return card.attackStrength();
        }
        if (card.isSkip()) {
            return 10;
        }
        if (card.isReverse()) {
            return 11;
        }
        return 20;
    }

    private static Comparator<PlayCard> calmOrder(OneCardBotView view) {
        Map<Suit, Integer> suitCounts = suitCounts(view.hand());
        return Comparator.<PlayCard>comparingInt(play -> calmRank(play.card()))
                .thenComparingInt(play -> -suitCount(suitCounts, play.card()))
                .thenComparingInt(play -> -play.card().rank())
                .thenComparingInt(play -> play.card().attackStrength())
                .thenComparingInt(play -> declarationRank(view.hand(), play));
    }

    /** 숫자 → K → J → Q → 7 → 2·A → 조커. */
    private static int calmRank(PlayingCard card) {
        if (card.isNormal()) {
            return 0;
        }
        if (card.isExtraTurn()) {
            return 1;
        }
        if (card.isSkip()) {
            return 2;
        }
        if (card.isReverse()) {
            return 3;
        }
        if (card.isSuitChange()) {
            return 4;
        }
        return card.isJoker() ? 6 : 5;
    }

    /** 7 의 지정 무늬 — 7 을 뺀 손패에서 많이 든 무늬일수록 앞. 7 이 아니면 0. */
    private static int declarationRank(List<PlayingCard> hand, PlayCard play) {
        if (play.declaredSuit() == null) {
            return 0;
        }
        int count = 0;
        for (PlayingCard card : hand) {
            if (!card.equals(play.card()) && !card.isJoker() && card.suit() == play.declaredSuit()) {
                count++;
            }
        }
        return -count;
    }

    private static int suitCount(Map<Suit, Integer> counts, PlayingCard card) {
        return card.isJoker() ? 0 : counts.getOrDefault(card.suit(), 0);
    }

    private static Map<Suit, Integer> suitCounts(List<PlayingCard> hand) {
        Map<Suit, Integer> counts = new EnumMap<>(Suit.class);
        for (PlayingCard card : hand) {
            if (!card.isJoker()) {
                counts.merge(card.suit(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static OneCardAction draw(List<OneCardAction> legal) {
        for (OneCardAction action : legal) {
            if (action instanceof OneCardAction.Draw) {
                return action;
            }
        }
        return legal.getFirst();
    }
}
```

- [ ] **Step 4: 구현 — 어댑터에 봇 연결**

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java`:

```diff
 import com.mirboard.domain.game.core.GameEvent;
 import com.mirboard.domain.game.core.GameState;
 import com.mirboard.domain.game.onecard.action.OneCardAction;
+import com.mirboard.domain.game.onecard.bot.OneCardBotPolicy;
+import com.mirboard.domain.game.onecard.bot.OneCardBotView;
 import com.mirboard.domain.game.onecard.event.OneCardEvent;
 import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
 import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
```

```diff
 import java.util.List;
 import java.util.Optional;
 import java.util.Random;
+import java.util.function.BiFunction;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.context.ApplicationEventPublisher;
```

```diff
         return List.<GameAction>copyOf(rules.legalActions(ocState(state), seat));
     }
 
+    /**
+     * 휴리스틱 {@link OneCardBotPolicy} — 공개 정보 뷰({@link OneCardBotView})만 본다. 결정적이라 {@code random}
+     * 은 쓰지 않는다. 경쟁 창의 누름은 여기서 하지 않는다(창을 열 때 추첨한 반응 시간으로 엔진 타이머가 맡는다).
+     */
+    @Override
+    public GameAction botAction(GameState state, int seat, Random random) {
+        return chooseBotAction(ocState(state), seat, OneCardBotPolicy::choose);
+    }
+
+    /**
+     * 정책 호출 + 안전망. 둘 수 없으면(남의 차례·경쟁 창·끝난 판) null. 정책이 예외를 던지거나 null·합법수 밖
+     * 액션을 내면 ERROR 로그 후 먹기({@code timeoutAction})로 떨어진다 — 턴 제한이 꺼진 방에서 정책 버그 하나로
+     * 방이 멈추지 않게(스컬킹 D-119 와 같은 안전망).
+     */
+    GameAction chooseBotAction(OneCardState state, int seat,
+                               BiFunction<OneCardBotView, List<OneCardAction>, OneCardAction> policy) {
+        if (state.race() != null || !rules.pendingSeats(state).contains(seat)) {
+            return null;
+        }
+        List<OneCardAction> legal = rules.legalActions(state, seat);
+        try {
+            OneCardAction chosen = policy.apply(OneCardBotView.of(state, seat), legal);
+            if (chosen != null && legal.contains(chosen)) {
+                return chosen;
+            }
+            log.error("OneCard bot policy returned a non-legal action, falling back: room={} seat={} action={}",
+                    context.roomId(), seat, chosen);
+        } catch (RuntimeException e) {
+            log.error("OneCard bot policy failed, falling back: room={} seat={}", context.roomId(), seat, e);
+        }
+        return rules.timeoutAction(state, seat);
+    }
+
     @Override
     public GameAction timeoutAction(GameState state, int seat) {
         return rules.timeoutAction(ocState(state), seat);
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 원카드 테스트 173건(정책 8 · 어댑터 봇 3 · 강도 3 추가). 강도 테스트의 INFO 로그 측정값: 1:1 대 무작위
0.960, 4인 대 무작위 0.919, 4인 대 탐욕 0.356(대조군 0.485 · 0.245 · 0.268).

- [ ] **Step 6: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/onecard/bot \
  server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardBotPolicyTest.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineBotActionTest.java \
  server/src/test/java/com/mirboard/domain/game/onecard/bot
git commit -m "feat(D-128): 원카드 휴리스틱 봇 — 공개 정보 뷰·안전망·강도 평가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: 매치 기록 — V12 + 기록기

**Files:**
- Create: `server/src/main/resources/db/migration/V12__onecard_match.sql`, `server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardMatchRecorder.java`
- Test: `server/src/test/java/com/mirboard/domain/game/onecard/persistence/OneCardMatchRecorderIT.java`

**Interfaces:**
- Consumes: Task 3 `OneCardMatchCompleted`, 기존 `UserGameStatsService.record(userId, gameType, win, newRating, deserted)`·
  `get(userId, gameType)`, `RatedMatchPolicy.eloApplies(playerIds)`, `EloCalculator.applyFreeForAll(placements)`,
  `BotUserRegistry.isBot(userId)`.
- Produces: 테이블 `onecard_match_results(id, room_id, finished_at, end_reason, payload_json)`·
  `onecard_match_participants(match_id, user_id, seat, final_rank, cards_left, is_win, deserted)`. 기록기는 승리 = 1등
  전원, ELO 점수 = 좌석 수 − 순위, 탈주 = `deserted`, 봇 계정은 전적 행을 만들지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/persistence/OneCardMatchRecorderIT.java`:

```java
package com.mirboard.domain.game.onecard.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** D-128 — 원카드 매치 종료가 매치 기록 + 게임별 전적(승패·개인전 ELO·탈주)으로 남는지. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=oc-recorder-test-secret-must-be-32-bytes-or-more"
})
class OneCardMatchRecorderIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserGameStatsService stats;
    @Autowired UserRepository users;
    @Autowired BotUserRegistry bots;
    @Autowired JdbcTemplate jdbc;

    private long human() {
        String name = "oc" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    private GameStats oc(long userId) {
        return stats.get(userId, OneCardGameDefinition.ID);
    }

    private static String room() {
        return UUID.randomUUID().toString();
    }

    private static MatchResult result(EndReason reason, Standing... standings) {
        return new MatchResult(reason, List.of(standings));
    }

    @Test
    void a_human_match_records_rows_win_lose_and_free_for_all_elo() {
        long a = human();
        long b = human();
        long c = human();
        String room = room();

        publisher.publishEvent(new OneCardMatchCompleted(room, List.of(a, b, c), result(EndReason.FINISHED,
                new Standing(0, 1, 0, SeatStatus.FINISHED),
                new Standing(1, 2, 3, SeatStatus.ALIVE),
                new Standing(2, 3, 5, SeatStatus.ALIVE))));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM onecard_match_results WHERE room_id = ? AND end_reason = 'FINISHED'",
                Integer.class, room)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT p.final_rank, p.cards_left, p.is_win FROM onecard_match_participants p"
                        + " JOIN onecard_match_results r ON r.id = p.match_id WHERE r.room_id = ?"
                        + " ORDER BY p.seat", room))
                .extracting(r -> r.get("final_rank") + "/" + r.get("cards_left") + "/" + r.get("is_win"))
                .containsExactly("1/0/true", "2/3/false", "3/5/false");

        // 신규(K=40), 3인 동일 레이팅: 1등 40/2 × (2 − 1) = +20, 2등 0, 3등 −20.
        assertThat(oc(a).rating()).isEqualTo(1020);
        assertThat(oc(b).rating()).isEqualTo(1000);
        assertThat(oc(c).rating()).isEqualTo(980);
        assertThat(oc(a).winCount()).isEqualTo(1);
        assertThat(oc(b).loseCount()).isEqualTo(1);
        assertThat(oc(c).loseCount()).isEqualTo(1);
    }

    @Test
    void tied_first_places_all_win_and_draw_against_each_other() {
        long a = human();
        long b = human();
        long c = human();

        publisher.publishEvent(new OneCardMatchCompleted(room(), List.of(a, b, c), result(EndReason.STALEMATE,
                new Standing(0, 1, 4, SeatStatus.ALIVE),
                new Standing(1, 1, 4, SeatStatus.ALIVE),
                new Standing(2, 3, 9, SeatStatus.ALIVE))));

        assertThat(oc(a).winCount()).isEqualTo(1);
        assertThat(oc(b).winCount()).isEqualTo(1);
        assertThat(oc(c).loseCount()).isEqualTo(1);
        // 공동 1등끼리 무(0.5), 3등에게 승: (0.5−0.5 + 1−0.5) × 40/2 = +10. 3등은 −20.
        assertThat(oc(a).rating()).isEqualTo(1010);
        assertThat(oc(b).rating()).isEqualTo(1010);
        assertThat(oc(c).rating()).isEqualTo(980);
    }

    @Test
    void a_bot_match_records_win_lose_but_skips_elo_and_bot_rows() {
        long a = human();
        long b = human();
        long bot = bots.getBotIds().get(0);

        publisher.publishEvent(new OneCardMatchCompleted(room(), List.of(a, bot, b), result(EndReason.FINISHED,
                new Standing(1, 1, 0, SeatStatus.FINISHED),
                new Standing(0, 2, 2, SeatStatus.ALIVE),
                new Standing(2, 3, 6, SeatStatus.ALIVE))));

        assertThat(oc(a).rating()).isEqualTo(1000);
        assertThat(oc(a).loseCount()).isEqualTo(1);
        assertThat(oc(b).loseCount()).isEqualTo(1);
        assertThat(stats.playedGames(bot)).isEmpty();
    }

    @Test
    void a_deserter_loses_counts_a_desertion_and_ranks_below_a_bankrupt_seat() {
        long winner = human();
        long bankrupt = human();
        long deserter = human();

        publisher.publishEvent(new OneCardMatchCompleted(room(), List.of(winner, bankrupt, deserter),
                result(EndReason.LAST_STANDING,
                        new Standing(0, 1, 6, SeatStatus.ALIVE),
                        new Standing(1, 2, 20, SeatStatus.BANKRUPT),
                        new Standing(2, 3, 4, SeatStatus.DESERTED))));

        assertThat(oc(deserter).loseCount()).isEqualTo(1);
        assertThat(oc(deserter).desertCount()).isEqualTo(1);
        assertThat(oc(bankrupt).desertCount()).isZero();
        assertThat(oc(winner).winCount()).isEqualTo(1);
        assertThat(oc(deserter).rating()).isLessThan(oc(bankrupt).rating());
        assertThat(oc(bankrupt).rating()).isLessThan(oc(winner).rating());
    }
}
```

- [ ] **Step 2: 실패 확인 (Docker)**

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.persistence.OneCardMatchRecorderIT"`
Expected: FAIL — 4건 모두 실패(`onecard_match_results` 테이블이 없다 — 기록이 남지 않는다).

- [ ] **Step 3: 구현**

`server/src/main/resources/db/migration/V12__onecard_match.sql`:

```sql
-- D-128 (원카드 S3) — 원카드 매치 결과. 스컬킹 skullking_match_*(V11) 와 같은 2테이블 구조이고,
-- 게임별 전적은 V11 의 user_game_stats(game_type = 'ONE_CARD')에 쌓인다.
--
-- PRIVACY POLICY 재확인 (D-02): 순위·남은 장수는 게임 결과일 뿐 식별/연락 정보가 아니다.
-- users 컬럼 화이트리스트는 건드리지 않는다.
--
-- end_reason: FINISHED / LAST_STANDING / NO_HUMANS / STALEMATE (`docs/rules-onecard.md` §11.1).

CREATE TABLE onecard_match_results (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    room_id       VARCHAR(36)  NOT NULL,
    finished_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_reason    VARCHAR(16)  NOT NULL,
    payload_json  TEXT         NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_oc_match_finished_at ON onecard_match_results (finished_at);

-- final_rank: 1부터, 동순위 다음은 건너뛴다(1, 1, 3 — §11.2). cards_left: 살아 있으면 남은 장수,
-- 탈락했으면 탈락 순간의 장수.
CREATE TABLE onecard_match_participants (
    match_id    BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    seat        INT      NOT NULL,
    final_rank  INT      NOT NULL,
    cards_left  INT      NOT NULL,
    is_win      BOOLEAN  NOT NULL,
    deserted    BOOLEAN  NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_oc_participant_match FOREIGN KEY (match_id) REFERENCES onecard_match_results(id),
    CONSTRAINT fk_oc_participant_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);
CREATE INDEX idx_oc_participant_user ON onecard_match_participants (user_id);
```

`server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardMatchRecorder.java`:

```java
package com.mirboard.domain.game.onecard.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.MatchResult.SeatStatus;
import com.mirboard.domain.game.onecard.state.MatchResult.Standing;
import com.mirboard.domain.game.scoring.EloCalculator;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-128 — 원카드 매치 종료를 {@code onecard_match_results/participants} 에 적재하고 게임별 전적(ONE_CARD
 * 행)을 갱신한다. 스컬킹 {@code SkullKingMatchRecorder} 와 같은 규칙: 봇(D-71)·게스트(D-117)가 낀 매치는
 * 승패만, ELO 는 제외({@link RatedMatchPolicy}). 탈주자는 패 + desert_count(D-75).
 *
 * <p>승리는 1등이다 — 동순위면 모두(룰 §11.2). ELO 는 개인전 쌍대 방식({@link EloCalculator#applyFreeForAll})에
 * 순위를 점수로 넘긴다(점수 = 좌석 수 − 순위, 순위가 같으면 무승부). 탈주 좌석은 점수와 무관하게 최하위다.
 */
@Component
public class OneCardMatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(OneCardMatchRecorder.class);
    private static final String GAME = OneCardGameDefinition.ID;

    private final JdbcTemplate jdbc;
    private final UserGameStatsService stats;
    private final BotUserRegistry bots;
    private final RatedMatchPolicy ratedMatchPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OneCardMatchRecorder(JdbcTemplate jdbc,
                                UserGameStatsService stats,
                                BotUserRegistry bots,
                                RatedMatchPolicy ratedMatchPolicy,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.jdbc = jdbc;
        this.stats = stats;
        this.bots = bots;
        this.ratedMatchPolicy = ratedMatchPolicy;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onMatchCompleted(OneCardMatchCompleted event) {
        List<Long> playerIds = event.playerIds();
        MatchResult result = event.result();
        Map<Integer, Standing> bySeat = new HashMap<>();
        result.standings().forEach(standing -> bySeat.put(standing.seat(), standing));
        boolean rated = ratedMatchPolicy.eloApplies(playerIds);

        Long matchId = jdbc.queryForObject(
                "INSERT INTO onecard_match_results (room_id, finished_at, end_reason, payload_json)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                event.roomId(), Timestamp.from(Instant.now(clock)), result.reason().name(), payload(result));

        // ELO 입력은 갱신 전 전적으로 만든다 (K-factor 의 판 수 임계가 정확하도록).
        Map<Long, Integer> newRatings = Map.of();
        if (rated) {
            List<EloCalculator.Placement> placements = new ArrayList<>();
            for (int seat = 0; seat < playerIds.size(); seat++) {
                long userId = playerIds.get(seat);
                Standing standing = bySeat.get(seat);
                GameStats current = stats.get(userId, GAME);
                placements.add(new EloCalculator.Placement(
                        new EloCalculator.PlayerInput(userId, current.rating(), current.gamesPlayed()),
                        playerIds.size() - standing.rank(),
                        standing.status() == SeatStatus.DESERTED));
            }
            newRatings = EloCalculator.applyFreeForAll(placements);
        }

        List<Integer> winners = result.winners();
        for (int seat = 0; seat < playerIds.size(); seat++) {
            long userId = playerIds.get(seat);
            Standing standing = bySeat.get(seat);
            boolean win = winners.contains(seat);
            boolean deserted = standing.status() == SeatStatus.DESERTED;
            jdbc.update("INSERT INTO onecard_match_participants"
                            + " (match_id, user_id, seat, final_rank, cards_left, is_win, deserted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    matchId, userId, seat, standing.rank(), standing.cardsLeft(), win, deserted);
            // 봇 계정 자신은 전적을 쌓지 않는다 (랭킹 대상 아님).
            if (!bots.isBot(userId)) {
                stats.record(userId, GAME, win, newRatings.get(userId), deserted);
            }
        }

        log.info("OneCard match recorded: room={} matchId={} reason={} winners={} eloApplied={} ratings={}",
                event.roomId(), matchId, result.reason(), winners, rated, newRatings);
    }

    private String payload(MatchResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize one card match payload", e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인 (Docker)**

Run: Step 2 와 같다.
Expected: PASS — 4건(사람 매치 ELO ±20, 공동 1등 무승부, 봇 매치 ELO 제외, 탈주자 최하위·desert_count).

- [ ] **Step 5: 커밋**

```bash
git add server/src/main/resources/db/migration/V12__onecard_match.sql \
  server/src/main/java/com/mirboard/domain/game/onecard/persistence/OneCardMatchRecorder.java \
  server/src/test/java/com/mirboard/domain/game/onecard/persistence/OneCardMatchRecorderIT.java
git commit -m "feat(D-128): 원카드 매치 기록 — V12 결과·참가자 + 게임별 전적·개인전 ELO

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 7: 경쟁 창 통합 테스트 — 서버 경로

Task 1~5 가 만든 것을 실제 서버 경로(STOMP 액션 컨트롤러·데드라인 폴러·탈주 서비스)로 확인한다. 그래서 처음부터
통과하는 것이 정상이고, Step 3 에서 엔진 타이머 무장 한 줄을 잠시 지워 **실제로 실패하는지**(검출력)를 본 뒤 되돌린다.

**Files:**
- Create: `server/src/test/java/com/mirboard/infra/bot/OneCardRaceIT.java`

**Interfaces:**
- Consumes: `GameStompController.onAction(roomId, Map payload, Principal)`, `DesertionService.processDesertion(roomId,
  userId)`, `RoomActionLock.acquireWaiting/release`, `OneCardStateStore`, `EngineTimerScheduler.KIND`, 설정
  `mirboard.onecard.*`·`mirboard.scheduling.poll-interval-millis`.

- [ ] **Step 1: 테스트 작성**

`server/src/test/java/com/mirboard/infra/bot/OneCardRaceIT.java`:

```java
package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameStompController;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-128 — 원카드 "원카드!/잡기!" 경쟁 창이 실제 서버 경로(STOMP 액션 컨트롤러·엔진 타이머·데드라인 폴러·
 * 탈주 서비스)로 닫히는지. 다섯 결과 — 주인이 먼저(CALLED), 사람이 잡음(CAUGHT), 봇이 잡음(엔진 타이머),
 * 아무도 안 누름(EXPIRED, 엔진 타이머), 창 중 탈주(CANCELLED) — 와 늦게 온 누름의 거절.
 *
 * <p>방이 시작되면 무작위로 나눠진 판을 같은 방 락 안에서 정해 둔 테이블로 바꿔 끼운다(좌석 0 이 ♥9·♣3 으로
 * 차례, ♥9 를 내면 1장). 창 길이·봇 반응은 짧게, 폴링은 촘촘히 잡아 몇 초 안에 끝난다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=onecard-race-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.onecard.status=AVAILABLE",
        "mirboard.onecard.race-window-millis=1000",
        "mirboard.onecard.bot-reaction-owner-min-millis=200",
        "mirboard.onecard.bot-reaction-owner-max-millis=200",
        "mirboard.onecard.bot-reaction-catcher-min-millis=300",
        "mirboard.onecard.bot-reaction-catcher-max-millis=300"
})
class OneCardRaceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired RoomService roomService;
    @Autowired UserRepository users;
    @Autowired OneCardStateStore stateStore;
    @Autowired GameStompController controller;
    @Autowired DesertionService desertion;
    @Autowired TurnTimeoutScheduler turnTimeout;
    @Autowired RoomActionLock lock;
    @Autowired StringRedisTemplate redis;

    private static final PlayingCard HEART_9 = PlayingCard.of(Suit.HEART, 9);

    private long human() {
        String name = "ocr" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.save(User.create(name, "x", Clock.systemUTC())).getId();
    }

    /** 사람만의 방 — 첫 번째가 호스트(좌석 0). */
    private Room humanRoom(long... players) {
        Room room = roomService.createRoom(players[0], "oc-race", "ONE_CARD", TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, players.length);
        for (int i = 1; i < players.length; i++) {
            roomService.joinRoom(room.roomId(), players[i]);
        }
        for (long player : players) {
            room = roomService.setReady(room.roomId(), player, true);
        }
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        return room;
    }

    /** 사람 호스트(좌석 0) + 봇(좌석 1). 봇은 입장 때 자동 준비라 호스트가 준비하면 시작한다. */
    private Room humanAndBotRoom(long host) {
        Room room = roomService.createRoom(host, "oc-race-bot", "ONE_CARD", TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, 2);
        room = roomService.setReady(room.roomId(), host, true);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.botSeats()).containsExactly(1);
        return room;
    }

    /** 좌석 0 이 ♥9·♣3 으로 차례인 테이블. 나머지 좌석은 3장씩, 남는 카드는 뽑을 더미. */
    private static OneCardState aboutToGoDownToOne(int seatCount) {
        List<List<PlayingCard>> hands = new ArrayList<>();
        hands.add(List.of(HEART_9, PlayingCard.of(Suit.CLUB, 3)));
        Suit[] suits = {Suit.SPADE, Suit.DIAMOND};
        for (int seat = 1; seat < seatCount; seat++) {
            Suit suit = suits[seat - 1];
            hands.add(List.of(PlayingCard.of(suit, 4), PlayingCard.of(suit, 6), PlayingCard.of(suit, 8)));
        }
        PlayingCard top = PlayingCard.of(Suit.HEART, 5);
        List<PlayingCard> used = new ArrayList<>();
        hands.forEach(used::addAll);
        used.add(top);
        List<PlayingCard> drawPile = Deck.all().stream().filter(card -> !used.contains(card)).toList();
        return new OneCardState(hands, drawPile, List.of(top), 0, 1, null, 0, null, List.of(), 0, 0, 1, null);
    }

    /** 나눠진 판을 정해 둔 테이블로 바꾼다 — 방 락 안에서 바꿔 봇 루프와 겹치지 않게 한다. */
    private void deal(String roomId, OneCardState table) {
        assertThat(lock.acquireWaiting(roomId)).isTrue();
        try {
            stateStore.save(roomId, table);
        } finally {
            lock.release(roomId);
        }
        turnTimeout.onTurnAdvanced(roomId);
    }

    private void act(String roomId, long userId, Map<String, Object> action) {
        controller.onAction(roomId, action, new AuthPrincipal(userId, "u" + userId));
    }

    private static Map<String, Object> playHeartNine() {
        return Map.of("@action", "PLAY_CARD", "card", Map.of("suit", "HEART", "rank", 9));
    }

    private static Map<String, Object> press(String type, int raceId) {
        return Map.of("@action", type, "raceId", raceId);
    }

    private OneCardState state(String roomId) {
        return stateStore.load(roomId).orElseThrow();
    }

    /** 좌석 0 이 ♥9 를 내 창을 연다. 엔진 타이머가 `deadlines:game` 에 걸렸는지도 본다. */
    private OneCardState openRace(String roomId, long ownerId) {
        act(roomId, ownerId, playHeartNine());
        OneCardState raced = state(roomId);
        assertThat(raced.race()).as("창이 열렸다").isNotNull();
        assertThat(raced.turnSeat()).isEqualTo(-1);
        return raced;
    }

    private boolean engineTimerArmed(String roomId) {
        var members = redis.opsForZSet().range("deadlines:" + EngineTimerScheduler.KIND, 0, -1);
        return members != null && members.stream().anyMatch(member -> member.startsWith(roomId + "#"));
    }

    private static void await(String what, java.util.concurrent.Callable<Boolean> condition) {
        Awaitility.await(what).atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(condition);
    }

    @Test
    void the_owner_calling_first_closes_the_race_safely() {
        long owner = human();
        long other = human();
        String roomId = humanRoom(owner, other).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        OneCardState raced = openRace(roomId, owner);
        assertThat(engineTimerArmed(roomId)).as("엔진 타이머 무장").isTrue();
        act(roomId, owner, press("CALL_ONE_CARD", raced.race().raceId()));

        OneCardState after = state(roomId);
        assertThat(after.race()).isNull();
        assertThat(after.hands().get(0)).hasSize(1);
        assertThat(after.turnSeat()).isEqualTo(1);
        assertThat(engineTimerArmed(roomId)).as("닫힌 창의 타이머는 지워진다").isFalse();
    }

    @Test
    void another_player_catching_first_costs_the_owner_one_card() {
        long owner = human();
        long catcher = human();
        String roomId = humanRoom(owner, catcher).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        OneCardState raced = openRace(roomId, owner);
        act(roomId, catcher, press("CATCH", raced.race().raceId()));

        OneCardState after = state(roomId);
        assertThat(after.race()).isNull();
        assertThat(after.hands().get(0)).hasSize(2);
        assertThat(after.turnSeat()).isEqualTo(1);
    }

    @Test
    void when_nobody_presses_the_engine_timer_closes_the_window_without_penalty() {
        long owner = human();
        long other = human();
        String roomId = humanRoom(owner, other).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        openRace(roomId, owner);

        await("창 만료", () -> state(roomId).race() == null);
        OneCardState after = state(roomId);
        assertThat(after.hands().get(0)).as("벌칙 없음").hasSize(1);
        assertThat(after.turnSeat()).isEqualTo(1);
    }

    @Test
    void a_bot_reacting_within_the_window_catches_a_slow_human() {
        long owner = human();
        String roomId = humanAndBotRoom(owner).roomId();
        deal(roomId, aboutToGoDownToOne(2));

        OneCardState raced = openRace(roomId, owner);
        assertThat(raced.race().botPress()).as("봇이 창 안에 반응하도록 추첨됐다").isNotNull();

        await("봇이 잡음", () -> state(roomId).race() == null || state(roomId).hands().get(0).size() == 2);
        await("벌칙 반영", () -> state(roomId).hands().get(0).size() == 2);
    }

    @Test
    void a_desertion_during_the_race_closes_it_without_penalty_and_the_reserved_seat_plays() {
        long owner = human();
        long next = human();
        long leaver = human();
        String roomId = humanRoom(owner, next, leaver).roomId();
        deal(roomId, aboutToGoDownToOne(3));
        openRace(roomId, owner);

        assertThat(desertion.processDesertion(roomId, leaver)).isTrue();

        OneCardState after = state(roomId);
        assertThat(after.race()).isNull();
        assertThat(after.hands().get(0)).as("벌칙 없음").hasSize(1);
        assertThat(after.turnSeat()).isEqualTo(1);
        assertThat(after.eliminations()).containsExactly(new Elimination(2, Elimination.Reason.DESERTED, 3));
        assertThat(roomService.getRoom(roomId).status()).isEqualTo(RoomStatus.IN_GAME);
    }

    @Test
    void a_press_for_a_closed_race_is_rejected_and_changes_nothing() {
        long owner = human();
        long other = human();
        String roomId = humanRoom(owner, other).roomId();
        deal(roomId, aboutToGoDownToOne(2));
        OneCardState raced = openRace(roomId, owner);
        act(roomId, owner, press("CALL_ONE_CARD", raced.race().raceId()));
        OneCardState closed = state(roomId);

        act(roomId, other, press("CATCH", raced.race().raceId()));

        assertThat(state(roomId)).isEqualTo(closed);
    }
}
```

- [ ] **Step 2: 통과 확인 (Docker)**

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.bot.OneCardRaceIT"`
Expected: PASS — 6건(약 4초).

- [ ] **Step 3: 검출력 확인 — 엔진 타이머 무장을 끄고 실패를 본 뒤 되돌린다**

`server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java` 의 `onTurnAdvanced` 에서 무장 호출을 잠시 지운다:

```diff
         if (room.status() != RoomStatus.IN_GAME) return;

-        armEngineTimer(roomId, room, gen);
-
         int turnSeconds = room.turnSeconds();
```

Run: Step 2 와 같다.
Expected: FAIL — `6 tests completed, 3 failed`(창 만료·봇의 잡기·엔진 타이머 무장 확인이 실패한다).

지운 두 줄을 원래대로 되돌리고 `git diff -- server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java` 출력이 비었는지 확인한 뒤 Step 2 를
다시 돌려 6건 통과를 본다.

- [ ] **Step 4: 커밋**

```bash
git add server/src/test/java/com/mirboard/infra/bot/OneCardRaceIT.java
git commit -m "test(D-128): 원카드 경쟁 창 서버 경로 — 외침·잡기·봇·만료·창 중 탈주·늦은 누름

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 8: 봇 풀매치 통합 테스트

**Files:**
- Create: `server/src/test/java/com/mirboard/infra/bot/OneCardBotMatchSimulationIT.java`

**Interfaces:**
- Consumes: 방 생성(`RoomService.createRoom(..., fillWithBots, ..., turnSeconds, ..., capacity)`)부터 기록(Task 6)까지 전 배선,
  `OneCardInvariantChecker.check`.

- [ ] **Step 1: 테스트 작성**

`server/src/test/java/com/mirboard/infra/bot/OneCardBotMatchSimulationIT.java`:

```java
package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.invariant.OneCardInvariantChecker;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.scoring.UserGameStatsService.GameStats;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-128 (S3) 완료 기준 — <b>봇만으로 원카드 한 판 완주</b>. 정의 등록 → GameStartingEvent → 라운드 시작 →
 * BotScheduler(휴리스틱 botAction) → 엔진 타이머(봇의 외치기 누름) → 매치 종료 → 방 FINISHED → 기록까지 전
 * 배선을 본다. 손을 놓은 사람이 낀 방은 턴 타임아웃(먹기)이 대신 진행해 끝까지 간다.
 *
 * <p>경쟁 창·봇 반응·폴링은 짧게 잡는다 — 판마다 경쟁이 여러 번 열리므로 운영값(3초)이면 수십 초가 걸린다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=onecard-sim-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=4242",
        "mirboard.bot.delay-millis=0",
        "mirboard.scheduling.poll-interval-millis=50",
        "mirboard.onecard.status=AVAILABLE",
        "mirboard.onecard.race-window-millis=300",
        "mirboard.onecard.bot-reaction-owner-min-millis=20",
        "mirboard.onecard.bot-reaction-owner-max-millis=60",
        "mirboard.onecard.bot-reaction-catcher-min-millis=20",
        "mirboard.onecard.bot-reaction-catcher-max-millis=60"
})
class OneCardBotMatchSimulationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired RoomService roomService;
    @Autowired BotUserRegistry bots;
    @Autowired OneCardStateStore stateStore;
    @Autowired UserRepository users;
    @Autowired UserGameStatsService stats;
    @Autowired JdbcTemplate jdbc;

    @Test
    void two_bots_finish_a_match() {
        runAllBotMatch(2);
    }

    @Test
    void four_bots_finish_a_match() {
        runAllBotMatch(4);
    }

    @Test
    void six_bots_finish_a_match() {
        runAllBotMatch(6);
    }

    /** 사람 호스트가 손을 놓으면 턴 타임아웃이 먹기로 대신 진행한다 — 판은 끝나고 사람의 전적이 남는다. */
    @Test
    void an_idle_human_is_carried_by_turn_timeouts_until_the_match_ends() {
        long human = users.save(User.create("oc_idle_" + UUID.randomUUID().toString().substring(0, 8), "x",
                Clock.systemUTC())).getId();
        Room room = roomService.createRoom(human, "oc-idle", "ONE_CARD", TeamPolicy.SEQUENTIAL, true,
                RoomService.DEFAULT_TARGET_SCORE, /*turnSeconds*/ 1, RoomService.DEFAULT_STAKE, 4);
        room = roomService.setReady(room.roomId(), human, true);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        String roomId = room.roomId();

        awaitEnded(roomId, 120);

        assertRecorded(roomId, 4);
        GameStats humanStats = stats.get(human, OneCardGameDefinition.ID);
        assertThat(humanStats.winCount() + humanStats.loseCount()).as("사람 전적 1판").isEqualTo(1);
        assertThat(humanStats.rating()).as("봇이 낀 매치는 ELO 제외").isEqualTo(UserGameStatsService.DEFAULT_RATING);
    }

    private void runAllBotMatch(int capacity) {
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(hostBotId, "oc-bot-sim-" + capacity, "ONE_CARD", TeamPolicy.SEQUENTIAL,
                true, RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, capacity);
        String roomId = room.roomId();

        // capacity 도달 + 봇 전원 자동 ready → IN_GAME → 리스너가 분배.
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.playerIds()).hasSize(capacity);

        awaitEnded(roomId, 60);
        assertRecorded(roomId, capacity);
    }

    private void awaitEnded(String roomId, int seconds) {
        Awaitility.await("판 종료")
                .atMost(seconds, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stateStore.load(roomId).map(OneCardState::ended).orElse(false));
        OneCardState state = stateStore.load(roomId).orElseThrow();
        OneCardInvariantChecker.check(state);
        assertThat(state.result().standings()).hasSize(state.seatCount());
    }

    /** 1판 = 1매치 → 방 FINISHED, 매치 결과 1행 + 좌석 수만큼 참가자. */
    private void assertRecorded(String roomId, int seats) {
        Awaitility.await("방 FINISHED")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> roomService.getRoom(roomId).status() == RoomStatus.FINISHED);
        Awaitility.await("매치 기록")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM onecard_match_results WHERE room_id = ?", Integer.class, roomId) == 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM onecard_match_participants p"
                        + " JOIN onecard_match_results r ON r.id = p.match_id WHERE r.room_id = ?",
                Integer.class, roomId)).isEqualTo(seats);
    }
}
```

- [ ] **Step 2: 통과 확인 (Docker)**

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.bot.OneCardBotMatchSimulationIT"`
Expected: PASS — 4건(약 25초 — 손을 놓은 사람이 낀 판이 턴 제한 1초 때문에 가장 길다).

- [ ] **Step 3: 커밋**

```bash
git add server/src/test/java/com/mirboard/infra/bot/OneCardBotMatchSimulationIT.java
git commit -m "test(D-128): 원카드 봇 풀매치 — 2·4·6인 봇 완주와 손 놓은 사람의 판

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: 문서·명령·수치 + Phase Gate

코드는 바꾸지 않는다. 아래 diff 는 Task 1~8 을 마친 뒤의 파일 기준이다(`docs/redis-keys.md` 는 Task 1 이 한 번 고쳤다).

**Files:**
- Modify: `docs/rules-onecard.md`(S3 코드·테스트 매핑, §9-4 결정, §12 22번), `docs/plans/onecard.md`(§4.5·§4.5b·§4.6·§6·§7
  — S3 결정 반영), `docs/stomp-protocol.md`(원카드 절), `docs/redis-keys.md`(원카드 상태 키)
- Modify: `CLAUDE.md`(결정 이력·테스트 명령·STOMP 규약), `scripts/check.sh`(`rules` 묶음)
- Modify: `docs/implementation-status.md`, `README.md`, `docs/case-study-multi-game.md`, `docs/plans/mvp-roadmap.md`(수치)

**Interfaces:**
- Consumes: Task 1~8 의 클래스·테스트 이름과 실측 수치.

- [ ] **Step 1: 룰·설계·프로토콜 문서**

`docs/rules-onecard.md`:

```diff
 어긋나면 코드를 고치거나 이 문서와 결정(`docs/decisions.md`)을 함께 고친다. 각 절 끝에 `rules-skullking.md` 와
 같은 표기(**코드:** / **테스트:** / **갭:**)로 코드 위치와 테스트를 붙였다. 경로는
 `server/src/{main,test}/java/com/mirboard/domain/game/onecard/` 기준이고, 위치는 줄 번호 대신 메서드 이름으로
-적는다(줄 번호는 코드가 바뀌면 바로 틀린다). 포트 어댑터·저장·봇 정책·기록은 S3 범위다.
+적는다(줄 번호는 코드가 바뀌면 바로 틀린다). 포트 어댑터·저장·봇 정책·기록은 S3(D-128)에서 붙였다 — 서버 경로로
+확인한 것은 각 절의 **코드(S3):** / **테스트(S3):** 다(인프라 테스트는 `server/src/test/java/com/mirboard/infra/` 기준).
 
 > **출처와 하우스 룰.** 원카드는 모임마다 규칙이 크게 다르다. 이 문서는 D-123 에서 고른 **표준 기본형**만
 > 다룬다. 원문이 답하지 않거나 갈리는 항목을 우리가 정한 것은 본문에 **[결정]** 으로 표시하고 §12 에 모았다.
```

```diff
 - **테스트:** `OneCardEnginePlayTest`(29건 — §2~§8·§10~§11 의 시작·내기·먹기·파산·종료) —
   `drawing_takes_one_card_even_when_a_card_could_be_played`,
   `the_timeout_action_is_drawing_for_the_seat_on_turn_only`, `legal_actions_*`·`under_attack_*`
+- **코드(S3):** 시간 초과는 `infra/bot/TurnTimeoutScheduler` 가 포트의 `timeoutAction` 으로 적용한다.
+- **테스트(S3):** `bot/OneCardBotMatchSimulationIT.an_idle_human_is_carried_by_turn_timeouts_until_the_match_ends`
 
 ## 5. 내기
 
```

```diff
 2. 창은 **3초**다. 창이 열린 동안에는 아무도 내거나 먹을 수 없다. 턴 제한 시계는 창이 닫힌 뒤 다시 시작한다.
 3. 1장 남은 사람(**주인**)은 "원카드!", 살아 있는 다른 플레이어는 "잡기!"를 누를 수 있다. 관전자와
    탈락자는 누를 수 없다.
-4. **첫 누름이 창을 닫는다.** "먼저"는 서버에 먼저 도착해 처리된 요청이다.
+4. **첫 누름이 창을 닫는다.** "먼저"는 서버에 먼저 도착해 처리된 요청이다. **[결정]** 창 끝이나 봇이 누를
+   시각 직후(타이머 폴링·락 대기로 최대 수백 ms)에 처리된 누름도 인정한다(D-128).
 
    | 결과 | 조건 | 효과 |
    | --- | --- | --- |
```

```diff
   `a_catch_with_an_empty_pile_reshuffles_the_discards_and_keeps_the_top_and_the_declared_suit`,
   `an_eliminated_bot_does_not_press_so_the_window_runs_its_full_length`.
   창 중 탈주(§9.2)는 `OneCardEngineDesertionTest`
-- **갭:** "먼저"를 가르는 서버 도착 순서·락 경합(`BUSY` 재시도)·엔진 타이머 무장은 포트 어댑터 몫이라 S3 통합
-  테스트에서 본다.
+- **코드(S3):** 엔진 타이머(D-128) — 어댑터 `OneCardGameEngine.timer`(창 끝 또는 추첨된 봇 시각까지 남은 시간)·
+  `onTimer`, 무장 `infra/bot/TurnTimeoutScheduler`, 발화 `infra/bot/EngineTimerScheduler`. 창 길이·봇 반응 구간은
+  `mirboard.onecard.*` 설정(`OneCardGameDefinition`).
+- **테스트(S3):** `bot/OneCardRaceIT`(6건 — 주인이 먼저 `CALLED`, 사람이 잡음 `CAUGHT`, 창 안에 반응한 봇이 잡음,
+  아무도 안 누르면 엔진 타이머가 `EXPIRED` 로 닫음, 창 중 탈주 `CANCELLED`, 닫힌 창의 누름 거절)
 
 ## 10. 파산과 탈락
 
```

```diff
   `the_owner_deserting_during_an_attack_race_still_passes_the_attack_to_the_reserved_seat`,
   `if_the_seat_a_jack_skipped_to_deserts_during_the_race_the_next_live_seat_plays`,
   `deserting_passes_the_turn_on_in_the_reversed_direction_too`
+- **테스트(S3):** `bot/OneCardRaceIT.a_desertion_during_the_race_closes_it_without_penalty_and_the_reserved_seat_plays`
+  (탈주 서비스 경로)
 
 ## 11. 종료와 순위
 
```

```diff
   `when_a_bankruptcy_leaves_only_bots_the_match_ends_as_no_humans_with_the_bankrupt_seat_last`, 연속 패스 초기화는
   `playing_or_drawing_a_card_resets_the_pass_streak`, `OneCardEngineDesertionTest`(LAST_STANDING, NO_HUMANS,
   연속 패스 초기화) 중 `the_last_standing_bot_beats_no_humans_when_a_human_deserts_a_two_seat_table`
+- **코드(S3):** 매치 기록 `persistence/OneCardMatchRecorder`(V12 `onecard_match_results`/`participants`) — 승리는 1등
+  전원(동순위 포함), 개인전 ELO 점수는 `좌석 수 − 순위`, 탈주 좌석은 최하위·desert_count, 봇·게스트가 낀 매치는
+  ELO 제외. 어댑터 `OneCardGameEngine.advance` 가 매치를 끝낸 전이에서만 한 번 발행한다.
+- **테스트(S3):** `persistence/OneCardMatchRecorderIT`(4건), `bot/OneCardBotMatchSimulationIT`(4건 — 2·4·6인 봇 완주와
+  손을 놓은 사람이 낀 판, 방 FINISHED + 기록 1행)
 
 ## 12. 우리가 정한 것 (미규정·하우스 룰 선택)
 
```

```diff
 | 19 | 탈락 뒤 나가기 | 탈주 아님 — 순위·상태 그대로 | 이미 끝난 사람에게 이중 처분 없음 |
 | 20 | 벌칙 먹기 | 차례·공격 누적·차례 수에 영향 없음 | §9.1 — K·J 효과와 충돌 방지 |
 | 21 | 창 중 탈주 | 다음 차례는 낸 순간 정해 둠 — 그 사람이 나가면 그다음 사람이 공격 없이 받음 | §9.2 |
+| 22 | 늦게 처리된 누름 | 인정 — 서버가 먼저 처리한 누름이 이김 | 네트워크 지연에 관대, 봇 반응 구간에 흡수 (D-128) |
 
 ## 13. 범위 밖 (v1)
 
```

`docs/plans/onecard.md`:

````diff
 
 ### 4.5 포트 확장 — 엔진 타이머
 
-정본은 S3 에서 `docs/game-port.md` 에 추가한다.
+**구현됨(S3, D-128)** — 정본은 `docs/game-port.md` §2 "시간이 지나면 일어나는 전이". 발화는 새
+`EngineTimerScheduler`(`deadlines:game`)가 맡고 무장은 아래대로 `TurnTimeoutScheduler.onTurnAdvanced` 다.
 
 ```java
 /** 이 상태에 시간이 지나면 저절로 일어나는 전이가 있으면, 지금부터 남은 시간. 기본: 없음. */
````

````diff
 
 ### 4.5b 포트 확장 — 비순번 이벤트
 
+**S3 에서 만들지 않았다(D-128, 사용자 결정)** — 같은 확장을 D-126(티츄 resync)이 넣는다. D-126 이 병합되면 원카드
+비공개 이벤트 2종에 `sequenced()` 를 false 로 재정의만 더한다(S4 착수 조건). 그 전까지 원카드 비공개 이벤트도
+방 순번을 쓴다. 아래는 원래 설계다.
+
 ```java
 /** false 면 envelope 에 seq 를 붙이지 않는다(순번을 소비하지 않음). 기본 true — 기존 게임 동작 그대로. */
 default boolean sequenced() { return true; }
````

```diff
 - **경쟁**: §4.4 의 반응 추첨으로만 참여한다(봇 루프와 무관).
 - **`timeoutAction`**(사람 시간 초과): 먹기. 결정적이다.
 - 휴리스틱 봇이 무작위 봇보다 우세한지 시뮬레이션으로 확인한다(D-119 방식).
+- **실측(S3, D-128, 1,000판·시드 고정)**: 무작위 봇(포트 기본, 합법수 균등) 대비 4인 승률 0.92(기준 0.25),
+  1:1 0.96. "낼 수 있으면 늘 내는" 탐욕 봇 3명 대비로도 0.36(기준 0.25, 대조군 0.27) — 규칙 자체가 값을 한다.
+  `bot/OneCardBotStrengthTest`.
 
 ### 4.7 탈주·파산·종료
 
```

```diff
 | S0 | 클라 seq 판정 공통화: 판정(duplicate/gap)은 `useStompRoom`, sink 는 순수 리듀서(D-103 부채) | — | 클라 | 티츄·스컬킹 스토어 테스트, D-103·D-122 회귀(스컬킹 라운드 시작 즉시 비우기) |
 | S1 | 룰 명세 `docs/rules-onecard.md`(§3.2 확정) | — | 없음(문서) | 사용자 검토 |
 | S2 | 순수 엔진 + 불변식 + 시뮬레이션 | S1 | 서버 신규 패키지 | 룰 단위 테스트, 2~6인 시뮬레이션 전부 종료, 54장 보존 |
-| S3 | 포트 확장 2건(엔진 타이머·비순번 이벤트) + 어댑터 + 봇 + 기록기(V12) + 라운드 시작 | S2 | 서버(인프라 2건) | 경쟁 통합 테스트 5종, 봇 풀매치 IT, 인프라 grep 0건, 티츄·스컬킹 회귀 |
+| S3 | 포트 확장 1건(엔진 타이머 — 비순번은 D-126 에 맡김) + 어댑터 + 봇 + 기록기(V12) + 라운드 시작 | S2 | 서버(인프라 1건) | 경쟁 통합 테스트 5종, 봇 풀매치 IT, 인프라 grep 0건, 티츄·스컬킹 회귀 |
 | S4 | 클라 게임판 + 경쟁 버튼 + 튜토리얼, `AVAILABLE` 전환 | S0·S3 | 클라(+정의 1줄) | Vitest, 브라우저 실측(데스크톱·모바일) |
 | S5 | 통합 리뷰 → 문서 수치 → 배포 | S4 | — | `check.sh` 전체, 운영 스모크 |
 
```

```diff
 - **3초 창이 템포를 늘어뜨릴 수 있다** → 대부분 첫 누름으로 일찍 닫힌다. 실측 후 창 길이를 조정한다.
 - **창 끝 뒤에 처리된 누름**(S2 최종 리뷰 N2): `press` 는 `now` 를 보지 않아 창 끝·봇 추첨 시각 뒤에 처리된 사람
   누름도 이긴다(§9-4 문구는 허용, §9-2·§9-6 의 시간 의미와는 어긋남). 엄격하게 하려면 `press` 에서
-  `now >= race.deadline()` 이면 `NO_RACE` — S3 에서 결정한다.
+  `now >= race.deadline()` 이면 `NO_RACE` — S3 에서 결정한다. **D-128 결정: 인정(현행 유지)** — 서버가 먼저 처리한
+  누름이 이긴다. 네트워크 지연에 관대하고, 봇이 추첨 시각보다 최대 한 폴링 주기(250ms) 늦게 누르는 셈이라 반응
+  구간 설정에 흡수된다. 배포 후 경쟁 결과 로그로 다시 본다.
 - **v1 제외**: 리매치(D-122 후속 "리매치 대기 중 나가면 좌석이 당겨짐"을 먼저 고친 뒤), 내기 칩,
   하우스 룰 옵션(방 옵션으로 변형 선택), 고정 위치 보조 모드.
 - **병합 순서**: D-116(다른 세션)이 먼저 main 에 들어가면 기록기 발행 방식은 그것을 따른다.
```

`docs/stomp-protocol.md`:

```diff
 
 ---
 
+## 원카드 (gameType=ONE_CARD, D-128)
+
+같은 목적지·같은 envelope 를 쓴다. 좌석은 **0 ~ seatCount−1** (2~6). 룰 정본은 `docs/rules-onecard.md`.
+클라 게임판(S4) 전까지 카탈로그 상태는 `COMING_SOON` 이다(`mirboard.onecard.status`).
+
+**클라 → 서버 `@action`**:
+
+| @action | 추가 필드 | 비고 |
+| --- | --- | --- |
+| `PLAY_CARD` | `card: PlayingCard`, `declaredSuit?: Suit` | `declaredSuit` 는 7 에만, 7 이면 필수(아니면 `INVALID_SUIT_DECLARATION`) |
+| `DRAW` | — | 공격받는 중이면 누적 장수, 아니면 1장. 낼 수 있어도 먹을 수 있다(§4) |
+| `CALL_ONE_CARD` | `raceId` | 경쟁 창 주인만 — "원카드!" |
+| `CATCH` | `raceId` | 주인이 아닌 살아 있는 좌석 — "잡기!" |
+
+`PlayingCard` 직렬화: `{ "suit": "SPADE"|"HEART"|"DIAMOND"|"CLUB", "rank": 1..13, "joker": null }`
+(A=1, J=11, Q=12, K=13) 또는 `{ "suit": null, "rank": 0, "joker": "BLACK"|"COLOR" }`.
+
+**서버 → 클라 (공개)** — payload 는 증감이 아니라 **결과값**이다(같은 이벤트를 두 번 적용해도 같고, 하나가
+빠져도 다음 이벤트에서 맞춰진다):
+
+| type | payload | 의미 |
+| --- | --- | --- |
+| `MATCH_STARTED` | `{ firstSeat, startCard, handSize, drawPileCount }` | 분배 직후(§3) |
+| `CARD_PLAYED` | `{ seat, card, declaredSuit?, handCount, attackStack, direction }` | 낸 뒤의 손패 장수·공격 누적·방향 |
+| `CARDS_DRAWN` | `{ seat, count, reason, handCount, drawPileCount }` | **장수만**. `reason`: `TURN`·`ATTACK`·`PENALTY` |
+| `PILE_RESHUFFLED` | `{ drawPileCount }` | 버린 더미를 섞어 다시 채움(§7.1) |
+| `TURN_CHANGED` | `{ seat, direction, attackStack }` | 차례 — `attackStack > 0` 이면 공격받는 중 |
+| `RACE_OPENED` | `{ raceId, ownerSeat, slot, jitterX, jitterY, windowMillis }` | 외치기 경쟁 창 — 전원에게 같은 위치. **봇 반응 시각은 싣지 않는다** |
+| `RACE_RESOLVED` | `{ raceId, outcome, bySeat }` | `CALLED`·`CAUGHT`·`EXPIRED`·`CANCELLED`(창 중 탈주). 아무도 안 눌렀으면 `bySeat` −1 |
+| `PLAYER_ELIMINATED` | `{ seat, reason, cardsHeld, drawPileCount }` | `BANKRUPT`·`DESERTED`. `drawPileCount` 는 손패를 더미에 넣은 뒤 장수(최종값) |
+| `MATCH_ENDED` | `{ reason, standings: [{seat, rank, cardsLeft, status}] }` | `FINISHED`·`LAST_STANDING`·`NO_HUMANS`·`STALEMATE`. 순위는 1, 1, 3 식 |
+
+**서버 → 클라 (비공개)**: `HAND_DEALT` `{ seat, hand, handVersion }`, `HAND_UPDATED`
+`{ seat, hand, received, handVersion }` — 손패 **전체**를 싣는다(내기·먹기·벌칙·탈락마다). `handVersion` 은 상태
+버전이라 좌석마다 단조 증가하고 resync 의 `privateHand.handVersion` 과 같은 축이다 — 클라는 가진 것보다 낮은 버전을
+버린다. `received` 는 이번에 새로 받은 카드(애니메이션용). `ERROR` 원카드 고유 코드: `MATCH_OVER`·
+`PLAYER_ELIMINATED`·`RACE_IN_PROGRESS`·`NOT_YOUR_TURN`·`CARD_NOT_OWNED`·`INVALID_SUIT_DECLARATION`·
+`CARD_NOT_PLAYABLE`·`COUNTER_REQUIRED`·`NO_RACE`·`NOT_RACE_OWNER`·`OWNER_CANNOT_CATCH`.
+
+**경쟁 창(§9)**:
+- `slot` 은 `0..7` — 슬롯 8개는 클라가 게임판 기준 좌표로 정의하는 **프로토콜 상수**다. `jitterX`·`jitterY` 는
+  `−100..100`(슬롯 반경의 백분율). 창 길이 기본 3000ms(`mirboard.onecard.race-window-millis`).
+- 창이 열린 동안 `PLAY_CARD`·`DRAW` 는 `RACE_IN_PROGRESS`. `raceId` 가 지금 창과 다르면(이미 닫힘 포함) `NO_RACE`
+  — 클라는 "늦었어요" 정도로 보여 주면 된다. 서버가 먼저 처리한 누름이 이기고, 창 끝·봇 시각 직후에 처리된
+  누름도 인정한다(D-128). 락 경합으로 `BUSY` 를 받으면 창이 열려 있는 동안 짧게 재시도한다.
+- 봇은 창을 열 때 추첨한 반응 시간에 누르고, 아무도 안 누르면 창 끝에 닫힌다 — 둘 다 **엔진 타이머**(D-128,
+  `docs/game-port.md` §2)가 서버에서 처리하므로 클라가 보낼 것은 없다.
+
+**resync**: `tableView` = `{ phase, seats: [{seat, handCount, eliminated}], topCard, declaredSuit, attackStack,
+direction, turnSeat, drawPileCount, race, result }` — `phase` 는 `PLAYING`·`RACE`·`ENDED`, `race` 는
+`{ raceId, ownerSeat, slot, jitterX, jitterY, windowMillis, remainingMillis }`(창 끝까지 남은 시간 — 봇 시각 아님),
+`result` 는 `MATCH_ENDED` 와 같은 모양. `privateHand` = `{ seat, hand, handVersion }`.
+
+> **순번**: 지금은 비공개 `HAND_*` 도 방 순번(`seq`)을 쓴다 — 받지 않는 좌석에는 구멍으로 보여 resync 를 부른다.
+> 같은 문제를 고치는 D-126 이 `GameEvent.sequenced()` 를 넣으면 원카드 비공개 이벤트를 `false` 로 둔다(S4 착수 조건).
+
+---
+
 ## 보안 검토 체크리스트
 
 - [x] 본인 큐 페이로드가 토픽으로 발행되지 않는지 통합 테스트로 검증.
```

`docs/redis-keys.md`:

```diff
 | `room:{roomId}` | HASH | 6h | `hostId`, `name`, `gameType`, `status`, `capacity`, `createdAt`, `updatedAt`, `teamPolicy`, `fillWithBots`, `targetScore`, `turnSeconds`, `stake` | 메타. `stake`(D-81)=판돈(가상 칩, 0=내기없음), 생성 시 고정·불변. `capacity`(D-99)=방 인원, 생성 시 게임의 `minPlayers()..maxPlayers()` 안에서 확정·불변 |
 | `room:{roomId}:players` | LIST | 6h (FINISHED 후 600s) | 입장 순서대로 `userId` push (`LLEN` ≤ `capacity`) | 자리 = index. **D-122: 게임이 시작된 뒤 좌석 인덱스는 불변** — FINISHED 방 leave 는 목록을 건드리지 않는다(`room_leave.lua`) |
 | `rooms:open` | ZSET | — | member=roomId, score=createdAt | 대기방 목록 표시 (status==WAITING 만 포함) |
-| `room:{roomId}:state` | STRING(JSON) | 6h | 마스터 `TichuState` 전체 (덱 잔여, 손패 포함) | 직렬화 책임은 GameEngine |
+| `room:{roomId}:state` | STRING(JSON) | 6h | 마스터 `TichuState` 전체 (덱 잔여, 손패 포함). 원카드는 `OneCardState`(손패·뽑을 더미 순서·경쟁 창·봇 누름 포함 — 서버 전용, 1판 = 1매치라 `match:` 키 없음, D-128) | 직렬화 책임은 GameEngine. 원카드는 모르는 필드를 무시한다(`@JsonIgnoreProperties(ignoreUnknown)`, 롤백 안전) |
 | `room:{roomId}:hand:{userId}` | STRING(JSON) | 6h | 해당 유저 손패 캐시 | resync 빠른 응답 용 (state로부터 파생 가능) |
 | `match:{roomId}:state` | STRING(JSON) | 6h | 티츄 `TichuMatchState` — 누적 점수/라운드 번호/라운드별 RoundScore. 스컬킹 `SkullKingMatchState` — `roundNumber`·`startSeat`·`cumulativeScores`(좌석→누적)·`desertedSeats`·`completedRounds`(`[{roundNumber, scores:{seat:{bid,won,base,bonus}}}]`, 정산 끝난 라운드만, D-120)·`roundsPlayed`(완주 라운드 수, 매치가 끝날 때 확정 — 진행 중·구 JSON 은 `null`, D-122) | Phase 5c 추가, 라운드 전환 시 유지. 방당 게임 하나라 키 공유. 스컬킹은 필드 부재 구 JSON 을 빈 값으로 읽고 모르는 필드는 무시(`@JsonIgnoreProperties(ignoreUnknown)`, D-120 — 다음 필드 추가부터 롤백 안전) |
 | `room:{roomId}:ready` | SET | 6h (FINISHED 후 600s) | 대기실 준비 완료 `userId` (봇은 join 시 자동 추가) | Phase 16(#2). 전원 ready+정원 → IN_GAME. D-74: 빈 방 leave 시 `room_leave.lua` 가 함께 삭제 |
```

- [ ] **Step 2: 명령과 `rules` 묶음** — 원카드 IT(기록기)가 생겨 `onecard.*` 대신 이름이 `Test` 로 끝나는 클래스만 묶는다

`scripts/check.sh`:

```diff
 
   fast              빠른 회귀 (클라 tsc+vitest + 서버 compile, ~30s)
                     pre-commit hook 과 동일 로직.
-  rules             서버 룰 도메인 단위 (티츄 + 스컬킹 + 원카드 전량 + 봇 강도 평가, ~20s)
+  rules             서버 룰 도메인 단위 (티츄 + 스컬킹 + 원카드 단위 + 봇 강도 평가, ~20s)
                     Docker 불필요.
   server            서버 풀 (단위 + IT, Docker 필요, ~1m20s)
   client            클라 풀 (build:check + test + build, ~10s)
```

```diff
         log "서버 룰 도메인 단위 테스트 (Docker 불필요)"
         # 스컬킹도 티츄처럼 하위 패키지를 명시한다 — skullking.* 로 쓸면 persistence 의
         # SkullKingMatchRecorderIT(D-115, Testcontainers)까지 잡혀 "Docker 불필요"가 거짓이 된다.
-        # 원카드는 아직(S2, D-127) 순수 테스트뿐이라 onecard.* 로 묶는다 — S3 에서 IT(기록기·라운드
-        # 시작)가 생기면 같은 이유로 하위 패키지를 명시할 것.
+        # 원카드는 IT 가 기록기(persistence.OneCardMatchRecorderIT, Testcontainers)뿐이라 이름이 Test 로 끝나는
+        # 클래스만 묶는다(D-128). 새 IT 도 이름을 IT 로 끝내면 저절로 빠진다.
         ./gradlew :server:test \
             --tests "com.mirboard.domain.game.tichu.card.*" \
             --tests "com.mirboard.domain.game.tichu.hand.*" \
```

```diff
             --tests "com.mirboard.domain.game.skullking.state.*" \
             --tests "com.mirboard.domain.game.skullking.trick.*" \
             --tests "com.mirboard.domain.game.skullking.persistence.SkullKingJsonRoundTripTest" \
-            --tests "com.mirboard.domain.game.onecard.*"
+            --tests "com.mirboard.domain.game.onecard.*Test"
         log "모두 통과"
         ;;
 
```

`CLAUDE.md`:

```diff
 
 **Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + **게임 2종**: 티츄(4인 2:2 팀전), 스컬킹(2~8인 개인전).
 
-현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M5 전부 완료, 결정 이력 D-127까지). 로비/방 → 두 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소.
+현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M5 전부 완료, 결정 이력 D-128까지). 로비/방 → 두 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소.
 
 - **서버** `server/` (Spring Boot 4 / Java 25, Gradle): 도메인 `domain.lobby`·`domain.game.{core,tichu,scoring}`, 인프라 `infra.{rest,ws,bot,messaging,metrics,config,web}`.
 - **클라이언트** `client/` (Vite + React 18 + TS, Zustand, @stomp/stompjs, Tailwind+shadcn).
```

```diff
 ./gradlew :server:test --tests "com.mirboard.domain.game.tichu.TichuGameEngine*Test"   # 포트 어댑터 (D-98)
 ./gradlew :server:test --tests "com.mirboard.domain.game.tichu.bot.*"       # 티츄 봇 정책·순수 시뮬레이션 (D-118, Docker 불필요)
 ./gradlew :server:test --tests "com.mirboard.domain.game.skullking.bot.*"   # 스컬킹 봇 정책·강도 평가 (D-119, Docker 불필요)
-./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*"         # 원카드 순수 엔진·시뮬레이션 (D-127, Docker 불필요)
+./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"     # 원카드 엔진·어댑터·봇 (D-127/D-128, Docker 불필요)
+./gradlew :server:test --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest"   # 포트 엔진 타이머 무장·발화 (D-128)
+./gradlew :server:test --tests "com.mirboard.infra.bot.OneCardRaceIT"   # 원카드 경쟁 창 서버 경로 (D-128, Docker)
 MIRBOARD_BOT_EVAL=1 ./gradlew :server:test --rerun --tests "com.mirboard.domain.game.tichu.bot.HeuristicBotEvaluationTest"   # 티츄 봇 대형 평가 (~1m30s)
 ./gradlew :server:test --tests "com.mirboard.domain.game.tichu.DealingLifecycleTest"
 ./gradlew :server:test --tests "com.mirboard.domain.game.tichu.persistence.TichuMatchStateTest"
```

```diff
 - 서버 → 클라 공개 `/topic/room/{roomId}`
   - 티츄: `PLAYED`, `PASSED`, `TURN_CHANGED`, `TRICK_TAKEN`, `TICHU_DECLARED`, `ROUND_ENDED`, `MATCH_ENDED` 등
   - 스컬킹(D-102): `BIDDING_STARTED`, `BID_SUBMITTED`(값 없음), `BIDS_REVEALED`, `PLAYING_STARTED`, `CARD_PLAYED`, `TURN_CHANGED`, `TRICK_TAKEN`, `ROUND_ENDED`, `SEAT_DESERTED`, `MATCH_ENDED`
+  - 원카드(D-128, 클라 S4 전 COMING_SOON): `MATCH_STARTED`, `CARD_PLAYED`, `CARDS_DRAWN`(장수만), `PILE_RESHUFFLED`, `TURN_CHANGED`, `RACE_OPENED`, `RACE_RESOLVED`, `PLAYER_ELIMINATED`, `MATCH_ENDED` — payload 는 결과값
 - 서버 → 클라 비공개 `/user/queue/room/{roomId}`
   - 티츄: `HAND_DEALT`, `CARDS_RECEIVED`, `ERROR`
   - 스컬킹: `HAND_DEALT`, `ERROR`
+  - 원카드: `HAND_DEALT`, `HAND_UPDATED`(손패 전체 + `handVersion`), `ERROR`
 - 클라 → 서버 `/app/room/{roomId}/action`
   - 티츄: `DECLARE_GRAND_TICHU`, `DECLARE_TICHU`, `READY`, `PASS_CARDS`, `PLAY_CARD`(마작 포함 시 `wishRank` 동봉, D-109), `PASS_TRICK`, `GIVE_DRAGON_TRICK`
   - 스컬킹: `PLACE_BID`, `PLAY_CARD`(티그리스는 `declaredAs`)
+  - 원카드: `PLAY_CARD`(7 은 `declaredSuit`), `DRAW`, `CALL_ONE_CARD`·`CATCH`(`raceId`). 봇 누름·창 만료는 서버의 엔진 타이머(D-128)
 
 전체 카탈로그: `docs/stomp-protocol.md`.
 
```

- [ ] **Step 3: 룰 묶음 확인**

Run: `./scripts/check.sh rules`
Expected: 마지막 줄 `──[check:rules]── 모두 통과`. 이어서 `ls server/build/test-results/test/ | grep -c 'game.onecard'` 가 `19`
(원카드 단위 테스트 클래스 — `OneCardMatchRecorderIT` 는 빠진다)이고, 그 결과 파일들의 `tests` 합이 173 이다.

- [ ] **Step 4: 서버 전체 실측 (Docker)**

Run: `./scripts/check.sh server` (Docker 필요, 약 4분 — 이 스크립트가 OrbStack/Colima 소켓을 잡아 준다) 뒤 집계:

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
t = s = f = 0
for p in glob.glob('server/build/test-results/test/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); s += int(r.get('skipped')); f += int(r.get('failures')) + int(r.get('errors'))
print(f"tests={t} skipped={s} failed={f}")
EOF
```

Expected: `tests=1214 skipped=5 failed=0`. 기준은 main `a761ae3` 의 1148건(Docker 불필요 965건)이고, S3 가 66건을 더한다 —
Docker 불필요 52건(원카드 단위 39 + `EngineTimerSchedulerTest` 13), Docker 필요 14건(`OneCardMatchRecorderIT` 4 ·
`OneCardRaceIT` 6 · `OneCardBotMatchSimulationIT` 4). 그래서 Docker 불필요는 965 + 52 = 1017(1214 의 84%)이다. main 이 그사이
바뀌어 기준 수치가 달라졌다면 바뀐 main 값에 위 증가분을 더해 아래 네 문서를 쓴다.

Run: `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra`
Expected: 출력 없음(인프라에 게임 이름 0건).

- [ ] **Step 5: 수치 반영**

`docs/implementation-status.md`:

```diff
 # Mirboard 구현 현황
 
 > 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
-> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-127),
+> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-128),
 > 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
 > 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
 > `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`·
```

```diff
 | 4 | WebSocket/STOMP 실시간 | ✅ | `infra.ws`, `infra.config.WebSocketConfig` |
 | 5 | 티츄 룰 엔진 (전 페이즈 + 특수 카드) | ✅ | `domain.game.tichu` |
 | 5b | 스컬킹 (룰 엔진 + 배선 + 클라 게임판) | ✅ | `domain.game.skullking`, `features/skullking` (§16) |
-| 5c | 원카드 순수 룰 엔진 (S2 — 포트 어댑터·클라는 S3·S4) | 🟡 | `domain.game.onecard`, `docs/rules-onecard.md` (D-127) |
+| 5c | 원카드 (룰 엔진 + 서버 배선·봇·기록 — 클라 게임판은 S4, 그때까지 COMING_SOON) | 🟡 | `domain.game.onecard`, `infra.bot.EngineTimerScheduler`, `docs/rules-onecard.md` (D-127·D-128) |
 | 6 | 봇 플레이어 (빈 좌석 자동 채움) | ✅ | `infra.bot`, `domain.game.tichu.bot` |
 | 7 | 재접속 동기화 (resync) | ✅ | `RoomService`, `GET /rooms/{id}/resync` |
 | 8 | 탈주/끊김 처리 (유예→패널티) | ✅ | `infra.ws` 탈주 핸들러, `DesertionService` |
```

```diff
 
 ## 13. 테스트 현황
 
-- **서버**: **1148건** (D-127 시점 실측, 실패 0, 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 스컬킹 도메인 375건(그중 Docker 불필요 371건), 원카드 도메인 134건(전부 Docker 불필요). 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
+- **서버**: **1214건** (D-128 시점 실측, 실패 0, 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 스컬킹 도메인 375건(그중 Docker 불필요 371건), 원카드 도메인 177건(그중 Docker 불필요 173건) + 원카드 서버 경로 IT 10건·엔진 타이머 단위 13건. 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
   단위(룰 엔진·족보·ELO·JWT·카탈로그·포트 어댑터) + 통합(Testcontainers PostgreSQL 16/
   Redis — auth/rooms/STOMP/봇/동시성/매치 영속/2-인스턴스 인계).
-- 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 두 봇
-  평가, ~20s). 스컬킹 매치 기록 IT(D-115)는 Docker 가 필요해 `rules` 에서 뺐다.
+- 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 세 봇
+  평가, ~20s). 스컬킹·원카드 매치 기록 IT(D-115·D-128)는 Docker 가 필요해 `rules` 에서 뺐다.
 - **클라이언트**: **420건 / 45파일** (D-124 시점 실측, 실패 0). Vitest + RTL — 스토어
   리듀서, 족보 타입, 카드 에셋 매핑 등.
 - 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.
```

`README.md`:

```diff
 [![Deploy](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml/badge.svg)](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml)
 
 Spring Boot 4 / Java 25 · PostgreSQL · Redis · React + TypeScript ·
-서버 테스트 1148건 / 클라 420건
+서버 테스트 1214건 / 클라 420건
 
 **라이브**: https://mirboard.fly.dev — 로그인 화면 「게스트로 바로 체험하기」로 가입 없이 들어갈 수 있습니다.
 유휴 시 머신이 멈춰 첫 접속에 약 30초 걸립니다(콜드 스타트).
```

`docs/case-study-multi-game.md`:

```diff
 `SkullKingInvariantChecker` 를 통과 케이스뿐 아니라 **고의로 위반시킨 상태 8건**
 (+ 오탐 방지 통과 2건)으로 검출 능력 자체를 테스트했습니다.
 
-**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1148건 중 965건(84%)이 Docker 불필요**합니다.
+**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1214건 중 1017건(84%)이 Docker 불필요**합니다.
 자랑이 아니라 §2 의 2계층 분리가 값을 냈다는 인과 증거입니다.
 
 `코드:` `skullking/trick/TrickResolver.java` · `skullking/invariant/SkullKingInvariantChecker.java` ·
```

`docs/plans/mvp-roadmap.md`:

```diff
 | M4 | G | 쇼케이스 마감: README 리뉴얼·데모 GIF·케이스 스터디·데모 계정·라이브 배포·CD | ✅ D-105: README·케이스 스터디·스크린샷·데모 계정 시더·CD 워크플로. **라이브 배포 2026-10-03**(https://mirboard.fly.dev) — 첫 재배포에서 5월의 Upstash Redis 소멸을 발견해 Fly 자체 Redis 로 교체(D-114), `FLY_API_TOKEN` 등록으로 main 푸시 = 자동 배포. GIF 는 정적 스크린샷으로 대체 |
 | M5 | E | 멀티게임: 포트 졸업 → 디스패치 seam 포트화 → 스컬킹(2~8인) | ✅ S0~S6 완료(D-97~D-104): 포트·인원 가변·룰 명세·순수 엔진(305건)·탈주(유령 좌석)·인게임 배선(봇 풀매치 IT)·클라 게임판(Row-Flow, 실측 완료). 실행 단위 `docs/plans/multi-game-sessions.md`. **잔여 별건**: 스컬킹 매치 영속·ELO(D-02 게임별 rating 분리 선행), 끊김 유예 구간 정지(D-104 한계), 요트/할리갈리 |
 | M6 | E·A | 스컬킹 완성도: ① 게임별 전적·레이팅(매치 영속·개인전 ELO·게임별 랭킹) ② 라운드 점수표 ③ 봇 휴리스틱 ④ 튜토리얼 | ✅ ① D-115 게임별 전적·레이팅 · ② D-120 라운드 결과(다음 라운드 예측 중 비차단)·점수표·종료 후 게임판 유지 · ③ D-119 봇 휴리스틱(공개 정보 뷰 + `TrickResolver` 승률) · ④ D-121 게임별 튜토리얼 레지스트리 + 스컬킹 13단계·퀴즈 (2026-10-03). 후속: 봇 상대 모델링(8인 대 최약수 대등), 티츄 봇 방 종료 화면 유지 |
-| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | 🟡 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`) · **S2 완료**(D-127 순수 엔진, 테스트 134건). 다음: S3 포트 타이머·통합 → S4 클라 → S5 통합·배포 |
+| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | 🟡 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`) · **S2 완료**(D-127 순수 엔진, 테스트 134건) · **S3 완료**(D-128 포트 엔진 타이머 + 어댑터·휴리스틱 봇·기록 V12, 경쟁 창 IT, 서버 1214건 — 클라 전까지 COMING_SOON). 다음: S4 클라(착수 조건: D-126 병합 뒤 비공개 이벤트 sequenced=false) → S5 통합·배포 |
 
 **M0 상세(완료)**: D-83(`SecurityConfig`/`WebSocketConfig` origin 화이트리스트+헤더),
 D-84(`LoginAttemptService`·`AuthRateLimiter`·`rate_limit_fixed_window.lua`, 전부 Redis 휘발 —
```

- [ ] **Step 6: 커밋**

```bash
git add docs/rules-onecard.md docs/plans/onecard.md docs/stomp-protocol.md docs/redis-keys.md CLAUDE.md scripts/check.sh \
  docs/implementation-status.md README.md docs/case-study-multi-game.md docs/plans/mvp-roadmap.md
git commit -m "docs(D-128): 원카드 S3 마감 — 룰↔코드 매핑·프로토콜·rules 묶음·테스트 수치

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Phase Gate**

`git log --oneline main..HEAD` 로 태스크 커밋 9개(리뷰 수정 커밋이 있으면 그만큼 더)를 확인하고 사용자에게 보고한다 — 만든 것
(포트 엔진 타이머, 어댑터·정의·시작·봇·기록, 서버 경로 IT), 실측 수치, 다음 단계(S4 클라 — 착수 조건: D-126 병합 뒤 원카드 비공개
이벤트 `sequenced()=false` 재정의). **사용자 승인 전에는 main 에 병합하지 않는다.** main 에 푸시하면 자동 배포되고, 그때부터
허브에 원카드가 "준비 중"으로 보인다(방 생성 불가).
