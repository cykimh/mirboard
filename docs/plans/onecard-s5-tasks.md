# 원카드 S5 구현 계획 — 공개 전 보강 (통합 리뷰 반영)

## 정오표 (최종 리뷰 반영, 2026-10-06)

이 계획의 본문은 **실행 기록이라 그대로 둔다**. 8태스크 구현(`878d5e5..a57c5f0`) 뒤 최종 브랜치 리뷰(opus, `s5-final-review`)가 필수 2건·권장 묶음을
짚었고 사용자가 "필수 + 권장 전부"와 'S5 —' → 'D-130 —' 치환을 승인해 한 묶음으로 고쳤다. 본문의 코드·문구·수치가 아래와 다른 곳은 이 절이 맞다.

**바뀐 것**

- **훅 — 정리된 이전 소켓의 콜백 무시(필수 I-1, Task 5).** 본문의 세대 표식은 REST 응답만 덮었다. stompjs `deactivate()` 는 비동기라 방을 바꾼
  뒤에도 DISCONNECT 영수증이 올 때까지 이전 소켓의 구독·닫힘 콜백이 돌아, 이전 방의 resync 가 새 세대로 세대 가드를 통과해 이전 방 스냅샷이 새 방에
  적용되고(기준점이 부풀어 새 방의 이벤트가 전부 '중복'), 늦은 영수증·닫힘이 새 소켓의 `connected` 를 내렸다 — Task 5 의 `<` 가드가 이 오염을 "다음
  resync 가 고치던 상태"에서 영구 정지로 바꿨다. 소켓 effect 지역 `disposed` 플래그(onConnect·구독 핸들러 4개·닫힘 콜백 3종, 로비 훅도)와 정리 때
  세대 +1(언마운트 직전에 보낸 resync 의 늦은 응답도 닫는다, N-1)을 더했다. 모의 `Client` 는 `deactivate()` 가 즉시 끝나 이 부류가 안 보이므로 **진짜
  stompjs 를 가짜 WebSocket 위에서 돌리는 테스트**(`staleSocket.test.tsx` 3건 — 방 2·로비 1)를 두었다. 훅·소켓 생명주기를 바꾸는 계획은 이런 테스트를 최소
  1건 두는 것을 규칙으로 한다.
- **런북 — DISABLED 확인 조건(필수 I-2, Task 8).** 본문의 조건("아래가 아무것도 출력하지 않거나 `IN_GAME` 줄이 없을 때")은 WAITING 방을 보지 않았다 —
  WAITING 방은 `DISABLED` 뒤에도 입장·준비·시작되고(시작 경로에 게임 상태 검사가 없다) 시작하는 순간 봇·타이머가 멈춘다. 이제 "`IN_GAME` 줄도 `WAITING`
  줄도 없을 때(빈 출력이거나 `FINISHED` 만)"이고 그 경고 한 줄이 붙는다(`docs/deploy.md`·`.env.example`). Task 8 Step 1 의 본문 문구는 이것으로 읽는다.
- **권장 묶음.**
  - 서버 — 진행 킥 `kick` 이 `executor.execute` 의 거절을 삼키고 로그만 남기며(resync 500 방지) WARN 에도 스택을 싣는다. 테스트: 예외로 끝난 봇 루프 토막의
    몫 반환(S5T2-M1), 엔진 타이머 무장 실패·킥의 ERROR 스택 단언(S5T2-M3), 킥 제출 거절 삼킴(S5T2-M5), 폴러 pop `Error` 의 다음 주기·스택(S5T1-M2),
    탈주가 매치를 끝내는 갈래의 경쟁 결과 로그(S5T3-M1). 주석: `RoomController` 킥 설명(S5T2-M5), 경쟁 결과 로그 "저장 전에 찍힘"을 PRESS·TIMER 로
    좁힘(S5T3-M3), 폴러 Javadoc "최선"(S5T1-M4), `GameDefinition`·`RoomCreationDefaultsTest`(S5T4-M5).
  - 클라 — 원카드 게임판의 다시 받기 effect 가 값이 아니라 **변화**에 반응(`handledResyncNonce`, 이전 세션이 남긴 신호로 마운트 직후 REST 가 한 번 더 나가던
    것 — S5T6-M2), 낡은 창 타이머의 정리·deps 테스트(S5T6-M1), 방 만들기 모달의 게임 전환 되돌림 테스트 2건(숨은 네이티브 select — 본문의 "Select 조작은
    자동 테스트 밖" 전제는 틀렸다, S5T4-M1)·선택지 사본 주석(S5T4-M3), 전환 뒤 실패한 이전 방 resync 가 `setError` 로 가지 않는 테스트(S5T5-M1), 허브
    방 목록 상태 칸을 한글 라벨로(`ROOM_STATUS_LABEL` 을 `features/lobby/roomStatusLabel.ts` 로 꺼냄, S5T7-M2), 주석·JSDoc 정정(S5T5-M3·S5T6-M3·S5T7-M1·M4).
  - 문서 — `deploy.md`(튜토리얼 결합 "1.0~2.5초"·"3초"·시크릿 변경의 앱 전체 재시작·로그 파일 보관·"다시 열기"의 기본값 조건), `api.md`(빈 줄·404
    `GAME_NOT_AVAILABLE`), `stomp-protocol.md`(`RoomPresence`), `game-port.md`(문장 순서), `onecard.md`(§6 S5 행·§8 표 4행 괄호·"둘 다 늘 보내므로"·
    §8 "남은 후속"에 최종 리뷰 follow-up 이전: 스컬킹 `recordIfEnded` 격리는 우선순위 높음, 턴 카운트다운은 "공개 전환 전 재검토").
- **표기 — 'S5 —' → 'D-130 —'.** 이 브랜치가 더한 줄만 치환했다(코드·테스트 주석, vitest `describe`/`it` 이름의 `(S5)`, 계약 문서의 `(S5)`·`S5 —`,
  `onecard.md` §7 의 `*(S5: …)*`). 제외: `onecard.md` 의 단계 이름(§6 표·§8 제목 "S5 — 공개 전 보강"·"S5 후반"), 이 계획서, 스컬킹의 기존 S5(D-102) 꼬리표,
  `V10__more_bots.sql` 같은 기존 파일의 기존 줄. 이 계획서 본문의 'S5 —' 인용은 실행 기록이라 그대로다.

**새 수치**(재측정 — `npm --prefix client run test` 마지막 두 줄, `./gradlew :server:test --rerun`)

| 항목 | 본문 | 정오표 |
| --- | --- | --- |
| 클라 (파일 / 테스트) | 60 / 632 | **61 / 642** (+`staleSocket.test.tsx`; 훅 +5, 모달 +2, 게임판 +3) |
| 서버 전체 (skipped / failed) | 1283 (5 / 0) | **1286** (5 / 0) |
| 서버 Docker 불필요 | 1083 (84%) | **1086** (84%) |
| `com.mirboard.infra.bot.*Test` | 44 | **46** |
| `com.mirboard.domain.game.onecard.*Test` (단위) / 도메인 전체 | 185 / 189 | **186 / 190** |
| `com.mirboard.infra.scheduling.*Test` | 3 | 3 |

인프라 게임 이름 grep(`onecard|one_card|원카드`) 0건, D-116 겹침 파일 변경 0 은 그대로다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원카드 공개(AVAILABLE) 전에 통합 리뷰(프로토콜·동시성·보안·운영 4렌즈)가 재현한 결함을 고친다 — 재기동·타이머 유실 뒤 영구히
멈추는 판을 되살리는 게임 중립 "진행 킥", 늦은·이전 방 resync 와 갑작스러운 끊김을 다루는 방 훅, 원카드 경쟁 결과 로그와 기록 실패 격리,
데드라인 폴러 보호, 게임이 선언하는 방 만들기 처음 선택(원카드 4명·턴 제한 30초), 공용 거절 문구, 운영 런북. 공개 전환 자체와 `decisions.md`·
"게임 3종" 문서·수치 반영은 D-116 병합 뒤(S5 후반)라 이 계획에 없다 — 이 계획이 끝나도 원카드는 `COMING_SOON` 이다.

**Architecture:** 서버는 인프라 공용 장치만 늘린다(게임 이름 0 유지) — `GameProgressKick` 이 resync 응답 뒤·게임 토픽 구독 때 대기 좌석이 봇이면
살아 있는 루프가 없을 때만 봇 루프를 걸고(`BotScheduler.scheduleBotsIfIdle`), 엔진 타이머가 있는데 이 세대 데드라인이 없으면 ZADD NX 로 다시
건다(`DeadlineQueue.scheduleIfAbsent`). 턴 데드라인·`onTurnAdvanced` 는 건드리지 않는다. 원카드 어댑터는 경쟁이 닫히는 세 경로에서 결과 한 줄을
남기고(로그만 — 메트릭 태그 금지), 동기 기록 리스너의 예외를 삼켜 진행을 잇는다. 클라는 게임 중립 훅(`useStompRoom`)이 세대 표식·순번
기준점으로 낡은 스냅샷을 버리고 `requestResync` 를 내주며, 원카드 스토어·게임판이 해소가 안 오는 창을 창마다 한 번 다시 받는다(D-103 유지).

**Tech Stack:** Java 25 + Spring Boot 4 + JUnit 5 + AssertJ + Mockito(+ Testcontainers IT 2건), React 18 + TypeScript + Zustand, Vitest + RTL.

**참고:** 통합 리뷰 `.superpowers/sdd/s5-review-{protocol,concurrency,security,ops-ux}.md`(gitignore 된 진행 폴더 — 원문은 설계 §8 에 요약),
설계 `docs/plans/onecard.md` §7·§8, 프로토콜 `docs/stomp-protocol.md`, 포트 `docs/game-port.md`. 이 계획의 코드는 검증용 스파이크(계획 전 설계 리뷰
반영)에서 옮겼고, 스파이크에서 태스크 N 까지만 적용한 트리가 N=1..8 모두 컴파일·테스트를 통과하는 것과 아래 각 단계의 실패·통과·테스트 수,
클라 632건·서버 1283건(실패 0)을 확인했다.

## Global Constraints

- 응답·주석·문서는 한국어.
- 작업 위치: 브랜치 `feat/onecard-s5`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan` (main `27910d1` 기반).
  메인 체크아웃(`/Users/yupchang/Developer/mirboard`)과 다른 워크트리는 건드리지 않는다. Bash 는 매 호출 작업 폴더가 바뀔 수 있으니 **명령마다
  워크트리 절대경로로 `cd` 하거나 `git -C` 를 쓴다.**
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` — 모델이 무엇이든 이 줄 그대로.
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 컴파일)를 돈다. `--no-verify` 금지. 그래서 **매 태스크가 끝난 트리는
  타입 검사와 클라 전체 테스트를 통과해야 한다.**
- 개발 서버(`bootRun`·`npm run dev`)·브라우저·운영 접속을 하지 않는다.
- **D-116 겹침 파일은 건드리지 않는다**(다른 세션이 커밋 전 상태로 들고 있다): `RoomService.java`, `MessageGateway.java`, `DomainEventBus.java`,
  `TichuGameDefinition.java`, `TichuGameEngine.java`, 티츄 엔진 테스트 3종, `BotMatchSimulationIT`, `DomainEventSingleDeliveryIT`, 문서 `CLAUDE.md`·
  `README.md`·`docs/decisions.md`·`docs/implementation-status.md`·`docs/plans/mvp-roadmap.md`·`docs/architecture.md`·`docs/case-study-multi-game.md`·
  `docs/qa-scenarios.md`·`docs/redis-keys.md`, `.claude/launch.json`. 원카드 리스너에 인스턴스 간 재발행 경로를 만들지 않는다(D-116 원칙).
- **도메인 경계**: 인프라에 게임 이름 0 — `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra` 가 0건. 클라 게임 중립(D-103):
  `useStompRoom` 은 게임 스토어를 모르고, sink 는 모듈 상수이며 각 메서드는 호출 시점에 `getState()` 를 읽는다.
- **사용자 결정(2026-10-06)**: 보강 범위 = 통합 리뷰 표 전부(Redis 메시지 순서 보정은 제외 — 후속). 버티기 대응 = 원카드 턴 제한 **기본 30초**.
  자동화 대응 = **로그로 탐지만**(누름 하한 없음). 원카드 방 만들기 기본 인원 = **4명**. 결정 번호는 **D-130** 으로 D-116 병합 뒤 `docs/decisions.md`
  에 옮겨 적는다(이 계획은 그 파일을 건드리지 않는다).
- 테스트 명령: 클라 특정 파일 `npm --prefix client run test -- <이름 일부>`, 클라 전체 `npm --prefix client run test`, 타입 검사 `npm --prefix client run build:check`. 서버 단위
  `./gradlew :server:test --tests "<클래스>"`(Docker 불필요). Docker IT 는 `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 같은 명령(이 워크트리의 OrbStack). Gradle 빌드 캐시가
  켜져 있어 같은 입력이면 `FROM-CACHE` 로 끝나니 **실측은 `--rerun`**.
- 로그 단언은 Task 1 의 `testsupport/LogCapture`(Logback `ListAppender`)로 한다 — 콘솔 문자열이 아니라 이벤트(수준·문장·스택)를 본다.
- 테스트 수(누적): 클라 `npm --prefix client run test` 마지막 두 줄 — 기준 58파일·594건 → Task 4 58·597 → Task 5 59·604 → Task 6 59·614 → Task 7 60·632(Task 1~3·8 은
  클라 불변). 서버 — `com.mirboard.infra.scheduling.*Test` 0 → 3(Task 1), `com.mirboard.infra.bot.*Test` 23 → 44(Task 2), `com.mirboard.domain.game.onecard.*Test`
  178 → 184(Task 3) → 185(Task 4), 전체 1238 → **1283**(Docker 불필요 1039 → **1083**, 84%).
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를 잡는 문맥이다(지우지 않는다). 세 경우 모두
  첫 글자 하나를 떼고 읽는다(나머지 들여쓰기는 파일 그대로). `+` 뒤가 비어 있으면 빈 줄을 넣는다. 한 블록은 파일에서 정확히 한 곳과 맞고, 블록은
  적힌 순서대로 적용한다. 안에 ``` 가 든 블록은 네 개짜리 펜스(````)로 감쌌다 — 그 ``` 줄은 파일 내용이다.

## 파일 지도

| 파일 | 태스크 | 책임 |
| --- | --- | --- |
| `server/src/main/java/com/mirboard/infra/scheduling/DeadlinePoller.java`, `server/src/test/java/com/mirboard/testsupport/LogCapture.java` | 1 | 주기 작업이 `Error` 에도 멈추지 않음 / 로그 단언 도구 |
| `server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java` | 2 | 진행 킥 — 봇 루프 재개, 사라진 엔진 타이머 재무장(참가자·관전자만) |
| `server/src/main/java/com/mirboard/infra/bot/BotScheduler.java`, `server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java` | 2 | 살아 있는 루프 수·`scheduleBotsIfIdle` / ZADD NX `scheduleIfAbsent` |
| `server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java`, `server/src/main/java/com/mirboard/infra/ws/WsSessionLifecycleListener.java`, `server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java` | 2 | 킥 배선(resync 뒤·게임 토픽 구독) / 무장 실패 ERROR |
| `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java` | 3 | 경쟁 결과 로그 3경로, 기록 리스너 실패 격리 |
| `server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java`, `OneCardGameDefinition.java`, `GameCatalogController.java`, `client/src/types/api.ts`, `CreateRoomModal.tsx` | 4 | 게임이 선언하는 방 만들기 처음 선택 |
| `client/src/ws/useStompRoom.ts`, `useLobbyStomp.ts`, `roomEventSink.ts` | 5 | 세대 표식·낡은 스냅샷 버림·구독 뒤 resync·끊김 감지·`requestResync` |
| `client/src/features/onecard/onecardStore.ts`, `OneCardTable.tsx` | 6 | resync 때 누름 정리, 해소가 안 오는 창은 창마다 한 번 다시 받기 |
| `client/src/ws/errorLabels.ts`, `onecardRoomSink.ts`, `client/src/pages/GameHubPage.tsx` | 7 | 공용 인프라 거절 문구, 허브 방 목록 표시 이름 |
| `docs/deploy.md`, `.env.example`, `docs/stomp-protocol.md`, `docs/api.md`, `docs/game-port.md`, `docs/plans/onecard.md` | 8 | 공개 상태 런북·튜닝 키·결과 로그, 프로토콜·API·포트 계약, 설계 §8 |

---

### Task 1: 서버 — 데드라인 폴러가 `Error` 한 번에 멈추지 않게

**Files:**
- Create: `server/src/test/java/com/mirboard/testsupport/LogCapture.java`, `server/src/test/java/com/mirboard/infra/scheduling/DeadlinePollerTest.java`
- Modify: `server/src/main/java/com/mirboard/infra/scheduling/DeadlinePoller.java`

**Interfaces:**
- Consumes: 기존 `DeadlinePoller`(단일 폴러 — 턴·엔진·탈주 데드라인을 꺼내 처리).
- Produces: 항목 처리·큐 pop 의 `Throwable` 을 잡아 ERROR(+스택)로 남기고 다음 주기를 잇는다(이미 pop 한 나머지 항목도 처리). 테스트 도구
  `LogCapture.of(Class<?>)` → `events()`·`messages(Level)`·`close()`(try-with-resources, 붙이는 동안 그 로거를 DEBUG 로).

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/testsupport/LogCapture.java`:

```java
package com.mirboard.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 테스트용 로그 캡처 — 한 클래스의 로거에 Logback {@link ListAppender} 를 붙여 <b>수준과 문장</b>을 단언한다.
 *
 * <p>S5 에서 들였다(프로젝트에 로그 단언 관례가 없었다). 콘솔 패턴은 Spring 컨텍스트가 떴는지에 따라 달라지므로
 * 출력 문자열 대신 이벤트를 본다 — "WARN 이던 것이 DEBUG 로", "ERROR 로(Sentry 는 ERROR 만 올린다)" 같은 수준
 * 변경을 정확히 고정하려는 것이다. 붙이는 동안 그 로거의 수준을 DEBUG 로 내리고 닫을 때 되돌린다.
 *
 * <pre>{@code
 * try (LogCapture logs = LogCapture.of(DeadlinePoller.class)) {
 *     ...
 *     assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("..."));
 * }
 * }</pre>
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        this.previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type) {
        return new LogCapture(type);
    }

    /** 지금까지 남은 이벤트(복사본). 다른 스레드(가상 스레드 루프 등)가 남긴 것도 들어온다. */
    public List<ILoggingEvent> events() {
        // AppenderBase.doAppend 는 appender 를 잠그고 추가한다 — 같은 잠금으로 읽는다.
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** 그 수준으로 남은 문장들(인자 치환 뒤). */
    public List<String> messages(Level level) {
        return events().stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
```

`server/src/test/java/com/mirboard/infra/scheduling/DeadlinePollerTest.java`:

```java
package com.mirboard.infra.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mirboard.testsupport.LogCapture;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S5 — 데드라인 폴러의 주기 작업은 무엇이 던져도 멈추지 않는다.
 *
 * <p>{@code scheduleWithFixedDelay} 는 작업이 한 번이라도 던지면 이후 실행을 <b>조용히</b> 멈추고 예외는 아무도 읽지
 * 않는 {@code Future} 에 묻는다. 폴러는 {@code RuntimeException} 만 잡고 있어서 {@code Error}(스택 넘침·메모리 부족) 한
 * 번이면 그 인스턴스의 턴·엔진·탈주 데드라인이 전부 멈췄다 — 운영은 머신 1대라 서비스 전체의 경쟁 창이 안 닫힌다.
 */
class DeadlinePollerTest {

    private static final String KIND = "probe";

    private final DeadlineQueue queue = mock(DeadlineQueue.class);
    private LogCapture logs;

    @BeforeEach
    void captureLogs() {
        logs = LogCapture.of(DeadlinePoller.class);
    }

    @AfterEach
    void releaseLogs() {
        logs.close();
    }

    private static DeadlineHandler handler(Consumer<String> body) {
        return new DeadlineHandler() {
            @Override
            public String kind() {
                return KIND;
            }

            @Override
            public void handle(String member) {
                body.accept(member);
            }
        };
    }

    @Test
    void an_error_in_a_handler_does_not_stop_later_polls() throws Exception {
        when(queue.pollDue(KIND)).thenReturn(List.of("r1#1"));
        CountDownLatch calls = new CountDownLatch(3);
        AtomicBoolean first = new AtomicBoolean(true);
        DeadlinePoller poller = new DeadlinePoller(queue, List.of(handler(member -> {
            calls.countDown();
            if (first.getAndSet(false)) {
                throw new StackOverflowError("simulated");
            }
        })), 10);

        poller.start();
        try {
            assertThat(calls.await(2, TimeUnit.SECONDS))
                    .as("Error 를 던진 다음 주기에도 폴링이 이어진다")
                    .isTrue();
        } finally {
            poller.shutdown();
        }
    }

    /** 이미 pop 한 항목은 이 인스턴스만 가진다 — 한 항목의 Error 로 나머지를 버리면 그 타이머들은 영영 사라진다. */
    @Test
    void an_error_from_one_member_does_not_drop_the_rest_of_the_popped_batch() {
        when(queue.pollDue(KIND)).thenReturn(List.of("r1#1", "r2#1"));
        List<String> handled = new CopyOnWriteArrayList<>();
        DeadlinePoller poller = new DeadlinePoller(queue, List.of(handler(member -> {
            handled.add(member);
            if (member.equals("r1#1")) {
                throw new StackOverflowError("simulated");
            }
        })), 10);

        assertThatCode(poller::pollOnce).doesNotThrowAnyException();

        assertThat(handled).containsExactly("r1#1", "r2#1");
        assertThat(logs.events())
                .as("ERROR 로 스택과 함께 남긴다 — Sentry 는 ERROR 만 올린다")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("r1#1");
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
    }

    @Test
    void an_error_while_popping_the_queue_is_logged_and_the_next_cycle_retries() {
        when(queue.pollDue(KIND)).thenThrow(new OutOfMemoryError("simulated"));
        DeadlinePoller poller = new DeadlinePoller(queue, List.of(handler(member -> { })), 10);

        assertThatCode(poller::pollOnce).doesNotThrowAnyException();

        assertThat(logs.events()).extracting(ILoggingEvent::getLevel).contains(Level.ERROR);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.scheduling.DeadlinePollerTest"`
Expected: FAIL — `3 tests completed, 3 failed`(`an_error_in_a_handler_does_not_stop_later_polls` — `Expecting value to be true but was false`,
`an_error_from_one_member_does_not_drop_the_rest_of_the_popped_batch` — `caught "java.lang.StackOverflowError: simulated"`,
`an_error_while_popping_the_queue_is_logged_and_the_next_cycle_retries` — `caught "java.lang.OutOfMemoryError: simulated"`).

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/infra/scheduling/DeadlinePoller.java`:

```diff
         log.info("데드라인 폴러 기동: kinds={} intervalMs={}", handlers.keySet(), intervalMillis);
     }
 
-    /** 한 사이클. 테스트에서 주기를 기다리지 않고 직접 부를 수 있게 package-private. */
+    /**
+     * 한 사이클. 테스트에서 주기를 기다리지 않고 직접 부를 수 있게 package-private.
+     *
+     * <p>S5 — <b>무엇도 밖으로 던지지 않는다.</b> {@code scheduleWithFixedDelay} 는 작업이 한 번이라도 던지면 이후
+     * 실행을 조용히 멈추고 예외는 아무도 읽지 않는 {@code Future} 에 묻는다. {@code RuntimeException} 만 잡던 때는
+     * {@code Error}(스택 넘침·메모리 부족) 한 번에 이 인스턴스의 턴·엔진·탈주 데드라인이 로그 없이 전부 멈췄다.
+     */
     void pollOnce() {
         for (var entry : handlers.entrySet()) {
             String kind = entry.getKey();
```

```diff
                 for (String member : queue.pollDue(kind)) {
                     try {
                         handler.handle(member);
-                    } catch (RuntimeException e) {
-                        // 한 항목의 실패가 나머지를 막지 않게. 재시도는 핸들러 책임.
+                    } catch (Throwable e) {
+                        // 한 항목의 실패가 나머지를 막지 않게 — Error 도 잡는다. 이미 pop 한 나머지 항목은 이 인스턴스만
+                        // 가지고 있어서 여기서 빠져나가면 그 타이머들은 영영 사라진다. 재시도는 핸들러 책임.
                         log.error("데드라인 처리 실패: kind={} member={} err={}",
                                 kind, member, e.toString(), e);
                     }
```

```diff
             } catch (RuntimeException e) {
                 // Redis 장애 등 — 다음 사이클에 재시도.
                 log.warn("데드라인 폴링 실패(다음 주기 재시도): kind={} err={}", kind, e.toString());
+            } catch (Throwable e) {
+                // S5 — Error 가 주기 작업 밖으로 새면 폴링이 영영 멈춘다. 남기고 다음 주기에 다시 한다.
+                log.error("데드라인 폴링 중 오류(다음 주기 재시도): kind={} err={}", kind, e.toString(), e);
             }
         }
     }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.scheduling.DeadlinePollerTest"`
Expected: PASS — 3건.

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/testsupport/LogCapture.java \
  server/src/test/java/com/mirboard/infra/scheduling/DeadlinePollerTest.java \
  server/src/main/java/com/mirboard/infra/scheduling/DeadlinePoller.java
git commit -m "fix(S5): 데드라인 폴러 — Error 한 번에 주기 작업이 조용히 멈추지 않게

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 서버 — 진행 킥(게임 중립) + 로그 수준

**Files:**
- Create: `server/src/test/java/com/mirboard/infra/bot/GameProgressKickTest.java`, `server/src/test/java/com/mirboard/infra/bot/GameProgressKickScenarioTest.java`, `server/src/test/java/com/mirboard/infra/bot/BotSchedulerTest.java`, `server/src/test/java/com/mirboard/infra/ws/WsSessionLifecycleListenerTest.java`, `server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java`
- Modify: `server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java`, `server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerResyncLockTest.java`, `server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerLeaveTest.java`, `server/src/test/java/com/mirboard/infra/scheduling/DistributedInfraIT.java`, `server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java`, `server/src/main/java/com/mirboard/infra/bot/BotScheduler.java`, `server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java`, `server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java`, `server/src/main/java/com/mirboard/infra/ws/WsSessionLifecycleListener.java`

**Interfaces:**
- Consumes: Task 1 `LogCapture`, 포트 `GameEngine`(`loadState`·`isRoundOver`·`pendingSeats`·`timer`·`phaseName`), `EngineTimerScheduler` 의 데드라인
  멤버 규약 `"{roomId}#{세대}"`(종류 `game`)과 세대 저장소, `RoomController.resync`, `WsSessionLifecycleListener.onSubscribe`.
- Produces: `GameProgressKick.kick(String roomId, long userId)` — 가상 스레드로 넘김(`RuntimeException` → WARN, `Error` → ERROR), 본체 `kickNow`
  (package-private): 방 없음·IN_GAME 아님·참가자도 관전자도 아님·상태 없음·라운드 끝 → 아무것도 안 함, 대기 좌석에 봇이 있으면
  `BotScheduler.scheduleBotsIfIdle(String roomId): boolean`, 엔진 타이머가 있으면 `DeadlineQueue.scheduleIfAbsent(String kind, String member,
  Duration delay): boolean`(ZADD NX — 걸려 있으면 덮지 않음). 세대 bump·턴 데드라인·`onTurnAdvanced` 는 건드리지 않는다. `BotScheduler` "no pending
  bot action" WARN → DEBUG, `TurnTimeoutScheduler` 무장 실패 WARN → ERROR(+스택).

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/infra/bot/GameProgressKickTest.java`:

```java
package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.testsupport.LogCapture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * S5 — 진행 킥. 방 진행은 메모리의 봇 루프와 유실될 수 있는 타이머에 기대 왔다 — 재기동(배포·Fly 자동 정지) 뒤에는
 * 봇 차례에서 매치가 영구 정지했고(C-I1), 엔진 타이머가 한 번 사라지면 경쟁 창이 영원히 열려 있었다(C-I2). 클라가 방을
 * 다시 볼 때(resync 응답 뒤·게임 토픽 구독) 거는 킥이 (a) 대기 중인 봇의 루프와 (b) 사라진 엔진 타이머를 되살린다.
 * 게임 중립이라 엔진은 모의 객체로 충분하다.
 */
class GameProgressKickTest {

    private static final String ROOM = "r1";
    /** 방의 참가자(좌석 0). 킥은 참가자·관전자의 것만 받는다. */
    private static final long PLAYER = 10L;

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    private final RoomGeneration generations = mock(RoomGeneration.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);

    /** 직접 실행기 — 테스트에서는 킥을 호출한 스레드에서 바로 돈다. */
    private final GameProgressKick kick =
            new GameProgressKick(roomService, engines, bots, deadlines, generations, Runnable::run);

    private static Room room(RoomStatus status, List<Integer> botSeats, int turnSeconds) {
        return room(status, botSeats, turnSeconds, Set.of());
    }

    private static Room room(RoomStatus status, List<Integer> botSeats, int turnSeconds, Set<Long> spectators) {
        return new Room(ROOM, "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), spectators,
                TeamPolicy.SEQUENTIAL, 0L, !botSeats.isEmpty(), botSeats, 1000, turnSeconds, 0, Set.of());
    }

    private void inGame(List<Integer> botSeats, int turnSeconds, List<Integer> pending) {
        when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, botSeats, turnSeconds));
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.pendingSeats(state)).thenReturn(pending);
    }

    @Nested
    class Bots {

        @Test
        void a_pending_bot_seat_gets_a_loop_only_through_the_idle_check() {
            inGame(List.of(1), 0, List.of(1));

            kick.kickNow(ROOM, PLAYER);

            // 살아 있는 루프가 있으면 겹쳐 걸지 않는 진입점만 쓴다 — 겹치면 봇 속도가 빨라진다.
            verify(bots).scheduleBotsIfIdle(ROOM);
            verify(bots, never()).scheduleBots(anyString());
        }

        @Test
        void a_human_turn_starts_no_bot_loop() {
            inGame(List.of(1), 0, List.of(0));

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(bots);
        }

        /**
         * 재기동 = 메모리(봇 루프 가상 스레드)는 사라지고 Redis(상태·세대)만 남는다. 턴 제한이 꺼져 있어 데드라인도 없다 —
         * 예전에는 사람이 '나가기'(탈주 기록)를 누를 때까지 영원히 멈췄다. 새 인스턴스의 실제 봇 스케줄러로 확인한다.
         */
        @Test
        void after_a_restart_one_kick_resumes_the_stuck_bot_turn() {
            RoomActionLock lock = mock(RoomActionLock.class);
            GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
            TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);
            BotScheduler freshInstance = new BotScheduler(roomService, engines, broadcaster, lock,
                    mock(MatchProgressService.class), mock(BotUserRegistry.class), turnTimeout, 1L, 0L);
            GameProgressKick kickOnFreshInstance =
                    new GameProgressKick(roomService, engines, freshInstance, deadlines, generations, Runnable::run);

            GameState afterBot = mock(GameState.class);
            GameAction action = mock(GameAction.class);
            GameEvent played = mock(GameEvent.class);
            AtomicReference<GameState> stored = new AtomicReference<>(state);
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, List.of(1), 0));
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenAnswer(i -> Optional.of(stored.get()));
            doAnswer(i -> {
                stored.set(i.getArgument(0));
                return null;
            }).when(engine).saveState(any());
            when(engine.pendingSeats(state)).thenReturn(List.of(1));
            when(engine.pendingSeats(afterBot)).thenReturn(List.of(0));
            when(engine.botAction(eq(state), eq(1), any())).thenReturn(action);
            when(engine.apply(state, 1, action)).thenReturn(new GameEngine.Result(afterBot, List.of(played)));
            when(lock.tryAcquire(ROOM)).thenReturn(true);

            kickOnFreshInstance.kickNow(ROOM, PLAYER);

            verify(broadcaster, timeout(2_000)).broadcast(eq(ROOM), eq(List.of(played)), eq(List.of(10L, 20L)));
            assertThat(stored.get()).isSameAs(afterBot);
        }
    }

    @Nested
    class EngineTimer {

        @Test
        void a_lost_engine_timer_is_armed_only_if_absent_with_the_current_generation_and_the_time_left() {
            inGame(List.of(), 0, List.of());
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1_200)));
            when(generations.current(ROOM)).thenReturn(7L);
            when(deadlines.scheduleIfAbsent(EngineTimerScheduler.KIND, "r1#7", Duration.ofMillis(1_200)))
                    .thenReturn(true);

            try (LogCapture logs = LogCapture.of(GameProgressKick.class)) {
                kick.kickNow(ROOM, PLAYER);

                // 그 member 가 없을 때만 더한다(ZADD NX) — 남은 시간은 상태가 정하므로 창을 늘리지도 앞당기지도 않는다.
                verify(deadlines).scheduleIfAbsent(EngineTimerScheduler.KIND, "r1#7", Duration.ofMillis(1_200));
                verify(deadlines, never()).schedule(anyString(), anyString(), any());
                // 유실만이 아니라 pop 뒤 처리 중일 때도 더해지므로(무해) 경보가 아니라 INFO 다.
                assertThat(logs.messages(Level.INFO)).anyMatch(m -> m.contains("armed an absent engine timer"));
                assertThat(logs.messages(Level.WARN)).isEmpty();
            }
        }

        /** 10분 뒤 '잡기!' 가 벌칙을 주던 경로 — 이미 끝났어야 할 창은 지금 바로 닫히게 건다. */
        @Test
        void a_lost_timer_already_past_its_deadline_is_armed_to_fire_now() {
            inGame(List.of(), 0, List.of());
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ZERO));
            when(generations.current(ROOM)).thenReturn(7L);

            kick.kickNow(ROOM, PLAYER);

            verify(deadlines).scheduleIfAbsent(EngineTimerScheduler.KIND, "r1#7", Duration.ZERO);
        }

        /** 이미 걸린 무장(정상·재시도·미만기 재무장)은 덮지 않는다 — 덮는 쪽(`schedule`)을 부르지 않는다. */
        @Test
        void a_timer_still_armed_for_this_generation_is_never_overwritten() {
            inGame(List.of(), 0, List.of());
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1_200)));
            when(generations.current(ROOM)).thenReturn(7L);
            when(deadlines.scheduleIfAbsent(anyString(), anyString(), any())).thenReturn(false);

            try (LogCapture logs = LogCapture.of(GameProgressKick.class)) {
                kick.kickNow(ROOM, PLAYER);

                verify(deadlines, never()).schedule(anyString(), anyString(), any());
                assertThat(logs.messages(Level.INFO)).noneMatch(m -> m.contains("armed an absent engine timer"));
            }
        }

        @Test
        void a_state_without_a_timer_arms_nothing() {
            inGame(List.of(), 0, List.of(0));
            when(engine.timer(state)).thenReturn(Optional.empty());

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(deadlines);
        }
    }

    @Nested
    class Guards {

        /**
         * resync 를 반복하는 클라가 시간 초과를 끝없이 미루지 못하게 — 킥은 턴 진행({@code onTurnAdvanced})이 아니다.
         * 세대를 올리지도, 턴 데드라인을 다시 걸지도, 걸린 데드라인을 지우지도 않는다.
         */
        @Test
        void kicking_again_and_again_never_extends_the_turn_deadline() {
            inGame(List.of(), 30, List.of(0));
            when(engine.timer(state)).thenReturn(Optional.empty());

            for (int i = 0; i < 5; i++) {
                kick.kickNow(ROOM, PLAYER);
            }

            verify(generations, never()).bump(anyString());
            verify(deadlines, never()).schedule(eq(TurnTimeoutScheduler.KIND), anyString(), any());
            verify(deadlines, never()).scheduleIfAbsent(eq(TurnTimeoutScheduler.KIND), anyString(), any());
            verify(deadlines, never()).cancel(anyString(), anyString());
        }

        /**
         * 공개 토픽 구독은 로그인한 누구나 할 수 있고 SUBSCRIBE 는 레이트리밋 밖이다 — 참가자·관전자가 아니면 아무것도
         * 하지 않는다(구독 폭주가 킥마다 Redis 왕복으로 커지지 않게). 관전자는 판을 보는 사람이라 킥한다.
         */
        @Test
        void a_non_member_subscription_starts_nothing() {
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, List.of(1), 0, Set.of(77L)));
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenReturn(Optional.of(state));
            when(engine.pendingSeats(state)).thenReturn(List.of(1));
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ZERO));

            kick.kickNow(ROOM, 999L);
            verifyNoInteractions(engines, bots, deadlines, generations);

            kick.kickNow(ROOM, 77L); // 관전자
            verify(bots).scheduleBotsIfIdle(ROOM);
        }

        @Test
        void a_finished_room_is_left_alone() {
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.FINISHED, List.of(1), 0));

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(engines, bots, deadlines, generations);
        }

        @Test
        void a_waiting_room_is_left_alone() {
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.WAITING, List.of(1), 0));

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(engines, bots, deadlines, generations);
        }

        @Test
        void a_vanished_room_or_an_unstarted_game_is_left_alone() {
            when(roomService.getRoom(ROOM)).thenThrow(new RoomNotFoundException(ROOM));
            kick.kickNow(ROOM, PLAYER);

            when(roomService.getRoom("r2")).thenReturn(room(RoomStatus.IN_GAME, List.of(1), 0));
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenReturn(Optional.empty());
            kick.kickNow("r2", PLAYER);

            verifyNoInteractions(bots, deadlines, generations);
        }

        @Test
        void a_round_already_over_is_left_alone() {
            inGame(List.of(1), 0, List.of(1));
            when(engine.isRoundOver(state)).thenReturn(true);

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(bots, deadlines, generations);
        }

        /**
         * resync 응답·구독 처리를 늦추지 않게 실행기로 넘기고, 실패해도 호출한 쪽으로 던지지 않는다. {@code Error} 는 ERROR 로
         * 남긴다 — 가상 스레드의 기본 처리기(stderr)로 가면 Sentry 에 보이지 않는다.
         */
        @Test
        void kick_runs_on_the_executor_and_swallows_failures() {
            List<Runnable> submitted = new ArrayList<>();
            GameProgressKick deferred =
                    new GameProgressKick(roomService, engines, bots, deadlines, generations, submitted::add);
            when(roomService.getRoom(ROOM))
                    .thenThrow(new IllegalStateException("redis down"))
                    .thenThrow(new StackOverflowError("simulated"));

            deferred.kick(ROOM, PLAYER);
            deferred.kick(ROOM, PLAYER);
            verifyNoInteractions(roomService);
            assertThat(submitted).hasSize(2);

            try (LogCapture logs = LogCapture.of(GameProgressKick.class)) {
                assertThatCode(() -> submitted.get(0).run()).doesNotThrowAnyException();
                assertThatCode(() -> submitted.get(1).run()).doesNotThrowAnyException();

                assertThat(logs.messages(Level.WARN)).singleElement().asString().contains("redis down");
                assertThat(logs.messages(Level.ERROR)).singleElement().asString().contains("Progress kick failed");
            }
        }
    }
}
```

`server/src/test/java/com/mirboard/infra/bot/GameProgressKickScenarioTest.java`:

```java
package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.onecard.OneCardGameEngine;
import com.mirboard.domain.game.onecard.RaceSettings;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.metrics.MirboardMetrics;
import com.mirboard.infra.scheduling.DeadlineHandler;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.GameStompController;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * S5 — 진행 킥의 끝-끝 시나리오. 리뷰가 재현한 세 정지(C-I1·C-I2)를 실제 부품으로 만들고, 킥 한 번(재접속이면 몇 번)이 판을
 * 다시 움직이는지 본다.
 *
 * <p>실제 {@link OneCardGameEngine}·{@link GameStompController}·{@link TurnTimeoutScheduler}·{@link EngineTimerScheduler}·
 * {@link BotScheduler}(지연 0)·{@link MatchProgressService}·{@link GameProgressKick} 을 쓰고, Redis 에 기대는 부품(상태 저장소·
 * 데드라인 큐·세대·방 락)만 메모리 가짜로 바꾼다. 폴러는 가짜 시계로 손으로 돌린다. Docker 불필요.
 */
class GameProgressKickScenarioTest {

    static final class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(1_700_000_000_000L);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }

        @Override
        public long millis() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    private static final String ROOM = "r1";
    private static final PlayingCard HEART_9 = PlayingCard.of(Suit.HEART, 9);

    final MutableClock clock = new MutableClock();
    final AtomicReference<OneCardState> stored = new AtomicReference<>();
    final Map<String, Map<String, Long>> queue = new ConcurrentHashMap<>();
    final AtomicLong gen = new AtomicLong();
    final AtomicBoolean locked = new AtomicBoolean();
    final AtomicBoolean failNextGameArm = new AtomicBoolean();
    final AtomicBoolean failNextBroadcast = new AtomicBoolean();
    final List<List<? extends GameEvent>> broadcasts = new CopyOnWriteArrayList<>();

    final OneCardStateStore store = mock(OneCardStateStore.class);
    final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    final RoomGeneration generations = mock(RoomGeneration.class);
    final RoomActionLock lock = mock(RoomActionLock.class);
    final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    final RoomService roomService = mock(RoomService.class);
    final GameEngineProvider engines = mock(GameEngineProvider.class);
    final GameRegistry games = mock(GameRegistry.class);
    final MirboardMetrics metrics = mock(MirboardMetrics.class);
    final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    final BotScheduler botProxy = mock(BotScheduler.class);

    TurnTimeoutScheduler turnTimeout;
    EngineTimerScheduler engineTimer;
    GameStompController controller;
    BotScheduler bots;
    GameProgressKick kick;
    Room room;

    @SuppressWarnings("unchecked")
    void wire(List<Long> players, List<Integer> botSeats, int turnSeconds) {
        room = new Room(ROOM, "방", "ONE_CARD", players.get(0), RoomStatus.IN_GAME, players.size(), players.size(),
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, !botSeats.isEmpty(), botSeats, 1000, turnSeconds, 0,
                Set.of());
        when(roomService.getRoom(ROOM)).thenAnswer(i -> room);
        GameContext ctx = new GameContext(ROOM, players, 1000, 0, botSeats);
        when(engines.forRoom(any())).thenAnswer(i ->
                new OneCardGameEngine(ctx, store, clock, new Random(7), RaceSettings.DEFAULT, publisher));
        GameDefinition def = mock(GameDefinition.class);
        when(def.supportsRematch()).thenReturn(false);
        when(games.require(anyString())).thenReturn(def);

        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());

        // 데드라인 큐 — 같은 member 는 하나뿐(ZSET), scheduleIfAbsent 는 없을 때만(ZADD NX).
        doAnswer(i -> {
            String kind = i.getArgument(0);
            if (kind.equals(EngineTimerScheduler.KIND) && failNextGameArm.getAndSet(false)) {
                throw new RedisConnectionFailureException("simulated blip on ZADD deadlines:game");
            }
            queue.computeIfAbsent(kind, k -> new ConcurrentHashMap<>())
                    .put(i.getArgument(1), clock.millis() + Math.max(0, ((Duration) i.getArgument(2)).toMillis()));
            return null;
        }).when(deadlines).schedule(anyString(), anyString(), any());
        when(deadlines.scheduleIfAbsent(anyString(), anyString(), any())).thenAnswer(i ->
                queue.computeIfAbsent((String) i.getArgument(0), k -> new ConcurrentHashMap<>())
                        .putIfAbsent(i.getArgument(1),
                                clock.millis() + Math.max(0, ((Duration) i.getArgument(2)).toMillis())) == null);
        doAnswer(i -> {
            queue.getOrDefault((String) i.getArgument(0), new ConcurrentHashMap<>()).remove((String) i.getArgument(1));
            return null;
        }).when(deadlines).cancel(anyString(), anyString());

        when(generations.current(ROOM)).thenAnswer(i -> gen.get());
        when(generations.bump(ROOM)).thenAnswer(i -> gen.incrementAndGet());

        when(lock.tryAcquire(ROOM)).thenAnswer(i -> locked.compareAndSet(false, true));
        when(lock.acquireWaiting(ROOM)).thenAnswer(i -> locked.compareAndSet(false, true));
        doAnswer(i -> {
            locked.set(false);
            return null;
        }).when(lock).release(ROOM);

        doAnswer(i -> {
            if (failNextBroadcast.getAndSet(false)) {
                throw new RedisConnectionFailureException("simulated blip on PUBLISH");
            }
            broadcasts.add(List.copyOf((List<? extends GameEvent>) i.getArgument(1)));
            return null;
        }).when(broadcaster).broadcast(anyString(), anyList(), anyList());

        MatchProgressService matchProgress = new MatchProgressService(roomService, metrics, games);
        turnTimeout = new TurnTimeoutScheduler(roomService, engines, broadcaster, lock, matchProgress, botProxy,
                deadlines, generations);
        bots = new BotScheduler(roomService, engines, broadcaster, lock, matchProgress,
                mock(BotUserRegistry.class), turnTimeout, 1L, 0L);
        doAnswer(i -> {
            bots.scheduleBots(i.getArgument(0));
            return null;
        }).when(botProxy).scheduleBots(anyString());
        engineTimer = new EngineTimerScheduler(roomService, engines, broadcaster, lock, matchProgress, botProxy,
                turnTimeout, deadlines, generations);
        kick = new GameProgressKick(roomService, engines, bots, deadlines, generations, Runnable::run);
        controller = new GameStompController(roomService, engines, new ObjectMapper(), broadcaster, lock,
                matchProgress, botProxy, turnTimeout, metrics);
    }

    /** 좌석 0 이 ♥9·♣3 으로 차례. 나머지 좌석은 3장씩. */
    static OneCardState aboutToGoDownToOne(int seatCount) {
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

    /** DeadlinePoller.pollOnce 흉내 — 가짜 시계로 만기분을 원자 pop 해 핸들러에 넘긴다. */
    void pollDue() {
        for (String kind : List.of(TurnTimeoutScheduler.KIND, EngineTimerScheduler.KIND)) {
            Map<String, Long> m = queue.getOrDefault(kind, new ConcurrentHashMap<>());
            List<String> due = m.entrySet().stream().filter(e -> e.getValue() <= clock.millis())
                    .map(Map.Entry::getKey).toList();
            due.forEach(m::remove);
            DeadlineHandler handler = kind.equals(TurnTimeoutScheduler.KIND) ? turnTimeout : engineTimer;
            for (String member : due) {
                try {
                    handler.handle(member);
                } catch (RuntimeException e) {
                    // DeadlinePoller 처럼 삼킨다.
                }
            }
        }
    }

    int armedCount() {
        return queue.values().stream().mapToInt(Map::size).sum();
    }

    void play(long userId, Map<String, Object> action) {
        controller.onAction(ROOM, action, new AuthPrincipal(userId, "u" + userId));
    }

    static Map<String, Object> playHeartNine() {
        return Map.of("@action", "PLAY_CARD", "card", Map.of("suit", "HEART", "rank", 9));
    }

    static void settle() throws InterruptedException {
        Thread.sleep(300); // 봇 루프(가상 스레드, 지연 0)가 돌 시간
    }

    /**
     * C-I2 경로 1 — 창을 연 직후 엔진 타이머 무장(ZADD)이 실패했다. 사람 둘이라 정상이면 3초 뒤 EXPIRED 인데, 10분이 지나도 창이
     * 열려 있었다(그 뒤의 '잡기!'가 벌칙을 줬다). 클라의 마감 + 1.5초 resync 가 건 킥이 타이머를 다시 걸어 다음 폴링에 만료로 닫는다.
     */
    @Test
    void a_kick_after_a_lost_arm_closes_the_race_as_expired_without_a_penalty() throws Exception {
        wire(List.of(10L, 30L), List.of(), 30);
        stored.set(aboutToGoDownToOne(2));
        failNextGameArm.set(true);

        play(10L, playHeartNine());
        settle();
        assertThat(stored.get().race()).as("창이 열렸다").isNotNull();
        assertThat(queue.getOrDefault(EngineTimerScheduler.KIND, Map.of())).as("엔진 타이머 없음").isEmpty();

        clock.advance(10 * 60_000);
        pollDue();
        settle();
        assertThat(stored.get().race()).as("10분 — 창이 그대로").isNotNull();

        kick.kickNow(ROOM, 30L);
        assertThat(queue.get(EngineTimerScheduler.KIND)).as("현 세대로 다시 걸렸다").containsKey(ROOM + "#" + gen.get());
        pollDue();
        settle();

        OneCardState after = stored.get();
        assertThat(after.race()).as("창이 닫혔다").isNull();
        assertThat(after.hands().get(0)).as("만료 — 벌칙 없음").hasSize(1);
        assertThat(broadcasts.getLast()).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(OneCardEvent.RaceResolved.class, resolved ->
                        assertThat(resolved.outcome()).isEqualTo(OneCardEvent.RaceOutcome.EXPIRED)));
    }

    /**
     * C-I2 경로 3 — 봇의 '잡기!' 발화가 창을 닫아 저장한 뒤 방송이 실패했다. 차례는 봇인데 턴 제한이 꺼져 걸린 데드라인이 0 개라
     * 아무도 봇을 깨우지 않았다. 킥 한 번에 봇이 둔다.
     */
    @Test
    void a_kick_after_a_fire_failure_resumes_the_bot_turn() throws Exception {
        wire(List.of(10L, 20L, 30L), List.of(1), 0);
        stored.set(aboutToGoDownToOne(3));
        play(10L, playHeartNine());
        settle();
        clock.advance(2_600); // 봇 반응(1.0~2.5초) 지남
        failNextBroadcast.set(true);
        pollDue();
        settle();
        OneCardState stuck = stored.get();
        assertThat(stuck.race()).as("창은 닫혀 저장됐다").isNull();
        assertThat(stuck.turnSeat()).as("차례는 봇").isEqualTo(1);
        assertThat(armedCount()).as("걸린 데드라인 0").isZero();

        kick.kickNow(ROOM, 10L);
        settle();

        assertThat(stored.get().version()).as("킥 한 번으로 봇이 둔다").isGreaterThan(stuck.version());
    }

    /**
     * C-I1 — 재기동(배포·Fly 자동 정지)으로 메모리의 봇 루프가 사라졌다. Redis 에는 봇(좌석 1) 차례로 저장된 상태와 세대만 남고
     * 턴 제한이 꺼져 데드라인도 없다. 다시 붙는 클라들의 킥(마운트 resync·구독·접속 resync — 세 번)이 봇 차례를 이어 준다.
     * (킥이 루프를 겹쳐 걸지 않는지는 {@link BotSchedulerTest} 가 본다 — 여기서는 봇이 하나·지연 0 이라 겹침이 보이지 않는다.)
     */
    @Test
    void after_a_restart_the_reconnecting_clients_kicks_resume_the_bot_turn() throws Exception {
        wire(List.of(10L, 20L, 30L), List.of(1), 0);
        OneCardState base = aboutToGoDownToOne(3);
        stored.set(new OneCardState(base.hands(), base.drawPile(), base.discardPile(), 1, 1, null, 0, null,
                List.of(), 0, 0, 1, null));
        gen.set(5);
        int before = stored.get().version();

        kick.kickNow(ROOM, 10L);
        kick.kickNow(ROOM, 10L);
        kick.kickNow(ROOM, 30L);
        settle();

        assertThat(stored.get().version()).as("봇이 뒀다").isGreaterThan(before);
        assertThat(stored.get().turnSeat()).as("봇 차례를 벗어났다").isNotEqualTo(1);
    }
}
```

`server/src/test/java/com/mirboard/infra/bot/BotSchedulerTest.java`:

```java
package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.testsupport.LogCapture;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * S5 — 봇 루프는 이 인스턴스에서 방마다 몇 개가 살아 있는지 센다. 진행 킥({@link GameProgressKick})은 resync·구독마다
 * 불리므로, 살아 있는 루프가 있는데도 하나 더 걸면 두 루프가 번갈아 락을 잡아 봇이 지연 없이 연달아 둔다. 그래서 킥은
 * {@link BotScheduler#scheduleBotsIfIdle} 로만 건다. 루프가 "봇이 기다리지 않음"으로 끝나는 것은 그런 겹친 루프·사람
 * 차례 인계마다 일어나는 정상 경로라 DEBUG 로 남긴다(예전 WARN 은 봇 방 사람 차례마다 Sentry breadcrumb 을 채웠다).
 */
class BotSchedulerTest {

    private static final String ROOM = "r1";

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);

    private BotScheduler scheduler(long botDelayMillis) {
        return new BotScheduler(roomService, engines, mock(GameEventBroadcaster.class), lock,
                mock(MatchProgressService.class), mock(BotUserRegistry.class), mock(TurnTimeoutScheduler.class),
                1L, botDelayMillis);
    }

    /** 봇(좌석 1)이 있는 진행 중 방인데 지금은 사람(좌석 0) 차례 — 루프는 지연 뒤 락을 잡고 할 일 없이 끝난다. */
    private void humanTurnInABotRoom() {
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "ANY", 10L, RoomStatus.IN_GAME, 2, 2,
                List.of(10L, 20L), Set.of(), TeamPolicy.SEQUENTIAL, 0L, true, List.of(1), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.pendingSeats(state)).thenReturn(List.of(0));
        when(engine.phaseName(state)).thenReturn("PLAYING");
    }

    @Test
    void the_idle_entry_starts_no_second_loop_while_one_is_alive() {
        humanTurnInABotRoom();
        when(lock.tryAcquire(ROOM)).thenReturn(true);
        BotScheduler bots = scheduler(300);

        bots.scheduleBots(ROOM); // 300ms 지연 중인 살아 있는 루프

        assertThat(bots.scheduleBotsIfIdle(ROOM)).as("살아 있는 루프 위에 겹쳐 걸지 않는다").isFalse();
        verify(lock, timeout(2_000)).release(ROOM);
        // 첫 루프가 끝나면 다시 걸 수 있고, 그렇게 건 루프도 살아 있는 루프로 센다.
        await().atMost(Duration.ofSeconds(2)).until(() -> bots.scheduleBotsIfIdle(ROOM));
        assertThat(bots.scheduleBotsIfIdle(ROOM)).isFalse();
    }

    /**
     * 락 경합으로 잠시 뒤 다시 도는 토막도 같은 루프다 — 그 사이에 킥이 하나 더 걸면 안 된다. 재시도 토막이 락에 들어온
     * 시점(첫 토막은 50ms + 지연 전에 이미 끝났다)에 붙잡고 단언해야 재시도 토막을 세는지 가려진다 — 첫 토막이 살아 있는
     * 동안 단언하면 계수를 지워도 통과한다(사전 리뷰 M-1).
     */
    @Test
    void only_the_retry_segment_is_alive_and_still_blocks_a_second_loop() throws Exception {
        humanTurnInABotRoom();
        CountDownLatch retrying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        when(lock.tryAcquire(ROOM)).thenAnswer(i -> {
            if (attempts.incrementAndGet() == 1) {
                return false; // 첫 토막 → 재시도 토막을 걸고 끝난다
            }
            retrying.countDown(); // 재시도 토막이 50ms + 지연 뒤 여기 왔다 — 첫 토막은 끝난 지 오래다
            release.await(5, TimeUnit.SECONDS);
            return true;
        });
        BotScheduler bots = scheduler(20);

        bots.scheduleBots(ROOM);
        assertThat(retrying.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(bots.scheduleBotsIfIdle(ROOM)).as("재시도 토막만 살아 있어도 겹쳐 걸지 않는다").isFalse();
        } finally {
            release.countDown();
        }
        verify(lock, timeout(2_000)).release(ROOM);
    }

    @Test
    void a_loop_ending_on_a_human_turn_is_a_debug_line_not_a_warning() {
        humanTurnInABotRoom();
        when(lock.tryAcquire(ROOM)).thenReturn(true);

        try (LogCapture logs = LogCapture.of(BotScheduler.class)) {
            scheduler(0).scheduleBots(ROOM);
            verify(lock, timeout(2_000)).release(ROOM);

            await().atMost(Duration.ofSeconds(2)).until(() -> logs.events().stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("no pending bot action")));
            assertThat(logs.events())
                    .filteredOn(e -> e.getFormattedMessage().contains("no pending bot action"))
                    .extracting(ILoggingEvent::getLevel)
                    .containsOnly(Level.DEBUG);
            assertThat(logs.messages(Level.WARN)).isEmpty();
        }
    }
}
```

`server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java`:

```diff
 package com.mirboard.infra.bot;
 
+import static org.assertj.core.api.Assertions.assertThat;
 import static org.mockito.ArgumentMatchers.any;
 import static org.mockito.ArgumentMatchers.anyString;
 import static org.mockito.ArgumentMatchers.eq;
+import static org.mockito.Mockito.doThrow;
 import static org.mockito.Mockito.inOrder;
 import static org.mockito.Mockito.mock;
 import static org.mockito.Mockito.never;
 import static org.mockito.Mockito.verify;
 import static org.mockito.Mockito.when;
 
+import ch.qos.logback.classic.Level;
 import com.mirboard.domain.game.core.GameEngine;
 import com.mirboard.domain.game.core.GameEvent;
 import com.mirboard.domain.game.core.GameState;
```

```diff
 import com.mirboard.infra.ws.GameEventBroadcaster;
 import com.mirboard.infra.ws.MatchProgressService;
 import com.mirboard.infra.ws.RoomActionLock;
+import com.mirboard.testsupport.LogCapture;
 import java.time.Duration;
 import java.util.List;
 import java.util.Optional;
```

```diff
 
             verify(deadlines).schedule(TurnTimeoutScheduler.KIND, "r1#4", Duration.ofSeconds(30));
         }
+
+        /**
+         * S5 — 조용히 사라지던 타이머가 보이게. 무장 실패는 그 창이 진행 킥({@link GameProgressKick}) 전까지 닫히지
+         * 않는다는 뜻이라 WARN 이 아니라 ERROR 다(Sentry 는 ERROR 만 올린다).
+         */
+        @Test
+        void a_failed_arm_is_logged_as_an_error() {
+            when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME, 0));
+            when(generations.current("r1")).thenReturn(3L);
+            when(generations.bump("r1")).thenReturn(4L);
+            engineWithState();
+            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1500)));
+            doThrow(new IllegalStateException("simulated blip on ZADD deadlines:game"))
+                    .when(deadlines).schedule(eq(EngineTimerScheduler.KIND), anyString(), any());
+
+            try (LogCapture logs = LogCapture.of(TurnTimeoutScheduler.class)) {
+                turnTimeout.onTurnAdvanced("r1");
+
+                assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("Engine timer arm failed"));
+                assertThat(logs.messages(Level.WARN)).noneMatch(m -> m.contains("Engine timer arm failed"));
+            }
+        }
     }
 
     @Nested
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerResyncLockTest.java`:

```diff
 import static org.assertj.core.api.Assertions.assertThatThrownBy;
 import static org.mockito.ArgumentMatchers.any;
 import static org.mockito.ArgumentMatchers.anyInt;
+import static org.mockito.ArgumentMatchers.anyLong;
 import static org.mockito.ArgumentMatchers.anyString;
 import static org.mockito.Mockito.inOrder;
 import static org.mockito.Mockito.mock;
```

```diff
 import com.mirboard.domain.lobby.room.RoomService;
 import com.mirboard.domain.lobby.room.RoomStatus;
 import com.mirboard.domain.lobby.room.TeamPolicy;
+import com.mirboard.infra.bot.GameProgressKick;
 import com.mirboard.infra.ws.DesertionService;
 import com.mirboard.infra.ws.GameAbortService;
 import com.mirboard.infra.ws.GameEngineProvider;
```

```diff
     private final RoomActionLock lock = mock(RoomActionLock.class);
     private final GameEngine engine = mock(GameEngine.class);
     private final GameState state = mock(GameState.class);
+    private final GameProgressKick kick = mock(GameProgressKick.class);
 
     private final RoomController controller = new RoomController(
             rooms, engines, seqs, mock(DesertionService.class), mock(RoomPresence.class),
-            mock(RoomChipStore.class), mock(GameAbortService.class), lock);
+            mock(RoomChipStore.class), mock(GameAbortService.class), lock, kick);
 
     private final AuthPrincipal me = new AuthPrincipal(ME, "me");
 
```

```diff
         assertThatThrownBy(() -> controller.resync(ROOM, me))
                 .isInstanceOf(ResyncNotAvailableException.class);
         verify(lock).release(ROOM);
+        verify(kick, never()).kick(anyString(), anyLong());
+    }
+
+    /**
+     * S5 — resync 는 클라가 방을 다시 보는 순간이라, 멈춘 진행(재기동 뒤 봇 차례·사라진 엔진 타이머)을 다시 거는 자리다.
+     * 락을 푼 <b>뒤</b>에 건다 — 킥은 비동기라 응답을 늦추지 않고, 락 안에서 걸면 킥이 같은 락을 기다린다.
+     */
+    @Test
+    void the_progress_kick_goes_out_after_the_lock_is_released() {
+        givenGameInProgress();
+        when(lock.acquireWaiting(ROOM)).thenReturn(true);
+
+        controller.resync(ROOM, me);
+
+        InOrder order = inOrder(lock, kick);
+        order.verify(lock).release(ROOM);
+        order.verify(kick).kick(ROOM, ME);
+    }
+
+    @Test
+    void an_unlocked_fallback_read_still_kicks() {
+        givenGameInProgress();
+        when(lock.acquireWaiting(ROOM)).thenReturn(false);
+
+        controller.resync(ROOM, me);
+
+        verify(kick).kick(ROOM, ME);
     }
 }
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerLeaveTest.java`:

```diff
 import com.mirboard.domain.lobby.room.RoomService;
 import com.mirboard.domain.lobby.room.RoomStatus;
 import com.mirboard.domain.lobby.room.TeamPolicy;
+import com.mirboard.infra.bot.GameProgressKick;
 import com.mirboard.infra.ws.DesertionService;
 import com.mirboard.infra.ws.GameAbortService;
 import com.mirboard.infra.ws.GameEngineProvider;
```

```diff
 
     private final RoomController controller = new RoomController(
             rooms, engines, mock(RoomSeq.class), desertion, mock(RoomPresence.class),
-            mock(RoomChipStore.class), mock(GameAbortService.class), mock(RoomActionLock.class));
+            mock(RoomChipStore.class), mock(GameAbortService.class), mock(RoomActionLock.class),
+            mock(GameProgressKick.class));
 
     private final AuthPrincipal me = new AuthPrincipal(ME, "me");
 
```

`server/src/test/java/com/mirboard/infra/ws/WsSessionLifecycleListenerTest.java`:

```java
package com.mirboard.infra.ws;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.bot.GameProgressKick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * S5 — 게임 토픽({@code /topic/room/{id}}) 구독은 클라가 (재)접속해 판을 다시 보기 시작한 순간이다 — 배포 뒤에는 모든
 * 클라가 다시 붙으며 이 구독을 보낸다. 그때 진행 킥을 건다. 같은 세션이 함께 구독하는 대기실 메타·채팅·리액션
 * 토픽에는 걸지 않는다(한 접속에 한 번이면 된다).
 */
class WsSessionLifecycleListenerTest {

    private final RoomPresence presence = mock(RoomPresence.class);
    private final RoomDisconnectHandler disconnects = mock(RoomDisconnectHandler.class);
    private final GameProgressKick kick = mock(GameProgressKick.class);
    private final WsSessionLifecycleListener listener =
            new WsSessionLifecycleListener(presence, disconnects, kick);

    private static SessionSubscribeEvent subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("ws-1");
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(new Object(), message, new AuthPrincipal(11L, "tester"));
    }

    @Test
    void subscribing_the_game_topic_kicks_the_room() {
        listener.onSubscribe(subscribe("/topic/room/r1"));

        verify(presence).join("ws-1", 11L, "r1");
        // 구독자를 넘긴다 — 킥은 참가자·관전자의 것만 받는다(GameProgressKickTest).
        verify(kick).kick("r1", 11L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/room/r1/meta", "/topic/room/r1/chat", "/topic/room/r1/reaction",
            "/topic/lobby/chat"})
    void other_topics_do_not_kick(String destination) {
        listener.onSubscribe(subscribe(destination));

        verify(kick, never()).kick(anyString(), anyLong());
    }
}
```

`server/src/test/java/com/mirboard/infra/scheduling/DistributedInfraIT.java`:

```diff
         assertThat(deadlines.pollDue("turn")).isEmpty();
     }
 
+    /**
+     * S5 — 진행 킥은 이 세대의 엔진 타이머를 <b>없을 때만</b> 건다(ZADD NX). 이미 걸린 무장을 덮으면 킥이 락 없이 읽은 낡은 남은
+     * 시간으로 정상 무장을 늦출 수 있었다. pop 된 항목은 없는 것이라 다시 걸린다.
+     */
+    @Test
+    void schedule_if_absent_never_overwrites_an_armed_deadline() {
+        deadlines.schedule("game", "room-1#3", Duration.ofHours(1));
+
+        assertThat(deadlines.scheduleIfAbsent("game", "room-1#3", Duration.ZERO)).isFalse();
+        assertThat(deadlines.pollDue("game")).as("1시간 무장이 그대로 — 0 으로 덮이지 않았다").isEmpty();
+
+        deadlines.schedule("game", "room-2#1", Duration.ZERO);
+        assertThat(deadlines.pollDue("game")).containsExactly("room-2#1");
+        assertThat(deadlines.scheduleIfAbsent("game", "room-2#1", Duration.ZERO)).as("pop 뒤에는 없다").isTrue();
+        assertThat(deadlines.pollDue("game")).containsExactly("room-2#1");
+    }
+
     @Test
     void cancel_removes_a_pending_deadline() {
         deadlines.schedule("desertion", "room-1:7", Duration.ZERO);
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.GameProgressKickTest" --tests "com.mirboard.infra.bot.GameProgressKickScenarioTest" --tests "com.mirboard.infra.bot.BotSchedulerTest" --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerResyncLockTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerLeaveTest" --tests "com.mirboard.infra.ws.WsSessionLifecycleListenerTest"`
Expected: FAIL — `compileTestJava` 실패(`33 errors`), 전부 `error: cannot find symbol` — `symbol: class GameProgressKick` ·
`symbol: method scheduleBotsIfIdle(String)` · `symbol: method scheduleIfAbsent(String,String,Duration)`(모의 `any()` 자리는 `(String,String,Object)`).
새 기능이 없어서 생기는 정상 RED 다 — 동작 판별력은 Step 5 에서 확인한다.

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java`:

```diff
     }
 
     /**
+     * S5 — 그 항목이 큐에 없을 때만 건다(`ZADD NX`). 더했으면 true. 이미 걸린 무장(정상 무장·락 경합 재시도·미만기 재무장)을
+     * 덮지 않는다 — 진행 킥이 "사라졌나"를 따로 읽고 쓰면, 그 사이 걸린 정상 무장을 킥이 락 없이 읽은 낡은 남은 시간으로
+     * 덮을 수 있었다. pop 된 항목·취소된 항목은 없는 것이다.
+     */
+    public boolean scheduleIfAbsent(String kind, String member, Duration delay) {
+        long dueAt = clock.millis() + Math.max(0L, delay.toMillis());
+        Boolean added = redis.opsForZSet().addIfAbsent(key(kind), member, dueAt);
+        redis.expire(key(kind), Duration.ofHours(12));
+        return Boolean.TRUE.equals(added);
+    }
+
+    /**
      * 만료분을 원자적으로 pop. 반환된 항목은 <b>이 인스턴스가 단독 소유</b>하므로
      * 호출자가 반드시 처리해야 한다(다시 큐에 없음).
      */
```

`server/src/main/java/com/mirboard/infra/bot/BotScheduler.java`:

```diff
 import java.util.ArrayList;
 import java.util.List;
 import java.util.Random;
+import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.ExecutorService;
 import java.util.concurrent.Executors;
 import org.slf4j.Logger;
```

```diff
  * <p>D-122 — <b>IN_GAME 인 방만</b> 진행한다(락 전·락 안 두 번 확인). 탈주 조기 종료·강제
  * 종료로 끝난 방에서 이미 돌던 루프가 버려진 라운드를 계속 두던 경로를 막는다. 게임 중립
  * 판정(방 상태)이다.
+ *
+ * <p>S5 — 이 인스턴스에서 방마다 <b>살아 있는 루프 수</b>를 센다(락 경합으로 잠시 뒤 다시 도는 토막도 같은
+ * 루프다). 진행 킥({@link GameProgressKick})은 resync·구독마다 불리므로 {@link #scheduleBotsIfIdle} 로만 건다 —
+ * 살아 있는 루프 위에 하나 더 걸면 두 루프가 번갈아 락을 잡아 봇이 지연 없이 연달아 둔다. 다른 인스턴스의 루프는
+ * 보이지 않는다(그때 겹쳐도 락 안 재조회 덕에 같은 수를 두 번 두지는 않고 속도만 빨라진다).
  */
 @Component
 public class BotScheduler {
 
     private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
     private static final int MAX_BOT_ACTIONS_PER_ROOM = 5000;
+
+    /** S5 — 방별로 살아 있는 루프 토막 수(이 인스턴스). 0 이 되면 키를 지운다. */
+    private final ConcurrentHashMap<String, Integer> liveLoops = new ConcurrentHashMap<>();
 
     private final RoomService roomService;
     private final GameEngineProvider engines;
```

```diff
 
     /** 비동기 진입점. 호출자는 락 비점유 상태여야 한다. */
     public void scheduleBots(String roomId) {
-        executor.execute(() -> runRoom(roomId, 0));
+        liveLoops.merge(roomId, 1, Integer::sum);
+        start(roomId, () -> runRoom(roomId, 0));
+    }
+
+    /**
+     * S5 — 진행 킥 전용 진입점. 이 인스턴스에 이 방의 루프가 하나도 살아 있지 않을 때만 건다(확인과 등록이 원자적이라
+     * 동시에 들어온 킥 둘이 둘 다 걸지 않는다).
+     *
+     * @return 루프를 걸었으면 true
+     */
+    public boolean scheduleBotsIfIdle(String roomId) {
+        if (liveLoops.putIfAbsent(roomId, 1) != null) {
+            return false;
+        }
+        start(roomId, () -> runRoom(roomId, 0));
+        return true;
+    }
+
+    /** 루프 한 토막을 가상 스레드로 돌린다. 부르는 쪽이 {@link #liveLoops} 에 몫을 먼저 더해 두고, 토막이 끝나면 덜어 낸다. */
+    private void start(String roomId, Runnable body) {
+        try {
+            executor.execute(() -> {
+                try {
+                    body.run();
+                } finally {
+                    loopEnded(roomId);
+                }
+            });
+        } catch (RuntimeException | Error e) {
+            // 종료 중 거절·메모리 부족 등 — 돌지 못한 토막의 몫을 되돌린다(안 그러면 그 방의 킥이 재기동 전까지 늘 막힌다).
+            loopEnded(roomId);
+            throw e;
+        }
+    }
+
+    private void loopEnded(String roomId) {
+        liveLoops.computeIfPresent(roomId, (id, live) -> live > 1 ? live - 1 : null);
     }
 
     private void runRoom(String roomId, int iterations) {
```

```diff
         }
 
         if (!lock.tryAcquire(roomId)) {
-            // 다른 액션 처리 중 — 잠시 후 재시도.
-            executor.execute(() -> {
+            // 다른 액션 처리 중 — 잠시 후 재시도. 이어 가는 토막도 살아 있는 루프로 센다(지금 토막이 끝나기 전에 더한다).
+            liveLoops.merge(roomId, 1, Integer::sum);
+            start(roomId, () -> {
                 try {
                     Thread.sleep(50);
                 } catch (InterruptedException e) {
```

```diff
 
             int botSeat = nextBotSeat(room, engine, state);
             if (botSeat < 0) {
-                log.warn("Bot loop: no pending bot action. roomId={} phase={} botSeats={} pending={}",
+                // S5 — 정상 경로다(봇 방의 사람 차례 인계, 경쟁 창, 같은 방에 겹쳐 돈 루프). WARN 이던 때는 봇 방 사람
+                // 차례마다 남아 Sentry breadcrumb 을 채웠다. 정말 이상한 "Bot has no legal action" 은 WARN 그대로다.
+                log.debug("Bot loop: no pending bot action. roomId={} phase={} botSeats={} pending={}",
                         roomId, engine.phaseName(state), room.botSeats(),
                         engine.pendingSeats(state));
                 return;
```

`server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java`:

```java
package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * S5 — 진행 킥. 방 진행이 메모리의 봇 루프와 유실될 수 있는 타이머에만 기대지 않게, 클라가 방을 다시 볼 때 — resync
 * 응답 뒤({@code RoomController}), 게임 토픽 구독({@code WsSessionLifecycleListener}) — 멈춘 진행을 다시 건다.
 *
 * <ul>
 *   <li><b>(a) 봇 루프</b> — 대기 좌석에 봇이 있는데 이 인스턴스에 그 방의 루프가 없으면 건다. 재기동(배포·Fly 자동
 *       정지)은 메모리의 루프를 지우고, 턴 제한을 끈 방(기본)에는 깨워 줄 데드라인도 없어 봇 차례에서 매치가 영구
 *       정지했다(C-I1). 살아 있는 루프 위에는 겹쳐 걸지 않는다({@link BotScheduler#scheduleBotsIfIdle}).</li>
 *   <li><b>(b) 엔진 타이머</b> — 상태가 타이머를 선언했는데 이 세대의 데드라인이 큐에 없으면 현 세대로 다시 건다.
 *       무장 실패·발화 중 실패·저장~무장 사이 종료로 타이머가 사라지면 경쟁 창이 영원히 열려 있었다(C-I2). 그 member 가
 *       <b>없을 때만 더한다</b>({@link DeadlineQueue#scheduleIfAbsent}, ZADD NX) — 확인과 쓰기가 원자라 그 사이 걸린 정상
 *       무장(재시도·미만기 재무장 포함)을 락 없이 읽은 낡은 남은 시간으로 덮지 않는다. 남은 시간은 상태가 정하므로 창을
 *       늘리지도 앞당기지도 않는다(이미 지났으면 0 — 바로 발화). 낡은 킥이 틈에서 걸어도 발화 쪽의 세대·락·{@code timer}
 *       재확인이 그대로 지킨다.</li>
 * </ul>
 *
 * <p><b>참가자·관전자의 킥만 받는다.</b> 공개 토픽 구독은 로그인한 누구나 할 수 있고 SUBSCRIBE 는 레이트리밋 밖이라,
 * 아무나의 구독 폭주가 킥마다 Redis 왕복 몇 번씩으로 커지지 않게 한다(킥은 어차피 방을 읽는다).
 *
 * <p><b>턴 진행({@code onTurnAdvanced})은 부르지 않는다.</b> 부르면 세대가 오르고 턴 데드라인이 처음부터 다시 걸려,
 * resync 를 반복하는 클라가 시간 초과를 끝없이 미룰 수 있다. 끝난 방·대기실·없는 방·시작 전 게임은 아무것도
 * 하지 않는다.
 *
 * <p>게임을 모른다 — 누가 기다리는지·타이머가 있는지는 엔진 포트가 답한다. 호출한 요청(resync 응답·구독 처리)을
 * 늦추지 않게 가상 스레드에서 돌고, 실패는 남기고 삼킨다(킥이 없던 때와 같아질 뿐이다). {@code Error} 는 ERROR 로 남긴다 —
 * 가상 스레드의 기본 처리기(stderr)로 가면 Sentry 에 보이지 않는다.
 */
@Component
public class GameProgressKick {

    private static final Logger log = LoggerFactory.getLogger(GameProgressKick.class);

    private final RoomService rooms;
    private final GameEngineProvider engines;
    private final BotScheduler bots;
    private final DeadlineQueue deadlines;
    private final RoomGeneration generations;
    private final Executor executor;

    @Autowired
    public GameProgressKick(RoomService rooms,
                            GameEngineProvider engines,
                            BotScheduler bots,
                            DeadlineQueue deadlines,
                            RoomGeneration generations) {
        this(rooms, engines, bots, deadlines, generations,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mirboard-kick-", 0).factory()));
    }

    /** 테스트용 — 실행기를 바꿔 끼운다(직접 실행기면 호출한 스레드에서 바로 돈다). */
    GameProgressKick(RoomService rooms,
                     GameEngineProvider engines,
                     BotScheduler bots,
                     DeadlineQueue deadlines,
                     RoomGeneration generations,
                     Executor executor) {
        this.rooms = rooms;
        this.engines = engines;
        this.bots = bots;
        this.deadlines = deadlines;
        this.generations = generations;
        this.executor = executor;
    }

    /**
     * 비동기 진입점 — 호출한 요청을 늦추지 않는다. 락을 쥔 채 부르지 말 것(봇 루프가 같은 락을 기다린다).
     *
     * @param userId 방을 다시 보는 사람(resync 요청자·게임 토픽 구독자). 참가자·관전자가 아니면 아무것도 안 한다
     */
    public void kick(String roomId, long userId) {
        executor.execute(() -> {
            try {
                kickNow(roomId, userId);
            } catch (RuntimeException e) {
                log.warn("Progress kick failed: roomId={} err={}", roomId, e.toString());
            } catch (Error e) {
                log.error("Progress kick failed: roomId={}", roomId, e);
            }
        });
    }

    /** 한 번의 킥. 테스트가 실행기 없이 부를 수 있게 package-private. */
    void kickNow(String roomId, long userId) {
        Room room;
        try {
            room = rooms.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            return;
        }
        if (room.status() != RoomStatus.IN_GAME) {
            return;
        }
        if (!room.playerIds().contains(userId) && !room.spectatorIds().contains(userId)) {
            return;
        }
        GameEngine engine = engines.forRoom(room);
        GameState state = engine.loadState().orElse(null);
        if (state == null || engine.isRoundOver(state)) {
            return;
        }

        boolean botPending = engine.pendingSeats(state).stream().anyMatch(room.botSeats()::contains);
        if (botPending && bots.scheduleBotsIfIdle(roomId)) {
            log.info("Progress kick resumed the bot loop: roomId={} phase={}", roomId, engine.phaseName(state));
        }

        engine.timer(state).ifPresent(left -> rearmIfLost(roomId, left));
    }

    /**
     * (b) — 이 세대의 엔진 타이머가 큐에 없을 때만 남은 시간(이미 지났으면 0)으로 건다. 더한 경우는 유실만이 아니다 — 폴러가
     * pop 한 뒤 그 발화가 세대를 올리기 전(수십 ms)에도 항목이 없다. 그때 더한 항목은 세대 불일치로 버려져 무해하므로 INFO 다.
     */
    private void rearmIfLost(String roomId, Duration left) {
        long gen = generations.current(roomId);
        String member = TurnTimeoutScheduler.member(roomId, gen);
        if (deadlines.scheduleIfAbsent(EngineTimerScheduler.KIND, member, left)) {
            log.info("Progress kick armed an absent engine timer (lost, or popped and still in flight): "
                    + "roomId={} gen={} leftMs={}", roomId, gen, left.toMillis());
        }
    }
}
```

`server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java`:

```diff
     /**
      * D-128 — 엔진이 시간 전이를 선언하면 남은 시간 뒤로 엔진 타이머를 건다. 실패해도 턴 타이머는
      * 막지 않는다(로그만) — 엔진 타이머가 없는 게임의 진행이 이 경로 때문에 멈추면 안 된다.
+     *
+     * <p>S5 — 실패는 ERROR 다. 타이머가 없으면 경쟁 창이 진행 킥({@link GameProgressKick})이 다시 걸 때까지 닫히지
+     * 않는다 — WARN 이던 때는 그렇게 사라진 타이머가 Sentry(ERROR 만)에 보이지 않았다.
      */
     private void armEngineTimer(String roomId, Room room, long gen) {
         try {
```

```diff
                     .ifPresent(delay -> deadlines.schedule(
                             EngineTimerScheduler.KIND, member(roomId, gen), delay));
         } catch (RuntimeException e) {
-            log.warn("Engine timer arm failed: roomId={} err={}", roomId, e.toString());
+            log.error("Engine timer arm failed: roomId={} err={}", roomId, e.toString(), e);
         }
     }
 
```

`server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java`:

```diff
 import com.mirboard.domain.lobby.room.RoomService;
 import com.mirboard.domain.lobby.room.RoomStatus;
 import com.mirboard.domain.lobby.room.TeamPolicy;
+import com.mirboard.infra.bot.GameProgressKick;
 import com.mirboard.infra.ws.DesertionService;
 import com.mirboard.infra.ws.GameAbortService;
 import com.mirboard.infra.ws.GameEngineProvider;
```

```diff
     private final RoomChipStore chipStore;
     private final GameAbortService aborts;
     private final RoomActionLock lock;
+    private final GameProgressKick kick;
 
     public RoomController(RoomService rooms,
                           GameEngineProvider engines,
```

```diff
                           RoomPresence sessions,
                           RoomChipStore chipStore,
                           GameAbortService aborts,
-                          RoomActionLock lock) {
+                          RoomActionLock lock,
+                          GameProgressKick kick) {
         this.rooms = rooms;
         this.engines = engines;
         this.seqs = seqs;
```

```diff
         this.chipStore = chipStore;
         this.aborts = aborts;
         this.lock = lock;
+        this.kick = kick;
     }
 
     @GetMapping
```

```diff
                 lock.release(roomId);
             }
         }
+        // S5 — 클라가 판을 다시 보는 순간: 멈춘 진행(재기동 뒤 봇 차례·사라진 엔진 타이머)을 다시 건다. 락을 푼 뒤에
+        // 걸고(킥이 같은 락을 기다리지 않게), 킥은 비동기라 이 응답을 늦추지 않는다. 턴 데드라인은 건드리지 않는다.
+        kick.kick(roomId, me.userId());
         return new ResyncResponse(
                 roomId,
                 snap.phase(),
```

`server/src/main/java/com/mirboard/infra/ws/WsSessionLifecycleListener.java`:

```diff
 package com.mirboard.infra.ws;
 
 import com.mirboard.domain.lobby.auth.AuthPrincipal;
+import com.mirboard.infra.bot.GameProgressKick;
 import java.security.Principal;
 import java.util.regex.Matcher;
 import java.util.regex.Pattern;
```

```diff
  * `/chat`)을 구독할 때 세션→방을 {@link RoomPresence}(Redis) 에 기록하고,
  * 끊김 시 제거 후 {@link RoomDisconnectHandler} 로 정리/유예를 위임한다.
  * 로비 채팅(`/topic/lobby/chat`) 등 방과 무관한 구독은 무시한다.
+ *
+ * <p>S5 — 게임 토픽 그 자체(`/topic/room/{id}`) 구독은 클라가 (재)접속해 판을 다시 보기 시작한 순간이다(배포
+ * 뒤에는 모든 클라가 다시 붙는다). 그때 {@link GameProgressKick} 을 건다(킥은 그 방의 참가자·관전자 구독만 받는다).
+ * 같은 세션이 함께 구독하는 메타·채팅·리액션 토픽에는 걸지 않는다 — 한 접속에 한 번이면 된다.
  */
 @Component
 public class WsSessionLifecycleListener {
```

```diff
 
     private final RoomPresence presence;
     private final RoomDisconnectHandler disconnectHandler;
+    private final GameProgressKick kick;
 
     public WsSessionLifecycleListener(RoomPresence presence,
-                                      RoomDisconnectHandler disconnectHandler) {
+                                      RoomDisconnectHandler disconnectHandler,
+                                      GameProgressKick kick) {
         this.presence = presence;
         this.disconnectHandler = disconnectHandler;
+        this.kick = kick;
     }
 
     @EventListener
```

```diff
         presence.join(sessionId, userId, roomId);
         // 끊김 유예 중이던 플레이어가 방 토픽을 재구독 = 재접속 → 유예 취소 + 알림.
         disconnectHandler.onReconnect(roomId, userId);
+        // S5 — 게임 토픽 그 자체일 때만(비동기 — 구독 처리를 늦추지 않는다).
+        if (destination.equals("/topic/room/" + roomId)) {
+            kick.kick(roomId, userId);
+        }
     }
 
     @EventListener
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.GameProgressKickTest" --tests "com.mirboard.infra.bot.GameProgressKickScenarioTest" --tests "com.mirboard.infra.bot.BotSchedulerTest" --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerResyncLockTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerLeaveTest" --tests "com.mirboard.infra.ws.WsSessionLifecycleListenerTest"`
Expected: PASS — 51건(약 4초 — 시나리오 테스트가 봇 가상 스레드를 `Thread.sleep(300)` 으로 기다린다).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.scheduling.DistributedInfraIT"`
Expected: PASS — 11건(`schedule_if_absent_never_overwrites_an_armed_deadline` 포함).

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.*Test"`
Expected: PASS — 44건(23 + 21).

- [ ] **Step 5: 판별력 확인 — 킥을 비우면 무엇이 빨개지나**

`kickNow` 본문을 잠시 비운다:

`server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java`:

```diff
 
     /** 한 번의 킥. 테스트가 실행기 없이 부를 수 있게 package-private. */
     void kickNow(String roomId, long userId) {
-        Room room;
-        try {
-            room = rooms.getRoom(roomId);
-        } catch (RoomNotFoundException e) {
-            return;
-        }
-        if (room.status() != RoomStatus.IN_GAME) {
-            return;
-        }
-        if (!room.playerIds().contains(userId) && !room.spectatorIds().contains(userId)) {
-            return;
-        }
-        GameEngine engine = engines.forRoom(room);
-        GameState state = engine.loadState().orElse(null);
-        if (state == null || engine.isRoundOver(state)) {
-            return;
-        }
-
-        boolean botPending = engine.pendingSeats(state).stream().anyMatch(room.botSeats()::contains);
-        if (botPending && bots.scheduleBotsIfIdle(roomId)) {
-            log.info("Progress kick resumed the bot loop: roomId={} phase={}", roomId, engine.phaseName(state));
-        }
-
-        engine.timer(state).ifPresent(left -> rearmIfLost(roomId, left));
+        // (변이) 본문 비움 — 판별력 확인
     }
 
     /**
```

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.GameProgressKickTest" --tests "com.mirboard.infra.bot.GameProgressKickScenarioTest" --tests "com.mirboard.infra.bot.BotSchedulerTest" --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerResyncLockTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerLeaveTest" --tests "com.mirboard.infra.ws.WsSessionLifecycleListenerTest" --rerun`
Expected: FAIL — `51 tests completed, 9 failed`: `GameProgressKickScenarioTest` 3건(`a_kick_after_a_lost_arm_closes_the_race_as_expired_without_a_penalty`,
`a_kick_after_a_fire_failure_resumes_the_bot_turn`, `after_a_restart_the_reconnecting_clients_kicks_resume_the_bot_turn`), `GameProgressKickTest` 6건
(`Bots.after_a_restart_one_kick_resumes_the_stuck_bot_turn`, `Bots.a_pending_bot_seat_gets_a_loop_only_through_the_idle_check`,
`EngineTimer.a_lost_engine_timer_is_armed_only_if_absent_with_the_current_generation_and_the_time_left`,
`EngineTimer.a_lost_timer_already_past_its_deadline_is_armed_to_fire_now`, `Guards.kick_runs_on_the_executor_and_swallows_failures`,
`Guards.a_non_member_subscription_starts_nothing`). 나머지 42건은 킥 밖의 동작과 "아무것도 안 함"을 고정하는 회귀 테스트라 통과가 정상이다.

되돌린다:

`server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java`:

```diff
 
     /** 한 번의 킥. 테스트가 실행기 없이 부를 수 있게 package-private. */
     void kickNow(String roomId, long userId) {
-        // (변이) 본문 비움 — 판별력 확인
+        Room room;
+        try {
+            room = rooms.getRoom(roomId);
+        } catch (RoomNotFoundException e) {
+            return;
+        }
+        if (room.status() != RoomStatus.IN_GAME) {
+            return;
+        }
+        if (!room.playerIds().contains(userId) && !room.spectatorIds().contains(userId)) {
+            return;
+        }
+        GameEngine engine = engines.forRoom(room);
+        GameState state = engine.loadState().orElse(null);
+        if (state == null || engine.isRoundOver(state)) {
+            return;
+        }
+
+        boolean botPending = engine.pendingSeats(state).stream().anyMatch(room.botSeats()::contains);
+        if (botPending && bots.scheduleBotsIfIdle(roomId)) {
+            log.info("Progress kick resumed the bot loop: roomId={} phase={}", roomId, engine.phaseName(state));
+        }
+
+        engine.timer(state).ifPresent(left -> rearmIfLost(roomId, left));
     }
 
     /**
```

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.GameProgressKickTest" --tests "com.mirboard.infra.bot.GameProgressKickScenarioTest" --tests "com.mirboard.infra.bot.BotSchedulerTest" --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerResyncLockTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerLeaveTest" --tests "com.mirboard.infra.ws.WsSessionLifecycleListenerTest" --rerun`
Expected: PASS — 51건.

- [ ] **Step 6: 커밋**

```bash
git add server/src/test/java/com/mirboard/infra/bot/GameProgressKickTest.java \
  server/src/test/java/com/mirboard/infra/bot/GameProgressKickScenarioTest.java \
  server/src/test/java/com/mirboard/infra/bot/BotSchedulerTest.java \
  server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerResyncLockTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerLeaveTest.java \
  server/src/test/java/com/mirboard/infra/ws/WsSessionLifecycleListenerTest.java \
  server/src/test/java/com/mirboard/infra/scheduling/DistributedInfraIT.java \
  server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java \
  server/src/main/java/com/mirboard/infra/bot/BotScheduler.java \
  server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java \
  server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java \
  server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java \
  server/src/main/java/com/mirboard/infra/ws/WsSessionLifecycleListener.java
git commit -m "feat(S5): 진행 킥 — resync·게임 토픽 구독 때 멈춘 봇 루프·사라진 엔진 타이머를 다시 건다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 서버 — 원카드 경쟁 결과 로그 + 매치 종료 기록 실패 격리

**Files:**
- Create: `server/src/test/java/com/mirboard/infra/ws/OneCardRecorderFailureTest.java`
- Modify: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineTest.java`, `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java`

**Interfaces:**
- Consumes: Task 1 `LogCapture`, 원카드 순수 엔진의 `race()`·`RaceResolved` 이벤트, 동기 기록 리스너 `OneCardMatchRecorder`(이벤트 `OneCardMatchCompleted`).
- Produces: 경쟁이 닫히는 세 경로(`apply` = PRESS, `onTimer` = TIMER, `desert` = DESERTION)에서 INFO 한 줄 —
  `OneCard race resolved: room={} raceId={} outcome={} via={} ownerSeat={} ownerUser={} ownerBot={} bySeat={} byUser={} byBot={} latencyMs={} windowMs={} lateMs={}`
  (`lateMs` 는 TIMER 만, 나머지 `-`; 누른 사람이 없으면 `bySeat=-1 byUser=-`; `room`+`raceId` 의 마지막 줄이 정본 — 저장 전에 찍혀 저장 실패 뒤 다시 찍힐
  수 있다). 메트릭은 건드리지 않는다. 기록 이벤트 발행이 `RuntimeException` 을 던지면 ERROR(room·players·result·스택) 뒤 진행을 잇는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineTest.java`:

```diff
 import static org.mockito.Mockito.verify;
 import static org.mockito.Mockito.when;
 
+import ch.qos.logback.classic.Level;
 import com.mirboard.domain.game.core.GameAction;
 import com.mirboard.domain.game.core.GameContext;
 import com.mirboard.domain.game.core.GameEngine;
 import com.mirboard.domain.game.core.GameEvent;
 import com.mirboard.domain.game.core.GameState;
+import com.mirboard.domain.game.onecard.action.OneCardAction.CallOneCard;
+import com.mirboard.domain.game.onecard.action.OneCardAction.Catch;
 import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
 import com.mirboard.domain.game.onecard.event.OneCardEvent;
 import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
```

```diff
 import com.mirboard.domain.game.onecard.state.OneCardState;
 import com.mirboard.domain.game.onecard.state.OneCardStateMapper.PrivateView;
 import com.mirboard.domain.game.onecard.state.OneCardStateMapper.TableView;
+import com.mirboard.testsupport.LogCapture;
 import java.time.Clock;
 import java.time.Duration;
 import java.time.Instant;
```

```diff
 import java.util.Optional;
 import java.util.Random;
 import java.util.stream.LongStream;
+import org.junit.jupiter.api.Nested;
 import org.junit.jupiter.api.Test;
+import org.springframework.context.ApplicationEventPublisher;
+import org.springframework.dao.DataAccessResourceFailureException;
 
 /**
  * D-128 — 포트 어댑터. 시계를 넣는 자리(경쟁 창 여는 시각·엔진 타이머의 남은 시간), 매치 종료 기록 발행,
```

```diff
     private final List<Object> published = new ArrayList<>();
 
     private OneCardGameEngine engine(long now, int seatCount, Integer... botSeats) {
+        return engine(published::add, now, seatCount, botSeats);
+    }
+
+    private OneCardGameEngine engine(ApplicationEventPublisher publisher, long now, int seatCount,
+                                     Integer... botSeats) {
         List<Long> ids = LongStream.range(0, seatCount).map(i -> 100 + i).boxed().toList();
         return new OneCardGameEngine(new GameContext("room-1", ids, 0, 0, List.of(botSeats)), store,
-                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), new Random(5), FIXED, published::add);
+                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), new Random(5), FIXED, publisher);
     }
 
     /** 좌석 0 이 두 장 중 ♥9 를 내 1장이 되는 3인 테이블. */
```

```diff
         assertThatThrownBy(() -> adapter.apply(aboutToGoDownToOne(), 0, mock(GameAction.class)))
                 .isInstanceOf(IllegalArgumentException.class);
     }
+
+    /**
+     * S5 — 경쟁 창이 닫힐 때마다 결과 한 줄(INFO). 설계서 §4.4·§7 과 D-128 이 "배포 후 경쟁 결과 로그로 다시 본다"고
+     * 미뤄 둔 판단(사람·봇 승률, 반응 시간 분포, 핑 유리, 폴러 지연)과 누름 자동화 탐지의 근거다. 창이 닫히는 세 경로 —
+     * 누름(apply)·엔진 타이머(onTimer)·탈주(desert) — 모두에서 남긴다. 사용자별 값이라 로그로만 둔다(메트릭 태그는
+     * 공개 {@code /actuator/prometheus} 로 나간다). 열 이름이 바뀌면 로그를 읽는 쪽이 깨지므로 문장 전체를 고정한다.
+     */
+    @Nested
+    class RaceResultLog {
+
+        private List<String> raceLines(LogCapture logs) {
+            return logs.messages(Level.INFO).stream().filter(m -> m.startsWith("OneCard race resolved")).toList();
+        }
+
+        @Test
+        void a_human_catching_a_bot_owner_is_one_line_with_the_reaction_time() {
+            OneCardState raced = raced(engine(NOW, 3, 0)); // 주인(좌석 0)이 봇
+            int raceId = raced.race().raceId();
+
+            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
+                engine(NOW + 420, 3, 0).apply(raced, 2, new Catch(raceId));
+
+                assertThat(raceLines(logs)).containsExactly("OneCard race resolved: room=room-1 raceId=" + raceId
+                        + " outcome=CAUGHT via=PRESS ownerSeat=0 ownerUser=100 ownerBot=true"
+                        + " bySeat=2 byUser=102 byBot=false latencyMs=420 windowMs=3000 lateMs=-");
+            }
+        }
+
+        @Test
+        void the_owner_calling_is_logged_and_opening_a_race_is_not() {
+            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
+                OneCardState raced = raced(engine(NOW, 3));
+                assertThat(raceLines(logs)).as("창을 여는 전이는 남기지 않는다").isEmpty();
+
+                engine(NOW + 250, 3).apply(raced, 0, new CallOneCard(raced.race().raceId()));
+
+                assertThat(raceLines(logs)).singleElement().asString()
+                        .contains("outcome=CALLED via=PRESS ownerSeat=0 ownerUser=100 ownerBot=false")
+                        .contains("bySeat=0 byUser=100 byBot=false latencyMs=250");
+            }
+        }
+
+        /** 타이머 경로는 마감보다 얼마나 늦게 처리됐는지(lateMs — 단일 폴러 지연의 실측)도 남긴다. */
+        @Test
+        void a_timer_resolution_also_says_how_late_it_fired() {
+            OneCardState botCatcher = raced(engine(NOW, 3, 2)); // 좌석 2 봇이 1.5초에 잡는다
+            OneCardState noBots = raced(engine(NOW, 3));
+
+            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
+                engine(NOW + 1_530, 3, 2).onTimer(botCatcher);
+                engine(NOW + 3_080, 3).onTimer(noBots);
+
+                assertThat(raceLines(logs)).containsExactly(
+                        "OneCard race resolved: room=room-1 raceId=" + botCatcher.race().raceId()
+                                + " outcome=CAUGHT via=TIMER ownerSeat=0 ownerUser=100 ownerBot=false"
+                                + " bySeat=2 byUser=102 byBot=true latencyMs=1530 windowMs=3000 lateMs=30",
+                        "OneCard race resolved: room=room-1 raceId=" + noBots.race().raceId()
+                                + " outcome=EXPIRED via=TIMER ownerSeat=0 ownerUser=100 ownerBot=false"
+                                + " bySeat=-1 byUser=- byBot=false latencyMs=3080 windowMs=3000 lateMs=80");
+            }
+        }
+
+        @Test
+        void a_desertion_during_the_race_logs_the_cancellation() {
+            OneCardState raced = raced(engine(NOW, 3));
+            when(store.load("room-1")).thenReturn(Optional.of(raced));
+
+            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
+                engine(NOW + 800, 3).desert(1, 101L, new ArrayList<>());
+
+                assertThat(raceLines(logs)).singleElement().asString()
+                        .contains("outcome=CANCELLED via=DESERTION")
+                        .contains("bySeat=-1 byUser=- byBot=false latencyMs=800 windowMs=3000 lateMs=-");
+            }
+        }
+    }
+
+    /**
+     * S5 — 매치 종료 기록기({@code OneCardMatchRecorder})는 동기 리스너라 DB 장애가 어댑터로 올라온다. 예전에는 그대로
+     * 던져 호출한 진행 경로가 저장 뒤 방송·FINISHED 전이·재무장을 건너뛰었다 — 마지막 {@code CARD_PLAYED}·
+     * {@code MATCH_ENDED} 가 아무에게도 안 가고 방은 IN_GAME 에 남았다. 기록이 빠지는 쪽이 덜 아프다: 결과를 실어
+     * ERROR 로 남기고(수동 복구용) 진행은 계속한다.
+     */
+    @Nested
+    class RecorderFailure {
+
+        private final ApplicationEventPublisher failingRecorder = event -> {
+            throw new DataAccessResourceFailureException("simulated DB outage in OneCardMatchRecorder");
+        };
+
+        @Test
+        void the_last_card_still_ends_the_match_and_keeps_the_events_to_broadcast() {
+            OneCardState lastCard = seats(hand(heart(9)), hand(spade(4), spade(6)), hand(diamond(4), diamond(6)))
+                    .top(heart(5)).turn(0).build();
+            OneCardGameEngine adapter = engine(failingRecorder, NOW, 3);
+            GameEngine.Result result = adapter.apply(lastCard, 0, PlayCard.of(heart(9)));
+            List<GameEvent> outbound = new ArrayList<>(result.events());
+
+            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
+                assertThat(adapter.advance(result.newState(), outbound)).isEqualTo(new GameEngine.Advance(true, true));
+
+                assertThat(logs.events()).anySatisfy(event -> {
+                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
+                    assertThat(event.getFormattedMessage()).contains("room=room-1").contains("FINISHED");
+                    assertThat(event.getThrowableProxy()).isNotNull();
+                });
+            }
+            assertThat(outbound).first().isInstanceOf(OneCardEvent.CardPlayed.class);
+            assertThat(outbound).last().isInstanceOf(OneCardEvent.MatchEnded.class);
+        }
+
+        @Test
+        void a_desertion_that_ends_the_match_still_reports_match_ended() {
+            OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6))).top(heart(5)).turn(0).build();
+            when(store.load("room-1")).thenReturn(Optional.of(twoSeats));
+            List<GameEvent> outbound = new ArrayList<>();
+
+            assertThat(engine(failingRecorder, NOW, 2).desert(1, 101L, outbound))
+                    .isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
+
+            verify(store).save(eq("room-1"), argThat(OneCardState::ended));
+            assertThat(outbound).last().isInstanceOf(OneCardEvent.MatchEnded.class);
+        }
+    }
 }
```

`server/src/test/java/com/mirboard/infra/ws/OneCardRecorderFailureTest.java`:

```java
package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.onecard.OneCardGameEngine;
import com.mirboard.domain.game.onecard.RaceSettings;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * S5 — 매치를 끝내는 마지막 카드는 기록기(DB)가 실패해도 모두에게 나가고 방이 끝난다. 실제 컨트롤러·진행 서비스·원카드
 * 어댑터를 묶고 저장소·브로커만 모의로 둔다. 예전에는 동기 기록기의 예외가 컨트롤러까지 올라와 저장 뒤의 방송과 FINISHED
 * 전이를 건너뛰었다 — 상태는 끝났는데 클라는 직전 화면에 멈추고 방은 IN_GAME 에 남았다(C-M2·P-F7).
 */
class OneCardRecorderFailureTest {

    private static final String ROOM = "r1";
    private static final PlayingCard HEART_9 = PlayingCard.of(Suit.HEART, 9);

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final OneCardStateStore store = mock(OneCardStateStore.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final AtomicReference<OneCardState> stored = new AtomicReference<>();

    /** 좌석 0 이 마지막 한 장(♥9)을 들고 차례. */
    private static OneCardState lastCardForSeatZero() {
        List<PlayingCard> second = List.of(PlayingCard.of(Suit.SPADE, 4), PlayingCard.of(Suit.SPADE, 6));
        PlayingCard top = PlayingCard.of(Suit.HEART, 5);
        List<PlayingCard> used = new ArrayList<>(second);
        used.add(HEART_9);
        used.add(top);
        List<PlayingCard> drawPile = Deck.all().stream().filter(card -> !used.contains(card)).toList();
        return new OneCardState(List.of(List.of(HEART_9), second), drawPile, List.of(top), 0, 1, null, 0, null,
                List.of(), 0, 0, 1, null);
    }

    @Test
    void the_last_card_reaches_everyone_and_the_room_finishes_even_if_recording_fails() {
        List<Long> players = List.of(10L, 30L);
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "ONE_CARD", 10L, RoomStatus.IN_GAME, 2, 2,
                players, Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenAnswer(i -> new OneCardGameEngine(new GameContext(ROOM, players), store,
                Clock.systemUTC(), new Random(7), RaceSettings.DEFAULT, event -> {
                    throw new DataAccessResourceFailureException("simulated DB outage in OneCardMatchRecorder");
                }));
        stored.set(lastCardForSeatZero());
        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());
        when(lock.tryAcquire(ROOM)).thenReturn(true);
        when(games.require(anyString())).thenReturn(mock(GameDefinition.class));
        MirboardMetrics metrics = mock(MirboardMetrics.class);
        GameStompController controller = new GameStompController(roomService, engines, new ObjectMapper(),
                broadcaster, lock, new MatchProgressService(roomService, metrics, games),
                mock(BotScheduler.class), mock(TurnTimeoutScheduler.class), metrics);

        controller.onAction(ROOM, Map.of("@action", "PLAY_CARD", "card", Map.of("suit", "HEART", "rank", 9)),
                new AuthPrincipal(10L, "u10"));

        assertThat(stored.get().ended()).isTrue();
        verify(broadcaster).broadcast(eq(ROOM), argThat((List<? extends GameEvent> events) ->
                events.stream().anyMatch(OneCardEvent.CardPlayed.class::isInstance)
                        && events.getLast() instanceof OneCardEvent.MatchEnded), eq(players));
        verify(roomService).markFinished(ROOM);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.OneCardGameEngineTest" --tests "com.mirboard.infra.ws.OneCardRecorderFailureTest"`
Expected: FAIL — `15 tests completed, 7 failed`: 경쟁 로그 4건(`Expecting actual: [] to contain exactly ...` / `Expected size: 1 but was: 0`), 기록 실패 3건
(`org.springframework.dao.DataAccessResourceFailureException: simulated DB outage in OneCardMatchRecorder`).

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java`:

```diff
 import com.mirboard.domain.game.onecard.state.MatchResult;
 import com.mirboard.domain.game.onecard.state.OneCardState;
 import com.mirboard.domain.game.onecard.state.OneCardStateMapper;
+import com.mirboard.domain.game.onecard.state.RaceWindow;
 import java.time.Clock;
 import java.time.Duration;
 import java.util.List;
```

```diff
  *
  * <p>1판 = 1매치 = 1라운드라 라운드가 끝나면 곧 매치가 끝난다. 본 클래스는 상태를 갖지 않는다(저장소 참조만)
  * — 동시성 직렬화는 호출자의 방 액션 락이 맡는다.
+ *
+ * <p>S5 — 경쟁 창이 닫힐 때마다 결과 한 줄을 남기고(누름·엔진 타이머·탈주 세 경로, {@link #logRaceResolved}),
+ * 매치 종료 기록이 실패해도 진행을 끊지 않는다({@link #record}).
  */
 public final class OneCardGameEngine implements GameEngine {
 
```

```diff
         return OneCardAction.class;
     }
 
-    /** 사람·봇·타임아웃 공용. 지금 시각은 경쟁 창을 열 때만 쓰인다. */
+    /** 사람·봇·타임아웃 공용. 지금 시각은 경쟁 창을 열 때(와 S5 경쟁 결과 로그)에만 쓰인다. */
     @Override
     public Result apply(GameState state, int seat, GameAction action) {
-        OneCardEngine.Result result = rules.apply(ocState(state), seat, ocAction(action), clock.millis());
+        OneCardState before = ocState(state);
+        long now = clock.millis();
+        OneCardEngine.Result result = rules.apply(before, seat, ocAction(action), now);
+        logRaceResolved(before.race(), result.events(), "PRESS", now);
         return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
     }
 
```

```diff
 
     @Override
     public Optional<Result> onTimer(GameState state) {
-        return rules.onTimer(ocState(state))
-                .map(result -> new Result(result.newState(), List.<GameEvent>copyOf(result.events())));
+        OneCardState before = ocState(state);
+        return rules.onTimer(before).map(result -> {
+            logRaceResolved(before.race(), result.events(), "TIMER", clock.millis());
+            return new Result(result.newState(), List.<GameEvent>copyOf(result.events()));
+        });
     }
 
     // ---------- 매치 진행 ----------
```

```diff
             case CONTINUED -> {
                 stateStore.save(context.roomId(), desertion.newState());
                 outbound.addAll(desertion.events());
+                logRaceResolved(state.race(), desertion.events(), "DESERTION", clock.millis());
                 log.warn("OneCard desertion continued: room={} seat={} userId={}",
                         context.roomId(), seat, deserterUserId);
                 return DesertOutcome.MATCH_CONTINUES;
```

```diff
             case MATCH_ENDED -> {
                 stateStore.save(context.roomId(), desertion.newState());
                 outbound.addAll(desertion.events());
+                logRaceResolved(state.race(), desertion.events(), "DESERTION", clock.millis());
                 record(desertion.newState().result());
                 log.warn("OneCard desertion ended match: room={} seat={} userId={} reason={}",
                         context.roomId(), seat, deserterUserId, desertion.newState().result().reason());
```

```diff
 
     // ---------- internals ----------
 
-    /** 로컬 발행 — 기록기({@code OneCardMatchRecorder})가 듣는다. */
+    /**
+     * 로컬 발행 — 기록기({@code OneCardMatchRecorder})가 듣는다.
+     *
+     * <p>S5 — 기록기는 동기 리스너(@Transactional)라 DB 장애가 여기로 올라온다. 그대로 던지면 호출한 진행 경로(컨트롤러·
+     * 봇·타이머·탈주)가 저장 뒤의 방송·FINISHED 전이·재무장을 건너뛰어, 마지막 {@code CARD_PLAYED}·{@code MATCH_ENDED} 가
+     * 아무에게도 안 가고 방이 IN_GAME 에 남았다. 기록이 빠지는 쪽이 결과 화면이 안 뜨는 쪽보다 덜 아프다 — 결과를 실어
+     * ERROR(Sentry)로 남겨 수동으로 복구할 수 있게 하고 진행은 계속한다. 다른 인스턴스로 다시 보내는 경로는 만들지 않는다
+     * (D-116 원칙 — 기록은 끝낸 인스턴스에서 한 번).
+     */
     private void record(MatchResult result) {
-        publisher.publishEvent(new OneCardMatchCompleted(context.roomId(), context.playerIds(), result));
+        try {
+            publisher.publishEvent(new OneCardMatchCompleted(context.roomId(), context.playerIds(), result));
+        } catch (RuntimeException e) {
+            log.error("OneCard match record failed, the match still ends: room={} players={} result={}",
+                    context.roomId(), context.playerIds(), result, e);
+        }
         log.info("OneCard match ended: room={} reason={} winners={}",
                 context.roomId(), result.reason(), result.winners());
+    }
+
+    /**
+     * S5 — 경쟁 창이 닫히면 결과 한 줄(INFO). 설계서 §4.4·§7 과 D-128 이 "배포 후 경쟁 결과 로그로 다시 본다"고 미룬
+     * 판단(사람·봇 승률, 반응 시간 분포, 핑 유리, 단일 폴러 지연)과 누름 자동화 탐지(창이 열리자마자의 누름이 반복되는
+     * 계정)의 근거다. 창이 닫히는 세 경로({@code via} = PRESS·TIMER·DESERTION)에서 부른다.
+     *
+     * <ul>
+     *   <li>{@code latencyMs} — 창을 연 뒤 이 전이를 처리하기까지. 누름이면 그 사람의 반응 + 왕복 시간이다.</li>
+     *   <li>{@code lateMs} — 타이머 경로만: 정해 둔 마감(봇 누름 또는 창 끝)보다 얼마나 늦게 처리했나. 나머지는 {@code -}.</li>
+     * </ul>
+     *
+     * <p><b>사용자별 값은 로그로만 둔다</b> — 메트릭 태그로 두면 공개된 {@code /actuator/prometheus} 로 나간다. 여러
+     * 인스턴스면 창을 연 시각({@code openedAt})과 지금 시각이 다른 시계일 수 있다(그 차이만큼 두 값이 흔들린다).
+     *
+     * <p><b>집계는 {@code room}+{@code raceId} 로 묶어 마지막 줄을 정본으로 센다.</b> 이 줄은 호출자가 저장·방송하기 <em>전에</em>
+     * 찍힌다 — 저장이 실패하면 상태에는 창이 그대로 남고, 나중에 다른 경로(타이머·킥이 다시 건 타이머)가 같은 창을 닫으며 다른
+     * 결과로 한 줄 더 찍는다.
+     */
+    private void logRaceResolved(RaceWindow race, List<OneCardEvent> events, String via, long now) {
+        if (race == null) {
+            return;
+        }
+        for (OneCardEvent event : events) {
+            if (event instanceof OneCardEvent.RaceResolved resolved && resolved.raceId() == race.raceId()) {
+                int owner = race.ownerSeat();
+                int by = resolved.bySeat();
+                log.info("OneCard race resolved: room={} raceId={} outcome={} via={} ownerSeat={} ownerUser={}"
+                                + " ownerBot={} bySeat={} byUser={} byBot={} latencyMs={} windowMs={} lateMs={}",
+                        context.roomId(), race.raceId(), resolved.outcome(), via, owner, userOf(owner), isBot(owner),
+                        by, userOf(by), isBot(by), now - race.openedAt(), race.windowMillis(),
+                        via.equals("TIMER") ? String.valueOf(now - race.deadline()) : "-");
+            }
+        }
+    }
+
+    private String userOf(int seat) {
+        return seat >= 0 && seat < context.playerIds().size() ? String.valueOf(context.playerIds().get(seat)) : "-";
+    }
+
+    private boolean isBot(int seat) {
+        return seat >= 0 && context.botSeats().contains(seat);
     }
 
     private static OneCardState ocState(GameState state) {
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.OneCardGameEngineTest" --tests "com.mirboard.infra.ws.OneCardRecorderFailureTest"`
Expected: PASS — 15건.

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 184건(178 + 6).

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameEngineTest.java \
  server/src/test/java/com/mirboard/infra/ws/OneCardRecorderFailureTest.java \
  server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameEngine.java
git commit -m "feat(S5): 원카드 경쟁 결과 로그 + 매치 종료 기록 실패 격리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 서버·클라 — 게임이 선언하는 방 만들기 처음 선택(원카드 4명·턴 제한 30초)

**Files:**
- Create: `server/src/test/java/com/mirboard/domain/game/core/RoomCreationDefaultsTest.java`
- Modify: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java`, `server/src/test/java/com/mirboard/infra/rest/games/GameSummaryOrderTest.java`, `server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java`, `client/src/features/lobby/CreateRoomModal.test.tsx`, `server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java`, `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`, `server/src/main/java/com/mirboard/infra/rest/games/GameCatalogController.java`, `client/src/types/api.ts`, `client/src/features/lobby/CreateRoomModal.tsx`

**Interfaces:**
- Consumes: `GameDefinition`(core, D-106 `supportedRoomOptions` 와 같은 "게임이 선언한다" 방식), 카탈로그 `GET /api/games` 의 `GameSummary`, 클라
  `CreateRoomModal`(턴 제한 선택지 0·30·60·90).
- Produces: `GameDefinition.defaultPlayers()`(기본 `maxPlayers()`)·`defaultTurnSeconds()`(기본 0), `OneCardGameDefinition` 4·30, `GameSummary` 의
  `defaultPlayers`·`defaultTurnSeconds`, 클라 `GameSummary` 선택 필드 둘(없으면 maxPlayers·0). 모달은 인원·턴 제한을 `null`(= 게임의 처음 선택)로 두고
  게임을 바꾸면 둘 다 되돌린다(티츄·스컬킹도 턴 제한이 처음 선택으로 되돌아간다). `RoomService` 의 capacity 생략 기본(maxPlayers)은 그대로다(D-116 —
  클라는 capacity 를 늘 명시해 보낸다).

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/core/RoomCreationDefaultsTest.java`:

```java
package com.mirboard.domain.game.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S5 — 방 만들기의 처음 선택(인원·턴 제한)도 게임이 선언한다. 기본은 지금까지의 동작 그대로 — 인원은 최대, 턴 제한은
 * 끔 — 이라 티츄·스컬킹은 한 줄도 바꾸지 않는다. 원카드만 4명·30초(설계 §3.1 의 기본 4, 버티기 대응 — 사용자 결정).
 *
 * <p>서버의 capacity 생략 기본({@code RoomService} — maxPlayers)은 그대로다. 인원 가변 게임은 클라가 늘 capacity 를
 * 보내므로 이 값은 클라 모달의 처음 선택으로만 쓰인다. 정의는 저장소를 생성자로 받지만 이 메서드는 쓰지 않으므로 null 로
 * 진짜 인스턴스를 만든다({@link RematchSupportTest} 와 같은 방식).
 */
class RoomCreationDefaultsTest {

    @Test
    void defaults_are_the_largest_table_without_a_turn_limit() {
        GameDefinition bare = new GameDefinition() {
            @Override public String id() { return "BARE"; }
            @Override public String displayName() { return "BARE"; }
            @Override public String shortDescription() { return ""; }
            @Override public int minPlayers() { return 2; }
            @Override public int maxPlayers() { return 5; }
            @Override public GameStatus status() { return GameStatus.AVAILABLE; }
            @Override public GameEngine newEngine(GameContext ctx) {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(bare.defaultPlayers()).isEqualTo(5);
        assertThat(bare.defaultTurnSeconds()).isZero();
    }

    @Test
    void tichu_keeps_four_seats_without_a_turn_limit() {
        TichuGameDefinition tichu = new TichuGameDefinition(null, null, null, null);

        assertThat(tichu.defaultPlayers()).isEqualTo(4);
        assertThat(tichu.defaultTurnSeconds()).isZero();
    }

    @Test
    void skull_king_keeps_eight_seats_without_a_turn_limit() {
        SkullKingGameDefinition skullKing = new SkullKingGameDefinition(null, null, null);

        assertThat(skullKing.defaultPlayers()).isEqualTo(8);
        assertThat(skullKing.defaultTurnSeconds()).isZero();
    }

    /**
     * 선언값은 모달이 실제로 고를 수 있는 값이어야 한다 — 인원은 그 게임의 범위 안, 턴 제한은 모달 선택지(끔·30·60·90초) 중
     * 하나. 밖이면 모달이 아무것도 고르지 않은 채 열리거나 서버가 인원을 거절한다.
     */
    @Test
    void every_declared_choice_is_one_the_modal_can_pick() {
        List<GameDefinition> definitions = List.of(
                new TichuGameDefinition(null, null, null, null),
                new SkullKingGameDefinition(null, null, null),
                new OneCardGameDefinition(mock(OneCardStateStore.class), Clock.systemUTC(), event -> { },
                        GameStatus.COMING_SOON, 3_000, 1_000, 2_500, 1_000, 2_500));

        assertThat(definitions).allSatisfy(def -> {
            assertThat(def.defaultPlayers()).as(def.id()).isBetween(def.minPlayers(), def.maxPlayers());
            assertThat(def.defaultTurnSeconds()).as(def.id()).isIn(0, 30, 60, 90);
        });
    }
}
```

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java`:

```diff
         assertThat(def.supportsRematch()).isFalse();
     }
 
+    /**
+     * S5 — 방 만들기의 처음 선택은 4명·턴 제한 30초다. 6석이 기본이면 친구 넷이 기본값으로 만든 방이 영원히 시작하지 않고
+     * (정원은 만든 뒤 못 바꾼다) 게스트 첫 판이 봇 5명이 된다. 턴 제한이 꺼져 있으면 자리를 비운 한 명이 판을 무기한 멈추고
+     * 결국 이긴다(버티기 — 사용자 결정으로 원카드만 30초). 30 은 방 만들기 모달의 선택지(0·30·60·90)다.
+     */
+    @Test
+    void room_creation_starts_at_four_seats_and_a_thirty_second_turn_limit() {
+        OneCardGameDefinition def = defaults();
+
+        assertThat(def.defaultPlayers()).isEqualTo(4).isBetween(def.minPlayers(), def.maxPlayers());
+        assertThat(def.defaultTurnSeconds()).isEqualTo(30);
+    }
+
     @Test
     void status_and_race_timing_come_from_configuration_with_the_protocol_slot_count() {
         OneCardGameDefinition def = definition(GameStatus.AVAILABLE, 2_000, 100, 200, 300, 400);
```

`server/src/test/java/com/mirboard/infra/rest/games/GameSummaryOrderTest.java`:

```diff
                 .containsExactly(RoomOption.TARGET_SCORE, RoomOption.TEAMS, RoomOption.BETTING);
     }
 
+    /** S5 — 방 만들기의 처음 선택(인원·턴 제한)도 정의에서 그대로 실린다. 재정의하지 않으면 최대 인원·끔. */
+    @Test
+    void room_creation_defaults_come_from_the_definition() {
+        var plain = GameCatalogController.GameSummary.of(new ShuffledOptionsGame(Set.of()));
+        var declared = GameCatalogController.GameSummary.of(new DeclaredDefaultsGame());
+
+        assertThat(plain.defaultPlayers()).isEqualTo(4);
+        assertThat(plain.defaultTurnSeconds()).isZero();
+        assertThat(declared.defaultPlayers()).isEqualTo(3);
+        assertThat(declared.defaultTurnSeconds()).isEqualTo(60);
+    }
+
+    /** 처음 선택을 선언한 정의(2~4인 · 기본 3명 · 60초). */
+    private record DeclaredDefaultsGame() implements GameDefinition {
+
+        @Override
+        public String id() {
+            return "DECLARED";
+        }
+
+        @Override
+        public String displayName() {
+            return "기본값 선언 게임";
+        }
+
+        @Override
+        public String shortDescription() {
+            return "";
+        }
+
+        @Override
+        public int minPlayers() {
+            return 2;
+        }
+
+        @Override
+        public int maxPlayers() {
+            return 4;
+        }
+
+        @Override
+        public GameStatus status() {
+            return GameStatus.AVAILABLE;
+        }
+
+        @Override
+        public int defaultPlayers() {
+            return 3;
+        }
+
+        @Override
+        public int defaultTurnSeconds() {
+            return 60;
+        }
+
+        @Override
+        public GameEngine newEngine(GameContext ctx) {
+            throw new UnsupportedOperationException("카탈로그 매핑만 검증한다");
+        }
+    }
+
     @Test
     void empty_options_map_to_empty_list() {
         var summary = GameCatalogController.GameSummary.of(new ShuffledOptionsGame(Set.of()));
```

`server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java`:

```diff
                 .andExpect(jsonPath("$.games[0].supportedRoomOptions").isArray())
                 .andExpect(jsonPath("$.games[0].supportedRoomOptions.length()").value(0))
                 .andExpect(jsonPath("$.games[0].status").value("AVAILABLE"))
+                // S5 — 방 만들기의 처음 선택. 재정의하지 않은 게임은 최대 인원·턴 제한 끔(지금까지와 같다).
+                .andExpect(jsonPath("$.games[0].defaultPlayers").value(8))
+                .andExpect(jsonPath("$.games[0].defaultTurnSeconds").value(0))
                 .andExpect(jsonPath("$.games[1].id").value("TICHU"))
                 .andExpect(jsonPath("$.games[1].displayName").value("티츄"))
                 .andExpect(jsonPath("$.games[1].minPlayers").value(4))
```

```diff
                 .andExpect(jsonPath("$.games[1].supportedRoomOptions[1]").value("TEAMS"))
                 .andExpect(jsonPath("$.games[1].supportedRoomOptions[2]").value("BETTING"))
                 .andExpect(jsonPath("$.games[1].status").value("AVAILABLE"))
+                .andExpect(jsonPath("$.games[1].defaultPlayers").value(4))
+                .andExpect(jsonPath("$.games[1].defaultTurnSeconds").value(0))
                 .andExpect(jsonPath("$.games[2].id").value("ONE_CARD"))
                 .andExpect(jsonPath("$.games[2].displayName").value("원카드"))
                 .andExpect(jsonPath("$.games[2].minPlayers").value(2))
                 .andExpect(jsonPath("$.games[2].maxPlayers").value(6))
                 .andExpect(jsonPath("$.games[2].supportedRoomOptions.length()").value(0))
-                .andExpect(jsonPath("$.games[2].status").value("COMING_SOON"));
+                .andExpect(jsonPath("$.games[2].status").value("COMING_SOON"))
+                // S5 — 원카드만 4명·30초를 선언한다(설계 §3.1 기본 4, 버티기 대응).
+                .andExpect(jsonPath("$.games[2].defaultPlayers").value(4))
+                .andExpect(jsonPath("$.games[2].defaultTurnSeconds").value(30));
     }
 
     @Test
```

```diff
                 .andExpect(jsonPath("$.id").value("TICHU"))
                 // D-106 — 목록과 단건이 같은 record 를 쓴다. 한쪽만 필드가 빠지는 회귀 방지.
                 .andExpect(jsonPath("$.supportedRoomOptions.length()").value(3))
+                .andExpect(jsonPath("$.defaultPlayers").value(4))
+                .andExpect(jsonPath("$.defaultTurnSeconds").value(0))
                 .andExpect(jsonPath("$.status").value("AVAILABLE"));
     }
 
```

`client/src/features/lobby/CreateRoomModal.test.tsx`:

```diff
   maxPlayers: 8,
   status: 'AVAILABLE',
   supportedRoomOptions: [],
+};
+
+/** 원카드 — 2~6인 가변, 방 만들기 처음 선택을 선언한다(S5: 4명·턴 제한 30초). */
+const DECLARED: GameSummary = {
+  id: 'ONE_CARD',
+  displayName: '원카드',
+  shortDescription: '',
+  minPlayers: 2,
+  maxPlayers: 6,
+  status: 'AVAILABLE',
+  supportedRoomOptions: [],
+  defaultPlayers: 4,
+  defaultTurnSeconds: 30,
 };
 
 /** availableGames 를 한 개만 넘기면 모달이 그 게임을 자동 선택한다(Radix Select 조작 회피). */
```

```diff
     expect(seats).toEqual(['2', '3', '4', '5', '6', '7', '8']);
   });
 
-  it('인원 가변 게임의 기본 인원은 maxPlayers (서버 기본값과 동일)', async () => {
+  it('선언이 없으면 인원 가변 게임의 처음 인원은 maxPlayers', async () => {
     openModal([VARIABLE]);
     submit();
     await waitFor(() => expect(createMock).toHaveBeenCalled());
```

```diff
     expect(lastCreateOpts().fillWithBots).toBe(false);
   });
 });
+
+describe('CreateRoomModal — 게임이 선언한 처음 선택 (S5)', () => {
+  beforeEach(() => {
+    vi.clearAllMocks();
+    createMock.mockResolvedValue({ roomId: 'room-1' });
+  });
+
+  it('게임이 선언한 인원·턴 제한을 처음부터 골라 둔다', () => {
+    openModal([DECLARED]);
+
+    expect(screen.getByRole('radio', { name: '4' })).toHaveAttribute('aria-checked', 'true');
+    expect(screen.getByRole('radio', { name: '30초' })).toHaveAttribute('aria-checked', 'true');
+  });
+
+  it('바꾸지 않으면 선언한 값으로 보낸다', async () => {
+    openModal([DECLARED]);
+    submit();
+    await waitFor(() => expect(createMock).toHaveBeenCalled());
+
+    expect(lastCreateOpts().capacity).toBe(4);
+    expect(lastCreateOpts().turnSeconds).toBe(30);
+  });
+
+  it('사용자가 바꾼 값을 그대로 보낸다', async () => {
+    openModal([DECLARED]);
+    fireEvent.click(screen.getByRole('radio', { name: '6' }));
+    fireEvent.click(screen.getByRole('radio', { name: '끔' }));
+    submit();
+    await waitFor(() => expect(createMock).toHaveBeenCalled());
+
+    expect(lastCreateOpts().capacity).toBe(6);
+    expect(lastCreateOpts().turnSeconds).toBe(0);
+  });
+});
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.core.RoomCreationDefaultsTest" --tests "com.mirboard.domain.game.onecard.OneCardGameDefinitionTest" --tests "com.mirboard.infra.rest.games.GameSummaryOrderTest"`
Expected: FAIL — `compileTestJava` 실패(`16 errors`): `symbol: method defaultPlayers()` · `symbol: method defaultTurnSeconds()` ·
`error: method does not override or implement a method from a supertype`.

Run: `npm --prefix client run test -- CreateRoomModal`
Expected: FAIL — `Test Files  1 failed (1)` · `Tests  2 failed | 14 passed (16)`(`게임이 선언한 인원·턴 제한을 처음부터 골라 둔다` —
`expect(element).toHaveAttribute("aria-checked", "true")`, `바꾸지 않으면 선언한 값으로 보낸다` — `expected 6 to be 4`).

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java`:

```diff
         return false;
     }
 
+    /**
+     * S5 — 방 만들기 모달이 처음 고르는 인원. <b>기본은 {@link #maxPlayers()}</b> — 지금까지의 동작 그대로라 재정의하지
+     * 않은 게임은 바뀌지 않는다. {@code minPlayers()..maxPlayers()} 안이어야 한다.
+     *
+     * <p>클라의 처음 선택일 뿐이다 — 서버의 capacity 생략 기본({@code RoomService}, maxPlayers)은 따로다. 인원 가변
+     * 게임은 클라가 늘 capacity 를 보내므로 둘이 실제로 갈리지 않는다.
+     */
+    default int defaultPlayers() {
+        return maxPlayers();
+    }
+
+    /**
+     * S5 — 방 만들기 모달이 처음 고르는 턴 제한(초). <b>기본은 0(끔)</b> — 서버 기본({@code RoomService
+     * .DEFAULT_TURN_SECONDS})과 같다. 모달의 선택지(0·30·60·90) 중 하나여야 처음부터 선택돼 보인다.
+     */
+    default int defaultTurnSeconds() {
+        return 0;
+    }
+
     /** Phase 3 에서 게임 시작 시 호출. 현재는 미구현 게임이면 throws. */
     GameEngine newEngine(GameContext ctx);
 }
```

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`:

```diff
     }
 
     /**
+     * S5 — 방 만들기의 처음 선택은 4명(설계 §3.1). 6석이 기본이면 친구 넷이 기본값으로 만든 방이 시작하지 않고(정원은 만든
+     * 뒤 못 바꾼다) 게스트 첫 판이 봇 5명이 된다.
+     */
+    @Override
+    public int defaultPlayers() {
+        return 4;
+    }
+
+    /**
+     * S5 — 턴 제한 30초가 처음 선택이다(사용자 결정). 끔이면 자리를 비운 한 명이 판을 무기한 멈추고, 남은 사람이 나가면
+     * (탈주) 결국 그 사람이 이긴다. 시간 초과는 먹기라 자리를 비운 사람은 파산으로 정리된다.
+     */
+    @Override
+    public int defaultTurnSeconds() {
+        return 30;
+    }
+
+    /**
      * 설정에서 만든 경쟁 창 설정 — 엔진 어댑터({@link OneCardGameEngine})가 쓴다. 라운드 시작
      * ({@code OneCardRoundStarter})은 쓰지 않는다: 시작에는 경쟁 창이 없어 스타터는 {@link RaceSettings#DEFAULT} 로
      * 엔진을 만들고, {@code startMatch} 는 설정을 읽지 않는다.
```

`server/src/main/java/com/mirboard/infra/rest/games/GameCatalogController.java`:

```diff
     /**
      * D-106 — `supportedRoomOptions` 는 방 만들기 UI 가 무엇을 노출할지 정하는 근거다.
      * 게임이 안 쓰는 설정을 화면에 띄우지 않기 위한 것이며, 서버도 같은 집합으로 검증한다.
+     *
+     * <p>S5 — `defaultPlayers`·`defaultTurnSeconds` 는 방 만들기 모달의 처음 선택이다(사용자는 바꿀 수 있다).
      */
     public record GameSummary(
             String id,
```

```diff
             int minPlayers,
             int maxPlayers,
             GameStatus status,
-            List<RoomOption> supportedRoomOptions) {
+            List<RoomOption> supportedRoomOptions,
+            int defaultPlayers,
+            int defaultTurnSeconds) {
 
         static GameSummary of(GameDefinition d) {
             return new GameSummary(
```

```diff
                     d.maxPlayers(),
                     d.status(),
                     // enum 선언 순서로 고정 — 응답이 실행마다 흔들리지 않게.
-                    d.supportedRoomOptions().stream().sorted().toList());
+                    d.supportedRoomOptions().stream().sorted().toList(),
+                    d.defaultPlayers(),
+                    d.defaultTurnSeconds());
         }
     }
 }
```

`client/src/types/api.ts`:

```diff
   maxPlayers: number;
   status: GameStatus;
   supportedRoomOptions: RoomOption[];
+  /**
+   * S5 — 방 만들기 모달의 처음 선택(게임이 선언, `GameDefinition.defaultPlayers()`·`defaultTurnSeconds()`). 서버는 늘
+   * 싣는다 — 선택 필드인 것은 이 필드를 모르는 픽스처와의 호환 때문이고, 없으면 maxPlayers·0(끔)으로 본다.
+   */
+  defaultPlayers?: number;
+  defaultTurnSeconds?: number;
 }
 
 export interface CatalogResponse {
```

`client/src/features/lobby/CreateRoomModal.tsx`:

```diff
   const [creating, setCreating] = useState(false);
   const [fillWithBots, setFillWithBots] = useState(defaultFillWithBots ?? false);
   const [targetScore, setTargetScore] = useState(1000);
-  const [turnSeconds, setTurnSeconds] = useState(0);
+  // S5 — null = 아직 안 고름(게임이 선언한 처음 선택을 쓴다). 인원과 같은 방식.
+  const [turnSeconds, setTurnSeconds] = useState<number | null>(null);
   const [stake, setStake] = useState(0);
-  // D-99 — 인원 가변 게임에서만 쓰는 좌석 수. null = 아직 안 고름(서버 기본값).
+  // D-99 — 인원 가변 게임에서만 쓰는 좌석 수. null = 아직 안 고름(게임이 선언한 처음 선택).
   const [capacity, setCapacity] = useState<number | null>(null);
 
   // 모달이 열릴 때 기본 게임을 첫 AVAILABLE 로 맞춘다.
```

```diff
           (_, i) => game.minPlayers + i,
         )
       : [];
-  // 서버 기본값(maxPlayers)과 같은 값을 기본 선택으로 — 클라·서버 기본이 갈리지 않게.
-  const selectedSeats = capacity ?? game?.maxPlayers ?? 0;
+  // S5 — 게임이 선언한 인원을 처음 선택으로(원카드 4, 나머지는 maxPlayers). 서버의 capacity 생략 기본(maxPlayers)과
+  // 다를 수 있지만 인원 가변 게임에서는 늘 capacity 를 보내므로 실제로 갈리지 않는다.
+  const selectedSeats = capacity ?? game?.defaultPlayers ?? game?.maxPlayers ?? 0;
+  // S5 — 턴 제한도 게임이 선언한 값이 처음 선택이다(원카드 30초 — 자리 비운 사람이 판을 멈추지 않게, 나머지는 끔).
+  const selectedTurnSeconds = turnSeconds ?? game?.defaultTurnSeconds ?? 0;
 
   // D-106 — 게임이 선언한 옵션만 노출한다. 스컬킹은 10라운드 고정(목표 점수 무의미)·
   // 개인전(팀 없음)·칩 정산 미지원(내기 불가)이라 셋 다 안 뜬다. 서버도 같은 집합으로
```

```diff
   // usesBetting 을 곱해 그 프레임을 없앤다: 게임이 BETTING 을 안 쓰면 state 와 무관하게 꺼짐.
   const stakeOn = usesBetting && stake > 0;
 
-  // 게임을 바꾸면 이전 게임의 좌석 수는 무효 — 새 게임의 기본값으로 되돌린다.
+  // 게임을 바꾸면 이전 게임의 좌석 수·턴 제한은 무효 — 새 게임의 처음 선택으로 되돌린다.
   // D-106 — 미지원 옵션 값도 같이 되돌린다. 티츄에서 판돈을 켠 뒤 스컬킹으로 바꾸면
   // 입력은 사라지지만 state 는 남아, 그대로 보내면 서버가 거절한다.
   useEffect(() => {
     setCapacity(null);
+    setTurnSeconds(null);
     if (!usesTargetScore) setTargetScore(DEFAULT_TARGET_SCORE);
     if (!usesBetting) setStake(0);
   }, [selectedGame, usesTargetScore, usesBetting]);
```

```diff
           // D-106 — 게임이 안 쓰는 옵션은 아예 보내지 않는다(D-99 의 capacity 와 같은 방식).
           // 서버는 기본값 아닌 값이 오면 UNSUPPORTED_ROOM_OPTION 으로 거절한다.
           targetScore: usesTargetScore ? targetScore : undefined,
-          turnSeconds,
+          turnSeconds: selectedTurnSeconds,
           stake: usesBetting ? stake : undefined,
           capacity: seatChoices.length > 0 ? selectedSeats : undefined,
         },
```

```diff
             <Label>턴 제한</Label>
             <ToggleGroup
               type="single"
-              value={String(turnSeconds)}
+              value={String(selectedTurnSeconds)}
               onValueChange={(v) => v !== '' && setTurnSeconds(Number(v))}
               className="justify-start"
             >
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.core.RoomCreationDefaultsTest" --tests "com.mirboard.domain.game.onecard.OneCardGameDefinitionTest" --tests "com.mirboard.infra.rest.games.GameSummaryOrderTest"`
Expected: PASS — 14건.

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.rest.games.GameCatalogIntegrationTest"`
Expected: PASS — 4건.

Run: `npm --prefix client run test -- CreateRoomModal` 뒤 `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 16건 통과, 타입 오류 없음, 마지막 두 줄 `Test Files  58 passed (58)` · `Tests  597 passed (597)`.

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 185건.

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/domain/game/core/RoomCreationDefaultsTest.java \
  server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java \
  server/src/test/java/com/mirboard/infra/rest/games/GameSummaryOrderTest.java \
  server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java \
  client/src/features/lobby/CreateRoomModal.test.tsx \
  server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java \
  server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java \
  server/src/main/java/com/mirboard/infra/rest/games/GameCatalogController.java \
  client/src/types/api.ts \
  client/src/features/lobby/CreateRoomModal.tsx
git commit -m "feat(S5): 게임이 선언하는 방 만들기 처음 선택 — 원카드 4명·턴 제한 30초

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 클라 — 방 STOMP 훅: 낡은·이전 방 스냅샷 버림, 구독 뒤 resync, 끊김 감지, `requestResync`

**Files:**
- Create: `client/src/ws/socketClose.test.tsx`
- Modify: `client/src/ws/useStompRoom.sink.test.tsx`, `client/src/ws/useStompRoom.ts`, `client/src/ws/useLobbyStomp.ts`, `client/src/ws/roomEventSink.ts`

**Interfaces:**
- Consumes: `useStompRoom`(D-124 — 순번 판정은 이 훅만), `RoomEventSink`, `roomsApi.resync`, `@stomp/stompjs` `Client`(`onWebSocketClose`).
- Produces: 세대 표식(`epochRef` — reset 마다 +1, 요청 때 잡고 응답·실패 때 다르면 버림), `snap.eventSeq < lastSeq` 인 응답 버림, onConnect 는 구독을 보낸
  뒤 `resync()`(틈을 좁힐 뿐 보장 아님 — SUBSCRIBE 등록이 비동기·영수증 없음), `onWebSocketClose` → `connected=false`(방·로비 훅), 반환값에
  `requestResync`(게임판이 권위 스냅샷을 다시 청할 때 — 훅은 여전히 게임 스토어를 모른다).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/ws/useStompRoom.sink.test.tsx`:

```diff
 // ── @stomp/stompjs 가짜 ──────────────────────────────────────────────
 // activate() 시 onConnect 를 즉시 호출하고, subscribe 핸들러를 목적지별로 캡처한다.
 const handlers = new Map<string, (frame: { body: string }) => void>();
+/** 구독·resync 호출 순서 기록 (S5 — 접속 직후 순서). */
+const calls: string[] = [];
 let activateCount = 0;
 let deactivateCount = 0;
 let clientCount = 0;
```

```diff
       this.onConnect();
     }
     subscribe(dest: string, cb: (frame: { body: string }) => void) {
+      calls.push(`sub:${dest}`);
       handlers.set(dest, cb);
       return { unsubscribe: () => {} };
     }
```

```diff
 
 const resyncMock = vi.fn();
 vi.mock('@/api/rooms', () => ({
-  roomsApi: { resync: (...args: unknown[]) => resyncMock(...args) },
+  roomsApi: {
+    resync: (...args: unknown[]) => {
+      calls.push('resync');
+      return resyncMock(...args);
+    },
+  },
 }));
 
 import { useStompRoom } from './useStompRoom';
```

```diff
 
 beforeEach(() => {
   handlers.clear();
+  calls.length = 0;
   activateCount = 0;
   deactivateCount = 0;
   clientCount = 0;
```

```diff
     expect(resyncMock).not.toHaveBeenCalled();
   });
 });
+
+describe('useStompRoom — S5 보강 (낡은 resync·접속 순서·다시 받기)', () => {
+  /** 응답을 테스트가 원하는 순서로 풀 수 있게 붙잡아 둔다. */
+  function heldResyncs() {
+    const pending: Array<(snap: unknown) => void> = [];
+    resyncMock.mockImplementation(() => new Promise((resolve) => pending.push(resolve)));
+    return pending;
+  }
+
+  /**
+   * 응답과 STOMP 프레임의 도착 순서는 정해져 있지 않다 — 락 안에서 seq 5 를 읽은 응답이 그 뒤 seq 6·7 프레임보다 늦게
+   * 닿을 수 있다. 그 응답을 적용하면 공개 상태와 기준점이 되돌아가, 사람 차례에서는 다음 이벤트가 오지 않아 판이 멈췄다.
+   */
+  it('기준점보다 낡은 응답은 버린다 — 이미 반영한 이벤트를 되돌리지 않는다', async () => {
+    const pending = heldResyncs();
+    const sink = makeSink();
+    renderHook(() => useStompRoom(ROOM, TOKEN, sink));
+    await waitFor(() => expect(pending).toHaveLength(2)); // 마운트 + 접속 직후
+
+    await act(async () => pending[0]({ ...SNAP, eventSeq: 5 }));
+    act(() => {
+      publish(6);
+      publish(7);
+    });
+    await act(async () => pending[1]({ ...SNAP, eventSeq: 5 })); // 6·7 보다 먼저 읽은 낡은 응답
+
+    expect(sink.applySnapshot).toHaveBeenCalledTimes(1);
+    const before = resyncMock.mock.calls.length;
+    act(() => publish(8)); // 기준점이 7 그대로면 '다음'이다
+    expect(sink.applyEvent).toHaveBeenCalledTimes(3);
+    expect(resyncMock.mock.calls.length).toBe(before);
+  });
+
+  it('기준점과 같은 순번의 응답은 그대로 적용한다', async () => {
+    const pending = heldResyncs();
+    const sink = makeSink();
+    renderHook(() => useStompRoom(ROOM, TOKEN, sink));
+    await waitFor(() => expect(pending).toHaveLength(2));
+
+    await act(async () => pending[0]({ ...SNAP, eventSeq: 5 }));
+    await act(async () => pending[1]({ ...SNAP, eventSeq: 5 }));
+
+    expect(sink.applySnapshot).toHaveBeenCalledTimes(2);
+  });
+
+  /**
+   * resync 를 먼저 보내면 서버가 스냅샷을 읽은 뒤·구독을 등록하기 전에 낸 이벤트가 스냅샷에도 프레임에도 없다. 공개 토픽과
+   * 본인 큐(손패) 둘 다 resync 앞이어야 한다. 서버 등록이 비동기라 틈을 좁힐 뿐 보장은 아니다(SUBSCRIBE 영수증이 없다).
+   */
+  it('접속하면 공개 토픽·본인 큐를 구독한 뒤에 resync 한다', async () => {
+    renderHook(() => useStompRoom(ROOM, TOKEN, makeSink()));
+    await waitFor(() => expect(calls.filter((c) => c === 'resync')).toHaveLength(2));
+
+    const afterMount = calls.indexOf('resync') + 1; // 첫 resync 는 마운트 때 것
+    const connectResync = calls.indexOf('resync', afterMount);
+    const topic = calls.indexOf(`sub:/topic/room/${ROOM}`, afterMount);
+    const queue = calls.indexOf(`sub:/user/queue/room/${ROOM}`, afterMount);
+    expect(topic).toBeGreaterThanOrEqual(0);
+    expect(queue).toBeGreaterThanOrEqual(0);
+    expect(topic).toBeLessThan(connectResync);
+    expect(queue).toBeLessThan(connectResync);
+  });
+
+  /**
+   * 방이 바뀌는 사이(게임판이 마운트된 채 roomId 가 바뀜 — 뒤로/앞으로) 이전 방의 resync 응답이 늦게 닿으면 새 방의 기준점을
+   * 올려, 새 방의 정상 스냅샷과 이벤트를 전부 '낡음'·'중복'으로 버렸다(사전 리뷰 I-1). 방 전환마다 오르는 세대로 버린다.
+   */
+  it('방이 바뀐 뒤 도착한 이전 방의 응답은 버린다 — 새 방의 기준점을 올리지 않는다', async () => {
+    const pending: Array<{ roomId: string; resolve: (snap: unknown) => void }> = [];
+    resyncMock.mockImplementation(
+      (_token: string, roomId: string) => new Promise((resolve) => pending.push({ roomId, resolve })),
+    );
+    const of = (roomId: string) => pending.filter((p) => p.roomId === roomId);
+    const sink = makeSink();
+    const { rerender } = renderHook(({ room }) => useStompRoom(room, TOKEN, sink), {
+      initialProps: { room: 'A' },
+    });
+    await waitFor(() => expect(of('A').length).toBeGreaterThan(0));
+
+    rerender({ room: 'B' });
+    await waitFor(() => expect(of('B').length).toBeGreaterThan(0));
+
+    // 이전 방 A 의 늦은 응답(순번 50)이 먼저, 새 방 B 의 응답(순번 3)이 뒤에 닿는다.
+    await act(async () => of('A').forEach((p) => p.resolve({ ...SNAP, roomId: 'A', eventSeq: 50 })));
+    await act(async () => of('B').forEach((p) => p.resolve({ ...SNAP, roomId: 'B', eventSeq: 3 })));
+
+    const applied = sink.applySnapshot.mock.calls.map((call) => (call[0] as { roomId: string }).roomId);
+    expect(applied).not.toContain('A');
+    expect(applied.at(-1)).toBe('B');
+    act(() => handlers.get('/topic/room/B')!(frame({ type: 'X', seq: 4, payload: {} })));
+    expect(sink.applyEvent).toHaveBeenCalledTimes(1);
+  });
+
+  it('게임판이 권위 스냅샷을 다시 청할 수 있다 — requestResync', async () => {
+    const sink = makeSink();
+    const { result } = renderHook(() => useStompRoom(ROOM, TOKEN, sink));
+    await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
+    const before = resyncMock.mock.calls.length;
+    const applied = sink.applySnapshot.mock.calls.length;
+
+    await act(async () => {
+      await result.current.requestResync();
+    });
+
+    expect(resyncMock.mock.calls.length).toBe(before + 1);
+    expect(sink.applySnapshot.mock.calls.length).toBe(applied + 1);
+  });
+});
```

`client/src/ws/socketClose.test.tsx`:

```tsx
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RoomEventSink } from './roomEventSink';

/**
 * S5 — 소켓이 갑자기 끊기면(네트워크 끊김·서버 재시작·배포, close 1006/1001) 연결 표시가 내려간다.
 *
 * <p>@stomp/stompjs 의 {@code onDisconnect} 는 <b>클라가 먼저 DISCONNECT 를 보내 영수증을 받을 때만</b> 불린다. 갑작스러운
 * 끊김은 {@code onWebSocketClose} 만 부르는데 두 훅은 그것을 등록하지 않아 끊긴 뒤에도 "● 연결"이었다 — 재연결 배너가
 * 안 뜨고, 원카드 버튼의 끊김 가드가 통과돼 누름이 조용히 버려졌다. 라이브러리의 콜백 의미가 핵심이라 모킹하지 않고
 * 진짜 stompjs 를 가짜 WebSocket 위에서 돌린다.
 */

const sockets: FakeWS[] = [];

class FakeWS {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  readyState = 0;
  binaryType = '';
  protocol = 'v12.stomp';
  url: string;
  sent: string[] = [];
  onopen: ((e: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: ((e: { code: number; reason: string; wasClean: boolean }) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  constructor(url: string) {
    this.url = url;
    sockets.push(this);
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.readyState = 3;
    this.onclose?.({ code: 1000, reason: '', wasClean: true });
  }
}
vi.stubGlobal('WebSocket', FakeWS);

vi.mock('@/api/rooms', () => ({
  roomsApi: { resync: () => new Promise(() => {}) },
}));

import { useStompRoom } from './useStompRoom';
import { useLobbyStomp } from './useLobbyStomp';

const sink: RoomEventSink = {
  reset() {},
  applySnapshot() {},
  applyEvent: () => 'applied',
  applyPrivateEvent() {},
  setError() {},
};

/** 서버가 소켓을 받고 CONNECTED 로 답한다. */
function accept(ws: FakeWS) {
  act(() => {
    ws.readyState = 1;
    ws.onopen?.({});
  });
  act(() => {
    ws.onmessage?.({ data: 'CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0' });
  });
}

/** 영수증 없이 소켓이 닫힌다 — 네트워크 끊김·서버 재시작. */
function dropAbruptly(ws: FakeWS) {
  act(() => {
    ws.readyState = 3;
    ws.onclose?.({ code: 1006, reason: '', wasClean: false });
  });
}

afterEach(() => {
  sockets.length = 0;
});

describe('갑작스러운 끊김 (S5)', () => {
  it('방 소켓 — 끊기면 connected 가 false 로 내려간다', async () => {
    const { result } = renderHook(() => useStompRoom('r-1', 'tok', sink));
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    dropAbruptly(sockets[0]);

    expect(result.current.connected).toBe(false);
  });

  it('로비 소켓 — 끊기면 connected 가 false 로 내려간다', async () => {
    const { result } = renderHook(() => useLobbyStomp('tok'));
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    dropAbruptly(sockets[0]);

    expect(result.current.connected).toBe(false);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- useStompRoom socketClose`
Expected: FAIL — `Test Files  2 failed (2)` · `Tests  6 failed | 18 passed (24)`(끊기면 connected 가 false 로 내려간다 2건 — `expected true to be false`,
`기준점보다 낡은 응답은 버린다` — `expected "spy" to be called 1 times, but got 2 times`, `접속하면 공개 토픽·본인 큐를 구독한 뒤에 resync 한다` —
`expected 2 to be less than 1`, 방이 바뀐 뒤 도착한 이전 방의 응답 — `expected [ 'A', 'A', 'B', 'B' ] to not include 'A'`, `requestResync` —
`TypeError: result.current.requestResync is not a function`).

- [ ] **Step 3: 구현**

`client/src/ws/useStompRoom.ts`:

```diff
  *
  * <p><b>D-124: 순번 판정(중복·구멍)은 이 훅만 한다.</b> 기준점은 resync 의 `eventSeq` 이고
  * 판정은 `judgeSeq`(./seqGate) — 게임 스토어는 판정이 끝난 이벤트만 받는 순수 리듀서다.
+ *
+ * <p><b>S5 — resync 응답이 기준점보다 낡으면 버린다.</b> 서버는 상태와 순번을 방 락 안에서 함께 읽지만(D-126) REST
+ * 응답과 STOMP 프레임의 도착 순서는 정해져 있지 않다 — 늦게 닿은 응답이 이미 반영한 이벤트를 되돌려, 사람 차례에서는
+ * 다음 이벤트가 오지 않아 판이 멈췄다. 서버 순번은 방이 살아 있는 동안 줄지 않는다. 방이 바뀐 뒤 도착한 이전 방의
+ * 응답도 버린다(방 전환마다 오르는 세대 — 안 그러면 이전 방 응답이 새 방의 기준점을 올려 새 방 스냅샷을 '낡음'으로
+ * 버렸다). 게임판은 `requestResync` 로 권위 스냅샷을 다시 청할 수 있다(게임 스토어가 "다시 받아야 함"을 표시하면 —
+ * 훅은 여전히 스토어를 모른다).
  *
  * @param sink 게임별 이벤트 싱크. **모듈 상수**를 넘길 것 — 규약은
  *             {@link RoomEventSink} javadoc 참조.
```

```diff
    * 순번 있는 이벤트마다 전진한다.
    */
   const lastSeqRef = useRef(0);
+  /**
+   * S5 — 방 전환(reset)마다 오르는 세대. 그 전에 보낸 resync 의 늦은 응답(이전 방 — 토큰이 바뀌었다면 이전 사용자)을
+   * 버린다.
+   */
+  const epochRef = useRef(0);
   const resetChat = useRoomChatStore((s) => s.reset);
   const appendChat = useRoomChatStore((s) => s.appendIncoming);
   const appendReaction = useReactionStore((s) => s.add);
```

```diff
 
   const resync = useCallback(async () => {
     if (!token) return;
+    const epoch = epochRef.current;
     try {
       const snap = await roomsApi.resync<ResyncEnvelope<TTable, TPrivate>>(
         token,
         roomId,
       );
+      // S5 — 그 사이 방이 바뀌었다(reset) — 이전 방의 응답이 새 방의 기준점을 올리지 않게 버린다.
+      if (epoch !== epochRef.current) return;
+      // S5 — 이미 반영한 공개 이벤트보다 낡은 응답(그 뒤 프레임이 먼저 닿았다)은 버린다. 같은 순번은 적용한다.
+      if (snap.eventSeq < lastSeqRef.current) return;
       // 껍데기를 가공하지 않고 그대로 넘긴다 — 게임별 필드 해석은 sink 책임.
       sinkRef.current.applySnapshot(snap);
       // 순번 기준점은 스냅샷이 다시 세운다 (D-124).
       lastSeqRef.current = snap.eventSeq;
     } catch (err) {
+      if (epoch !== epochRef.current) return;
       sinkRef.current.setError((err as Error).message);
     }
   }, [token, roomId]);
 
   useEffect(() => {
+    epochRef.current += 1; // reset 보다 먼저 — 이 앞에 보낸 resync 의 응답은 이제 낡았다
     sinkRef.current.reset(roomId);
     lastSeqRef.current = 0;
     resetChat(roomId);
```

```diff
       reconnectDelay: 2000,
       onConnect: () => {
         setConnected(true);
-        // 연결/재연결 직후 권위 있는 스냅샷으로 순번 기준점 동기화.
-        resync();
+        // S5 — 구독을 모두 보낸 뒤에 resync 한다(맨 끝). resync 를 먼저 보내면 서버가 스냅샷을 읽은 뒤·구독을 등록하기 전에
+        // 낸 이벤트가 스냅샷에도 프레임에도 없다. 이 순서는 그 틈을 좁힐 뿐 보장은 아니다 — SUBSCRIBE 등록은 서버에서
+        // 비동기이고 단순 브로커는 SUBSCRIBE 영수증을 주지 않는다. 남은 틈은 다음 이벤트의 구멍 판정·탭 복귀 resync·
+        // (원카드) 해소 없는 창 resync 가 메운다. 그 사이 닿은 프레임보다 낡은 응답은 위에서 버린다.
         client.subscribe(`/topic/room/${roomId}`, (frame) => {
           const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
           // D-124 — 순번 판정은 여기서만 한다. sink 는 판정이 끝난 이벤트만 받는다.
```

```diff
           if (env.type !== 'REACTION') return;
           appendReaction(env.payload.fromSeat, env.payload.emoji);
         });
+        // 연결/재연결 직후 권위 있는 스냅샷으로 순번 기준점 동기화 — 구독을 모두 보낸 뒤에.
+        resync();
       },
       onDisconnect: () => setConnected(false),
       onStompError: () => setConnected(false),
+      // S5 — 네트워크 끊김·서버 재시작·배포(1006/1001)는 onDisconnect 가 아니라 이것만 부른다(onDisconnect 는 클라가 먼저
+      // DISCONNECT 를 보내 영수증을 받을 때만). 없으면 끊긴 뒤에도 "연결"로 보여 재연결 배너가 안 뜨고 보내기가 조용히 버려졌다.
+      onWebSocketClose: () => setConnected(false),
     });
     client.activate();
     clientRef.current = client;
```

```diff
     [roomId],
   );
 
-  return { connected, sendAction, sendChat, sendReaction, chatPanelOpenRef };
+  return { connected, sendAction, sendChat, sendReaction, chatPanelOpenRef, requestResync: resync };
 }
```

`client/src/ws/useLobbyStomp.ts`:

```diff
       },
       onDisconnect: () => setConnected(false),
       onStompError: () => setConnected(false),
+      // S5 — 갑작스러운 끊김(네트워크·서버 재시작)은 onDisconnect 가 아니라 이것만 부른다.
+      onWebSocketClose: () => setConnected(false),
     });
 
     client.activate();
```

`client/src/ws/roomEventSink.ts`:

```diff
   /** 방 진입/전환 시 게임 상태 초기화. */
   reset(roomId: string): void;
 
-  /** REST `/resync` 응답을 권위 스냅샷으로 반영. 순번 기준점(`eventSeq`)은 훅이 가진다(D-124). */
+  /**
+   * REST `/resync` 응답을 권위 스냅샷으로 반영. 순번 기준점(`eventSeq`)은 훅이 가진다(D-124). S5 — 기준점보다 낡은
+   * 응답(그 뒤 프레임이 먼저 닿았다)은 훅이 버려 여기로 오지 않는다.
+   */
   applySnapshot(snapshot: ResyncEnvelope<TTable, TPrivate>): void;
 
   /**
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- useStompRoom socketClose`
Expected: PASS — 24건.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  59 passed (59)` · `Tests  604 passed (604)`(티츄·스컬킹 게임판 테스트 그대로 통과).

- [ ] **Step 5: 커밋**

```bash
git add client/src/ws/useStompRoom.sink.test.tsx \
  client/src/ws/socketClose.test.tsx \
  client/src/ws/useStompRoom.ts \
  client/src/ws/useLobbyStomp.ts \
  client/src/ws/roomEventSink.ts
git commit -m "fix(S5): 방 STOMP 훅 — 낡은·이전 방 resync 버림, 구독 뒤 resync, 갑작스러운 끊김, requestResync

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: 클라 — 원카드: resync 때 누름 정리, 해소가 안 오는 창은 창마다 한 번 다시 받기

**Files:**
- Modify: `client/src/features/onecard/onecardStore.test.ts`, `client/src/features/onecard/OneCardTable.test.tsx`, `client/src/features/onecard/onecardStore.ts`, `client/src/features/onecard/OneCardTable.tsx`

**Interfaces:**
- Consumes: Task 5 `requestResync`, S4 스토어의 누름 표식(`press` — `lost`·BUSY 재시도)과 경쟁 창(`race.closesAt`).
- Produces: `applySnapshot` 은 기다리던 누름을 비운다. 상태 `staleRaceResyncFor: number | null`·`resyncNonce: number`, 액션 `requestRaceResync(raceId)`
  (같은 창이면 무시). `NO_RACE` 는 창이 열려 있고 그 창을 기다리던 누름이면 다시 받기를 청한다. 기다리는 누름이 없는 `BUSY` 는 창이 열려 있으면 삼킨다
  (스냅샷이 표식을 비운 뒤 늦게 온 누름의 거절 — 창 동안 내기·먹기는 막혀 있다). 게임판 `STALE_RACE_GRACE_MS = 1500`: 창이 `closesAt + 1500` 을 넘겨도
  열려 있으면 그 창에 한 번 `requestRaceResync`, `resyncNonce` 가 바뀌면 훅의 `requestResync()`. 테스트의 후속 스냅샷은
  `onecardRoomSink.applySnapshot(snapshotOf(...))` 로 넣는다(`seed` 는 reset 을 먼저 불러 거짓 통과한다).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/onecard/onecardStore.test.ts`:

```diff
     expect(store().result).toEqual(result);
   });
 
-  it('같은 창을 기다리던 누름만 남기고 오류 문구는 지운다', () => {
+  /**
+   * S5 — 기다리던 누름은 같은 창이라도 비운다. 끊긴 사이에 보낸 누름은 버려졌을 수 있는데(소켓이 이미 죽어 있었다) 남겨 두면
+   * 그 창 동안 다시 누를 수 없어 주인이면 봇에게 잡혔다. 다시 누른 것이 늦으면 서버가 NO_RACE 로 거절할 뿐이다.
+   */
+  it('기다리던 누름은 같은 창이어도 비우고 오류 문구도 지운다', () => {
     const race = {
       raceId: 7,
       ownerSeat: 0,
```

```diff
     store().setError('무언가');
 
     store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'RACE', race } }));
-    expect(store().press?.raceId).toBe(7);
+    expect(store().race?.raceId).toBe(7);
+    expect(store().press).toBeNull();
     expect(store().errorMessage).toBeNull();
-
-    store().applySnapshot(snapshot());
-    expect(store().press).toBeNull();
   });
 
   it('방금 닫힌 경쟁 안내는 서버 뷰에 없는 값이라 비운다 — 오래 떠난 뒤 돌아와도 낡은 줄이 남지 않는다', () => {
```

```diff
 
   beforeEach(() => store().applySnapshot(snapshot()));
 
-  it('BUSY 인데 기다리는 누름이 없으면 false — 카드 내기·먹기의 락 경합이라 일반 오류로 보여 준다', () => {
-    open();
+  it('창이 없을 때 기다리는 누름 없는 BUSY 는 false — 카드 내기·먹기의 락 경합이라 일반 오류로 보여 준다', () => {
     expect(store().notePressRejected('BUSY')).toBe(false);
+  });
+
+  /**
+   * S5 — 누름 → (재접속·탭 복귀·구멍) resync 스냅샷이 같은 창으로 와서 표식을 비움 → 그 누름의 BUSY 가 늦게 도착. 창이 열린
+   * 동안 내기·먹기는 막혀 있어 이 BUSY 는 누름의 것이다 — 일반 오류(빨간 줄)로 새지 않게 처리됐다고 답하고, 재시도·"늦었어요"도
+   * 없다(버튼은 이미 다시 누를 수 있다).
+   */
+  it('스냅샷이 누름을 비운 뒤 온 BUSY 는 창이 열려 있으면 오류 없이 삼킨다', () => {
+    const race = { raceId: 7, ownerSeat: 0, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000, remainingMillis: 2000 };
+    const racing = snapshot({ tableView: { ...TABLE, phase: 'RACE', turnSeat: -1, race } });
+    store().applySnapshot(racing);
+    store().startPress(7, 'CATCH');
+    store().applySnapshot(racing); // 같은 창 — 누름 표식이 비었다
+
+    expect(store().notePressRejected('BUSY')).toBe(true);
+    expect(store().errorMessage).toBeNull();
+    expect(store().raceNotice).toBeNull();
+    expect(store().retryNonce).toBe(0);
   });
 
   it('내가 이긴 경쟁 뒤의 BUSY 도 일반 오류다 — 이긴 누름은 남지 않는다', () => {
```

```diff
     expect(store().suitChoice).toBeNull();
   });
 });
+
+describe('낡은 창 복구 — 다시 받기 신호 (S5)', () => {
+  // 서버가 실제로 내는 순서로 넣는다 — 창 9(주인 좌석 0, seq 11) → 해소(12) → 차례(13, 좌석 1) → 좌석 1 이 1장이 되는
+  // 카드(14) → 창 10(주인 좌석 1, seq 15). 창 사이에는 늘 해소·차례·카드가 있다.
+  const open = (raceId: number, seq: number, ownerSeat = 0) =>
+    store().applyEvent(
+      ev('RACE_OPENED', { raceId, ownerSeat, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000 }, seq),
+    );
+  const closeAndPlayOn = (outcome: string, bySeat: number) => {
+    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome, bySeat }, 12));
+    store().applyEvent(ev('TURN_CHANGED', { seat: 1, direction: 1, attackStack: 0 }, 13));
+    store().applyEvent(
+      ev('CARD_PLAYED', { seat: 1, card: card('HEART', 4), declaredSuit: null, handCount: 1, attackStack: 0, direction: 1 }, 14),
+    );
+  };
+
+  beforeEach(() => store().applySnapshot(snapshot()));
+
+  it('창마다 한 번만 다시 받기를 청한다', () => {
+    open(9, 11);
+
+    store().requestRaceResync(9);
+    store().requestRaceResync(9);
+    expect(store().resyncNonce).toBe(1);
+
+    closeAndPlayOn('EXPIRED', -1);
+    open(10, 15, 1);
+    store().requestRaceResync(10);
+    expect(store().resyncNonce).toBe(2);
+  });
+
+  /**
+   * 서버가 창을 닫아 저장했는데 방송이 실패하면(C-I2) 해소 이벤트가 끝내 안 온다 — 내 누름은 NO_RACE 로 거절되는데 창은
+   * 계속 열려 보인다. 그 창을 기다리던 누름의 NO_RACE 면 권위 스냅샷을 다시 받는다.
+   */
+  it('창이 열린 채 그 창을 기다리던 누름이 NO_RACE 를 받으면 다시 받기를 청한다', () => {
+    open(9, 11);
+    store().startPress(9, 'CATCH');
+
+    expect(store().notePressRejected('NO_RACE')).toBe(true);
+
+    expect(store().raceNotice).toBe('LATE');
+    expect(store().resyncNonce).toBe(1);
+  });
+
+  it('해소 이벤트가 먼저 와 창이 닫혔으면 NO_RACE 가 와도 다시 받지 않는다 — 서버가 보내는 보통 순서', () => {
+    open(9, 11);
+    store().startPress(9, 'CATCH');
+    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome: 'CALLED', bySeat: 0 }, 12));
+    store().applyEvent(ev('TURN_CHANGED', { seat: 1, direction: 1, attackStack: 0 }, 13));
+
+    store().notePressRejected('NO_RACE');
+
+    expect(store().resyncNonce).toBe(0);
+  });
+
+  it('지난 창의 누름에 늦게 온 NO_RACE 는 새 창을 의심하지 않는다', () => {
+    open(9, 11);
+    store().startPress(9, 'CATCH');
+    closeAndPlayOn('CALLED', 0);
+    open(10, 15, 1); // 새 창이 열려 누름 표식은 비었다
+
+    store().notePressRejected('NO_RACE');
+
+    expect(store().resyncNonce).toBe(0);
+  });
+});
+
```

`client/src/features/onecard/OneCardTable.test.tsx`:

```diff
 import { act, fireEvent, render, screen, within } from '@testing-library/react';
 import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
-import { LATE_NOTICE_MS, OneCardTable, PRESS_RETRY_DELAY_MS } from './OneCardTable';
+import { LATE_NOTICE_MS, OneCardTable, PRESS_RETRY_DELAY_MS, STALE_RACE_GRACE_MS } from './OneCardTable';
 import { onecardRoomSink } from './onecardRoomSink';
 import { useOneCardStore } from './onecardStore';
 import { useAuthStore } from '@/features/auth/authStore';
```

```diff
 
 // 소켓만 모킹하고 스토어는 실물을 seed 한다 (스컬킹 게임판 테스트와 같은 방식).
 const sendAction = vi.fn();
+/** S5 — 훅의 권위 스냅샷 재요청. 훅이 주는 것처럼 렌더마다 같은 참조다. */
+const requestResync = vi.fn();
 let socketConnected = true;
 vi.mock('@/ws/useStompRoom', () => ({
   useStompRoom: () => ({
```

```diff
     sendChat: vi.fn(),
     sendReaction: vi.fn(),
     chatPanelOpenRef: { current: false },
+    requestResync,
   }),
 }));
 
```

```diff
 
 const NOW = 1_000_000;
 
-function seed(opts: {
+interface SeedOptions {
   seatCount: number;
   mySeat: number;
   hand?: OneCardCard[];
```

```diff
   seats?: OneCardSeatView[];
   race?: OneCardRaceView | null;
   result?: OneCardMatchResult | null;
-}) {
+}
+
+function seed(opts: SeedOptions) {
+  useOneCardStore.getState().reset('r-1');
+  useOneCardStore.getState().applySnapshot(snapshotOf(opts));
+}
+
+/** resync 응답 모양 — 훅이 sink 로 넘기는 그대로. */
+function snapshotOf(opts: SeedOptions) {
   const table: OneCardTableView = {
     phase: opts.result ? 'ENDED' : opts.race ? 'RACE' : 'PLAYING',
     seats: opts.seats ?? Array.from({ length: opts.seatCount }, (_, i) => seatOf(i)),
```

```diff
     race: opts.race ?? null,
     result: opts.result ?? null,
   };
-  useOneCardStore.getState().reset('r-1');
-  useOneCardStore.getState().applySnapshot({
+  return {
     roomId: 'r-1',
     phase: table.phase,
     eventSeq: 1,
```

```diff
       opts.mySeat >= 0 ? { seat: opts.mySeat, hand: opts.hand ?? [], handVersion: 1 } : null,
     disconnectedSeats: [],
     chips: null,
-  });
+  };
 }
 
 const RACE: OneCardRaceView = {
```

```diff
   vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
   vi.setSystemTime(NOW);
   sendAction.mockReset();
+  requestResync.mockReset();
   socketConnected = true;
   useAuthStore.setState({ token: 'tok' } as never);
   useOneCardStore.getState().reset('r-1');
```

```diff
 
     expect(screen.getAllByText('잡기 성공: #102 → #101 벌칙 1장')).toHaveLength(2);
     expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
+  });
+});
+
+describe('OneCardTable — 낡은 창 복구 (S5)', () => {
+  // 서버가 실제로 만드는 순서로 넣는다. 해소 이벤트가 끝내 안 오는 경우는 둘이다 — 엔진 타이머가 사라져 서버에서도 창이
+  // 열린 채 멈췄거나(C-I2 경로 1·4: 서버 resync 가 진행 킥으로 타이머를 다시 건다), 서버는 창을 닫아 저장했는데 방송이
+  // 실패했다(경로 3: 누름은 NO_RACE 로만 돌아온다).
+  const errorEnvelope = (code: string) => ({ eventId: 'e', type: 'ERROR', ts: 0, payload: { code, message: 'detail' } });
+
+  it('창이 마감 + 1.5초가 지나도 열려 있으면 권위 스냅샷을 창마다 한 번 다시 청한다', () => {
+    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
+    renderTable({ playerIds: [100, 101, 102] });
+
+    act(() => {
+      vi.advanceTimersByTime(RACE.remainingMillis + STALE_RACE_GRACE_MS - 1);
+    });
+    expect(requestResync).not.toHaveBeenCalled();
+    act(() => {
+      vi.advanceTimersByTime(1);
+    });
+    expect(requestResync).toHaveBeenCalledTimes(1);
+
+    // 서버도 창을 아직 들고 있다(타이머 유실) — 스냅샷은 남은 시간 0 인 같은 창. 같은 창으로는 더 청하지 않는다.
+    act(() =>
+      onecardRoomSink.applySnapshot(
+        snapshotOf({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: { ...RACE, remainingMillis: 0 } }),
+      ),
+    );
+    act(() => {
+      vi.advanceTimersByTime(STALE_RACE_GRACE_MS * 3);
+    });
+    expect(requestResync).toHaveBeenCalledTimes(1);
+
+    // 서버 resync 의 진행 킥이 다시 건 타이머가 발화해 창이 닫힌다.
+    act(() => {
+      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', payload: { raceId: 7, outcome: 'EXPIRED', bySeat: -1 } });
+    });
+    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
+  });
+
+  it('제때 닫힌 창은 다시 청하지 않는다', () => {
+    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
+    renderTable({ playerIds: [100, 101, 102] });
+
+    act(() => {
+      vi.advanceTimersByTime(2_000);
+      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', payload: { raceId: 7, outcome: 'CAUGHT', bySeat: 2 } });
+      vi.advanceTimersByTime(STALE_RACE_GRACE_MS * 3);
+    });
+
+    expect(requestResync).not.toHaveBeenCalled();
+  });
+
+  it('해소 이벤트 없이 내 누름이 NO_RACE 로 돌아오면 한 번 다시 청하고, 스냅샷이 창을 닫는다', () => {
+    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
+    renderTable({ playerIds: [100, 101, 102] });
+
+    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
+    act(() => onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE')));
+
+    expect(requestResync).toHaveBeenCalledTimes(1);
+    expect(screen.queryByRole('alert')).toBeNull();
+
+    // 스냅샷 — 서버는 이미 창을 닫고 좌석 1 차례로 넘겼다.
+    act(() => onecardRoomSink.applySnapshot(snapshotOf({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], turnSeat: 1 })));
+    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
+  });
+
+  it('해소 → 차례 → NO_RACE 의 보통 순서에서는 다시 청하지 않는다', () => {
+    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
+    renderTable({ playerIds: [100, 101, 102] });
+
+    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
+    act(() => {
+      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', payload: { raceId: 7, outcome: 'CALLED', bySeat: 1 } });
+      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', payload: { seat: 0, direction: 1, attackStack: 0 } });
+      onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));
+    });
+
+    expect(requestResync).not.toHaveBeenCalled();
+  });
+
+  /**
+   * 끊긴 줄 모르고 누른 경우(F2) — 누름은 버려졌는데 표식만 남아 그 창 동안 버튼이 잠겼다. 재접속 resync 의 스냅샷이 오면
+   * 같은 창이라도 다시 누를 수 있다.
+   */
+  it('resync 스냅샷이 오면 같은 창을 다시 누를 수 있다', () => {
+    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
+    renderTable({ playerIds: [100, 101, 102] });
+
+    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
+    expect(screen.getByRole('button', { name: '잡기!' })).toBeDisabled();
+
+    act(() =>
+      onecardRoomSink.applySnapshot(
+        snapshotOf({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: { ...RACE, remainingMillis: 2_000 } }),
+      ),
+    );
+    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
+
+    expect(sendAction).toHaveBeenCalledTimes(2);
+    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'CATCH', raceId: 7 });
   });
 });
 
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- onecardStore OneCardTable`
Expected: FAIL — `Test Files  2 failed (2)` · `Tests  9 failed | 77 passed (86)`(스토어 6 — 같은 창 스냅샷이 누름을 비움, 스냅샷 뒤 늦은 BUSY,
`requestRaceResync is not a function`, NO_RACE 3건 / 게임판 3 — 마감 + 1.5초 지나도 열린 창, 해소 없이 온 NO_RACE, resync 뒤 같은 창 다시 누르기).

- [ ] **Step 3: 구현**

`client/src/features/onecard/onecardStore.ts`:

```diff
   retryNonce: number;
   /** 경쟁에서 진 내 누름(남이 먼저 이겼거나 창이 닫힘) — 오류 대신 "늦었어요"를 잠깐 보여 준다. */
   raceNotice: 'LATE' | null;
+  /**
+   * S5 — 낡았다고 의심돼 권위 스냅샷을 청한 창 번호(창마다 한 번). 해소 이벤트가 끝내 안 오는 창 — 서버 타이머가
+   * 사라졌거나 방송이 실패했다 — 은 클라가 스스로 닫을 길이 없다.
+   */
+  staleRaceResyncFor: number | null;
+  /**
+   * S5 — 다시 받기 신호. 게임판이 이 값의 변화를 보고 훅의 `requestResync` 를 부른다 — sink·스토어는 훅을 모른다(D-103).
+   */
+  resyncNonce: number;
 
   // ── 메타 ──
   disconnectedSeats: Set<number>;
```

```diff
    * <p>`NO_RACE` 는 누름(`CALL_ONE_CARD`·`CATCH`)에서만 나오므로 항상 "늦었어요"다 — 해소 이벤트가 먼저 와 누름 표식을
    * 이미 바꿨어도 같다. `BUSY` 는 내기·먹기의 락 경합에서도 오므로 기다리는 누름이 있을 때만 경쟁 누름의 것으로 본다:
    * 창이 열려 있고 횟수가 남았으면 재시도 신호를 올리고, 더 시도할 창이 없으면(횟수 소진·마감·남이 이김) "늦었어요".
+   * S5 — 기다리는 누름이 없어도 창이 열려 있으면 경쟁 누름의 것이다(스냅샷이 표식을 비운 뒤 늦게 온 BUSY) — 조용히 삼킨다.
    */
   notePressRejected: (code: 'BUSY' | 'NO_RACE') => boolean;
   clearRaceNotice: () => void;
+  /** S5 — 이 창이 낡았다고 의심되면 권위 스냅샷을 청한다. 같은 창으로는 한 번만(신호를 올리지 않는다). */
+  requestRaceResync: (raceId: number) => void;
 }
 
 const INITIAL: OneCardRoomState = {
```

```diff
   press: null,
   retryNonce: 0,
   raceNotice: null,
+  staleRaceResyncFor: null,
+  resyncNonce: 0,
   disconnectedSeats: new Set(),
   errorMessage: null,
   turnStartedAt: 0,
```

```diff
       lastRace: null,
       result: t.result,
       ...mine,
-      // 같은 창을 기다리던 누름만 남긴다.
-      press: race && state.press?.raceId === race.raceId ? state.press : null,
+      // S5 — 기다리던 누름은 같은 창이라도 비운다. 끊긴 사이에 보낸 누름은 버려졌을 수 있다(소켓이 이미 죽어 있었다) —
+      // 남겨 두면 그 창 동안 다시 누를 수 없었다. 다시 누른 것이 늦으면 서버가 NO_RACE 로 거절할 뿐이다.
+      press: null,
       disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
       errorMessage: null,
       turnStartedAt: Date.now(),
```

```diff
     // 이벤트가 먼저 와서 누름 표식을 바꿨거나 지웠어도(resync 등) 같다.
     if (code === 'NO_RACE') {
       set({ press: null, raceNotice: 'LATE' });
+      // S5 — 그 창을 기다리던 누름인데 창이 아직 열려 보인다: 해소 이벤트를 못 받았다(서버는 닫아 저장했는데 방송이
+      // 실패했거나, 순서가 뒤바뀌어 오는 중이다). 창마다 한 번 권위 스냅샷을 받는다 — 뒤바뀐 경우면 한 번 더 받을 뿐이다.
+      if (race !== null && press?.raceId === race.raceId) get().requestRaceResync(race.raceId);
       return true;
     }
-    // BUSY 는 내기·먹기의 락 경합에서도 온다 — 기다리는 누름이 없으면 일반 오류로 둔다.
-    if (!press) return false;
+    // BUSY 는 내기·먹기의 락 경합에서도 온다 — 창이 없을 때 기다리는 누름이 없으면 일반 오류로 둔다.
+    if (!press) {
+      // S5 — 창이 열려 보이는데 기다리는 누름이 없다: resync 스냅샷이 표식을 비운 뒤 그 누름의 BUSY 가 늦게 왔다(창이 열린
+      // 동안 내기·먹기는 버튼이 막혀 BUSY 는 누름의 것이다). 버튼은 이미 다시 누를 수 있다 — 빨간 오류 없이 삼킨다.
+      return race !== null;
+    }
     const open =
       !press.lost && race !== null && race.raceId === press.raceId && Date.now() < race.closesAt;
     if (open && press.attempts < MAX_PRESS_ATTEMPTS) {
```

```diff
   clearRaceNotice() {
     set({ raceNotice: null });
   },
+
+  requestRaceResync(raceId) {
+    const { staleRaceResyncFor, resyncNonce } = get();
+    if (staleRaceResyncFor === raceId) return;
+    set({ staleRaceResyncFor: raceId, resyncNonce: resyncNonce + 1 });
+  },
 }));
```

`client/src/features/onecard/OneCardTable.tsx`:

```diff
 export const PRESS_RETRY_DELAY_MS = 120;
 /** "늦었어요"를 보여 주는 시간. */
 export const LATE_NOTICE_MS = 1500;
+/**
+ * S5 — 경쟁 창이 마감 뒤 이만큼 지나도 열려 있으면 해소 이벤트를 놓쳤거나 서버 타이머가 사라진 것으로 보고 권위 스냅샷을
+ * 다시 받는다(서버 resync 는 진행 킥으로 사라진 타이머를 다시 건다). 폴링 주기·왕복 시간보다 넉넉히.
+ */
+export const STALE_RACE_GRACE_MS = 1500;
 
 interface Props {
   roomId: string;
```

```diff
   roomFinished = false,
 }: Props) {
   const token = useAuthStore((s) => s.token);
-  const { connected, sendAction, sendChat, chatPanelOpenRef } = useStompRoom(
+  const { connected, sendAction, sendChat, chatPanelOpenRef, requestResync } = useStompRoom(
     roomId,
     token,
     onecardRoomSink,
```

```diff
     }, PRESS_RETRY_DELAY_MS);
     return () => window.clearTimeout(timer);
   }, [s.retryNonce, sendAction]);
+
+  // S5 — 낡은 창 복구. 창이 마감 + 유예 뒤에도 열려 있으면 그 창을 낡았다고 표시한다(창마다 한 번 — 스토어가 거른다).
+  const staleRaceId = s.race?.raceId;
+  const staleRaceClosesAt = s.race?.closesAt;
+  useEffect(() => {
+    if (staleRaceId === undefined || staleRaceClosesAt === undefined) return;
+    const timer = window.setTimeout(() => {
+      const { race, requestRaceResync } = useOneCardStore.getState();
+      if (race?.raceId === staleRaceId) requestRaceResync(staleRaceId);
+    }, Math.max(0, staleRaceClosesAt + STALE_RACE_GRACE_MS - Date.now()));
+    return () => window.clearTimeout(timer);
+  }, [staleRaceId, staleRaceClosesAt]);
+
+  // 스토어가 다시 받기를 청하면(낡은 창 — 마감 초과·창이 열린 채 NO_RACE) 훅으로 권위 스냅샷을 받는다.
+  useEffect(() => {
+    if (s.resyncNonce === 0) return;
+    requestResync();
+  }, [s.resyncNonce, requestResync]);
 
   // "늦었어요"는 잠깐만.
   const clearRaceNotice = s.clearRaceNotice;
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- onecardStore OneCardTable`
Expected: PASS — 86건.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  59 passed (59)` · `Tests  614 passed (614)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/onecard/onecardStore.test.ts \
  client/src/features/onecard/OneCardTable.test.tsx \
  client/src/features/onecard/onecardStore.ts \
  client/src/features/onecard/OneCardTable.tsx
git commit -m "fix(S5): 원카드 클라 — resync 때 누름 정리, 낡은 경쟁 창은 창마다 한 번 다시 받기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: 클라 — 공용 인프라 거절 문구(원카드 sink 적용) + 허브 방 목록 표시 이름

**Files:**
- Create: `client/src/ws/errorLabels.test.ts`, `client/src/ws/errorLabels.ts`
- Modify: `client/src/features/onecard/onecardRoomSink.test.ts`, `client/src/pages/GameHubPage.test.tsx`, `client/src/features/onecard/onecardRoomSink.ts`, `client/src/pages/GameHubPage.tsx`

**Interfaces:**
- Consumes: 원카드 sink 의 거절 문구 표(`RejectionReason`), 허브의 기존 `gameName(gameType)` 헬퍼(카탈로그 표시 이름).
- Produces: `client/src/ws/errorLabels.ts` — `INFRA_ERROR_LABELS`(9코드: `INVALID_ACTION`·`INTERNAL_ERROR`·`NOT_IN_ROOM`·`ROOM_NOT_FOUND`·`GAME_NOT_AVAILABLE`·
  `RATE_LIMITED`·`BUSY`·`GAME_NOT_STARTED`·`GAME_NOT_IN_PROGRESS`), `errorText(code, message, gameLabels)` = 게임 표 → 인프라 표 → `CODE: 원문`. 티츄·스컬킹
  sink 적용은 후속. 허브 방 목록은 `gameType` 원문 대신 표시 이름(모르는 게임은 원문).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/ws/errorLabels.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { INFRA_ERROR_LABELS, errorText } from './errorLabels';

/**
 * S5 — 게임과 무관하게 인프라가 보내는 거절 코드(인게임 컨트롤러·레이트 리미터)의 한국어 문구는 한 곳에 둔다. 라벨이
 * 없으면 `CODE: 영문 메시지` 가 그대로 보였다 — Redis 장애의 INTERNAL_ERROR, 연타의 RATE_LIMITED, 끈 게임의
 * GAME_NOT_AVAILABLE 이 실제로 닿는 경로다.
 */
describe('인프라 거절 문구', () => {
  it.each([
    'BUSY',
    'GAME_NOT_STARTED',
    'GAME_NOT_IN_PROGRESS',
    'INVALID_ACTION',
    'INTERNAL_ERROR',
    'NOT_IN_ROOM',
    'ROOM_NOT_FOUND',
    'GAME_NOT_AVAILABLE',
    'RATE_LIMITED',
  ])('%s 는 한국어 문구가 있다', (code) => {
    expect(INFRA_ERROR_LABELS[code]).toMatch(/[가-힣]/);
    expect(errorText(code, 'English detail')).toBe(INFRA_ERROR_LABELS[code]);
  });

  it('게임 라벨이 먼저다', () => {
    expect(errorText('BUSY', 'x', { BUSY: '게임 문구' })).toBe('게임 문구');
  });

  it('모르는 코드는 코드와 원문을 그대로 — 새 코드를 놓치지 않게', () => {
    expect(errorText('SOMETHING_NEW', 'detail')).toBe('SOMETHING_NEW: detail');
  });
});
```

`client/src/features/onecard/onecardRoomSink.test.ts`:

```diff
 import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
 import { onecardRoomSink } from './onecardRoomSink';
 import { useOneCardStore } from './onecardStore';
+import { INFRA_ERROR_LABELS } from '@/ws/errorLabels';
 
 /**
  * 원카드 비공개 큐 → 스토어. 손패 이벤트 두 종류는 같은 경로(`handVersion` 가드)로, `ERROR` 는 문구로, 경쟁
```

```diff
 
     expect(store().errorMessage).toBe('SOMETHING_NEW: detail');
   });
+
+  /** S5 — 인프라 거절(게임 중립)은 공용 문구로. 예전에는 `INTERNAL_ERROR: Failed to apply action` 처럼 영문 원문이 보였다. */
+  it.each(['INVALID_ACTION', 'INTERNAL_ERROR', 'NOT_IN_ROOM', 'ROOM_NOT_FOUND', 'GAME_NOT_AVAILABLE', 'RATE_LIMITED'])(
+    '인프라 거절 %s 도 한국어 문구로 보여 준다',
+    (code) => {
+      onecardRoomSink.applyPrivateEvent(errorEnvelope(code, 'English detail'));
+
+      expect(store().errorMessage).toBe(INFRA_ERROR_LABELS[code]);
+      expect(store().errorMessage).not.toContain('English detail');
+    },
+  );
 
   it('기다리는 누름이 없으면 BUSY 도 일반 오류다 — 카드를 낼 때의 락 경합', () => {
     onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));
```

`client/src/pages/GameHubPage.test.tsx`:

```diff
 import { beforeEach, describe, expect, it, vi } from 'vitest';
 import { GameHubPage } from './GameHubPage';
 import { useAuthStore } from '@/features/auth/authStore';
-import type { GameSummary } from '@/types/api';
+import type { GameSummary, Room } from '@/types/api';
 
 /**
  * D-121 — 허브의 '게임 방법'은 게임 카드마다 달린다. 허브는 게임을 고를 수 없으므로
```

```diff
   game('TICHU', '티츄'),
   game('COMING_SOON', '준비 중 게임', 'COMING_SOON'),
 ];
+
+function room(roomId: string, gameType: string, capacity: number): Room {
+  return {
+    roomId,
+    name: `방 ${roomId}`,
+    gameType,
+    hostId: 2,
+    status: 'WAITING',
+    capacity,
+    playerCount: 1,
+    playerIds: [2],
+    spectatorIds: [],
+    teamPolicy: 'SEQUENTIAL',
+    createdAt: 0,
+    fillWithBots: false,
+    botSeats: [],
+    targetScore: 1000,
+    turnSeconds: 0,
+    stake: 0,
+    readyUserIds: [],
+  };
+}
 
 function renderHub() {
   return render(
```

```diff
     expect(screen.queryByRole('dialog')).toBeNull();
   });
 });
+
+describe('GameHubPage — 대기 중인 방 목록 (S5)', () => {
+  beforeEach(() => {
+    vi.clearAllMocks();
+    localStorage.clear();
+    catalog.mockResolvedValue({ games: CATALOG });
+    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
+    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
+  });
+
+  /** 대기실 헤더(D-110)처럼 카탈로그의 표시 이름을 쓴다 — `SKULL_KING`·`ONE_CARD` 원문이 나란히 보였다. */
+  it('게임 종류를 표시 이름으로, 카탈로그에 없는 게임은 원문으로 보여 준다', async () => {
+    list.mockResolvedValue({ rooms: [room('a', 'SKULL_KING', 8), room('b', 'MYSTERY', 4)] });
+    renderHub();
+
+    expect(await screen.findByText(/스컬킹 · 1 \/ 8/)).toBeInTheDocument();
+    expect(screen.getByText(/MYSTERY · 1 \/ 4/)).toBeInTheDocument();
+    expect(screen.queryByText(/SKULL_KING ·/)).toBeNull();
+  });
+});
+
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- errorLabels onecardRoomSink GameHubPage`
Expected: FAIL — `Test Files  3 failed (3)` · `Tests  1 failed | 4 passed (5)`(`Failed to resolve import "./errorLabels"`,
`Failed to resolve import "@/ws/errorLabels"`, `게임 종류를 표시 이름으로 …` — `Unable to find an element with the text: /스컬킹 · 1 \/ 8/`).

- [ ] **Step 3: 구현**

`client/src/ws/errorLabels.ts`:

```ts
/**
 * 게임 중립 거절 코드 → 사용자 문구 (S5). 서버 인프라가 어느 게임에서나 같은 코드로 본인 큐 `ERROR` 를 보낸다 —
 * 인게임 컨트롤러(`GameStompController`: 방·좌석·진행 상태·역직렬화·락 경합·예기치 못한 실패)와 레이트 리미터
 * (`StompRateLimitInterceptor`). 게임 sink 는 자기 거절 사유 표를 먼저 보고 없으면 여기를 본다({@link errorText}).
 *
 * <p>지금은 원카드 sink 만 쓴다. 티츄·스컬킹 sink 는 이 코드 일부를 자기 표에 들고 있다(옮기는 것은 후속).
 */
export const INFRA_ERROR_LABELS: Readonly<Record<string, string>> = {
  BUSY: '다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.',
  GAME_NOT_STARTED: '아직 게임이 시작되지 않았습니다.',
  // D-122 — 강제 종료·탈주 조기 종료 뒤(FINISHED) 늦게 낸 액션의 거절.
  GAME_NOT_IN_PROGRESS: '이미 끝난 게임입니다.',
  INVALID_ACTION: '이 게임에서 처리할 수 없는 요청입니다. 화면을 새로 고쳐 주세요.',
  INTERNAL_ERROR: '서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.',
  NOT_IN_ROOM: '이 방의 참가자가 아닙니다.',
  ROOM_NOT_FOUND: '방을 찾을 수 없습니다. 이미 끝났거나 사라진 방입니다.',
  GAME_NOT_AVAILABLE: '지금은 이 게임을 할 수 없습니다.',
  RATE_LIMITED: '요청이 너무 빠릅니다. 잠시 후 다시 시도하세요.',
};

/** 거절 코드 → 문구. 게임 라벨이 먼저, 다음이 인프라 라벨, 둘 다 없으면 `CODE: 원문`(새 코드를 놓치지 않게). */
export function errorText(
  code: string,
  message: string,
  gameLabels: Readonly<Record<string, string>> = {},
): string {
  return gameLabels[code] ?? INFRA_ERROR_LABELS[code] ?? `${code}: ${message}`;
}
```

`client/src/features/onecard/onecardRoomSink.ts`:

```diff
 import type { RoomEventSink } from '@/ws/roomEventSink';
+import { errorText } from '@/ws/errorLabels';
 import type { StompEnvelope } from '@/types/stomp';
 import type { HandPayload, OneCardPrivateView, OneCardTableView } from '@/types/onecard';
 import { useOneCardStore } from './onecardStore';
```

```diff
   message: string;
 }
 
-/** 서버 거절 사유(원카드 `RejectionReason` + 인프라 공통) → 사용자 문구. */
+/**
+ * 서버 거절 사유(원카드 `RejectionReason`) → 사용자 문구. 게임 중립 인프라 코드(BUSY·RATE_LIMITED·INTERNAL_ERROR 등)는
+ * 공용 표(`ws/errorLabels`)가 맡는다(S5).
+ */
 const ERROR_LABEL: Record<string, string> = {
   MATCH_OVER: '이미 끝난 판입니다.',
   PLAYER_ELIMINATED: '탈락한 좌석은 더 할 수 없습니다.',
```

```diff
   NO_RACE: '이미 끝난 경쟁입니다.',
   NOT_RACE_OWNER: '"원카드!"는 카드가 1장 남은 사람만 누를 수 있습니다.',
   OWNER_CANNOT_CATCH: '자기 자신은 잡을 수 없습니다.',
-  BUSY: '다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.',
-  GAME_NOT_STARTED: '아직 게임이 시작되지 않았습니다.',
-  GAME_NOT_IN_PROGRESS: '이미 끝난 게임입니다.',
 };
 
 /**
```

```diff
     } else if (envelope.type === 'ERROR') {
       const p = envelope.payload as ErrorPayload;
       if ((p.code === 'BUSY' || p.code === 'NO_RACE') && store.notePressRejected(p.code)) return;
-      store.setError(ERROR_LABEL[p.code] ?? `${p.code}: ${p.message}`);
+      store.setError(errorText(p.code, p.message, ERROR_LABEL));
     }
     // 그 외 타입은 조용히 무시한다.
   },
```

`client/src/pages/GameHubPage.tsx`:

```diff
                       )}
                     </span>
                     <span className="text-xs text-muted-foreground">
-                      {room.gameType} · {room.playerCount} / {room.capacity} ·{' '}
+                      {/* S5 — 대기실 헤더(D-110)처럼 카탈로그 표시 이름. 모르는 게임은 원문. */}
+                      {gameName(room.gameType)} · {room.playerCount} / {room.capacity} ·{' '}
                       {room.status}
                     </span>
                     <Button
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- errorLabels onecardRoomSink GameHubPage`
Expected: PASS — 33건.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  60 passed (60)` · `Tests  632 passed (632)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/ws/errorLabels.test.ts \
  client/src/features/onecard/onecardRoomSink.test.ts \
  client/src/pages/GameHubPage.test.tsx \
  client/src/ws/errorLabels.ts \
  client/src/features/onecard/onecardRoomSink.ts \
  client/src/pages/GameHubPage.tsx
git commit -m "feat(S5): 공용 인프라 거절 문구(원카드 sink 적용) + 허브 방 목록 표시 이름

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: 문서 — 공개 상태 런북·경쟁 튜닝·결과 로그, 프로토콜·API·포트 계약, 설계 §8 + 실측·Phase Gate

코드는 바꾸지 않는다. 아래 diff 는 Task 1~7 을 마친 뒤의 파일 기준이다(이 문서들은 앞 태스크가 건드리지 않았다). `docs/decisions.md`(D-130)·`CLAUDE.md`·
`README.md`·`docs/implementation-status.md`·`docs/case-study-multi-game.md`·`docs/plans/mvp-roadmap.md`·`docs/qa-scenarios.md` 는 D-116 겹침이라 S5 후반에서 고친다.

**Files:**
- Modify: `docs/deploy.md`, `.env.example`, `docs/stomp-protocol.md`, `docs/api.md`, `docs/game-port.md`, `docs/plans/onecard.md`

**Interfaces:**
- Consumes: Task 1~7 의 동작·이름(킥·`scheduleIfAbsent`·결과 로그 형식·`defaultPlayers`/`defaultTurnSeconds`·훅 규약·공용 거절 문구).

- [ ] **Step 1: 운영 런북과 환경 변수 예시**

`docs/deploy.md`:

````diff
 
 ---
 
+## 원카드 공개 상태 (`MIRBOARD_ONECARD_STATUS`, D-128)
+
+원카드의 카탈로그 상태는 설정이다(`mirboard.onecard.status`, 코드 기본값은 `application.yml`). 운영에서는 시크릿으로만
+덮어쓴다 — `fly.toml` 의 `[env]` 에 같은 키를 두지 않는다(두 곳에 있으면 어느 값이 이기는지 헷갈린다).
+
+| 값 | 허브 카탈로그 | 새 원카드 방 | 진행 중인 원카드 방 |
+| --- | --- | --- | --- |
+| `AVAILABLE` | 보인다 | 만들 수 있다 | 정상 |
+| `COMING_SOON` | "Coming Soon" | 404 `GAME_NOT_AVAILABLE` | **끝까지 정상 진행** |
+| `DISABLED` | 안 보인다 | 404 `GAME_NOT_AVAILABLE` | **멈춘다** — 액션은 `GAME_NOT_AVAILABLE`, resync 404, 봇·턴·경쟁 타이머가 오류로 멈추고 '나가기'도 404. 방은 마지막 입장부터 6시간(방 해시 TTL)까지 IN_GAME 으로 남고 결과는 기록되지 않는다 |
+
+**되돌리기는 `COMING_SOON` 으로만 한다.** 새 방만 막고 진행 중인 판은 끝까지 간다. 이미 열려 있던 WAITING 원카드 방은
+허브 목록에 남고 입장·준비·시작이 된다(막는 것은 방 만들기뿐이다).
+
+```bash
+# 끄기(되돌리기) — 머신이 재시작된다
+flyctl secrets set MIRBOARD_ONECARD_STATUS=COMING_SOON -a mirboard
+# 다시 열기 — 시크릿을 지우면 코드 기본값으로 돌아간다
+flyctl secrets unset MIRBOARD_ONECARD_STATUS -a mirboard
+```
+
+- **`DISABLED` 는 진행 중인 원카드 방이 0 일 때만.** 경과 시간으로는 보장되지 않는다 — `COMING_SOON` 뒤에도 남은 WAITING
+  방은 입장·준비·시작될 수 있고, 방 해시 TTL(6시간)은 **입장할 때마다** 다시 걸린다(게임 상태 키는 저장할 때마다 6시간씩
+  밀린다). Redis 에서 직접 확인한 뒤에만 바꾼다 — 아래가 아무것도 출력하지 않거나 `IN_GAME` 줄이 없을 때:
+
+  ```bash
+  flyctl ssh console -a mirboard-redis
+  # 머신 안(sh)에서 — REDISCLI_AUTH 에 Redis 비밀번호(시크릿 REDIS_PASSWORD)를 넣는다
+  export REDISCLI_AUTH="$REDIS_PASSWORD"
+  redis-cli --scan --pattern 'room:*' | while read -r k; do
+    [ "$(redis-cli TYPE "$k")" = hash ] && [ "$(redis-cli HGET "$k" gameType)" = ONE_CARD ] && echo "$k $(redis-cli HGET "$k" status)"
+  done
+  ```
+
+  (`room:*` 키 가운데 해시이고 `gameType` 이 `ONE_CARD` 인 것만 — 방 해시다. `redis-cli` 7.4 의 `--scan` 에는 `--type` 이
+  없어서 키마다 `TYPE` 을 본다. 스크립트는 `redis:7-alpine` 에서 검증했다 — 운영 머신 셸에 `REDIS_PASSWORD` 가 보이는지는
+  처음 쓸 때 한 번 확인한다.)
+- **잘못된 값은 앱 전체 기동 실패다**(빈 값·오타 — 의도된 fail-fast). 사고 중에 쓰는 손잡이이므로 값은 위 명령을
+  그대로 복사한다(대문자).
+- 시크릿을 바꾸면 머신이 재시작된다. 재시작 순간 봇 차례였던 방은 클라가 다시 붙을 때(resync·게임 토픽 구독) 진행
+  킥이 봇 루프와 사라진 경쟁 타이머를 다시 건다(S5) — 판이 멈춘 채 남지 않는다.
+
+**경쟁 튜닝**(룰 §9). 환경 변수 이름은 설정 키에서 나온다(Spring relaxed binding). 잘못된 조합(최소 > 최대, 창 ≤ 0)도
+기동 실패다(`RaceSettings`). 봇 반응 구간을 바꾸면 튜토리얼의 "봇 반응 1.0~2.5초" 문구도 같은 배포에서 고친다.
+
+| 환경 변수 | 기본 | 뜻 |
+| --- | --- | --- |
+| `MIRBOARD_ONECARD_RACE_WINDOW_MILLIS` | `3000` | "원카드!/잡기!" 경쟁 창 길이 |
+| `MIRBOARD_ONECARD_BOT_REACTION_OWNER_MIN_MILLIS` · `..._OWNER_MAX_MILLIS` | `1000` · `2500` | 1장 남은 봇이 "원카드!"를 누르는 반응 시간 구간 |
+| `MIRBOARD_ONECARD_BOT_REACTION_CATCHER_MIN_MILLIS` · `..._CATCHER_MAX_MILLIS` | `1000` · `2500` | 다른 봇이 "잡기!"를 누르는 반응 시간 구간 |
+
+튜닝 근거는 경쟁 결과 로그다(S5) — 창이 닫힐 때마다 INFO 한 줄. 사용자별 값이라 메트릭이 아니라 로그로만 남긴다.
+
+```bash
+flyctl logs -a mirboard | grep "OneCard race resolved"
+# OneCard race resolved: room=… raceId=… outcome=CAUGHT via=PRESS ownerSeat=0 ownerUser=… ownerBot=true
+#   bySeat=2 byUser=… byBot=false latencyMs=420 windowMs=3000 lateMs=-
+```
+
+`outcome` 은 CALLED·CAUGHT·EXPIRED·CANCELLED, `via` 는 PRESS(사람 누름)·TIMER(봇 누름·창 만료)·DESERTION(창 중 탈주).
+`latencyMs` 는 창을 연 뒤 처리까지(누름이면 반응 + 왕복 시간 — 창이 열리자마자의 누름이 반복되는 계정은 자동화를
+의심한다), `lateMs` 는 타이머 경로가 정해 둔 마감보다 늦게 처리된 시간(단일 폴러 지연)이다. **집계는 `room`+`raceId` 로
+묶어 마지막 줄을 정본으로 센다** — 줄은 저장 전에 찍히므로, 저장이 실패한 뒤 같은 창이 다른 경로(타이머·킥이 다시 건
+타이머)로 닫히면 같은 창이 두 번(다른 결과로) 찍힐 수 있다.
+
+---
+
 ## CD (GitHub Actions)
 
 `.github/workflows/deploy.yml` — `main` 푸시 + 수동 실행(`workflow_dispatch`).
````

`.env.example`:

```diff
 # (prod 전용) 레이트리밋 IP 를 읽을 신뢰 헤더 MIRBOARD_CLIENT_IP_HEADER — 기본 Fly-Client-IP,
 # application-prod.yml 에서만 읽는다. 로컬은 remoteAddr.
 
+# ── 원카드 (D-128, 런북 docs/deploy.md "원카드 공개 상태") ────────────────
+# 카탈로그 공개 상태: AVAILABLE | COMING_SOON | DISABLED. 쓰지 않으면 이 줄을 빼 둔다(코드 기본값) —
+# 빈 값·오타는 앱 전체 기동 실패다. 운영의 되돌리기는 COMING_SOON 으로만(진행 중인 판은 끝까지 간다).
+# DISABLED 는 진행 중인 원카드 방을 멈추므로 그런 방이 0 일 때만 — 경과 시간으로는 보장되지 않아 Redis 로 확인한다(런북).
+# MIRBOARD_ONECARD_STATUS=COMING_SOON
+# "원카드!/잡기!" 경쟁 창 길이와 봇 반응 시간 구간(ms, 룰 §9). 잘못된 조합(최소 > 최대, 창 <= 0)은 기동 실패.
+MIRBOARD_ONECARD_RACE_WINDOW_MILLIS=3000
+MIRBOARD_ONECARD_BOT_REACTION_OWNER_MIN_MILLIS=1000
+MIRBOARD_ONECARD_BOT_REACTION_OWNER_MAX_MILLIS=2500
+MIRBOARD_ONECARD_BOT_REACTION_CATCHER_MIN_MILLIS=1000
+MIRBOARD_ONECARD_BOT_REACTION_CATCHER_MAX_MILLIS=2500
+
 # ── Sentry 오류 리포팅 (D-107) ──────────────────────────────────────────
 # 비우면 완전히 비활성(로컬·CI 기본). 배포 환경에서만 DSN 을 준다.
 # 공식 스타터가 Boot 4 비호환이라 sentry-logback 어펜더를 코드로 붙인다.
```

- [ ] **Step 2: 프로토콜·API·포트 계약**

`docs/stomp-protocol.md`:

```diff
   `DesertionGraceScheduler` 가 `mirboard.desertion.grace-seconds`(기본
   **120s**, D-79) 후 재접속 없으면 탈주 확정(상대팀 승리, `desert_count`+1·
   lose+1·ELO−). **FINISHED**: no-op.
+- **S5 — 진행 킥.** 게임 토픽 그 자체(`/topic/room/{id}` — `/meta`·`/chat`·`/reaction` 은 아님)를 구독할 때와
+  REST resync 응답 뒤에, 그 방의 **참가자·관전자**이면 서버가 그 방의 멈춘 진행을 다시 건다: 봇 차례인데 그
+  인스턴스에 봇 루프가 없으면 루프를, 상태가 선언한 엔진 타이머가 큐에 없으면 그 타이머를(남은 시간 그대로 — 큐에
+  **없을 때만** 더하는 ZADD NX 라 걸린 무장을 덮지 않는다). 턴 데드라인은 건드리지 않는다 — resync 를 반복해도 시간
+  초과가 밀리지 않는다. 재기동(배포·자동 정지) 뒤에도 판이 멈춘 채 남지 않게 하려는 것이다.
 
 ## 토픽 / 큐 카탈로그
 
```

```diff
 | TOPIC | `/topic/room/{roomId}/meta` | 서버→방 | 대기실 메타 (`ROOM_META_UPDATED`, `ROOM_DESTROYED`) — Phase 13C, RoomPage 폴링 대체 |
 | TOPIC | `/topic/room/{roomId}/chat` | 서버→방 | 인-게임 채팅 (`CHAT`) — 참가자+관전자 |
 | TOPIC | `/topic/room/{roomId}/reaction` | 서버→방 | 이모지 반응 (`REACTION`) |
-| QUEUE | `/user/queue/room/{roomId}` | 서버→본인 | `HAND_DEALT`, `CARDS_RECEIVED`, `ERROR` |
+| QUEUE | `/user/queue/room/{roomId}` | 서버→본인 | `HAND_DEALT`, `CARDS_RECEIVED`(티츄), `HAND_UPDATED`(원카드), `ERROR` |
 | APP   | `/app/lobby/chat` | 클라→서버 | `{ message }` (≤500자) |
 | APP   | `/app/room/{roomId}/chat` | 클라→서버 | `{ message }` (≤500자, 참가자·관전자만) |
 | APP   | `/app/room/{roomId}/reaction` | 클라→서버 | `{ emoji }` (서버 화이트리스트 8종) |
```

```diff
   직접 반영하고, 라이프사이클 이벤트(DEALING_PHASE_STARTED, PASSING_STARTED,
   CARDS_PASSED, PLAYING_STARTED, ROUND_STARTED) 또는 seq gap
   (`seq > lastSeq + 1`) 에서만 REST `/resync` 로 권위 스냅샷을 재취득한다.
-  초기 mount 및 STOMP onConnect 직후 `/resync` 는 유지.
+  초기 mount 및 STOMP onConnect 직후 `/resync` 는 유지 — onConnect 에서는 **구독을 모두 보낸 뒤에**
+  resync 한다(S5 — 먼저 보내면 서버가 스냅샷을 읽은 뒤·구독을 등록하기 전에 낸 이벤트가 스냅샷에도 프레임에도 없다).
+  이 순서는 틈을 **좁힐 뿐 보장은 아니다** — SUBSCRIBE 등록은 서버에서 비동기이고 단순 브로커는 SUBSCRIBE 영수증을
+  주지 않는다. 남은 틈은 다음 이벤트의 구멍 판정·탭 복귀 resync·(원카드) 해소 없는 창 resync 가 메운다.
   **순번 판정은 훅만 한다(D-124)** — 기준점은 resync 의 `eventSeq`, 판정은
   `client/src/ws/seqGate.ts`. 게임 스토어는 판정이 끝난 이벤트만 받아 `applied`/`unhandled`/
   `ignored` 만 돌려준다.
```

```diff
   상태·순번·뷰를 읽는다. 액션은 같은 락 안에서 저장→브로드캐스트(순번 발급)를 끝내므로,
   스냅샷에 반영된 이벤트는 정확히 `seq <= eventSeq` 다 — 그 뒤 이벤트를 두 번 적용하거나
   놓치지 않는다. 락을 약 3초 안에 못 잡으면 예전처럼 잠금 없이 읽는다.
+- **S5 — 기준점보다 낡은 resync 응답은 클라가 버린다.** 서버가 같은 시점으로 읽어도 REST 응답과 STOMP 프레임의
+  **도착** 순서는 정해져 있지 않다 — 그 뒤 프레임이 먼저 닿아 반영됐는데 응답을 적용하면 공개 상태와 기준점이
+  되돌아간다(사람 차례면 다음 이벤트가 오지 않아 판이 멈춘다). 그래서 클라(`useStompRoom`)는 응답의 `eventSeq` 가
+  지금 기준점보다 **작으면** 버리고, 같거나 크면 적용한다. 서버 순번은 방이 살아 있는 동안 줄지 않는다(`RoomSeq`).
+  **방이 바뀐 뒤 도착한 이전 방의 응답도 버린다** — 클라는 방 전환(reset)마다 세대를 올리고, 요청 때 잡은 세대와 다른
+  응답은 적용하지 않는다(안 그러면 이전 방 응답이 새 방의 기준점을 올려 새 방 스냅샷을 '낡음'으로 버린다).
 
 ---
 
 ## 서버 → 클라 (공개) — `/topic/room/{roomId}`
 
-좌석 식별은 전부 **seat(0~3, playerIds 인덱스)** 기준이다. userId 가 필요한 화면은
-`Room.playerIds` 로 매핑한다.
+좌석 식별은 전부 **seat(`playerIds` 인덱스, 0 ~ 정원−1)** 기준이다 — 티츄 0~3, 스컬킹·원카드는 방 인원만큼
+(아래 게임별 절). userId 가 필요한 화면은 `Room.playerIds` 로 매핑한다.
 
 **게임 이벤트 (seq 있음)** — 서버 `TichuEvent.envelopeType()` 과 1:1:
 
```

```diff
   누름도 인정한다(D-128). 락 경합으로 `BUSY` 를 받으면 창이 열려 있는 동안 짧게 재시도한다.
 - 봇은 창을 열 때 추첨한 반응 시간에 누르고, 아무도 안 누르면 창 끝에 닫힌다 — 둘 다 **엔진 타이머**(D-128,
   `docs/game-port.md` §2)가 서버에서 처리하므로 클라가 보낼 것은 없다.
+- **S5 — 해소 이벤트가 끝내 안 오는 창.** 서버 타이머가 사라졌거나(무장·발화 실패) 해소를 저장한 뒤 방송이 실패하면
+  `RACE_RESOLVED` 가 오지 않는다. 클라는 창이 마감 + 1.5초가 지나도 열려 있거나, 그 창을 기다리던 누름이 창이 열린
+  채 `NO_RACE` 로 거절되면 **창마다 한 번** resync 한다 — 서버 resync 는 진행 킥으로 사라진 타이머를 다시 건다.
+  resync 스냅샷을 받으면 기다리던 누름 표식은 같은 창이라도 비운다(끊긴 사이 보낸 누름은 버려졌을 수 있다). 그 뒤
+  늦게 온 그 누름의 `BUSY` 는 창이 열려 있으면 조용히 삼킨다(창 동안 내기·먹기는 막혀 있어 그 `BUSY` 는 누름의 것이다).
+- **S5 — 경쟁 결과 로그.** 창이 닫힐 때마다 서버가 INFO 한 줄(`OneCard race resolved: … outcome via owner… by…
+  latencyMs windowMs lateMs`)을 남긴다. 사용자별 값이라 메트릭이 아니라 로그로만 둔다. 줄은 저장 전에 찍혀 저장 실패 뒤
+  같은 창이 두 번 찍힐 수 있으므로 `room`+`raceId` 의 마지막 줄이 정본이다(`docs/deploy.md`).
 
 **resync**: `tableView` = `{ phase, seats: [{seat, handCount, eliminated}], topCard, declaredSuit, attackStack,
 direction, turnSeat, drawPileCount, race, result }` — `phase` 는 `PLAYING`·`RACE`·`ENDED`, `seats[].eliminated` 는
```

`docs/api.md`:

```diff
       "minPlayers": 4,
       "maxPlayers": 4,
       "status": "AVAILABLE",
-      "supportedRoomOptions": ["TARGET_SCORE", "TEAMS", "BETTING"]
+      "supportedRoomOptions": ["TARGET_SCORE", "TEAMS", "BETTING"],
+      "defaultPlayers": 4,
+      "defaultTurnSeconds": 0
     },
     {
       "id": "SKULL_KING",
```

```diff
       "minPlayers": 2,
       "maxPlayers": 8,
       "status": "AVAILABLE",
-      "supportedRoomOptions": []
+      "supportedRoomOptions": [],
+      "defaultPlayers": 8,
+      "defaultTurnSeconds": 0
+    },
+    {
+      "id": "ONE_CARD",
+      "displayName": "원카드",
+      "shortDescription": "2~6인 손패 털기. 공격을 쌓아 넘기고, 한 장 남으면 누구보다 먼저 \"원카드!\"를 외친다.",
+      "minPlayers": 2,
+      "maxPlayers": 6,
+      "status": "COMING_SOON",
+      "supportedRoomOptions": [],
+      "defaultPlayers": 4,
+      "defaultTurnSeconds": 30
     }
   ]
 }
```

```diff
   것이므로, 새 게임은 아무것도 안 써도 무관한 설정이 화면에 뜨지 않는다.
   모든 게임에 통하는 설정(방 이름·`capacity`·`turnSeconds`·`fillWithBots`)은 여기
   들어가지 않는다.
+- `defaultPlayers`·`defaultTurnSeconds`(S5): 방 만들기 모달의 **처음 선택**(사용자는 바꿀 수 있다). 게임이
+  선언하며(`GameDefinition.defaultPlayers()`·`defaultTurnSeconds()`) 기본은 `maxPlayers`·`0`(끔) — 티츄·스컬킹은
+  지금까지와 같다. 원카드만 4명·30초다. 서버의 `capacity`·`turnSeconds` 생략 기본(`maxPlayers`·0, 아래 방 만들기)과는
+  별개다 — 인원 가변 게임은 클라가 늘 `capacity` 를 보낸다.
 
 ### GET `/api/games/{gameId}`
 단일 게임 상세. 응답은 위 항목 형식과 동일하되 룰 요약 등 추가 필드가 들어갈 수 있다
```

```diff
 - `eventSeq`: 이 스냅샷에 반영된 마지막 공개 이벤트의 순번. D-126 부터 서버는 방 액션 락
   안에서 상태·`eventSeq`·뷰를 함께 읽으므로, 클라는 `seq <= eventSeq` 이벤트를 버리고 그
   다음부터 이어 붙이면 된다(락을 약 3초 안에 못 잡으면 예전처럼 잠금 없이 읽는다).
+  **S5 — 응답이 그 뒤 프레임보다 늦게 닿으면 클라가 버린다**: `eventSeq` 가 클라 기준점(이미 반영한 마지막
+  순번)보다 작으면 적용하지 않는다(같거나 크면 적용). 서버 순번은 방이 살아 있는 동안 줄지 않는다. 방이 바뀐 뒤 도착한
+  이전 방의 응답도 버린다(클라가 방 전환마다 올리는 세대 — `docs/stomp-protocol.md`).
+- **S5 — 진행 킥.** 응답을 만든 뒤(락 해제 뒤, 비동기라 응답을 늦추지 않는다) 서버는 그 방의 멈춘 진행을 다시
+  건다 — 봇 차례인데 봇 루프가 없으면 루프를, 상태가 선언한 엔진 타이머가 큐에 없으면 그 타이머를(없을 때만 더한다 —
+  ZADD NX). 턴 데드라인은 건드리지 않는다(resync 를 반복해도 시간 초과가 밀리지 않는다). 게임 토픽 구독 때도 같고, 둘 다
+  그 방의 참가자·관전자일 때만이다(`docs/stomp-protocol.md`).
 - `disconnectedSeats`: 현재 끊긴 플레이어 좌석(재접속 배지 즉시 반영, D-75).
 - `chips`: D-82 방 단위 테이블 칩(userId→칩). 내기 없는 방은 빈 맵.
 - `completedRounds`: D-108 **끝난 라운드들의** 점수(순서 = 라운드 1..N). 바로 위
```

````diff
 - 방이 `FINISHED` 여도 방 해시가 살아 있는 동안(`room_finish.lua` 가 TTL 을 600s 로 줄인다)
   마지막 상태를 돌려준다. 종료 전이 직후 스컬킹 게임판을 유지하는 클라(D-120)가 이 구간에
   resync 한다.
+**원카드(`gameType=ONE_CARD`)의 `tableView`** — 서버 `OneCardStateMapper.TableView`, 클라 `types/onecard.ts` 미러와
+1:1(실제 MVC 직렬화로 대조, S5 프로토콜 리뷰). 아래는 4인 방에서 좌석 3 이 탈주했고 좌석 0 이 1장이 되어 경쟁 창이
+열린 순간이다.
+```json
+{
+  "phase": "RACE",
+  "seats": [
+    { "seat": 0, "handCount": 1, "eliminated": null },
+    { "seat": 1, "handCount": 5, "eliminated": null },
+    { "seat": 2, "handCount": 7, "eliminated": null },
+    { "seat": 3, "handCount": 0, "eliminated": "DESERTED" }
+  ],
+  "topCard": { "suit": "HEART", "rank": 9, "joker": null },
+  "declaredSuit": null,
+  "attackStack": 0,
+  "direction": 1,
+  "turnSeat": -1,
+  "drawPileCount": 23,
+  "race": { "raceId": 41, "ownerSeat": 0, "slot": 5, "jitterX": -32, "jitterY": 18,
+            "windowMillis": 3000, "remainingMillis": 1840 },
+  "result": null
+}
+```
+- `phase`: `PLAYING`·`RACE`·`ENDED`. 경쟁 창이 열렸거나 끝났으면 `turnSeat` 은 −1.
+- `seats[].eliminated`: 살아 있으면 `null`, 탈락했으면 사유 `"BANKRUPT"`·`"DESERTED"`(boolean 아님).
+- `race.remainingMillis`: 창 끝까지 남은 시간 — 봇이 누를 시각은 어디에도 싣지 않는다. 창이 없으면 `race: null`.
+- `result`: 끝났으면 `MATCH_ENDED` payload 와 같은 `{ reason, standings: [{seat, rank, cardsLeft, status}] }`.
+- `privateHand` = `{ "seat": 0, "hand": [{ "suit": "CLUB", "rank": 3, "joker": null }], "handVersion": 41 }` —
+  손패 전체와 상태 버전. 클라는 가진 것보다 낮은 `handVersion` 의 손패를 버린다(비공개 `HAND_UPDATED` 와 같은 축).
+  `chips` 는 늘 `{}`(내기 미지원).
+
 - **`privateHand` 는 요청자가 실제로 앉은 좌석에만**(D-122 심층 방어, 모든 게임 공통). 좌석은
   `playerIds` 의 인덱스인데, 게임이 시작된 뒤 목록이 정원(`capacity`)보다 줄었다면 인덱스가
   당겨져 남의 좌석을 가리킬 수 있으므로 `privateHand: null`(관전자 뷰)을 준다. 목록에 없는
````

`docs/game-port.md`:

````diff
   리매치를 지원할 때(리매치 대기, IN_GAME 유지).
 - `RoomOption` 이 아니라 메서드인 이유: 방 생성 때 사용자가 고르는 설정이 아니라 게임 구조의
   성질이다. UI 게이팅도 없다(리매치 버튼은 지원 게임의 게임판에만 있다).
+
+### 방 만들기의 처음 선택도 게임이 선언한다 (S5)
+
+인원·턴 제한은 모든 게임에 통하는 설정이라 `RoomOption` 이 아니다(위). 그런데 **처음에 무엇을 골라 둘지**는 게임마다
+다르다 — 원카드는 2~6인인데 모달이 최대(6)를 골라 두어 친구 넷이 기본값으로 만든 방이 시작하지 않았고, 턴 제한 '끔'이면
+자리를 비운 한 명이 판을 무기한 멈췄다.
+
+```java
+default int defaultPlayers()     { return maxPlayers(); }
+default int defaultTurnSeconds() { return 0; }
+```
+
+- **기본이 지금까지의 동작**(최대 인원·끔)이라 티츄·스컬킹은 한 줄도 바꾸지 않았다. 원카드만 4·30.
+- 카탈로그(`GET /api/games`)에 실려 모달의 **처음 선택**으로만 쓰인다 — 사용자는 바꿀 수 있고, 서버는 강제하지 않는다.
+  서버의 `capacity` 생략 기본(`RoomService` — maxPlayers)은 따로다(인원 가변 게임은 클라가 늘 `capacity` 를 보낸다).
 
 ### 시간이 지나면 일어나는 전이도 게임이 선언한다 (D-128)
 
````

```diff
   전이를 그 계층에 두면 클라가 위조해 보낼 수 있다.
 - 시간 전이가 진행 중인 동안 게임은 `pendingSeats` 를 비워 두면 된다(원카드 경쟁 창) — 봇 루프와 턴
   타이머가 끼어들지 않고, 시간 진행은 엔진 타이머 하나가 맡는다.
+- **사라진 타이머는 진행 킥이 다시 건다 (S5).** 그래서 이 타이머가 무장 실패·발화 중 실패·저장~무장 사이 종료로
+  사라지면 창을 닫을 주체가 없었다(봇 루프·턴 타이머는 창 동안 아무것도 안 한다). 클라가 방을 다시 볼 때(resync 응답
+  뒤·게임 토픽 구독 — 그 방의 참가자·관전자일 때, `infra.bot.GameProgressKick`) 인프라가 `timer(state)` 를 물어 이 세대의
+  `deadlines:game` 항목을 남은 시간으로 건다 — 그 member 가 **없을 때만 더한다**(`DeadlineQueue.scheduleIfAbsent`, ZADD NX).
+  확인과 쓰기가 원자라 그 사이 걸린 정상 무장(재시도·미만기 재무장)을 락 없이 읽은 낡은 남은 시간으로 덮지 않는다. 같은
+  킥이 봇 차례인데 이 인스턴스에 봇 루프가 없으면(재기동) 루프도 다시 건다. 게임에 새로 요구하는 것은 없다 — 위 계약(남은
+  시간·락 안 재확인)이 그대로 지킨다. 킥은 `onTurnAdvanced` 를 부르지 않는다(세대를 올리면 resync 반복으로 턴 데드라인을
+  미룰 수 있다).
 
 ## 3. 인원 가변 (스컬킹 2~8 결정의 파급) — **구현 완료 (D-99 / S2)**
 
```

- [ ] **Step 3: 설계 §8 과 §7 표시**

`docs/plans/onecard.md`:

```diff
     엔진 타이머(봇 누름 1.0~2.5초·창 만료 3초)가 밀린다. 팝한 항목의 `handle` 을 가상 스레드로 넘긴다(게임 중립).
   - 발화 중 `saveState` 뒤 `advance`/`broadcast` 가 던지면 창은 닫혔는데 봇·타이머 재무장이 없다(턴 타임아웃과 같은 기존
     패턴). 실패 가시성도 같이: `fire` 실패는 ERROR 로그만 남고 타이머는 이미 사라지며 무장 실패는 WARN 이다 — 한정 횟수
-    재무장·ERROR 승격을 S4 전 하드닝으로. *(D-129: 열림 전환 전으로 읽는다.)*
+    재무장·ERROR 승격을 S4 전 하드닝으로. *(D-129: 열림 전환 전으로 읽는다.)* *(S5: 한정 재무장 대신 진행 킥이 사라진
+    타이머를 다시 걸고, 무장 실패는 ERROR 로 올렸다 — §8.)*
   - 테스트: `fire` 예외 경로(`onTimer`/`saveState` 가 던질 때 락 해제·후속 호출 없음), `onTurnAdvanced` 의 방 없음 → `cleanup`
     분기, 인터페이스의 실제 기본 메서드(`timer`/`onTimer` → empty) 실행.
 - **운영**
   - `MIRBOARD_ONECARD_STATUS` 를 `docs/deploy.md`·`.env.example` 에 적는다. **S4 전에는 AVAILABLE 로 켜지 않는다** — 현 클라는
     ONE_CARD 방을 기본 분기(티츄 게임판)로 그린다. S4 에서 코드 기본값 전환을 클라와 같은 배포로 한다. *(D-129: 클라는 S4 로
-    준비됐고, 전환은 D-116 병합 뒤 별건으로 뺐다.)*
+    준비됐고, 전환은 D-116 병합 뒤 별건으로 뺐다.)* *(S5: 런북을 적었다 — 되돌리기는 COMING_SOON 으로만, §8.)*
   - D-116·D-126 이 이 브랜치와 같은 문서(CLAUDE.md·README·케이스 스터디·decisions·status·roadmap·redis-keys)와 테스트 수를
     건드린다 — 병합 뒤 인용 수치를 케이스 스터디 §부록 명령으로 다시 잰다. 또 운영은 `MIRBOARD_MESSAGING_GATEWAY=redis` 라 D-116
     전에는 머신이 2대 이상일 때 `GameStartingEvent` 가 재발행돼 라운드 시작이 인스턴스마다 반복된다(티츄·스컬킹과 같은 기존
```

```diff
 S4 브라우저 실측(데스크톱·모바일, 라이트·다크)에서 본 것 중 이번에 고치지 않은 것이다.
 
 - **경쟁 창마다 봇 루프 WARN** — 창이 열리면 `pendingSeats` 가 비어 봇 루프가 `Bot loop: no pending bot action`(WARN)을 남긴다.
-  운영 로그 소음이라 그 경우는 DEBUG 로 낮춘다(인프라, 게임 중립 — 차례 없는 상태는 정상일 수 있다).
+  운영 로그 소음이라 그 경우는 DEBUG 로 낮춘다(인프라, 게임 중립 — 차례 없는 상태는 정상일 수 있다). *(S5: DEBUG 로 낮췄다.)*
 - **카드 내기 연타** — `카드 내기` 를 빠르게 두 번 누르면 두 번째가 서버에서 거절된다(`NOT_YOUR_TURN` 등, 스컬킹도 같다). 보낸
   뒤 다음 상태가 올 때까지 버튼을 잠그는 게임판 공용 처리를 검토한다.
 - **번들 크기** — 메인 번들이 566kB(원카드 +27kB, 500kB 경고는 그 전부터)다. 게임판을 `RoomPage` 에서 지연 로딩(`lazy`)해
```

```diff
 - **케이스 스터디 재현 명령**(T8-M7) — `docs/case-study-multi-game.md` 의 "1238건 중 1039건(84%)" 재현 명령이 §부록에 없다(기존 공백). 서버 집계를 (6)으로
   싣고 `check.sh server` 는 Gradle 캐시로 `FROM-CACHE` 에 끝날 수 있으니 `--rerun` 을 적는다.
 - **턴 카운트다운**(N-4) — `turnStartedAt` 은 쓰기만 하고 읽지 않는다. 원카드 게임판엔 카운트다운이 없는데, 턴 제한(`turnSeconds`)이 있는 방은 시간
-  초과가 자동 먹기라 남은 시간 표시가 필요할 수 있다. 붙이거나 필드를 지운다.
+  초과가 자동 먹기라 남은 시간 표시가 필요할 수 있다. 붙이거나 필드를 지운다. *(S5: 원카드 방 만들기의 턴 제한 처음 선택이 30초가 되어 기본 방마다 안 보이는 시간 초과가 생겼다 — 공개 직후 우선, §8.)*
 - **원카드 순번 스트림 IT**(N-5) — "내거나 먹어도 resync 없음"은 `sequenced()` 단위 테스트와 공용 브로드캐스터(D-126 테스트)에만 기댄다.
   `TichuEventStreamIT` 의 원카드판(봇 매치에서 공개 seq 연속·비공개 seq 부재)을 S5 에서(선택).
 - **같은 세션 프레임 순서**(N-6) — `WebSocketConfig` 가 `preservePublishOrder` 를 켜지 않아 같은 세션의 프레임 순서가 보장되지 않는다(실측 안 함). 클라는
   해소 → 거절 / 거절 → 해소 두 순서를 모두 받게 했다(`lost` 표식). 인프라·게임 중립 후속: `setPreservePublishOrder(true)` 를 성능 영향 측정과 함께 검토.
 - **스컬킹 `.sk-play` hover** — `18-skullking-table.css` 의 `.sk-play` 도 전역 `button:hover:not(:disabled)` 에 배경이 덮인다(원카드 I-2 와 같은 문제,
   이 브랜치 범위 밖).
+
+## 8. S5 — 공개 전 보강 (2026-10-06)
+
+4렌즈 통합 리뷰(프로토콜·동시성·보안·운영/UX)에서 공개를 막는 Critical 은 없었다. 아래는 열림 전환 전에 넣은 보강이다
+(결정 번호는 `docs/decisions.md` 에 반영할 때 붙인다). 근거 표기는 리뷰 항목 번호다 — 스파이크 뒤 계획 전 설계 리뷰
+(`s5-prereview`)의 항목은 `PR-` 를 붙였다.
+
+### 결정 요약 (사용자, 2026-10-06)
+
+- 보강 범위 = 리뷰 표 전부 — 진행 재개 장치, 클라 연결 훅 2건, 경쟁 결과 로그, 장애 격리, 운영 문서, 소소한 UX·문서.
+  Redis 메시지 순서 보정(C-M1)은 하지 않는다(후속).
+- 버티기 대응 = 원카드 방 만들기의 턴 제한 처음 선택 **30초**. 자동화 대응 = **로그로 탐지만**(누름 하한 없음).
+  원카드 방 만들기의 인원 처음 선택 = **4명**.
+- **30초의 대가**(결정 문안에 한 줄로): 게임판에 턴 카운트다운이 없어(N-4) 기본 방마다 **안 보이는 시간 초과**가 생긴다.
+  시간 초과는 먹기라 공격 누적 중이면 그 장수를 통째로(파산 20장까지) 먹고, 끊긴 사람은 유예 120초 동안 30초마다 자동으로
+  먹는다(지금은 "턴 30초" 배지뿐). 카운트다운은 공개 직후 우선(아래 후속).
+
+### 진행
+
+| # | 내용 | 근거 |
+| --- | --- | --- |
+| 1 | 데드라인 폴러가 `Error` 한 번에 조용히 멈추지 않는다(`Throwable` → ERROR, 이미 pop 한 나머지 항목도 처리) | C-M3 |
+| 2 | 진행 킥 `GameProgressKick` — resync 응답 뒤(락 해제 뒤·비동기)와 게임 토픽 구독 때(참가자·관전자만), 봇 차례인데 이 인스턴스에 루프가 없으면 루프를, 이 세대의 엔진 타이머 항목이 큐에 없으면 그 타이머를 건다(ZADD NX — 걸린 무장을 덮지 않음). `onTurnAdvanced` 는 부르지 않는다. 봇 루프 "no pending" WARN→DEBUG, 엔진 타이머 무장 실패 WARN→ERROR, 킥·루프 시작의 `Error` 처리. 끝-끝 시나리오 3건(무장 유실·발화 실패·재기동) | C-I1·C-I2·PR-M-1~4 |
+| 3 | 경쟁 결과 로그(창이 닫히는 세 경로마다 INFO 한 줄 — 사용자별 값은 로그로만, `room`+`raceId` 마지막 줄이 정본)와 매치 기록 실패 격리(ERROR 후 진행 계속 — 마지막 `CARD_PLAYED`·`MATCH_ENDED` 가 나간다) | O-I1·S-I2·C-M2·P-F7·PR-M-5 |
+| 4 | `GameDefinition.defaultPlayers()`·`defaultTurnSeconds()`(기본 최대 인원·끔) — 원카드만 4·30, 카탈로그에 싣고 방 만들기 모달의 처음 선택으로. 게임을 바꾸면 인원·턴 제한 둘 다 그 게임의 처음 선택으로 되돌아간다(예전엔 턴 제한이 남았다 — 티츄에서 고른 60초가 스컬킹으로 바꾸면 '끔'). 선언값이 모달이 고를 수 있는 값인지 불변식 | P-F4·O-Minor·사용자 결정·PR-M-11 |
+| 5 | 방 STOMP 훅 — 기준점보다 낡은 resync 응답 버림(방 전환 세대 표식으로 이전 방 응답도), 접속 때 구독을 보낸 뒤 resync(틈을 좁힐 뿐 보장 아님), `onWebSocketClose` 로 끊김 표시(로비 훅도), 게임판용 `requestResync` | P-F1·P-F2·PR-I-1·PR-M-8 |
+| 6 | 원카드 클라 — resync 스냅샷이 오면 기다리던 누름을 비움(그 뒤 늦게 온 그 누름의 `BUSY` 는 창이 열려 있으면 삼킴), 해소 이벤트가 끝내 안 오는 창(마감 + 1.5초 초과·창이 열린 채 그 창의 누름이 `NO_RACE`)은 창마다 한 번 resync | P-F2·C-I2·PR-M-6·PR-M-7 |
+| 7 | 공용 인프라 거절 문구(`ws/errorLabels` — 원카드 sink 적용), 허브 방 목록의 게임 표시 이름 | P-F3·P-F6·M-3·M-5 |
+| 8 | 문서 — `deploy.md` 원카드 공개 상태 런북·경쟁 튜닝·결과 로그, `.env.example`, `stomp-protocol.md`, `api.md`, `game-port.md`, 이 절 | O-I2·P-F5·P-F6 |
+
+### 설계에서 판단한 것
+
+- **킥은 살아 있는 봇 루프 위에 겹쳐 걸지 않는다.** 킥은 resync·구독마다 불리므로(게임 시작·탭 복귀마다 2~3번) 그대로
+  `scheduleBots` 하면 두 루프가 번갈아 락을 잡아 봇이 지연(700ms) 없이 연달아 둔다. `BotScheduler` 가 방마다 살아 있는
+  루프 토막 수를 세고(락 경합 재시도 토막 포함), 킥은 `scheduleBotsIfIdle` 로만 건다. 수는 인스턴스 메모리라 다른 인스턴스의
+  루프는 못 본다 — 그때 겹쳐도 락 안 재조회로 같은 수를 두 번 두지는 않고 속도만 빨라진다.
+- **킥의 엔진 타이머는 "이 세대의 `deadlines:game` 항목이 큐에 없을 때만" 더한다 — ZADD NX(`scheduleIfAbsent`).** 확인과
+  쓰기를 따로 하면(처음 스파이크의 ZSCORE → ZADD) 그 사이 걸린 정상 무장을 킥이 락 없이 읽은 낡은 남은 시간으로 덮을 수
+  있었다(PR-M-2). 만기인데 항목이 남아 있으면 폴러가 다음 주기에 꺼낸다. 더할 때는 상태가 답한 남은 시간(이미 지났으면 0)을
+  쓰므로 창을 늘리지도 앞당기지도 않고, 낡은 킥은 발화 쪽의 세대·락·`timer` 재확인이 거른다. "더했다"는 유실만이 아니라
+  pop 뒤 처리 중(수십 ms)이기도 해서 로그는 INFO 다.
+- **킥은 참가자·관전자의 것만 받는다**(PR-M-4). 결과 무결성만 보면 누구의 킥이든 무해하지만, 공개 토픽 구독은 로그인한
+  누구나 할 수 있고 SUBSCRIBE 는 레이트리밋 밖이라 구독 폭주가 킥마다 Redis 왕복 몇 번씩으로 커진다(머신·Redis 1대).
+- **훅의 낡은 응답 버림은 방 전환을 안다**(PR-I-1). "기준점보다 낡으면 버림"만 두면 방이 바뀌는 사이 이전 방의 늦은 응답이
+  새 방의 기준점을 올려 새 방의 스냅샷·이벤트를 전부 버렸다 — reset 마다 오르는 세대를 요청 때 잡아 다르면 버린다.
+- 클라의 `NO_RACE` resync 는 "창이 열린 채 **그 창을 기다리던** 누름이 거절됨"일 때만 — 지난 창의 누름에 늦게 온 `NO_RACE` 로
+  새 창을 의심하지 않게. 해소 → 거절의 보통 순서에서는 창이 이미 닫혀 있어 resync 가 없다.
+- 클라 `GameSummary` 의 `defaultPlayers`·`defaultTurnSeconds` 는 선택 필드(없으면 maxPlayers·0) — 서버는 늘 싣는다.
+- 수동 확인 한 줄(QA 문서는 D-116 뒤에 옮긴다): 방 만들기에서 티츄 → 턴 제한 60초 → 원카드로 바꾸면 인원 4·턴 30초, 다시
+  스컬킹으로 바꾸면 인원 8·턴 '끔'으로 보인다(Radix Select 조작이라 자동 테스트 밖).
+
+### 남은 후속
+
+- **Redis 게이트웨이 메시지 순서**(C-M1) — `RedisMessageGateway` 에 단일(또는 채널별 직렬) 실행기 + `preservePublishOrder`(N-6 과 함께).
+- **강제 종료 기록 정책**(S-I3 C) — abort 를 "무효 + 이미 일어난 탈주는 기록"으로, 또는 끊긴 좌석이 있을 때만 허용
+  (`RoomService`·`GameAbortService` — D-116 뒤).
+- **사람 차례 장기 정지의 탈주화**(S-I3 A) — 연결된 채 N분 멈추면 탈주(턴 제한과 별개인 게임 중립 데드라인). 원카드는 턴 제한
+  30초 처음 선택으로 대부분 풀리지만 사용자가 '끔'을 고른 방은 그대로다.
+- **누름 하한**(S-I1 B) — 경쟁 결과 로그의 `latencyMs` 분포를 본 뒤 판단. 늦은 누름 인정의 상한(C-I2 선택)도 같은 데이터로.
+- **티츄·스컬킹 sink 의 공용 거절 문구 적용**, resync 실패(REST)·'나가기' 실패의 영문 서버 메시지(M-5 나머지).
+- **`RoomService` 의 생략 기본을 게임 선언과 정렬**(D-116 뒤) — `capacity` 생략은 maxPlayers(원카드 선언 4), `turnSeconds`
+  생략은 0(원카드 선언 30). 지금 클라는 둘 다 늘 보내므로 실제로 갈리지 않는다.
+- **턴 카운트다운**(N-4, §7) — 공개 직후 우선. 원카드 기본 30초가 되어 기본 방마다 안 보이는 시간 초과가 생겼다(위 "30초의
+  대가"). 남은 시간을 resync 에 실어야 재접속 직후에도 맞는다.
+- **구독 등록 뒤 스냅샷 보장**(PR-M-8) — "구독을 보낸 뒤 resync"는 틈을 좁힐 뿐이다(SUBSCRIBE 등록은 비동기·영수증 없음). 예:
+  서버가 게임 토픽 구독을 받으면 본인 큐로 스냅샷을 밀어 준다.
+- (참고) 킥은 라운드 끝(`isRoundOver`) 상태의 정지는 다루지 않는다 — 티츄는 `RoundEnd` 저장(컨트롤러)과 다음 라운드 시작 저장
+  사이에 프로세스가 죽으면 그대로 멈춘다(기존, ms 틈). 원카드는 1판 = 1라운드라 결과 화면으로 끝나 무해하다.
+- **Hikari `connection-timeout` 을 수 초로**, 매치 기록을 폴러 스레드 밖으로(C-M2 의 남은 절반 — DB 장애 때 폴러가 30초 멈춘다).
+- 다중 인스턴스에서 킥이 다른 인스턴스의 봇 루프를 못 보는 것 — 장기안은 봇 차례도 데드라인(D-96)으로 옮기는 것.
```

- [ ] **Step 4: 실측**

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  60 passed (60)` · `Tests  632 passed (632)`.

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --rerun`(약 4분 — `./scripts/check.sh server` 는 빌드 캐시로 `FROM-CACHE` 에 끝나 실측이 안 될 수 있다) → 집계:

```bash
python3 - <<'EOF'
import glob, re, xml.etree.ElementTree as ET
t = s = f = d = oc = ocu = 0
for p in glob.glob('server/build/test-results/test/*.xml'):
    r = ET.parse(p).getroot(); n = int(r.get('tests')); name = r.get('name').split('$')[0]  # @Nested 는 바깥 클래스로
    t += n; s += int(r.get('skipped')); f += int(r.get('failures')) + int(r.get('errors'))
    if not re.search(r'(IT|IntegrationTest)$', name): d += n
    if name.startswith('com.mirboard.domain.game.onecard'):
        oc += n
        if name.endswith('Test'): ocu += n
print(f"tests={t} skipped={s} failed={f} dockerfree={d} ({d/t:.0%}) onecardDomain={oc} onecardUnit={ocu}")
EOF
```

Expected: `tests=1283 skipped=5 failed=0 dockerfree=1083 (84%) onecardDomain=189 onecardUnit=185`. 기준은 main `27910d1` 의 1238건(Docker 불필요 1039건)이고
이 계획이 서버 테스트 45건(Docker 불필요 44건)을 더한다. main 이 그사이 바뀌었다면 바뀐 값에 증가분을 더해 S5 후반 문서 수치에 쓴다.

Run: `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra` 와 `git diff --name-only 27910d1..HEAD`
Expected: 첫 명령 출력 없음, 둘째 목록에 D-116 겹침 파일 없음.

- [ ] **Step 5: 커밋**

```bash
git add docs/deploy.md \
  .env.example \
  docs/stomp-protocol.md \
  docs/api.md \
  docs/game-port.md \
  docs/plans/onecard.md
git commit -m "docs(S5): 원카드 공개 상태 런북·경쟁 튜닝·결과 로그, 프로토콜·API·포트 계약, 설계 §8

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Phase Gate (컨트롤러)**

구현 에이전트가 아니라 컨트롤러가 한다. `git log --oneline main..HEAD` 로 태스크 커밋 8개(리뷰 수정 커밋이 있으면 그만큼 더)를 확인하고 사용자에게
보고한다 — 만든 것, 실측 수치, 다음 단계(S5 후반: D-116 병합 뒤 `decisions.md` D-130, 공개 전환 — 기본값 AVAILABLE·`application.yml`·`OneCardGameDefinition`
낡은 주석·`RoomService` 생략 기본 정렬, 문서 "게임 3종"·수치, 배포·운영 스모크). **사용자 승인 전에는 main 에 병합하지 않는다.** main 에 푸시하면 자동
배포되며 원카드는 계속 "준비 중"이다.
