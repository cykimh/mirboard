# 원카드 S5b 구현 계획 — 공개 전환 (D-130 기록 + D-131)

## 정오표 (최종 리뷰 반영, 2026-10-07)

아래 본문은 실행 기록이라 그대로 둔다. 최종 리뷰(서버·클라·릴리스 3렌즈 + 반박 검증)의 fix-now 28건(렌즈 간 중복을 묶으면 id 23개)을 반영한 뒤
바뀐 것과 새 수치만 여기 적는다. 근거·항목별 내역은 커밋 `8298708`·`77971e5`·`9bc2db9`·`5e76c6f` 와 이 정오표를 단 커밋.

- **재무장 실패 처리**(F-server-1·S5bT4-M4): 본문은 "컨트롤러·탈주 계속은 잡고, 두 스케줄러는 자기 catch 가 잡는다, 봇은 원래 락
  안"이라 했지만 봇 경로는 재무장 예외가 루프 catch 로 빠져 재귀가 끊겼다(D-131 이전부터). 이제 다섯 경로(액션·봇·턴 시간 초과·엔진
  타이머·탈주 계속) 모두 재무장만 따로 잡아 `Turn rearm after … failed` ERROR, 봇 스케줄·재귀는 그대로. 테스트 +3
  (`BotSchedulerTest`·`TurnRemainingTest$RearmInsideTheLock`·`EngineTimerSchedulerTest$Firing`).
- **카운트다운**(F-client-1·F-client-4·S5bT5-M1): resync 값을 턴 제한으로 자른다(+1 테스트), 숫자 2ch 고정폭, 위험 배지 규칙을
  `.oc-attack` 과 한 규칙으로. 스토어 RACE_OPENED 단독 테스트 +1(S5bT5-M2).
- **테스트 판별력**(수 불변): 원카드 시뮬레이션의 클라 턴 시계 모델 단언(F-client-5), 티츄 기록 실패 테스트의 리스너 호출 순서
  InOrder(S5bT2-M1), staleSocket 의 핸들러 동일성 사전조건·공용 코드 BUSY(S5bT7-M1·M2), resync IT 사용자 rs7_*(S5bT4-M3).
- **이름·주석·문서**: `RoomCapacityIntegrationTest` 의 `…_defaults_to_max_players` → `…_defaults_to_declared_players`(본문 Task 4
  코드 블록의 옛 이름은 실행 당시 그대로), 짧은 `createRoom` 오버로드 Javadoc, `GameEngine.onTimer` 계약 Javadoc(줄 수 184 유지),
  `MatchResultRecorder` 전파 전제(+2줄 → 티츄 main 6,007 → **6,009줄**), `game-port.md` 기록기 계약 3·`api.md` 만기 순간 null·
  `deploy.md` 공개 직후 확인·QA 시나리오 6·README/CLAUDE.md 포트 확장 범위·케이스 스터디 §2 앵커 시점·implementation-status 순서.
- **후속으로 기록만**(`docs/plans/onecard.md` §9 남은 후속): F-server-2(기록 지연은 락 안 — 운영 타임아웃·락 소유 확인), S5bT2-M3(칩 정산
  리스너 미격리), F-client-3("0초" 정지·자가 치유 없음), F-client-2(표시 마감 지연), F-client-6(이미 기록 — 근거 표시만).
- **새 수치**(본문 Step 5 의 Expected 대신): 클라 `Test Files  61 passed (61)` · `Tests  660 passed (660)`(658 + 2). 서버
  `tests=1327 skipped=5 failed=0 dockerfree=1121 (84%)`(1324 + 3, 모두 Docker 불필요). 게임 도메인 내역은 그대로(티츄 312/304 ·
  스컬킹 375/371 · 원카드 191/187), 엔진 타이머 단위 16 → **17**, `*RecorderFailureTest` 8·원카드 서버 경로 IT 10 그대로. 인프라 게임
  이름 grep 16·원카드 0 그대로. 코드 규모 core 515 · tichu **6009** · skullking 3766 · onecard 2612 · `GameEngine.java` 184.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원카드를 공개(AVAILABLE)한다. 공개 전에 사용자가 고른 보강을 같이 넣는다 — resync 응답의 **남은 턴 시간**(게임 중립)과 원카드 게임판
**턴 카운트다운**, 스컬킹·티츄 **매치 기록 실패 격리**(원카드는 S5 앞부분 D-130), 같은 방 **진행 킥 합치기**, 방 만들기 **생략 기본을 게임 선언과
정렬**, S5 앞부분 재리뷰 Minor(N-2~N-6) 정리, 결정 기록(D-130·D-131)과 "게임 3종" 문서·수치. 이 계획이 끝나 main 에 병합·배포되면 원카드
방을 만들 수 있다(운영에 `MIRBOARD_ONECARD_STATUS` 시크릿 없음 — 기본값이 그대로 적용).

**Architecture:** 서버는 게임 중립 인프라만 늘린다(인프라 게임 이름 grep 16줄 그대로, 원카드 0). 남은 턴 시간은 `deadlines:turn` 의 **지금 세대**
항목 점수 − 지금(`DeadlineQueue.remaining` → `TurnTimeoutScheduler.turnRemaining`)이고 resync 가 상태와 **같은 방 액션 락 안에서** 읽는다 — 그래서
락을 쥐는 진행 경로 4곳(액션·엔진 타이머 발화·턴 시간 초과·탈주 계속)의 재무장(`onTurnAdvanced`)을 락 안으로 옮긴다(실패는 잡아 ERROR, 봇
스케줄은 해제 뒤 그대로). 기록 격리는 리스너 수에 따라 위치가 다르다 — 티츄는 `TichuMatchCompleted` 리스너가 둘(기록·칩 정산)이라 기록기가
`TransactionTemplate` 으로 본문·커밋 예외를 스스로 삼키고, 스컬킹은 하나라 발행 지점에서 잡는다. 클라는 원카드 스토어(순수 리듀서)가 `turnClock`
을 resync 의 남은 시간과 서버가 재무장하는 공개 이벤트(`TURN_CHANGED`·차례가 남은 `PLAYER_ELIMINATED`)로 맞추고, 경쟁 창이 열린 동안·끝난 매치는 세지 않는다.

**Tech Stack:** Java 25 + Spring Boot 4 + JUnit 5 + AssertJ + Mockito(+ Testcontainers IT), React 18 + TypeScript + Zustand, Vitest + RTL.

**참고:** 설계 `docs/plans/onecard.md` §8(S5 결정·남은 후속), 포트 `docs/game-port.md`, REST `docs/api.md`, 운영 `docs/deploy.md`. 이 계획의 코드는 검증용
스파이크(4렌즈 사전 리뷰 + 반박 검증 + 반영)에서 옮겼고, 스파이크에서 태스크 N 까지만 적용한 트리가 N=1..8 모두 RED→GREEN·컴파일·클라 전체를 통과하는
것과 아래 각 단계의 실패·통과·테스트 수, 클라 658건·서버 1324건(실패 0)을 확인했다.

## Global Constraints

- 응답·주석·문서는 한국어.
- 작업 위치: 브랜치 `feat/onecard-s5b`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan` (main `997b246` 기반 — S5 앞부분과
  D-116 포함). 메인 체크아웃(`/Users/yupchang/Developer/mirboard`)과 다른 워크트리는 건드리지 않는다. Bash 는 매 호출 작업 폴더가 바뀔 수 있으니
  **명령마다 워크트리 절대경로로 `cd` 하거나 `git -C` 를 쓴다.** `git add` 는 파일을 콕 집어서 한다.
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` — 모델이 무엇이든 이 줄 그대로.
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 컴파일)를 돈다. `--no-verify` 금지. 그래서 **매 태스크가 끝난 트리는
  타입 검사와 클라 전체 테스트를 통과해야 한다.**
- 개발 서버(`bootRun`·`npm run dev`)·브라우저·운영 접속(`flyctl` 포함)·푸시를 하지 않는다. `.env` 를 읽지 않는다.
- **한 파일은 정확히 한 태스크가 고친다**(아래 파일 지도). 다른 태스크의 파일이 필요해 보이면 멈추고 보고한다.
- **도메인 경계**: `grep -rniE 'onecard|one_card|원카드|skullking|tichu' server/src/main/java/com/mirboard/infra | wc -l` 이 **16**(기준과 같다 — 티츄 칩
  서비스 등 기존 예외), 그중 `onecard|one_card|원카드` 는 0. 도메인 이벤트는 로컬 발행만(D-116 — 인스턴스 간 재발행 경로를 만들지 않는다).
  클라 게임 중립(D-103·D-124): `useStompRoom` 은 게임 스토어를 모르고, 게임 스토어는 순수 리듀서, sink 는 모듈 상수이며 호출 시점에 `getState()` 를 읽는다.
- **사용자 결정(2026-10-06)**: 턴 카운트다운 = 서버 남은 시간 + 카운트다운 / 공개 전에 같이 고침 = 스컬킹·티츄 기록 실패 격리, 같은 방 진행 킥 합치기 /
  재리뷰 Minor N-2~N-6 은 이 단계에 묶음. 결정 번호 **D-130**(S5 앞부분 보강 — 코드가 이미 가리킨다)·**D-131**(이 묶음).
- 테스트 명령: 클라 특정 파일 `npm --prefix client run test -- <이름 일부>`, 클라 전체 `npm --prefix client run test`, 타입 검사 `npm --prefix client run build:check`. 서버 단위
  `./gradlew :server:test --tests "<클래스>"`(Docker 불필요). Docker IT 는 `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 같은 명령(이 워크트리의 OrbStack). Gradle 빌드 캐시가
  켜져 있어 같은 입력이면 `FROM-CACHE` 로 끝나니 **실측은 `--rerun`**.
- 로그 단언은 `testsupport/LogCapture`(Logback `ListAppender`)로 한다 — 콘솔 문자열이 아니라 이벤트(수준·문장·스택)를 본다.
- 테스트 수(누적): 클라 `npm --prefix client run test` 마지막 두 줄 — 기준 61파일·642건 → Task 5 61·657 → Task 7 61·658(나머지 태스크는 클라 불변). 서버 전체
  1288 → **1324**(Docker 불필요 1086 → **1118**, 84%, 스킵 5). 서버 묶음(`*Test`, 이름이 `Test` 로 끝나는 IT 포함) 태스크 뒤: skullking 371 · tichu 304(스킵 5) ·
  onecard 186 → 187(T6) · infra.bot 46 → 51(T3) → 57(T4) · infra.ws 53 → 60(T2) → 64(T4) · infra.scheduling 3 · infra.rest 117 → 123(T4) → 126(T6) ·
  domain.lobby 42 → 46(T4) · domain.game.core 13.
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를 잡는 문맥이다(지우지 않는다). 세 경우 모두
  첫 글자 하나를 떼고 읽는다(나머지 들여쓰기는 파일 그대로). `+` 뒤가 비어 있으면 빈 줄을 넣는다. 한 블록은 파일에서 정확히 한 곳과 맞고, 블록은
  적힌 순서대로 적용한다. 안에 ``` 가 든 블록은 네 개짜리 펜스(````)로 감쌌다 — 그 ``` 줄은 파일 내용이다. 새 파일 블록은 그대로 쓰고 끝에 줄바꿈 하나.

## 파일 지도

| 파일 | 태스크 | 책임 |
| --- | --- | --- |
| `docs/decisions.md` | 1 | D-130·D-131 결정 기록(D-129 위), D-99·D-128 에 *변경 → D-131* |
| `server/src/main/java/com/mirboard/domain/game/tichu/persistence/MatchResultRecorder.java`, `server/src/main/java/com/mirboard/domain/game/skullking/SkullKingGameEngine.java` | 2 | 매치 기록 실패 격리 — 티츄 기록기 자체(`TransactionTemplate`), 스컬킹 발행 지점 |
| `server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java` | 3 | 같은 방 진행 킥 합치기(참가 확인 뒤 방별 진행 중 집합) |
| `server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java`, `server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java` | 4 | 남은 시간 조회(`remaining`·`turnRemaining`) |
| `server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java` | 4 | resync `turnRemainingMs`(락 안), 방 만들기 턴 제한 생략을 서비스로 |
| `server/src/main/java/com/mirboard/infra/ws/GameStompController.java`, `server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java`, `server/src/main/java/com/mirboard/infra/ws/DesertionService.java` | 4 | 락을 쥔 경로의 재무장을 락 안으로(실패 격리) |
| `server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java`, `server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java`, `client/src/features/lobby/CreateRoomModal.tsx` | 4 | 생략 기본 = 게임 선언(`defaultPlayers`·`defaultTurnSeconds`) |
| `client/src/types/stomp.ts`, `client/src/features/onecard/onecardStore.ts`, `OneCardTurnCountdown.tsx`, `OneCardCenter.tsx`, `OneCardTable.tsx`, `client/src/styles/parts/19-onecard-table.css` | 5 | 원카드 턴 카운트다운 |
| `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`, `server/src/main/resources/application.yml` | 6 | 원카드 공개 상태 기본값 AVAILABLE |
| `client/src/ws/staleSocket.test.tsx`, `client/src/pages/RoomPage.tsx` | 7 | 재리뷰 N-2(본인 큐 가드 테스트)·N-4(블록 주석) |
| 문서 14개(아래 Task 8) | 8 | 게임 3종·수치, 계약·런북·QA, 설계 §8·§9 |

---

### Task 1: 문서 — 결정 기록 D-130·D-131 (문서 선행)

코드보다 결정 기록이 먼저다(저장소 규칙). 테스트는 없다.

**Files:**
- Modify: `docs/decisions.md`

**Interfaces:**
- Produces: `## D-131 (2026-10-06) — …`·`## D-130 (2026-10-06) — …` 두 항목(파일 관례대로 최근 항목이 위 — D-129 바로 위에 D-131 → D-130), D-99·D-128
  끝에 `*변경 → D-131*` 한 줄씩. 뒤 태스크의 코드 주석·문서는 `D-131 —` 로 이 항목을 가리킨다.

- [ ] **Step 1: 결정 항목 추가**

`docs/decisions.md`:

```diff
 게임별 분리 결정이 선행이라 별건. ② 봇은 포트 기본(합법 균등 분포)으로 시작 — 휴리스틱은
 후속. ③ 카탈로그에 노출되지만 클라 게임판은 S6(D-103) — 직후 과제.
 
+## D-131 (2026-10-06) — 원카드 공개(AVAILABLE) + resync 남은 턴 시간·원카드 카운트다운 + 매치 기록 실패 격리 3게임
+
+원카드 정의의 공개 상태 기본값을 `AVAILABLE` 로 바꾸고, 방 만들기에서 인원·턴 제한을 생략하면 서버도 게임 선언
+(`defaultPlayers()`·`defaultTurnSeconds()`)을 쓴다(티츄·스컬킹은 값이 같아 동작 불변). D-130 의 "30초의 대가"는 카운트다운으로 갚는다 —
+resync 응답에 게임 중립 `turnRemainingMs`(지금 세대 턴 데드라인 − 지금. 턴 제한 끔·기다리는 좌석 없음·걸린 데드라인 없음이면 `null`)를
+싣고, 원카드 게임판은 그 값과 서버가 턴 데드라인을 다시 거는 순간의 공개 이벤트(`TURN_CHANGED`·`PLAYER_ELIMINATED`)로 센다(경쟁 창 동안은
+숨김). 값이 새 상태와 어긋나지 않게 진행 경로의 재무장(`onTurnAdvanced`)을 방 액션 락 **안**으로 옮기고 resync 도 락 안에서 읽는다. 매치
+기록 실패는 세 게임 모두 진행을 막지 않는다 — 스컬킹은 원카드처럼 발행 지점에서, 티츄는 리스너가 둘(기록·칩 정산)이라 기록기가 자기
+트랜잭션의 예외를 삼킨다(발행 지점에서 잡으면 멀티캐스터가 첫 예외에서 멈춰 리스너 순서에 따라 칩 정산이 빠진다). 같은 방의 진행 킥은
+한 번에 하나만 돈다(방별 진행 중 집합).
+
+## D-130 (2026-10-06) — 원카드 공개 전 보강: 진행 킥·훅 정리·경쟁 결과 로그·기록 격리·방 만들기 처음 선택 4명·30초
+
+공개를 막는 Critical 이 없던 4렌즈 리뷰의 보강 묶음이다(`docs/plans/onecard.md` §8). 재기동·타이머 유실로 멈춘 진행은 클라가 판을 다시
+볼 때(resync 응답 뒤·게임 토픽 구독) 진행 킥(`GameProgressKick`)이 봇 루프와 사라진 엔진 타이머(ZADD NX)를 다시 건다(턴 데드라인은
+건드리지 않는다). 방 STOMP 훅은 낡은 resync 응답과 정리된 이전 소켓의 콜백을 버리고, 경쟁 창이 닫히는 세 경로마다 결과 로그 한 줄(INFO)을
+남기며(자동화는 로그로 탐지만), 원카드 매치 기록 실패는 ERROR 후 진행을 계속한다. 방 만들기의 처음 선택도 게임이 선언한다(원카드만 4명·
+턴 제한 30초 — 버티기 대응). **30초의 대가**: 게임판에 턴 카운트다운이 없어 기본 방마다 안 보이는 시간 초과(먹기 — 공격 누적이면
+통째로, 끊긴 동안은 30초마다)가 생긴다 — 공개 전환 전에 재검토한다. *변경 → D-131*(카운트다운을 넣었다)
+
 ## D-129 (2026-10-05) — 원카드 S4: 클라 게임판 + 비공개 이벤트 비순번 (열림 전환은 별건)
 
 원카드 손패 이벤트(`HAND_DEALT`·`HAND_UPDATED`)가 D-126 의 `GameEvent.sequenced()` 를 false 로 재정의해 방 순번을 쓰지
```

```diff
 게임 이름이 없다. 계획 단계에서 사용자가 정한 셋: 비공개 이벤트 비순번(설계 §4.5b)은 같은 확장을 넣는 D-126 에
 맡기고 원카드는 병합 뒤 재정의만 한다(S4 착수 조건), 창 끝·봇 시각 직후에 처리된 누름도 인정한다(서버가 먼저
 처리한 누름이 이김, 룰 §9-4 그대로), 봇은 휴리스틱으로 넣는다(무작위 봇 대비 4인 승률 0.92).
+*변경 → D-131* (공개 상태 기본 `AVAILABLE`. 락을 쥔 진행 경로의 턴 재무장을 락 안으로 옮겨 "락 해제와 세대 상승 사이 틈"을 그 경로에서 닫았다 — 매치 시작(라운드 스타터)은 그대로.)
 
 ## D-127 (2026-10-04) — 원카드 순수 룰 엔진 (원카드 S2, 신규 패키지)
 
```

```diff
 적었으나, S2 가 만지는 파일(`RoomService`·`RoomController`·클라 모달)과 S1 이 만지는 파일
 (`GameEngine`·`GameStompController`·스케줄러)의 교집합이 없고 `game-port.md` §3 이 포트
 표면을 참조하지 않아 먼저 진행했다. D-98 은 S1 용으로 예약 상태를 유지한다.
+
+*변경 → D-131* (생략한 `capacity` 는 게임 선언 `defaultPlayers()` — 기본 구현이 `maxPlayers` 라 티츄·스컬킹은 그대로, 원카드만 4.)
 
 ## D-98 (2026-07-30) — 포트 추출: 티츄를 `GameEngine` 뒤로 (M5/T7 S1, 동작 무변경)
 
```

- [ ] **Step 2: 확인**

Run: `grep -n "^## D-13[01] " docs/decisions.md` 뒤 `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 두 줄(`## D-131 (2026-10-06) — 원카드 공개(AVAILABLE) + resync 남은 턴 시간·원카드 카운트다운 + 매치 기록 실패 격리 3게임`, `## D-130 (2026-10-06) — …`),
타입 오류 없음, 마지막 두 줄 `Test Files  61 passed (61)` · `Tests  642 passed (642)`.

- [ ] **Step 3: 커밋**

```bash
git add docs/decisions.md
git commit -m "docs(D-131): 결정 기록 — D-130(원카드 S5 앞부분 보강)·D-131(공개 전환 묶음)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 서버 — 매치 기록 실패 격리(스컬킹·티츄)

**Files:**
- Create: `server/src/test/java/com/mirboard/infra/ws/TichuRecorderFailureTest.java`, `server/src/test/java/com/mirboard/infra/ws/SkullKingRecorderFailureTest.java`
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/persistence/MatchResultRecorder.java`, `server/src/main/java/com/mirboard/domain/game/skullking/SkullKingGameEngine.java`

**Interfaces:**
- Consumes: D-116 로컬 동기 발행(`ApplicationEventPublisher`), 티츄 `TichuMatchCompleted` 의 리스너 둘 — `MatchResultRecorder`(기록·ELO)와 `RoomChipService`(칩 정산·
  리매치 WAITING 복귀), 스컬킹 `SkullKingMatchCompleted` 의 리스너 하나 `SkullKingMatchRecorder`, `testsupport/LogCapture`, 원카드의 같은 격리(`OneCardRecorderFailureTest`, D-130).
- Produces: `MatchResultRecorder(…, PlatformTransactionManager transactionManager)` — 본문을 `TransactionTemplate.executeWithoutResult` 로 감싸 본문·커밋 예외를
  삼키고 ERROR(+스택). Spring 멀티캐스터는 첫 예외에서 멈추므로 발행 지점에서 잡으면 기록이 먼저 불린 순서에서 **칩 정산이 빠진다** — 그래서 티츄는 기록기가
  스스로 삼킨다. `SkullKingGameEngine.recordIfEnded` 는 발행을 try/catch(→ ERROR) — 리스너가 하나라 발행 지점이 맞다. 정상 종료·탈주 두 경로 모두 마지막 이벤트 방송·종료 처리가 이어진다.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/infra/ws/TichuRecorderFailureTest.java`:

```java
package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameEngine;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.MatchResultRecorder;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchParticipantRepository;
import com.mirboard.domain.game.tichu.persistence.TichuMatchResult;
import com.mirboard.domain.game.tichu.persistence.TichuMatchResultRepository;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.messaging.StompPublisher;
import com.mirboard.infra.metrics.MirboardMetrics;
import com.mirboard.testsupport.LogCapture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * D-131 — 티츄 매치 기록이 실패해도 매치는 끝나고, 같은 이벤트의 <b>다른 리스너(칩 정산)</b>도 돈다.
 *
 * <p>D-116 뒤 {@code TichuGameEngine} 은 {@code TichuMatchCompleted} 를 {@code ApplicationEventPublisher} 로 동기 발행하고
 * 리스너가 둘이다 — {@code MatchResultRecorder}(기록, DB) 와 {@code RoomChipService}(칩 정산, Redis). 스프링 멀티캐스터는
 * 리스너를 차례로 부르다 <b>첫 예외에서 멈추고</b> 그 예외를 발행자에게 던진다. 그래서 기록 예외가 (1) 진행 경로로 새어
 * 마지막 방송·리매치 대기(사람끼리 방은 IN_GAME 유지)를 건너뛰고, (2) 기록이 먼저 불리는 순서면 칩 정산까지 빠졌다. 발행
 * 지점에서 잡으면 (1)만 막고 (2)는 남는다 — 기록기가 자기 트랜잭션의 예외(본문·커밋 모두)를 삼킨다.
 *
 * <p>진짜 스프링 이벤트 배선(@EventListener 처리기·멀티캐스터)에 두 리스너를 실제 객체로 올리고, 저장소·브로커만 모의로
 * 둔다. 리스너 순서는 등록 순서를 따르므로 두 순서를 모두 본다. Docker 불필요.
 */
class TichuRecorderFailureTest {

    private static final String ROOM = "r1";
    private static final List<Long> PLAYERS = List.of(10L, 20L, 30L, 40L);
    private static final int STAKE = 100;

    /** 두 리스너의 등록(= 호출) 순서. */
    enum ListenerOrder { RECORDER_FIRST, CHIPS_FIRST }

    private final TichuMatchResultRepository matchRepo = mock(TichuMatchResultRepository.class);
    private final UserGameStatsService stats = mock(UserGameStatsService.class);
    private final BotUserRegistry bots = mock(BotUserRegistry.class);
    private final FakeTransactions transactions = new FakeTransactions();
    private final MatchResultRecorder recorder = new MatchResultRecorder(matchRepo,
            mock(TichuMatchParticipantRepository.class), stats, bots, mock(RatedMatchPolicy.class),
            new ObjectMapper(), Clock.systemUTC(), transactions);

    private final RoomChipStore chipStore = mock(RoomChipStore.class);
    private final StompPublisher stomp = mock(StompPublisher.class);
    private final RoomChipService chips =
            new RoomChipService(chipStore, mock(RoomService.class), bots, stomp, Clock.systemUTC());

    private final TichuMatchStateStore matchStateStore = mock(TichuMatchStateStore.class);
    private final RoomService roomService = mock(RoomService.class);

    TichuRecorderFailureTest() {
        when(matchStateStore.load(ROOM)).thenReturn(Optional.of(TichuMatchState.initial(PLAYERS, 1000)));
        when(chipStore.stacks(ROOM)).thenReturn(Map.of(10L, 1000L, 20L, 1000L, 30L, 1000L, 40L, 1000L));
        when(stats.get(anyLong(), anyString()))
                .thenReturn(new UserGameStatsService.GameStats("TICHU", 1000, 0, 0, 0));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ListenerOrder.class)
    void a_normal_match_end_still_settles_chips_waits_for_a_rematch_and_ends_the_match(ListenerOrder order) {
        when(matchRepo.save(any())).thenThrow(
                new DataAccessResourceFailureException("simulated DB outage in MatchResultRecorder"));
        List<GameEvent> outbound = new ArrayList<>();

        try (AnnotationConfigApplicationContext events = events(order);
             LogCapture logs = LogCapture.of(MatchResultRecorder.class)) {
            // 팀 A 가 목표 1000 에 닿는 라운드 끝 — 매치 종료.
            assertThatCode(() -> progress().advance(engine(events), room(),
                    new TichuState.RoundEnd(players(), 1000, 0), outbound))
                    .doesNotThrowAnyException();

            assertThat(outbound.getLast()).isInstanceOf(TichuEvent.MatchEnded.class);
            // 사람끼리 티츄 방은 리매치를 기다린다(D-82) — FINISHED 로 가지 않는다.
            verify(roomService, never()).markFinished(ROOM);
            assertChipsSettled();
            assertRecordFailureLogged(logs, DataAccessResourceFailureException.class);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ListenerOrder.class)
    void a_desertion_still_ends_the_match_and_settles_chips(ListenerOrder order) {
        when(matchRepo.save(any())).thenThrow(
                new DataAccessResourceFailureException("simulated DB outage in MatchResultRecorder"));
        List<GameEvent> outbound = new ArrayList<>();

        try (AnnotationConfigApplicationContext events = events(order);
             LogCapture logs = LogCapture.of(MatchResultRecorder.class)) {
            GameEngine.DesertOutcome outcome = engine(events).desert(0, 10L, outbound);

            assertThat(outcome).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
            assertThat(outbound).singleElement().isInstanceOf(TichuEvent.MatchEnded.class);
            assertChipsSettled();
            assertRecordFailureLogged(logs, DataAccessResourceFailureException.class);
        }
    }

    /**
     * 본문은 끝났는데 커밋이 실패하는 경우(연결 끊김·직렬화 실패). 메서드 본문 안의 try/catch 로는 못 잡는다 — 커밋은
     * {@code @Transactional} 프록시가 본문이 돌아온 뒤에 한다. 그래서 기록기는 트랜잭션 경계 자체를 감싼다.
     */
    @Test
    void a_commit_failure_is_isolated_too() {
        TichuMatchResult saved = mock(TichuMatchResult.class);
        when(saved.getId()).thenReturn(1L);
        when(matchRepo.save(any())).thenReturn(saved);
        transactions.commitFailure = new TransactionSystemException("simulated commit failure");
        List<GameEvent> outbound = new ArrayList<>();

        try (AnnotationConfigApplicationContext events = events(ListenerOrder.RECORDER_FIRST);
             LogCapture logs = LogCapture.of(MatchResultRecorder.class)) {
            assertThatCode(() -> progress().advance(engine(events), room(),
                    new TichuState.RoundEnd(players(), 1000, 0), outbound))
                    .doesNotThrowAnyException();

            assertThat(outbound.getLast()).isInstanceOf(TichuEvent.MatchEnded.class);
            assertChipsSettled();
            assertRecordFailureLogged(logs, TransactionSystemException.class);
        }
    }

    // ---------- helpers ----------

    private AnnotationConfigApplicationContext events(ListenerOrder order) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (order == ListenerOrder.RECORDER_FIRST) {
            context.registerBean("recorder", MatchResultRecorder.class, () -> recorder);
            context.registerBean("chips", RoomChipService.class, () -> chips);
        } else {
            context.registerBean("chips", RoomChipService.class, () -> chips);
            context.registerBean("recorder", MatchResultRecorder.class, () -> recorder);
        }
        context.refresh();
        return context;
    }

    private TichuGameEngine engine(AnnotationConfigApplicationContext events) {
        return new TichuGameEngine(new GameContext(ROOM, PLAYERS, 1000, STAKE, List.of()),
                mock(TichuGameStateStore.class), matchStateStore, mock(TichuRoundStarter.class), events);
    }

    private MatchProgressService progress() {
        GameRegistry games = mock(GameRegistry.class);
        when(games.require(TichuGameDefinition.ID)).thenReturn(new TichuGameDefinition(null, null, null, null));
        return new MatchProgressService(roomService, mock(MirboardMetrics.class), games);
    }

    private static Room room() {
        return new Room(ROOM, "방", TichuGameDefinition.ID, 10L, RoomStatus.IN_GAME, 4, 4, PLAYERS, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, STAKE, Set.of());
    }

    private static List<PlayerState> players() {
        return IntStream.range(0, 4)
                .mapToObj(seat -> PlayerState.initial(seat, List.of(Card.normal(Suit.JADE, 5))))
                .toList();
    }

    private void assertChipsSettled() {
        verify(chipStore).setStacks(eq(ROOM), any());
        verify(stomp).publishToTopic(eq("/topic/room/" + ROOM), any());
    }

    /**
     * 결과를 실은 ERROR 에 <b>스택</b>도 붙어야 한다 — 본문 예외(DB)인지 커밋 실패인지는 스택으로만 가를 수 있다(Sentry 는
     * ERROR 를 올린다). 문장만 보면 {@code err={}}·{@code e.toString()} 식으로 바뀌어도 통과했다.
     */
    private static void assertRecordFailureLogged(LogCapture logs, Class<? extends Throwable> cause) {
        assertThat(logs.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("Tichu match record failed").contains("room=" + ROOM);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(cause.getName());
        });
    }

    /** 커밋만 실패시킬 수 있는 가짜 트랜잭션 관리자 — DB 없이 "본문은 끝났는데 커밋이 실패"를 만든다. */
    private static final class FakeTransactions implements PlatformTransactionManager {

        RuntimeException commitFailure;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
```

`server/src/test/java/com/mirboard/infra/ws/SkullKingRecorderFailureTest.java`:

```java
package com.mirboard.infra.ws;

import static com.mirboard.domain.game.skullking.state.MatchStateFixtures.scored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.skullking.SkullKingEngine;
import com.mirboard.domain.game.skullking.SkullKingGameEngine;
import com.mirboard.domain.game.skullking.event.SkullKingEvent;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.metrics.MirboardMetrics;
import com.mirboard.testsupport.LogCapture;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * D-131 — 스컬킹 매치 기록이 실패해도(동기 {@code @Transactional} 리스너 {@code SkullKingMatchRecorder} 의 DB 장애) 매치는
 * 끝난다: 마지막 이벤트({@code ROUND_ENDED}·{@code MATCH_ENDED})가 나갈 묶음에 남고, 방은 FINISHED 로 간다. 예전에는 예외가
 * {@code recordIfEnded} 에서 진행 경로로 새어 호출자(컨트롤러·봇·타이머·탈주)가 저장 뒤의 방송과 {@code markFinished} 를
 * 건너뛰었다 — 방이 IN_GAME 에 남고(끝난 매치라 진행 킥 대상도 아니다) 클라는 직전 화면에 멈췄다. 원카드(D-130,
 * {@link OneCardRecorderFailureTest})와 같은 격리다. 기록 리스너는 하나뿐이라 발행 지점에서 잡아도 건너뛸 다른 리스너가 없다.
 */
class SkullKingRecorderFailureTest {

    private static final String ROOM = "r1";
    private static final List<Long> PLAYERS = List.of(10L, 20L, 30L);

    private final SkullKingStateStore states = mock(SkullKingStateStore.class);
    private final SkullKingMatchStateStore matchStates = mock(SkullKingMatchStateStore.class);
    private final ApplicationEventPublisher failingRecorder = event -> {
        throw new DataAccessResourceFailureException("simulated DB outage in SkullKingMatchRecorder");
    };
    private final SkullKingGameEngine engine = new SkullKingGameEngine(
            new GameContext(ROOM, PLAYERS), states, matchStates, new SecureRandom(), failingRecorder);

    private static final Map<Integer, RoundScore> LAST_ROUND = Map.of(
            0, new RoundScore(1, 1, 20, 0),
            1, new RoundScore(0, 1, -10, 0),
            2, new RoundScore(0, 0, 10, 0));

    @Test
    void the_final_round_still_ends_the_match_and_finishes_the_room_when_recording_fails() {
        SkullKingMatchState beforeLastRound = SkullKingMatchState.initial(3, 0);
        for (int i = 0; i < SkullKingMatchState.TOTAL_ROUNDS - 1; i++) {
            beforeLastRound = scored(beforeLastRound, Map.of(0, 10, 1, 20, 2, 0), 3);
        }
        when(matchStates.load(ROOM)).thenReturn(Optional.of(beforeLastRound));
        RoomService roomService = mock(RoomService.class);
        GameRegistry games = mock(GameRegistry.class);
        // 스컬킹은 리매치를 선언하지 않는다(기본 false) — 끝나면 방이 FINISHED 로 간다.
        when(games.require(anyString())).thenReturn(mock(GameDefinition.class));
        MatchProgressService progress = new MatchProgressService(roomService, mock(MirboardMetrics.class), games);
        List<GameEvent> outbound = new ArrayList<>();

        try (LogCapture logs = LogCapture.of(SkullKingGameEngine.class)) {
            assertThatCode(() -> progress.advance(engine, room(), roundEnd(SkullKingMatchState.TOTAL_ROUNDS), outbound))
                    .doesNotThrowAnyException();

            assertThat(outbound).anyMatch(SkullKingEvent.RoundEnded.class::isInstance);
            assertThat(outbound.getLast()).isInstanceOf(SkullKingEvent.MatchEnded.class);
            verify(matchStates).save(eq(ROOM), argThat(SkullKingMatchState::isMatchOver));
            verify(roomService).markFinished(ROOM);
            assertRecordFailureLogged(logs);
        }
    }

    /** 탈주로 한 명만 남아 끝나는 매치 — 탈주 경로도 같은 발행 지점을 탄다. 포트 답은 MATCH_ENDED 그대로다. */
    @Test
    void a_desertion_that_ends_the_match_still_answers_match_ended_when_recording_fails() {
        SkullKingEngine rules = new SkullKingEngine(new GameContext(ROOM, PLAYERS));
        SkullKingMatchState match = SkullKingMatchState.initial(3, 0);
        SkullKingState state = rules.startRound(match, new Random(7)).newState();
        SkullKingEngine.Desertion first = rules.desert(state, match, 0, Set.of(0, 1, 2));
        when(states.load(ROOM)).thenReturn(Optional.of(first.newState()));
        when(matchStates.load(ROOM)).thenReturn(Optional.of(first.matchState()));
        List<GameEvent> outbound = new ArrayList<>();

        try (LogCapture logs = LogCapture.of(SkullKingGameEngine.class)) {
            GameEngine.DesertOutcome outcome = engine.desert(1, 20L, outbound);

            assertThat(outcome).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
            assertThat(outbound).anyMatch(SkullKingEvent.MatchEnded.class::isInstance);
            verify(matchStates).save(eq(ROOM), argThat(SkullKingMatchState::isMatchOver));
            assertRecordFailureLogged(logs);
        }
    }

    /** 결과를 실은 ERROR 에 기록 예외의 스택도 붙는다 — 문장만 보면 스택을 빼도({@code err={}}) 통과했다. */
    private static void assertRecordFailureLogged(LogCapture logs) {
        assertThat(logs.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("SkullKing match record failed").contains("room=" + ROOM);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(DataAccessResourceFailureException.class.getName());
        });
    }

    private static Room room() {
        return new Room(ROOM, "방", "SKULL_KING", 10L, RoomStatus.IN_GAME, 3, 3, PLAYERS, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    private static SkullKingState.RoundEnd roundEnd(int round) {
        return new SkullKingState.RoundEnd(round, List.of(
                PlayerState.initial(0, List.of()),
                PlayerState.initial(1, List.of()),
                PlayerState.initial(2, List.of())), 0, LAST_ROUND);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.ws.TichuRecorderFailureTest" --tests "com.mirboard.infra.ws.SkullKingRecorderFailureTest" --tests "com.mirboard.infra.ws.OneCardRecorderFailureTest"`
Expected: FAIL — `compileTestJava` 실패(`1 error`): `TichuRecorderFailureTest.java:89: error: constructor MatchResultRecorder in class MatchResultRecorder cannot be applied to given types;`
(새 생성자 인자 `PlatformTransactionManager`). 동작 RED 는 스파이크에서 옛 생성자 임시본으로 확인했다 — 티츄 정상 종료·탈주가 두 리스너 순서 모두
`DataAccessResourceFailureException: simulated DB outage in MatchResultRecorder` 를 진행 경로로 흘렸고(기록이 먼저면 칩 정산까지 빠짐), 스컬킹 2건도 같았다.

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/domain/game/tichu/persistence/MatchResultRecorder.java`:

```diff
 import org.slf4j.LoggerFactory;
 import org.springframework.context.event.EventListener;
 import org.springframework.stereotype.Component;
-import org.springframework.transaction.annotation.Transactional;
+import org.springframework.transaction.PlatformTransactionManager;
+import org.springframework.transaction.support.TransactionTemplate;
 
 /**
  * 한 매치(여러 라운드 합산) 가 종료되면 {@link TichuMatchCompleted} 를 받아
```

```diff
  *
  * <p>D-115 — 승패·레이팅·탈주 수는 {@code users} 가 아니라 게임별 {@code user_game_stats}
  * (TICHU 행)에 쌓는다. {@code users} 의 옛 컬럼은 이관 후 쓰기를 멈췄다.
+ *
+ * <p>D-131 — <b>기록 실패는 여기서 끝낸다</b>(ERROR 후 삼킨다). 이 이벤트는 엔진이 동기로 발행하고 리스너가 둘이다 — 이
+ * 기록기와 {@code RoomChipService}(칩 정산). 스프링 멀티캐스터는 첫 예외에서 멈추고 그 예외를 발행자에게 던지므로, 기록
+ * 예외가 진행 경로로 새어 마지막 방송·리매치 대기를 건너뛰고, 기록이 먼저 불리는 순서면 칩 정산까지 빠졌다. 발행 지점에서
+ * 잡으면 앞의 것만 막는다. 트랜잭션 경계를 {@link TransactionTemplate} 으로 직접 감싸는 것은 커밋 실패까지 잡기 위해서다 —
+ * {@code @Transactional} 프록시의 커밋은 메서드 본문이 돌아온 뒤라 본문 안의 try/catch 에 걸리지 않는다. 다른 인스턴스로 다시
+ * 보내는 경로는 만들지 않는다(D-116 — 기록은 끝낸 인스턴스에서 한 번). 결과는 로그로 남겨 수동 복구할 수 있게 한다.
  */
 @Component
 public class MatchResultRecorder {
```

```diff
     private final RatedMatchPolicy ratedMatchPolicy;
     private final ObjectMapper objectMapper;
     private final Clock clock;
+    private final TransactionTemplate transactions;
 
     public MatchResultRecorder(TichuMatchResultRepository matchRepo,
                                TichuMatchParticipantRepository participantRepo,
```

```diff
                                BotUserRegistry bots,
                                RatedMatchPolicy ratedMatchPolicy,
                                ObjectMapper objectMapper,
-                               Clock clock) {
+                               Clock clock,
+                               PlatformTransactionManager transactionManager) {
         this.matchRepo = matchRepo;
         this.participantRepo = participantRepo;
         this.stats = stats;
```

```diff
         this.ratedMatchPolicy = ratedMatchPolicy;
         this.objectMapper = objectMapper;
         this.clock = clock;
+        this.transactions = new TransactionTemplate(transactionManager);
     }
 
     @EventListener
-    @Transactional
     public void onMatchCompleted(TichuMatchCompleted event) {
+        try {
+            transactions.executeWithoutResult(status -> record(event));
+        } catch (RuntimeException e) {
+            log.error("Tichu match record failed, the match still ends: room={} players={} winner={} A={} B={} "
+                            + "rounds={} deserterUserId={}",
+                    event.roomId(), event.playerIds(), event.winningTeam(), event.cumulativeTeamAScore(),
+                    event.cumulativeTeamBScore(), event.roundScores().size(), event.deserterUserId(), e);
+        }
+    }
+
+    /** 한 트랜잭션 — 매치·참가자 행과 게임별 전적. */
+    private void record(TichuMatchCompleted event) {
         // Phase 16(#4) — 봇 포함 매치도 win/lose·match_result 는 기록하되 ELO(rating)
         // 만 제외 (rating 인플레이션 방지). D-117 — 게스트가 낀 매치도 같은 규칙.
         // 정회원 4인 매치만 ELO 반영.
```

`server/src/main/java/com/mirboard/domain/game/skullking/SkullKingGameEngine.java`:

```diff
      * 완주 라운드 수·승자는 엔진이 정한 값을 그대로 쓴다(조기 종료 계산을 여기서 반복하지
      * 않는다). 로컬 발행만(D-116) — 각 인스턴스가 다시 기록하면 같은
      * 매치가 중복으로 남는다.
+     *
+     * <p>D-131 — 기록기({@code SkullKingMatchRecorder})는 동기 리스너(@Transactional)라 DB 장애가 여기로 올라온다. 그대로
+     * 던지면 호출한 진행 경로(컨트롤러·봇·타이머·탈주)가 저장 뒤의 방송·FINISHED 전이를 건너뛰어 마지막 {@code ROUND_ENDED}·
+     * {@code MATCH_ENDED} 가 아무에게도 안 가고 방이 IN_GAME 에 남았다(끝난 매치라 진행 킥 대상도 아니다). 원카드(D-130)와
+     * 같이 결과를 실어 ERROR(Sentry)로 남기고 진행은 계속한다. 이 이벤트의 리스너는 기록기 하나뿐이라 발행 지점에서 잡아도
+     * 건너뛸 다른 리스너가 없다(리스너가 둘인 티츄는 기록기가 스스로 삼킨다).
      */
     private void recordIfEnded(List<SkullKingEvent> events, SkullKingMatchState match) {
         for (SkullKingEvent event : events) {
             if (event instanceof SkullKingEvent.MatchEnded ended) {
-                publisher.publishEvent(new SkullKingMatchCompleted(
-                        context.roomId(), context.playerIds(), ended.finalScores(),
-                        ended.winners(), match.desertedSeats(), ended.roundsPlayed()));
+                try {
+                    publisher.publishEvent(new SkullKingMatchCompleted(
+                            context.roomId(), context.playerIds(), ended.finalScores(),
+                            ended.winners(), match.desertedSeats(), ended.roundsPlayed()));
+                } catch (RuntimeException e) {
+                    log.error("SkullKing match record failed, the match still ends: room={} players={} winners={} "
+                                    + "scores={} rounds={} deserted={}",
+                            context.roomId(), context.playerIds(), ended.winners(), ended.finalScores(),
+                            ended.roundsPlayed(), match.desertedSeats(), e);
+                }
             }
         }
     }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.ws.TichuRecorderFailureTest" --tests "com.mirboard.infra.ws.SkullKingRecorderFailureTest" --tests "com.mirboard.infra.ws.OneCardRecorderFailureTest"`
Expected: PASS — 8건(티츄 5 = 정상 종료×2순서·탈주×2순서·커밋 실패, 스컬킹 2, 원카드 회귀 1).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.domain.game.tichu.persistence.MatchResultRecorderIT" --tests "com.mirboard.infra.ws.RoomChipServiceIT" --tests "com.mirboard.infra.messaging.DomainEventSingleDeliveryIT"`
Expected: PASS — 12건(`MatchResultRecorderIT` 4 · `RoomChipServiceIT` 6 · `DomainEventSingleDeliveryIT` 2 — 실제 트랜잭션 관리자로 기록이 그대로 된다).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.ws.*Test"`(이 묶음에는 `*IntegrationTest` 3개가 있어 Docker 가 필요하다)
Expected: PASS — 60건(53 + 7).

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/infra/ws/TichuRecorderFailureTest.java \
  server/src/test/java/com/mirboard/infra/ws/SkullKingRecorderFailureTest.java \
  server/src/main/java/com/mirboard/domain/game/tichu/persistence/MatchResultRecorder.java \
  server/src/main/java/com/mirboard/domain/game/skullking/SkullKingGameEngine.java
git commit -m "fix(D-131): 매치 기록 실패 격리 — 스컬킹(발행 지점)·티츄(기록기가 자기 트랜잭션 예외를 삼킴)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 서버 — 같은 방 진행 킥 합치기

**Files:**
- Modify: `server/src/test/java/com/mirboard/infra/bot/GameProgressKickTest.java`, `server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java`

**Interfaces:**
- Consumes: S5 앞부분의 `GameProgressKick.kick(roomId, userId)`(가상 스레드로 넘김)·`kickNow`(참가자·관전자만, 봇 루프·엔진 타이머 재개).
- Produces: 방별 진행 중 집합 `inFlight`(`ConcurrentHashMap.newKeySet()`) — **참가자·관전자 확인을 지난 뒤에만** `add`, 이미 있으면 DEBUG 후 반환,
  `try { … } finally { inFlight.remove(roomId); }`(예외여도 빠진다). 비참가자 킥은 집합에 들지 않아 참가자 킥을 막지 못한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/infra/bot/GameProgressKickTest.java`:

```diff
 
 import static org.assertj.core.api.Assertions.assertThat;
 import static org.assertj.core.api.Assertions.assertThatCode;
+import static org.assertj.core.api.Assertions.assertThatThrownBy;
 import static org.mockito.ArgumentMatchers.any;
 import static org.mockito.ArgumentMatchers.anyString;
+import static org.mockito.ArgumentMatchers.argThat;
 import static org.mockito.ArgumentMatchers.eq;
 import static org.mockito.Mockito.doAnswer;
 import static org.mockito.Mockito.mock;
 import static org.mockito.Mockito.never;
 import static org.mockito.Mockito.timeout;
+import static org.mockito.Mockito.times;
 import static org.mockito.Mockito.verify;
 import static org.mockito.Mockito.verifyNoInteractions;
 import static org.mockito.Mockito.when;
```

```diff
 import java.util.List;
 import java.util.Optional;
 import java.util.Set;
+import java.util.concurrent.CountDownLatch;
 import java.util.concurrent.Executor;
 import java.util.concurrent.RejectedExecutionException;
+import java.util.concurrent.TimeUnit;
+import java.util.concurrent.atomic.AtomicInteger;
 import java.util.concurrent.atomic.AtomicReference;
 import org.junit.jupiter.api.Nested;
 import org.junit.jupiter.api.Test;
```

```diff
             verifyNoInteractions(roomService);
         }
     }
+
+    /**
+     * D-131 — 같은 방의 킥은 한 번에 하나만 돈다(S5T2-M4). 관전은 로그인한 누구에게나 열려 있어 참가자·관전자 게이트를 지나고
+     * SUBSCRIBE 는 레이트리밋 밖이라, 구독 폭주마다 상태 GET·봇 루프·타이머 확인이 겹쳐 돌았다(킥마다의 가상 스레드와 방 읽기는
+     * 그대로다 — 합치기는 그 뒤의 일만 합친다). 참가 확인을 지난 뒤 같은 방 킥이 돌고 있으면 건너뛴다 — 돌고 있는 킥이 같은 일을
+     * 하고 있다. 끝나면(예외여도) 반드시 빠져 다음 킥이 다시 돈다.
+     */
+    @Nested
+    class Coalescing {
+
+        @Test
+        void a_second_kick_while_one_is_running_for_the_same_room_does_no_work() throws Exception {
+            inGame(List.of(1), 0, List.of(1));
+            CountDownLatch entered = new CountDownLatch(1);
+            CountDownLatch release = new CountDownLatch(1);
+            AtomicInteger loads = new AtomicInteger();
+            // 첫 킥만 상태를 읽는 자리에서 멈춘다 — 그 사이 같은 방 킥이 일을 하면 여기를 그냥 지나간다.
+            when(engine.loadState()).thenAnswer(invocation -> {
+                if (loads.incrementAndGet() == 1) {
+                    entered.countDown();
+                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
+                }
+                return Optional.of(state);
+            });
+            Thread first = Thread.ofVirtual().start(() -> kick.kickNow(ROOM, PLAYER));
+            assertThat(entered.await(5, TimeUnit.SECONDS)).as("첫 킥이 상태를 읽는 중").isTrue();
+
+            kick.kickNow(ROOM, PLAYER);
+            kick.kickNow(ROOM, 20L);
+
+            // 참가 확인(방 읽기)은 하지만 그 뒤의 일 — 엔진·상태·봇 루프 — 은 첫 킥 하나만 한다.
+            verify(engine).loadState();
+            release.countDown();
+            first.join(5_000);
+            assertThat(first.isAlive()).isFalse();
+            verify(bots).scheduleBotsIfIdle(ROOM);
+        }
+
+        /**
+         * 참가 확인을 지난 킥만 진행 중 집합에 든다. 공개 토픽은 로그인한 누구나 구독할 수 있어(SUBSCRIBE 는 레이트리밋 밖)
+         * 슬롯을 참가 확인보다 먼저 잡으면, 비참가자의 킥이 방을 읽는 동안 슬롯을 쥔다 — 그 사이 들어온 참가자의 킥은
+         * 건너뛰어지고 비참가자의 킥은 게이트에서 끝나, 복구(멈춘 봇 루프·사라진 엔진 타이머)가 통째로 빠진다. 슬롯을 앞에
+         * 두는 변이를 이 테스트가 잡는다.
+         */
+        @Test
+        void a_stranger_kick_in_flight_does_not_hold_back_a_participant_kick() throws Exception {
+            inGame(List.of(1), 0, List.of(1));
+            CountDownLatch entered = new CountDownLatch(1);
+            CountDownLatch release = new CountDownLatch(1);
+            AtomicInteger reads = new AtomicInteger();
+            // 첫 방 읽기(비참가자의 킥)만 멈춘다.
+            when(roomService.getRoom(ROOM)).thenAnswer(invocation -> {
+                if (reads.incrementAndGet() == 1) {
+                    entered.countDown();
+                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
+                }
+                return room(RoomStatus.IN_GAME, List.of(1), 0);
+            });
+            Thread stranger = Thread.ofVirtual().start(() -> kick.kickNow(ROOM, 99L));
+            try {
+                assertThat(entered.await(5, TimeUnit.SECONDS)).as("비참가자의 킥이 방을 읽는 중").isTrue();
+
+                kick.kickNow(ROOM, PLAYER);
+
+                verify(engine).loadState();
+                verify(bots).scheduleBotsIfIdle(ROOM);
+            } finally {
+                release.countDown();
+                stranger.join(5_000);
+            }
+            assertThat(stranger.isAlive()).isFalse();
+        }
+
+        @Test
+        void the_next_kick_after_one_finished_runs_again() {
+            inGame(List.of(1), 0, List.of(1));
+
+            kick.kickNow(ROOM, PLAYER);
+            kick.kickNow(ROOM, PLAYER);
+
+            verify(engine, times(2)).loadState();
+            verify(bots, times(2)).scheduleBotsIfIdle(ROOM);
+        }
+
+        @Test
+        void a_kick_that_failed_still_lets_the_next_one_run() {
+            inGame(List.of(1), 0, List.of(1));
+            when(engine.loadState())
+                    .thenThrow(new IllegalStateException("redis blip"))
+                    .thenReturn(Optional.of(state));
+
+            assertThatThrownBy(() -> kick.kickNow(ROOM, PLAYER)).hasMessageContaining("redis blip");
+            kick.kickNow(ROOM, PLAYER);
+
+            verify(engine, times(2)).loadState();
+            verify(bots).scheduleBotsIfIdle(ROOM);
+        }
+
+        @Test
+        void a_running_kick_does_not_hold_back_another_room() throws Exception {
+            inGame(List.of(1), 0, List.of(1));
+            GameEngine otherEngine = mock(GameEngine.class);
+            when(roomService.getRoom("r2")).thenReturn(new Room("r2", "방", "ANY", 10L, RoomStatus.IN_GAME, 2, 2,
+                    List.of(10L, 20L), Set.of(), TeamPolicy.SEQUENTIAL, 0L, true, List.of(1), 1000, 0, 0, Set.of()));
+            when(engines.forRoom(argThat(room -> room != null && room.roomId().equals("r2")))).thenReturn(otherEngine);
+            when(otherEngine.loadState()).thenReturn(Optional.of(state));
+            when(otherEngine.pendingSeats(state)).thenReturn(List.of(1));
+            CountDownLatch entered = new CountDownLatch(1);
+            CountDownLatch release = new CountDownLatch(1);
+            when(engine.loadState()).thenAnswer(invocation -> {
+                entered.countDown();
+                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
+                return Optional.of(state);
+            });
+            Thread first = Thread.ofVirtual().start(() -> kick.kickNow(ROOM, PLAYER));
+            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
+
+            kick.kickNow("r2", PLAYER);
+
+            verify(bots).scheduleBotsIfIdle("r2");
+            release.countDown();
+            first.join(5_000);
+            verify(bots).scheduleBotsIfIdle(ROOM);
+        }
+    }
 }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.GameProgressKickTest" --tests "com.mirboard.infra.bot.GameProgressKickScenarioTest"`
Expected: FAIL — `23 tests completed, 1 failed`: `GameProgressKickTest > Coalescing > a_second_kick_while_one_is_running_for_the_same_room_does_no_work()`
(`TooManyActualInvocations: gameEngine.loadState(); Wanted 1 time` — 3번). 같은 `Coalescing` 의 나머지 4건(끝난 뒤 다시 돎·예외 뒤 다시 돎·다른 방은 막지 않음·
비참가자 킥이 참가자 킥을 막지 않음)은 합치기가 지나치지 않음을 지키는 고정 테스트라 처음부터 통과한다.

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java`:

```diff
 import com.mirboard.infra.scheduling.RoomGeneration;
 import com.mirboard.infra.ws.GameEngineProvider;
 import java.time.Duration;
+import java.util.Set;
+import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.Executor;
 import java.util.concurrent.Executors;
 import org.slf4j.Logger;
```

```diff
  * <p><b>참가자·관전자의 킥만 받는다.</b> 공개 토픽 구독은 로그인한 누구나 할 수 있고 SUBSCRIBE 는 레이트리밋 밖이라,
  * 아무나의 구독 폭주가 킥마다 Redis 왕복 몇 번씩으로 커지지 않게 한다(킥은 어차피 방을 읽는다).
  *
+ * <p><b>같은 방의 킥은 한 번에 하나만 돈다</b>(D-131). 관전은 로그인한 누구에게나 열려 있어 관전 한 번이면 위 게이트를
+ * 지난다 — 그 뒤의 구독 폭주가 킥마다 상태 GET·봇 루프 확인으로 커지지 않게, 참가 확인을 지난 킥은 방별 진행 중 집합에
+ * 들고 같은 방 킥이 이미 돌고 있으면 건너뛴다(돌고 있는 킥이 같은 일을 하고 있다). 끝나면 — 예외여도 — 반드시 뺀다.
+ * 집합은 인스턴스 메모리라 다른 인스턴스의 킥과는 합쳐지지 않는다(겹쳐도 무해 — 아래 두 동작이 각자 멱등이다).
+ *
  * <p><b>턴 진행({@code onTurnAdvanced})은 부르지 않는다.</b> 부르면 세대가 오르고 턴 데드라인이 처음부터 다시 걸려,
  * resync 를 반복하는 클라가 시간 초과를 끝없이 미룰 수 있다. 끝난 방·대기실·없는 방·시작 전 게임은 아무것도
  * 하지 않는다.
```

```diff
     private final DeadlineQueue deadlines;
     private final RoomGeneration generations;
     private final Executor executor;
+    /** D-131 — 지금 이 인스턴스에서 킥이 돌고 있는 방. */
+    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
 
     @Autowired
     public GameProgressKick(RoomService rooms,
```

```diff
         if (!room.playerIds().contains(userId) && !room.spectatorIds().contains(userId)) {
             return;
         }
+        if (!inFlight.add(roomId)) {
+            log.debug("Progress kick skipped, one is already running: roomId={}", roomId);
+            return;
+        }
+        try {
+            resume(roomId, room);
+        } finally {
+            inFlight.remove(roomId);
+        }
+    }
+
+    /** (a)·(b) — 참가 확인과 합치기를 지난 한 번의 일. */
+    private void resume(String roomId, Room room) {
         GameEngine engine = engines.forRoom(room);
         GameState state = engine.loadState().orElse(null);
         if (state == null || engine.isRoundOver(state)) {
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.GameProgressKickTest" --tests "com.mirboard.infra.bot.GameProgressKickScenarioTest"`
Expected: PASS — 23건(20 + 시나리오 3).

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.*Test"`
Expected: PASS — 51건.

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/infra/bot/GameProgressKickTest.java \
  server/src/main/java/com/mirboard/infra/bot/GameProgressKick.java
git commit -m "fix(D-131): 같은 방 진행 킥 합치기 — 방별 진행 중 집합 (S5T2-M4)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 서버 — resync 남은 턴 시간(게임 중립) + 재무장을 락 안으로 + 방 만들기 생략 기본 정렬

**Files:**
- Create: `server/src/test/java/com/mirboard/infra/bot/TurnRemainingTest.java`, `server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerCreateDefaultsTest.java`, `server/src/test/java/com/mirboard/domain/lobby/room/RoomServiceCreateDefaultsTest.java`
- Modify: `server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerResyncLockTest.java`, `server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerLeaveTest.java`, `server/src/test/java/com/mirboard/infra/ws/GameStompControllerGuardTest.java`, `server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java`, `server/src/test/java/com/mirboard/infra/ws/DesertionServiceTest.java`, `server/src/test/java/com/mirboard/domain/game/core/RoomCreationDefaultsTest.java`, `server/src/test/java/com/mirboard/infra/rest/rooms/RoomResyncIntegrationTest.java`, `server/src/test/java/com/mirboard/infra/rest/rooms/RoomCapacityIntegrationTest.java`, `server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java`, `server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java`, `server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java`, `server/src/main/java/com/mirboard/infra/ws/GameStompController.java`, `server/src/main/java/com/mirboard/infra/ws/DesertionService.java`, `server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java`, `server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java`, `server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java`, `client/src/features/lobby/CreateRoomModal.tsx`

**Interfaces:**
- Consumes: `TurnTimeoutScheduler.onTurnAdvanced`(세대 bump → 이전 세대 턴·엔진 타이머 취소 → 엔진 타이머·턴 데드라인 무장), 세대 저장소, 데드라인 멤버
  규약 `"{roomId}#{세대}"`, `RoomController.resync`(D-126 — 상태·`eventSeq` 를 방 액션 락 안에서 함께 읽음), `GameDefinition.defaultPlayers()`·`defaultTurnSeconds()`(D-130).
- Produces: `DeadlineQueue.remaining(String kind, String member): Optional<Duration>`(ZSCORE − 지금, 음수면 0), `TurnTimeoutScheduler.turnRemaining(Room room):
  Optional<Duration>`(지금 세대의 `deadlines:turn` 항목, 기다리는 좌석이 없으면 빈 값), `RoomController.ResyncResponse` 끝 필드 `Long turnRemainingMs`(null =
  턴 제한 끔·걸린 데드라인 없음·기다리는 좌석 없음 — 상태와 같은 락 안에서 읽는다), `RoomController` 생성자 끝 인자 `TurnTimeoutScheduler turnTimeout`.
  락을 쥔 진행 경로 4곳 — `GameStompController` 액션, `EngineTimerScheduler` 발화, `TurnTimeoutScheduler` 시간 초과, `DesertionService` 계속 — 의 재무장을 락
  해제 **앞**으로(그래야 resync 가 "새 상태 + 이전 턴 남은 시간"을 보지 않는다). 재무장 실패는 잡아 ERROR — 락 해제와 봇 스케줄(해제 뒤)은 그대로 돈다.
  방 만들기 생략 기본: `capacity` 생략 → `def.defaultPlayers()`, 턴 제한 생략(`RoomController.create` 가 null 로 넘김) → `def.defaultTurnSeconds()` — 티츄·
  스컬킹은 기존 값과 같다(테스트로 고정).

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/infra/bot/TurnRemainingTest.java`:

```java
package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * D-131 — 턴의 남은 시간. resync 응답이 실어 클라 카운트다운이 재접속 직후에도 맞게 한다. 값은 서버가 실제로 발화할
 * 데드라인 그대로다 — 지금 세대의 {@code deadlines:turn} 항목 점수 − 지금(음수면 0). 게임 중립이라 엔진은 모의 객체로
 * 충분하다.
 */
class TurnRemainingTest {

    private static final String ROOM = "r1";

    private static Room room(RoomStatus status, int turnSeconds) {
        return new Room(ROOM, "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, turnSeconds, 0, Set.of());
    }

    /** 실제 {@link DeadlineQueue} 를 가짜 시계·모의 Redis 위에 올린다 — 점수 − 지금의 계산을 그대로 본다. */
    @Nested
    class Remaining {

        private static final long NOW = 1_000_000L;

        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        private final DeadlineQueue deadlines = new DeadlineQueue(redis, mock(RedisScript.class),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        private final RoomGeneration generations = mock(RoomGeneration.class);
        private final TurnTimeoutScheduler scheduler = new TurnTimeoutScheduler(mock(RoomService.class),
                mock(GameEngineProvider.class), mock(GameEventBroadcaster.class), mock(RoomActionLock.class),
                mock(MatchProgressService.class), mock(BotScheduler.class), deadlines, generations);

        Remaining() {
            when(redis.opsForZSet()).thenReturn(zset);
            when(generations.current(ROOM)).thenReturn(5L);
        }

        @Test
        void the_armed_turn_answers_the_time_left_until_its_deadline() {
            when(zset.score("deadlines:turn", "r1#5")).thenReturn((double) (NOW + 12_345));

            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 30)))
                    .contains(Duration.ofMillis(12_345));
        }

        @Test
        void a_room_without_a_turn_limit_answers_nothing() {
            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 0))).isEmpty();

            verifyNoInteractions(zset, generations);
        }

        /** 폴러가 아직 꺼내지 않은 만기 항목(폴링 주기·락 경합 재시도) — 음수가 아니라 0 이다. */
        @Test
        void an_overdue_deadline_answers_zero() {
            when(zset.score("deadlines:turn", "r1#5")).thenReturn((double) (NOW - 500));

            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 30))).contains(Duration.ZERO);
        }

        /**
         * 세대가 오른 뒤 남은 이전 세대 항목은 발화해도 세대 검사에서 버려진다 — 남은 시간도 아니다. 지금 세대의 항목만 본다
         * (그 사이 이 세대의 항목이 아직 없으면 "걸린 데드라인 없음").
         */
        @Test
        void an_entry_of_an_older_generation_is_ignored() {
            when(zset.score("deadlines:turn", "r1#4")).thenReturn((double) (NOW + 9_000));
            when(zset.score("deadlines:turn", "r1#5")).thenReturn(null);   // 모의 객체의 기본값(0.0)이 아니라 "없음".

            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 30))).isEmpty();
        }

        @Test
        void a_room_that_is_not_in_game_answers_nothing() {
            when(zset.score("deadlines:turn", "r1#5")).thenReturn((double) (NOW + 12_345));

            assertThat(scheduler.turnRemaining(room(RoomStatus.FINISHED, 30))).isEmpty();
            assertThat(scheduler.turnRemaining(room(RoomStatus.WAITING, 30))).isEmpty();
        }
    }

    /**
     * D-131 — 시간 초과 자동 액션은 다음 턴 데드라인을 <b>락을 풀기 전에</b> 건다. resync 는 같은 락 안에서 상태와 남은 시간을
     * 함께 읽으므로, 락을 푼 뒤에 걸면 그 틈에 들어온 resync 가 새 상태와 <b>이전 턴의</b> 남은 시간(또는 없음)을 받았다. 봇
     * 루프는 락을 풀고 건다(호출자가 락을 쥔 채 루프를 걸면 루프가 이 락과 부딪쳐 재시도한다).
     */
    @Nested
    class RearmInsideTheLock {

        private final RoomService roomService = mock(RoomService.class);
        private final GameEngineProvider engines = mock(GameEngineProvider.class);
        private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
        private final RoomActionLock lock = mock(RoomActionLock.class);
        private final BotScheduler botScheduler = mock(BotScheduler.class);
        private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
        private final RoomGeneration generations = mock(RoomGeneration.class);
        private final TurnTimeoutScheduler scheduler = new TurnTimeoutScheduler(roomService, engines, broadcaster,
                lock, mock(MatchProgressService.class), botScheduler, deadlines, generations);

        @Test
        void a_timeout_auto_action_rearms_the_next_turn_before_releasing_the_lock() {
            GameEngine engine = mock(GameEngine.class);
            GameState state = mock(GameState.class);
            GameAction action = mock(GameAction.class);
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, 30));
            when(generations.current(ROOM)).thenReturn(5L);
            when(generations.bump(ROOM)).thenReturn(6L);
            when(lock.tryAcquire(ROOM)).thenReturn(true);
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenReturn(Optional.of(state));
            when(engine.pendingSeat(state)).thenReturn(0);
            when(engine.timeoutAction(state, 0)).thenReturn(action);
            when(engine.apply(state, 0, action)).thenReturn(new GameEngine.Result(state, List.of()));

            scheduler.handle("r1#5");

            InOrder order = inOrder(broadcaster, generations, deadlines, lock, botScheduler);
            order.verify(broadcaster).broadcast(eq(ROOM), any(), any());
            order.verify(generations).bump(ROOM);
            order.verify(deadlines).schedule(TurnTimeoutScheduler.KIND, "r1#6", Duration.ofSeconds(30));
            order.verify(lock).release(ROOM);
            order.verify(botScheduler).scheduleBots(ROOM);
        }
    }
}
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerResyncLockTest.java`:

```diff
 import com.mirboard.domain.lobby.room.RoomStatus;
 import com.mirboard.domain.lobby.room.TeamPolicy;
 import com.mirboard.infra.bot.GameProgressKick;
+import com.mirboard.infra.bot.TurnTimeoutScheduler;
 import com.mirboard.infra.ws.DesertionService;
 import com.mirboard.infra.ws.GameAbortService;
 import com.mirboard.infra.ws.GameEngineProvider;
 import com.mirboard.infra.ws.RoomActionLock;
 import com.mirboard.infra.ws.RoomPresence;
 import com.mirboard.infra.ws.RoomSeq;
+import java.time.Duration;
 import java.util.List;
 import java.util.Optional;
 import java.util.Set;
```

```diff
     private final GameEngine engine = mock(GameEngine.class);
     private final GameState state = mock(GameState.class);
     private final GameProgressKick kick = mock(GameProgressKick.class);
+    private final TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);
 
     private final RoomController controller = new RoomController(
             rooms, engines, seqs, mock(DesertionService.class), mock(RoomPresence.class),
-            mock(RoomChipStore.class), mock(GameAbortService.class), lock, kick);
+            mock(RoomChipStore.class), mock(GameAbortService.class), lock, kick, turnTimeout);
 
     private final AuthPrincipal me = new AuthPrincipal(ME, "me");
 
```

```diff
 
         verify(kick).kick(ROOM, ME);
     }
+
+    /**
+     * D-131 — 남은 턴 시간도 스냅샷과 같은 락 안에서 읽는다. 진행 경로는 저장·방송·다음 턴 데드라인 재무장을 모두 이 락 안에서
+     * 끝내므로, 락 안에서 읽은 값은 함께 읽은 상태의 턴 것이다(락 밖에서 읽으면 새 상태 + 이전 턴의 남은 시간이 나올 수 있다).
+     */
+    @Test
+    void the_turn_remaining_time_is_read_inside_the_lock_with_the_snapshot() {
+        givenGameInProgress();
+        when(lock.acquireWaiting(ROOM)).thenReturn(true);
+        when(engine.pendingSeats(state)).thenReturn(List.of(0));
+        when(turnTimeout.turnRemaining(any())).thenReturn(Optional.of(Duration.ofMillis(12_345)));
+
+        var res = controller.resync(ROOM, me);
+
+        assertThat(res.turnRemainingMs()).isEqualTo(12_345L);
+        InOrder order = inOrder(lock, engine, turnTimeout);
+        order.verify(lock).acquireWaiting(ROOM);
+        order.verify(engine).loadState();
+        order.verify(turnTimeout).turnRemaining(any());
+        order.verify(lock).release(ROOM);
+    }
+
+    /** 기다리는 좌석이 없으면(원카드 경쟁 창·끝난 매치) 걸린 데드라인이 있어도 발화해 봐야 아무 일이 없다 — 남은 시간이 아니다. */
+    @Test
+    void nobody_on_the_clock_means_no_remaining_time() {
+        givenGameInProgress();
+        when(lock.acquireWaiting(ROOM)).thenReturn(true);
+        when(engine.pendingSeats(state)).thenReturn(List.of());
+
+        var res = controller.resync(ROOM, me);
+
+        assertThat(res.turnRemainingMs()).isNull();
+        verify(turnTimeout, never()).turnRemaining(any());
+    }
+
+    /** 턴 제한이 꺼졌거나 지금 세대의 데드라인이 없으면 null — 클라는 세지 않는다. */
+    @Test
+    void no_armed_deadline_means_no_remaining_time() {
+        givenGameInProgress();
+        when(lock.acquireWaiting(ROOM)).thenReturn(true);
+        when(engine.pendingSeats(state)).thenReturn(List.of(0));
+        when(turnTimeout.turnRemaining(any())).thenReturn(Optional.empty());
+
+        assertThat(controller.resync(ROOM, me).turnRemainingMs()).isNull();
+    }
 }
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerLeaveTest.java`:

```diff
 import com.mirboard.domain.lobby.room.RoomStatus;
 import com.mirboard.domain.lobby.room.TeamPolicy;
 import com.mirboard.infra.bot.GameProgressKick;
+import com.mirboard.infra.bot.TurnTimeoutScheduler;
 import com.mirboard.infra.ws.DesertionService;
 import com.mirboard.infra.ws.GameAbortService;
 import com.mirboard.infra.ws.GameEngineProvider;
```

```diff
     private final RoomController controller = new RoomController(
             rooms, engines, mock(RoomSeq.class), desertion, mock(RoomPresence.class),
             mock(RoomChipStore.class), mock(GameAbortService.class), mock(RoomActionLock.class),
-            mock(GameProgressKick.class));
+            mock(GameProgressKick.class), mock(TurnTimeoutScheduler.class));
 
     private final AuthPrincipal me = new AuthPrincipal(ME, "me");
 
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerCreateDefaultsTest.java`:

```java
package com.mirboard.infra.rest.rooms;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.GameProgressKick;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameAbortService;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.infra.ws.RoomPresence;
import com.mirboard.infra.ws.RoomSeq;
import org.junit.jupiter.api.Test;

/**
 * D-131 — 방 만들기 요청에서 생략한 인원·턴 제한은 컨트롤러가 채우지 않고 그대로(null) 넘긴다. 생략 기본은 게임 선언이고
 * 그 판단은 {@code RoomService} 한 곳이다 — 예전에는 컨트롤러가 턴 제한 생략을 0(끔)으로 바꿔 넘겨, 서비스가 게임 선언을 쓸
 * 길이 없었다.
 */
class RoomControllerCreateDefaultsTest {

    private final RoomService rooms = mock(RoomService.class);
    private final RoomController controller = new RoomController(rooms, mock(GameEngineProvider.class),
            mock(RoomSeq.class), mock(DesertionService.class), mock(RoomPresence.class), mock(RoomChipStore.class),
            mock(GameAbortService.class), mock(RoomActionLock.class), mock(GameProgressKick.class),
            mock(TurnTimeoutScheduler.class));

    @Test
    void omitted_capacity_and_turn_limit_reach_the_service_as_omitted() {
        controller.create(new AuthPrincipal(10L, "me"),
                new RoomController.CreateRequest("방", "ONE_CARD", null, null, null, null, null, null));

        verify(rooms).createRoom(eq(10L), eq("방"), eq("ONE_CARD"), eq(TeamPolicy.SEQUENTIAL), eq(false),
                eq(RoomService.DEFAULT_TARGET_SCORE), isNull(), eq(RoomService.DEFAULT_STAKE), isNull());
    }

    @Test
    void sent_choices_pass_through_unchanged() {
        controller.create(new AuthPrincipal(10L, "me"),
                new RoomController.CreateRequest("방", "ONE_CARD", null, null, null, 60, null, 5));

        verify(rooms).createRoom(eq(10L), eq("방"), eq("ONE_CARD"), eq(TeamPolicy.SEQUENTIAL), eq(false),
                eq(RoomService.DEFAULT_TARGET_SCORE), eq(60), eq(RoomService.DEFAULT_STAKE), eq(5));
    }
}
```

`server/src/test/java/com/mirboard/domain/lobby/room/RoomServiceCreateDefaultsTest.java`:

```java
package com.mirboard.domain.lobby.room;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-131 — 방 만들기에서 인원·턴 제한을 <b>생략</b>하면 서버도 게임 선언(D-130 {@code defaultPlayers()}·
 * {@code defaultTurnSeconds()})을 쓴다. 예전에는 서버 생략 기본이 최대 인원·끔이라 모달의 처음 선택(원카드 4명·30초)과
 * 갈렸다 — 지금 클라는 둘 다 보내 실제로 갈리지 않았지만, 다른 클라(스크립트·구버전)가 생략하면 원카드가 6석·턴 제한 없이
 * 열렸다. 티츄·스컬킹의 선언은 예전 생략 기본(최대 인원·끔)과 같아 동작이 바뀌지 않는다. 정의는 저장소를 생성자로 받지만
 * 이 경로는 쓰지 않아 null(원카드는 모의 저장소)로 만든다.
 */
class RoomServiceCreateDefaultsTest {

    private final RoomRepository repository = mock(RoomRepository.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final RoomService service = new RoomService(repository, games, Clock.systemUTC(),
            mock(ApplicationEventPublisher.class), mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    RoomServiceCreateDefaultsTest() {
        register(new TichuGameDefinition(null, null, null, null));
        register(new SkullKingGameDefinition(null, null, null));
        register(new OneCardGameDefinition(mock(OneCardStateStore.class), Clock.systemUTC(), event -> { },
                GameStatus.AVAILABLE, 3_000, 1_000, 2_500, 1_000, 2_500));
        when(repository.findById(anyString())).thenReturn(Optional.of(new Room("r", "방", "ANY", 7L,
                RoomStatus.WAITING, 4, 1, List.of(7L), Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(),
                1000, 0, 0, Set.of())));
    }

    private void register(GameDefinition def) {
        when(games.require(def.id())).thenReturn(def);
    }

    private void createOmittingCapacityAndTurnLimit(String gameType) {
        service.createRoom(7L, "방", gameType, TeamPolicy.SEQUENTIAL, false, RoomService.DEFAULT_TARGET_SCORE,
                null, RoomService.DEFAULT_STAKE, null);
    }

    /** 저장소에 들어간 인원·턴 제한. 나머지 인자는 이 테스트의 관심이 아니다. */
    private void verifyStored(int capacity, int turnSeconds) {
        verify(repository).create(anyString(), anyLong(), anyString(), anyString(), eq(capacity), anyLong(),
                eq(TeamPolicy.SEQUENTIAL), anyBoolean(), anyInt(), eq(turnSeconds), anyInt());
    }

    @Test
    void one_card_omitted_choices_follow_its_declaration_four_seats_and_thirty_seconds() {
        createOmittingCapacityAndTurnLimit(OneCardGameDefinition.ID);

        verifyStored(4, 30);
    }

    @Test
    void tichu_omitted_choices_stay_four_seats_without_a_turn_limit() {
        createOmittingCapacityAndTurnLimit(TichuGameDefinition.ID);

        verifyStored(4, 0);
    }

    @Test
    void skull_king_omitted_choices_stay_eight_seats_without_a_turn_limit() {
        createOmittingCapacityAndTurnLimit(SkullKingGameDefinition.ID);

        verifyStored(8, 0);
    }

    /** 보낸 값이 이긴다 — 원카드를 6명·턴 제한 끔(0)으로 만들 수 있다. */
    @Test
    void explicit_choices_win_over_the_declaration() {
        service.createRoom(7L, "방", OneCardGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, 6);

        verifyStored(6, 0);
    }
}
```

`server/src/test/java/com/mirboard/infra/ws/GameStompControllerGuardTest.java`:

```diff
 package com.mirboard.infra.ws;
 
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.assertj.core.api.Assertions.assertThatCode;
 import static org.mockito.ArgumentMatchers.any;
 import static org.mockito.ArgumentMatchers.anyInt;
 import static org.mockito.ArgumentMatchers.anyString;
 import static org.mockito.ArgumentMatchers.eq;
 import static org.mockito.Mockito.doReturn;
+import static org.mockito.Mockito.doThrow;
+import static org.mockito.Mockito.inOrder;
 import static org.mockito.Mockito.mock;
 import static org.mockito.Mockito.never;
 import static org.mockito.Mockito.verify;
 import static org.mockito.Mockito.when;
 
+import ch.qos.logback.classic.Level;
 import com.fasterxml.jackson.databind.ObjectMapper;
 import com.mirboard.domain.game.core.GameAction;
 import com.mirboard.domain.game.core.GameEngine;
```

```diff
 import com.mirboard.infra.bot.BotScheduler;
 import com.mirboard.infra.bot.TurnTimeoutScheduler;
 import com.mirboard.infra.metrics.MirboardMetrics;
+import com.mirboard.testsupport.LogCapture;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
 import java.util.Set;
 import org.junit.jupiter.api.Test;
+import org.mockito.InOrder;
+import org.springframework.dao.QueryTimeoutException;
 
 /**
  * D-122 — 끝난 방(FINISHED)의 STOMP 액션은 적용하지 않는다. 게임 중립 가드(방 상태)라 강제
```

```diff
         verify(engine).apply(eq(state), eq(0), eq(new Poke(1)));
         verify(engine).saveState(state);
     }
+
+    /**
+     * D-131 — 다음 턴 데드라인은 방송 뒤, <b>락을 풀기 전에</b> 건다. resync 는 같은 락 안에서 상태와 남은 턴 시간을 함께
+     * 읽으므로, 락을 푼 뒤에 걸면 그 틈(락을 기다리던 resync 가 곧바로 들어오는 자리)에서 새 상태 + 이전 턴의 남은 시간이
+     * 나갔다. 봇 루프는 락을 푼 뒤에 건다 — 쥔 채 걸면 루프가 이 락과 부딪쳐 재시도한다.
+     */
+    @Test
+    void the_next_turn_deadline_is_armed_before_the_lock_is_released() {
+        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
+        engineAcceptsAnything();
+
+        controller.onAction("r1", Map.of("n", 1), ME);
+
+        InOrder order = inOrder(broadcaster, turnTimeout, lock, botScheduler);
+        order.verify(broadcaster).broadcast(eq("r1"), any(), any());
+        order.verify(turnTimeout).onTurnAdvanced("r1");
+        order.verify(lock).release("r1");
+        order.verify(botScheduler).scheduleBots("r1");
+    }
+
+    /**
+     * D-131 — 락 안으로 옮긴 재무장이 던져도(Redis 순간 장애) 락은 풀리고 봇 루프는 걸린다. 예전 순서(봇 → 재무장)에서는
+     * 재무장이 실패해도 봇이 걸렸다 — 그대로 두면 사람이 낸 직후 봇 차례로 넘어간 판이, 화면만 보고 기다리는 동안 아무것도
+     * 진행 킥(resync·구독)을 부르지 않아 멈췄다. 탈주 계속 경로({@code DesertionService})와 같이 잡아 ERROR(스택 포함)로 남긴다.
+     */
+    @Test
+    void a_failed_turn_rearm_still_releases_the_lock_and_schedules_bots() {
+        when(roomService.getRoom("r1")).thenReturn(room(RoomStatus.IN_GAME));
+        engineAcceptsAnything();
+        doThrow(new QueryTimeoutException("redis blip")).when(turnTimeout).onTurnAdvanced("r1");
+
+        try (LogCapture logs = LogCapture.of(GameStompController.class)) {
+            assertThatCode(() -> controller.onAction("r1", Map.of("n", 1), ME)).doesNotThrowAnyException();
+
+            verify(lock).release("r1");
+            verify(botScheduler).scheduleBots("r1");
+            assertThat(logs.events()).anySatisfy(event -> {
+                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
+                assertThat(event.getFormattedMessage()).contains("Turn rearm after action failed").contains("roomId=r1");
+                assertThat(event.getThrowableProxy()).isNotNull();
+            });
+        }
+    }
 }
```

`server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java`:

```diff
             order.verify(engine).saveState(next);
             order.verify(matchProgress).advance(eq(engine), any(), eq(next), eq(List.of(event)));
             order.verify(broadcaster).broadcast("r1", List.of(event), List.of(10L, 20L));
+            // D-131 — 재무장은 락 안(resync 가 같은 락 안에서 남은 턴 시간을 읽는다), 봇 루프는 락을 푼 뒤.
+            order.verify(turnTimeout).onTurnAdvanced("r1");
             order.verify(lock).release("r1");
             order.verify(botScheduler).scheduleBots("r1");
-            order.verify(turnTimeout).onTurnAdvanced("r1");
         }
 
         /**
```

`server/src/test/java/com/mirboard/infra/ws/DesertionServiceTest.java`:

```diff
 import static org.mockito.ArgumentMatchers.anyInt;
 import static org.mockito.ArgumentMatchers.anyLong;
 import static org.mockito.ArgumentMatchers.eq;
+import static org.mockito.Mockito.doThrow;
+import static org.mockito.Mockito.inOrder;
 import static org.mockito.Mockito.mock;
 import static org.mockito.Mockito.never;
 import static org.mockito.Mockito.verify;
 import static org.mockito.Mockito.when;
 
+import ch.qos.logback.classic.Level;
 import com.mirboard.domain.game.core.GameEngine;
 import com.mirboard.domain.lobby.auth.BotUserRegistry;
 import com.mirboard.domain.lobby.room.Room;
 import com.mirboard.domain.lobby.room.RoomService;
 import com.mirboard.domain.lobby.room.RoomStatus;
 import com.mirboard.domain.lobby.room.TeamPolicy;
+import com.mirboard.testsupport.LogCapture;
 import java.util.List;
 import java.util.Set;
 import org.junit.jupiter.api.Test;
+import org.mockito.InOrder;
+import org.springframework.dao.QueryTimeoutException;
 
 /**
  * D-98 이후 본 서비스는 게임을 모른다 — 여기서 검증하는 것은 <b>인프라 절차</b>다:
```

```diff
         verify(lock).release("r1");
     }
 
+    /**
+     * D-131 — 남은 사람끼리 계속하면 다음 턴 데드라인을 <b>락을 풀기 전에</b> 건다(resync 가 같은 락 안에서 상태와 남은 턴
+     * 시간을 함께 읽는다). 봇 루프는 락을 푼 뒤 — 호출자가 락을 쥔 채 걸면 루프가 이 락과 부딪쳐 재시도한다.
+     */
+    @Test
+    void continued_desertion_rearms_the_turn_inside_the_lock_and_bots_after_it() {
+        List<Long> players = List.of(10L, 20L, 30L, 40L);
+        when(bots.isBot(10L)).thenReturn(false);
+        when(lock.acquireWaiting("r1")).thenReturn(true);
+        when(roomService.getRoom("r1")).thenReturn(inGame(players));
+        when(engines.forRoom(any())).thenReturn(engine);
+        when(engine.desert(eq(0), eq(10L), any()))
+                .thenReturn(GameEngine.DesertOutcome.MATCH_CONTINUES);
+
+        service.processDesertion("r1", 10L);
+
+        InOrder order = inOrder(broadcaster, turnTimeout, lock, botScheduler);
+        order.verify(broadcaster).broadcast(eq("r1"), any(), eq(players));
+        order.verify(turnTimeout).onTurnAdvanced("r1");
+        order.verify(lock).release("r1");
+        order.verify(botScheduler).scheduleBots("r1");
+    }
+
+    /**
+     * D-131 — 락 안의 턴 재무장이 던져도(Redis 순간 장애) 락을 푼 뒤의 봇 루프 재무장을 건너뛰지 않고, 이미 저장·방송된
+     * 탈주를 처리 실패(false·"Desertion processing error")로 남기지 않는다. 잡지 않으면 남은 사람끼리 계속하는 판이 봇 차례에서
+     * 멈췄다(호출자는 false 를 받아도 D-122 가드로 좌석을 그대로 둔다 — 진행 중 매치라서).
+     */
+    @Test
+    void a_failed_turn_rearm_after_a_continued_desertion_still_schedules_bots_and_counts_as_processed() {
+        List<Long> players = List.of(10L, 20L, 30L, 40L);
+        when(bots.isBot(10L)).thenReturn(false);
+        when(lock.acquireWaiting("r1")).thenReturn(true);
+        when(roomService.getRoom("r1")).thenReturn(inGame(players));
+        when(engines.forRoom(any())).thenReturn(engine);
+        when(engine.desert(eq(0), eq(10L), any()))
+                .thenReturn(GameEngine.DesertOutcome.MATCH_CONTINUES);
+        doThrow(new QueryTimeoutException("redis blip")).when(turnTimeout).onTurnAdvanced("r1");
+
+        try (LogCapture logs = LogCapture.of(DesertionService.class)) {
+            boolean processed = service.processDesertion("r1", 10L);
+
+            assertThat(processed).isTrue();
+            verify(lock).release("r1");
+            verify(botScheduler).scheduleBots("r1");
+            assertThat(logs.events()).anySatisfy(event -> {
+                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
+                assertThat(event.getFormattedMessage()).contains("Turn rearm after desertion failed");
+                assertThat(event.getThrowableProxy()).isNotNull();
+            });
+        }
+    }
+
     /** 엔진이 탈주로 보지 않으면(티츄: 리매치 대기 방, D-82) 매치를 건드리지 않는다. */
     @Test
     void desertion_declined_by_game_is_noop() {
```

`server/src/test/java/com/mirboard/domain/game/core/RoomCreationDefaultsTest.java`:

```diff
  * D-130 — 방 만들기의 처음 선택(인원·턴 제한)도 게임이 선언한다. 기본은 지금까지의 동작 그대로 — 인원은 최대, 턴 제한은
  * 끔 — 이라 티츄·스컬킹은 한 줄도 바꾸지 않는다. 원카드만 4명·30초(설계 §3.1 의 기본 4, 버티기 대응 — 사용자 결정).
  *
- * <p>서버의 capacity 생략 기본({@code RoomService} — maxPlayers)은 그대로다. 인원 가변 게임은 클라가 늘 capacity 를
- * 보내므로 이 값은 클라 모달의 처음 선택으로만 쓰인다. 정의는 저장소를 생성자로 받지만 이 메서드는 쓰지 않으므로 null
+ * <p>D-131 — 이 값은 서버의 생략 기본이기도 하다({@code RoomService} — {@code RoomServiceCreateDefaultsTest}). 정의는
+ * 저장소를 생성자로 받지만 이 메서드는 쓰지 않으므로 null
  * (원카드는 모의 저장소)로 인스턴스를 만든다({@link RematchSupportTest} 와 같은 방식).
  */
 class RoomCreationDefaultsTest {
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomResyncIntegrationTest.java`:

```diff
 package com.mirboard.infra.rest.rooms;
 
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.hamcrest.Matchers.nullValue;
 import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
 import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
 import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

```diff
                 .andExpect(jsonPath("$.tableView.matchScores.A").value(0))
                 .andExpect(jsonPath("$.tableView.matchScores.B").value(0))
                 .andExpect(jsonPath("$.privateHand.seat").value(0))
-                .andExpect(jsonPath("$.privateHand.cards.length()").value(8));
+                .andExpect(jsonPath("$.privateHand.cards.length()").value(8))
+                // D-131 — 턴 제한이 꺼진 방(생략 — 티츄 선언 0)은 남은 턴 시간이 없다.
+                .andExpect(jsonPath("$.turnRemainingMs").value(nullValue()));
+    }
+
+    /**
+     * D-131 — 턴 제한이 있는 방의 resync 는 지금 턴의 남은 시간을 싣는다(지금 세대 턴 데드라인 − 지금). 매치 시작(라운드
+     * 스타터)이 건 데드라인이라 턴 제한 이하·0 초과다. 클라는 이 값으로 카운트다운을 맞춘다 — 재접속 직후에도.
+     */
+    @Test
+    void resync_carries_the_turn_time_left_when_the_room_has_a_turn_limit() throws Exception {
+        Map<String, String> tokens = registerAndLoginAll(
+                List.of("rs6_alice", "rs6_bob", "rs6_charlie", "rs6_dave"));
+        String roomId = createRoomAndJoinAll(tokens,
+                Map.of("name", "timed-room", "gameType", "TICHU", "turnSeconds", 30));
+
+        MvcResult res = mockMvc.perform(get("/api/rooms/" + roomId + "/resync")
+                        .header("Authorization", "Bearer " + tokens.get("rs6_alice")))
+                .andExpect(status().isOk())
+                .andReturn();
+
+        long left = objectMapper.readTree(res.getResponse().getContentAsString()).get("turnRemainingMs").asLong();
+        assertThat(left).isPositive().isLessThanOrEqualTo(30_000L);
     }
 
     @Test
```

```diff
     }
 
     private String createRoomOnly(String token) throws Exception {
-        var body = objectMapper.writeValueAsString(
-                Map.of("name", "resync-room", "gameType", "TICHU"));
+        return createRoomOnly(token, Map.of("name", "resync-room", "gameType", "TICHU"));
+    }
+
+    private String createRoomOnly(String token, Map<String, Object> request) throws Exception {
+        var body = objectMapper.writeValueAsString(request);
         MvcResult created = mockMvc.perform(post("/api/rooms")
                         .header("Authorization", "Bearer " + token)
                         .contentType(MediaType.APPLICATION_JSON)
```

```diff
     }
 
     private String createRoomAndJoinAll(Map<String, String> tokens) throws Exception {
+        return createRoomAndJoinAll(tokens, Map.of("name", "resync-room", "gameType", "TICHU"));
+    }
+
+    private String createRoomAndJoinAll(Map<String, String> tokens, Map<String, Object> request) throws Exception {
         List<String> ordered = List.copyOf(tokens.keySet());
         String host = ordered.get(0);
-        String roomId = createRoomOnly(tokens.get(host));
+        String roomId = createRoomOnly(tokens.get(host), request);
         for (int i = 1; i < ordered.size(); i++) {
             mockMvc.perform(post("/api/rooms/" + roomId + "/join")
                             .header("Authorization", "Bearer " + tokens.get(ordered.get(i))))
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomCapacityIntegrationTest.java`:

```diff
     void variable_game_without_capacity_defaults_to_max_players() throws Exception {
         String token = registerAndLogin("cap_default_user", "validpass1");
 
-        // 현행 호환 — capacity 미지정이면 def.maxPlayers().
+        // D-131 — capacity 미지정이면 게임 선언 def.defaultPlayers()(스컬킹은 최대 8 과 같다 — 예전 생략 기본 그대로).
         mockMvc.perform(create(token, Map.of(
                         "name", "기본 인원 방", "gameType", VARIABLE_GAME)))
                 .andExpect(status().isCreated())
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.TurnRemainingTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerResyncLockTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerLeaveTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerCreateDefaultsTest" --tests "com.mirboard.domain.lobby.room.RoomServiceCreateDefaultsTest" --tests "com.mirboard.infra.ws.GameStompControllerGuardTest" --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest" --tests "com.mirboard.infra.ws.DesertionServiceTest" --tests "com.mirboard.domain.game.core.RoomCreationDefaultsTest"`
Expected: FAIL — `compileTestJava` 실패(`17 errors`): `TurnRemainingTest` 의 `error: cannot find symbol`(`turnRemaining`·`remaining` 없음),
`RoomControllerCreateDefaultsTest`·`RoomControllerLeaveTest`·`RoomControllerResyncLockTest` 의 `constructor RoomController in class RoomController cannot be applied to given types;`,
`RoomServiceCreateDefaultsTest.java:58: error: incompatible types: <null> cannot be converted to int`, `turnRemainingMs()` 없음. 동작 RED 는 스파이크에서 API 만
넣은 중간 트리로 확인했다 — 순서 테스트 4건이 `VerificationInOrderFailure … Wanted but not invoked: roomActionLock.release("r1")`(재무장이 락 해제 뒤였다).

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java`:

```diff
 import java.time.Duration;
 import java.util.Collections;
 import java.util.List;
+import java.util.Optional;
 import org.springframework.beans.factory.annotation.Qualifier;
 import org.springframework.data.redis.core.StringRedisTemplate;
 import org.springframework.data.redis.core.script.RedisScript;
```

```diff
     }
 
     /**
+     * D-131 — 그 항목이 걸려 있으면 만기까지 남은 시간(이미 지났으면 0), 없으면 empty. pop 된 항목·취소된 항목은 없는 것이다.
+     * 읽기만 한다(ZSCORE).
+     */
+    public Optional<Duration> remaining(String kind, String member) {
+        Double dueAt = redis.opsForZSet().score(key(kind), member);
+        if (dueAt == null) {
+            return Optional.empty();
+        }
+        return Optional.of(Duration.ofMillis(Math.max(0L, dueAt.longValue() - clock.millis())));
+    }
+
+    /**
      * 만료분을 원자적으로 pop. 반환된 항목은 <b>이 인스턴스가 단독 소유</b>하므로
      * 호출자가 반드시 처리해야 한다(다시 큐에 없음).
      */
```

`server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java`:

```diff
  * 실행이 아니라 Redis 데드라인 큐의 지연 실행.
  *
  * <p>매 액션 적용 후 (인간/봇/타임아웃) {@link #onTurnAdvanced(String)} 가 호출되어
- * per-room generation 을 증가시키고 새 타이머를 (re)스케줄한다. 발화된 task 는
+ * per-room generation 을 증가시키고 새 타이머를 (re)스케줄한다. D-131 — 방 액션 락을 쥔 진행 경로(컨트롤러·봇·두
+ * 스케줄러·탈주)는 락을 풀기 전에 부른다 — resync 가 같은 락 안에서 상태와 남은 턴 시간({@link #turnRemaining})을 함께
+ * 읽기 때문이다. 락 밖에서 부르는 곳은 매치 시작(라운드 스타터 — 시작 상태 저장 직후)뿐이다. 발화된 task 는
  * 캡처한 generation 이 현재와 다르면 abort — 그 사이 누군가 행동했다는 뜻
  * (중복 자동행동 방지). {@link RoomActionLock} 2초 TTL 로 human/bot/timeout 3자
  * 경합을 직렬화.
```

```diff
     }
 
     /**
+     * D-131 — 지금 턴의 남은 시간: 지금 세대의 턴 데드라인까지(이미 지났으면 0 — 폴러가 곧 꺼낸다). 턴 제한이 꺼졌거나
+     * IN_GAME 이 아니거나 지금 세대의 데드라인이 없으면 empty. 이전 세대의 항목은 발화해도 버려지므로 남은 시간이 아니다.
+     *
+     * <p>호출자(resync)는 방 액션 락 안에서 상태와 함께 읽는다 — 진행 경로가 저장·방송·재무장을 모두 그 락 안에서 끝내므로
+     * 락 안에서 읽은 값은 함께 읽은 상태의 턴 것이다. 기다리는 좌석이 있는지는 상태를 아는 호출자가 본다.
+     */
+    public Optional<Duration> turnRemaining(Room room) {
+        if (room.turnSeconds() <= 0 || room.status() != RoomStatus.IN_GAME) {
+            return Optional.empty();
+        }
+        long gen = generations.current(room.roomId());
+        return deadlines.remaining(KIND, member(room.roomId(), gen));
+    }
+
+    /**
      * D-122 — 걸려 있는 턴 데드라인을 취소한다(재무장 없음). 매치를 액션 경로 밖에서 끝내는
      * 쪽 — 탈주 MATCH_ENDED, 호스트/어드민 강제 종료 — 이 부른다. generation 을 올리므로 이미
      * 폴러가 집어 간 항목도 발화 시점에 버려진다. 발화 쪽에도 방 상태 가드가 있으니 이것은
```

```diff
             acted = true;
             log.info("Turn timeout auto-action: roomId={} seat={} action={}",
                     roomId, seat, action.getClass().getSimpleName());
+            // 다음 턴 타이머 재스케줄. D-131 — 락 안에서(resync 가 같은 락 안에서 상태와 남은 턴 시간을 함께 읽는다).
+            onTurnAdvanced(roomId);
         } catch (RuntimeException e) {
             log.error("TurnTimeoutScheduler error in room {}: {}", roomId, e.getMessage(), e);
         } finally {
             lock.release(roomId);
         }
         if (acted) {
-            // 봇이 이어받을 수 있으면 진행 + 다음 턴 타이머 재스케줄.
+            // 봇이 이어받을 수 있으면 진행 — 락을 푼 뒤에(쥔 채 걸면 루프가 이 락과 부딪쳐 재시도한다).
             botScheduler.scheduleBots(roomId);
-            onTurnAdvanced(roomId);
         }
     }
 
```

`server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java`:

```diff
  * 탄다(저장 → {@code matchProgress.advance} → 브로드캐스트 → 봇·타이머 재무장).
  *
  * <p><b>마지막 방어선은 락 안의 {@code timer} 재확인이다(D-128).</b> 세대 번호는 대부분의 낡은 발화를
- * 거르지만, 진행 경로(컨트롤러·매치가 이어지는 탈주·두 스케줄러)는 락을 푼 <em>뒤에</em> 세대를 올리므로 그 틈이 있다 — 틈에
+ * 거르지만, 진행 경로(컨트롤러·매치가 이어지는 탈주·두 스케줄러)는 락을 푼 <em>뒤에</em> 세대를 올렸으므로 그 틈이 있었다 — 틈에
  * 만기된 옛 타이머는 락 앞뒤의 세대 검사를 모두 통과해 이미 넘어간 상태를 만난다. 그래서 락 안에서 상태를 읽은 뒤
  * {@code timer(state)} 를 다시 묻는다: 비어 있으면 낡은 발화라 멈추고, 아직 남았으면 같은 세대로 그 시간 뒤에
- * 다시 걸고 멈추며, 0 이하일 때만 {@code onTimer} 를 적용한다. (턴 타임아웃에도 같은 틈이 있다 —
- * 후속 과제로 남겼다(D-128).)
+ * 다시 걸고 멈추며, 0 이하일 때만 {@code onTimer} 를 적용한다. (D-131 — 락을 쥔 진행 경로는 이제 락을 풀기 전에 세대를
+ * 올린다(resync 의 남은 턴 시간 때문). 틈은 락 밖에서 재무장하는 매치 시작(라운드 스타터)에만 남고, 이 재확인은 그대로
+ * 마지막 방어선이다.)
  *
  * <p>게임을 모른다 — 무엇이 언제 일어나는지는 엔진이 답한다.
  */
```

```diff
             advanced = true;
             log.info("Engine timer fired: roomId={} phase={} events={}",
                     roomId, engine.phaseName(newState), outbound.size());
+            // 턴·엔진 타이머를 새 상태로 다시 건다. D-131 — 락 안에서(resync 가 같은 락 안에서 남은 턴 시간을 읽는다).
+            turnTimeout.onTurnAdvanced(roomId);
         } catch (RuntimeException e) {
             log.error("EngineTimerScheduler error in room {}: {}", roomId, e.getMessage(), e);
         } finally {
             lock.release(roomId);
         }
         if (advanced) {
-            // 다음 차례가 봇이면 이어받고, 턴·엔진 타이머를 새 상태로 다시 건다.
+            // 다음 차례가 봇이면 이어받는다 — 락을 푼 뒤에.
             botScheduler.scheduleBots(roomId);
-            turnTimeout.onTurnAdvanced(roomId);
         }
     }
 
```

`server/src/main/java/com/mirboard/infra/ws/GameStompController.java`:

```diff
  *   <li>{@link GameEngine#apply} 호출 — 검증/룰 적용.</li>
  *   <li>새 상태 저장 + {@link MatchProgressService#advance} 로 라운드/매치 진행 →
  *       발생 이벤트들을 {@link GameEventBroadcaster} 로 한 번에 분기 발행.</li>
- *   <li>락 해제 후 봇/타임아웃 재스케줄.</li>
+ *   <li>다음 턴 데드라인 재무장(락 안, D-131) → 락 해제 후 봇 재스케줄.</li>
  * </ol>
  *
  * <p>D-98: 과거 {@code @Payload TichuAction} 으로 타입이 고정돼 있어 티츄 외의 게임은
```

```diff
             matchProgress.advance(engine, room, result.newState(), outbound);
 
             broadcaster.broadcast(roomId, outbound, room.playerIds());
+            // Phase 13D — 다음 턴 타임아웃 타이머 (re)스케줄 (turnSeconds=0 이면 취소만).
+            // D-131 — 락을 풀기 전에 건다. resync 는 같은 락 안에서 상태와 남은 턴 시간을 함께 읽으므로, 락을 푼 뒤에
+            // 걸면 그 틈(락을 기다리던 resync 가 곧바로 들어오는 자리)에서 새 상태 + 이전 턴의 남은 시간이 나갔다.
+            // 실패해도(Redis 순간 장애) 액션은 이미 저장·방송됐다 — 잡아서 아래 봇 루프 스케줄을 건너뛰지 않는다(탈주 계속
+            // 경로와 같다). 던지게 두면 봇 차례로 넘어간 판이 다음 resync·구독 전까지 멈췄다.
+            try {
+                turnTimeout.onTurnAdvanced(roomId);
+            } catch (RuntimeException e) {
+                log.error("Turn rearm after action failed: roomId={} err={}", roomId, e.toString(), e);
+            }
         } finally {
             lock.release(roomId);
         }
-        // 락 해제 후 봇 차례면 비동기로 봇 액션 트리거.
+        // 락 해제 후 봇 차례면 비동기로 봇 액션 트리거(쥔 채 걸면 루프가 이 락과 부딪쳐 재시도한다).
         botScheduler.scheduleBots(roomId);
-        // Phase 13D — 다음 턴 타임아웃 타이머 (re)스케줄 (turnSeconds=0 이면 no-op).
-        turnTimeout.onTurnAdvanced(roomId);
     }
 
     /** 지금 방 상태. 방이 사라졌으면 null. */
```

`server/src/main/java/com/mirboard/infra/ws/DesertionService.java`:

```diff
                     roomId, deserterUserId);
             return false;
         }
-        // MATCH_CONTINUES 재무장은 락 해제 후 — BotScheduler 계약(호출자 락 비점유).
+        // MATCH_CONTINUES 의 봇 재무장은 락 해제 후 — BotScheduler 계약(호출자 락 비점유). 턴 데드라인은 락 안(D-131).
         boolean needsReschedule = false;
         boolean processed;
         try {
```

```diff
             } else {
                 // D-102/D-104 — 남은 사람끼리 계속. 방은 IN_GAME 유지, 다음 차례가
                 // 사람이면 타이머, 봇이면 봇 루프가 이어받도록 재무장한다.
+                // D-131 — 턴 데드라인은 락을 풀기 전에 건다(resync 가 같은 락 안에서 남은 턴 시간을 읽는다). 실패해도 봇
+                // 루프 재무장(락 해제 뒤)을 건너뛰지 않고, 이미 저장·방송된 탈주를 처리 실패로 남기지 않는다(호출자는 D-122
+                // 가드로 좌석을 그대로 둔다 — 진행 중 매치라서).
+                try {
+                    turnTimeout.onTurnAdvanced(roomId);
+                } catch (RuntimeException e) {
+                    log.error("Turn rearm after desertion failed: roomId={} err={}", roomId, e.toString(), e);
+                }
                 needsReschedule = true;
             }
             log.warn("Desertion processed: roomId={} deserterUserId={} seat={} outcome={}",
```

```diff
         }
         if (needsReschedule) {
             botScheduler.scheduleBots(roomId);
-            turnTimeout.onTurnAdvanced(roomId);
         }
         return processed;
     }
```

`server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java`:

```diff
 import com.mirboard.domain.lobby.room.RoomStatus;
 import com.mirboard.domain.lobby.room.TeamPolicy;
 import com.mirboard.infra.bot.GameProgressKick;
+import com.mirboard.infra.bot.TurnTimeoutScheduler;
 import com.mirboard.infra.ws.DesertionService;
 import com.mirboard.infra.ws.GameAbortService;
 import com.mirboard.infra.ws.GameEngineProvider;
```

```diff
 import com.mirboard.infra.ws.RoomSeq;
 import jakarta.validation.Valid;
 import jakarta.validation.constraints.NotBlank;
+import java.time.Duration;
 import java.util.ArrayList;
 import java.util.List;
 import org.slf4j.Logger;
```

```diff
     private final GameAbortService aborts;
     private final RoomActionLock lock;
     private final GameProgressKick kick;
+    private final TurnTimeoutScheduler turnTimeout;
 
     public RoomController(RoomService rooms,
                           GameEngineProvider engines,
```

```diff
                           RoomChipStore chipStore,
                           GameAbortService aborts,
                           RoomActionLock lock,
-                          GameProgressKick kick) {
+                          GameProgressKick kick,
+                          TurnTimeoutScheduler turnTimeout) {
         this.rooms = rooms;
         this.engines = engines;
         this.seqs = seqs;
```

```diff
         this.aborts = aborts;
         this.lock = lock;
         this.kick = kick;
+        this.turnTimeout = turnTimeout;
     }
 
     @GetMapping
```

```diff
         int targetScore = req.targetScore() == null
                 ? com.mirboard.domain.lobby.room.RoomService.DEFAULT_TARGET_SCORE
                 : req.targetScore();
-        int turnSeconds = req.turnSeconds() == null
-                ? com.mirboard.domain.lobby.room.RoomService.DEFAULT_TURN_SECONDS
-                : req.turnSeconds();
         int stake = req.stake() == null
                 ? com.mirboard.domain.lobby.room.RoomService.DEFAULT_STAKE
                 : req.stake();
-        // D-99 — capacity 는 선택. null 이면 RoomService 가 def.maxPlayers() 로 채운다.
+        // D-131 — 인원·턴 제한은 선택. 생략(null)은 그대로 넘긴다 — RoomService 가 게임 선언
+        // (def.defaultPlayers()·def.defaultTurnSeconds())으로 채운다(D-99 의 capacity 와 같은 자리).
         return rooms.createRoom(me.userId(), req.name(), req.gameType(), policy,
-                fillWithBots, targetScore, turnSeconds, stake, req.capacity());
+                fillWithBots, targetScore, req.turnSeconds(), stake, req.capacity());
     }
 
     /** Phase 8C — WAITING 방에서 호스트가 팀 정책 변경. */
```

```diff
                     seqs.current(roomId),
                     engine.publicView(state),
                     // 관전자는 손패 없음 — 공개 뷰만 받음. 비공개 상태가 없는 게임도 null.
-                    privateSeat >= 0 ? engine.privateView(state, privateSeat).orElse(null) : null);
+                    privateSeat >= 0 ? engine.privateView(state, privateSeat).orElse(null) : null,
+                    turnRemainingMs(room, engine, state));
         } finally {
             if (locked) {
                 lock.release(roomId);
```

```diff
                 snap.tableView(),
                 snap.privateHand(),
                 disconnectedSeats(room, me.userId()),
-                chipStore.stacks(roomId)); // D-82 — 방 칩 스택(입장/재접속 시 즉시 표시).
-    }
-
-    /** 락 안에서 함께 읽어야 하는 resync 부분 — 게임 상태에서 나온 것과 그 시점의 순번. */
-    private record Snapshot(String phase, long eventSeq, Object tableView, Object privateHand) {
+                chipStore.stacks(roomId), // D-82 — 방 칩 스택(입장/재접속 시 즉시 표시).
+                snap.turnRemainingMs());
+    }
+
+    /** 락 안에서 함께 읽어야 하는 resync 부분 — 게임 상태에서 나온 것과 그 시점의 순번·남은 턴 시간. */
+    private record Snapshot(String phase, long eventSeq, Object tableView, Object privateHand,
+                            Long turnRemainingMs) {
+    }
+
+    /**
+     * D-131 — 함께 읽은 상태의 턴이 끝나기까지 남은 시간(ms). 기다리는 좌석이 없으면(경쟁 창·끝난 매치 — 걸린 데드라인이
+     * 발화해도 아무 일이 없다) null, 턴 제한이 꺼졌거나 지금 세대의 데드라인이 없어도 null. 게임을 모른다 — 누가 기다리는지는
+     * 엔진 포트가, 데드라인은 턴 타임아웃 스케줄러가 답한다. 락 안에서 부른다: 진행 경로가 저장·방송·다음 턴 데드라인 재무장을
+     * 모두 이 락 안에서 끝내므로, 여기서 읽은 값은 함께 읽은 상태의 턴 것이다.
+     */
+    private Long turnRemainingMs(Room room, GameEngine engine, GameState state) {
+        if (engine.pendingSeats(state).isEmpty()) {
+            return null;
+        }
+        return turnTimeout.turnRemaining(room).map(Duration::toMillis).orElse(null);
     }
 
     /**
```

```diff
                                 TeamPolicy teamPolicy,
                                 Boolean fillWithBots,
                                 Integer targetScore,
+                                // D-131 — null 이면 GameDefinition.defaultTurnSeconds().
                                 Integer turnSeconds,
                                 Integer stake,
-                                // D-99 — 방 인원. null 이면 GameDefinition.maxPlayers().
+                                // D-99 — 방 인원. D-131 — null 이면 GameDefinition.defaultPlayers().
                                 Integer capacity) {
     }
 
```

```diff
             Object privateHand,
             List<Integer> disconnectedSeats,
             // D-82 — 방 단위 테이블 칩 스택(userId→칩). 내기 없는 방은 빈 맵.
-            java.util.Map<Long, Long> chips) {
+            java.util.Map<Long, Long> chips,
+            // D-131 — 지금 턴의 남은 시간(ms, 0 이상). 턴 제한 끔·기다리는 좌석 없음·걸린 데드라인 없음이면 null.
+            Long turnRemainingMs) {
     }
 }
```

`server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java`:

```diff
      * 방 메타라 TichuMatchState 까진 흘리지 않고 스케줄러가 room 으로 참조.
      * D-81 — `stake` 판돈(가상 칩, 0=내기 없음). 허용값 외/음수는 거절하고,
      * stake>0 이면 봇 채우기 금지(봇=무한 잔액 → 칩 파밍 방지).
-     * D-99 — `requestedCapacity` 방 인원. **null 이면 `def.maxPlayers()`**(현행 호환).
-     * 게임이 정한 `minPlayers()..maxPlayers()` 를 벗어나면 InvalidCapacityException.
-     * 티츄는 min=max=4 라 4 또는 미지정만 통과한다.
+     * D-99 — `requestedCapacity` 방 인원. 게임이 정한 `minPlayers()..maxPlayers()` 를 벗어나면
+     * InvalidCapacityException. 티츄는 min=max=4 라 4 또는 미지정만 통과한다.
+     * D-131 — 생략(null)한 인원·턴 제한은 게임 선언 `def.defaultPlayers()`·`def.defaultTurnSeconds()` 다(방 만들기 모달의
+     * 처음 선택과 같은 값, D-130). 티츄·스컬킹은 선언이 예전 생략 기본(최대 인원·끔)과 같아 동작이 바뀌지 않는다.
      */
     public Room createRoom(long hostUserId, String name, String gameType,
                            TeamPolicy teamPolicy, boolean fillWithBots, int targetScore,
-                           int turnSeconds, int stake, Integer requestedCapacity) {
+                           Integer requestedTurnSeconds, int stake, Integer requestedCapacity) {
         GameDefinition def = games.require(gameType);
         if (def.status() != GameStatus.AVAILABLE) {
             throw new com.mirboard.domain.game.core.GameNotFoundException(gameType);
```

```diff
             throw new StakedRoomNoBotsException();
         }
         requireSupportedRoomOptions(def, targetScore, stake);
-        int capacity = requestedCapacity == null ? def.maxPlayers() : requestedCapacity;
+        int capacity = requestedCapacity == null ? def.defaultPlayers() : requestedCapacity;
+        int turnSeconds = requestedTurnSeconds == null ? def.defaultTurnSeconds() : requestedTurnSeconds;
         if (capacity < def.minPlayers() || capacity > def.maxPlayers()) {
             throw new InvalidCapacityException(capacity, def.minPlayers(), def.maxPlayers());
         }
```

`server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java`:

```diff
      * D-130 — 방 만들기 모달이 처음 고르는 인원. <b>기본은 {@link #maxPlayers()}</b> — 지금까지의 동작 그대로라 재정의하지
      * 않은 게임은 바뀌지 않는다. {@code minPlayers()..maxPlayers()} 안이어야 한다.
      *
-     * <p>클라의 처음 선택일 뿐이다 — 서버의 capacity 생략 기본({@code RoomService}, maxPlayers)은 따로다. 인원 가변
-     * 게임은 클라가 늘 capacity 를 보내므로 둘이 실제로 갈리지 않는다.
+     * <p>D-131 — 서버의 capacity 생략 기본({@code RoomService})도 이 값이다. 클라 모달의 처음 선택과 요청에서 인원을 뺀
+     * 방이 같은 인원으로 열린다.
      */
     default int defaultPlayers() {
         return maxPlayers();
     }
 
     /**
-     * D-130 — 방 만들기 모달이 처음 고르는 턴 제한(초). <b>기본은 0(끔)</b> — 서버 기본
-     * ({@code RoomService.DEFAULT_TURN_SECONDS})과 같다. 모달의 선택지(0·30·60·90) 중 하나여야 처음부터 선택돼 보인다.
+     * D-130 — 방 만들기 모달이 처음 고르는 턴 제한(초). <b>기본은 0(끔)</b> — {@code RoomService.DEFAULT_TURN_SECONDS} 와
+     * 같다. 모달의 선택지(0·30·60·90) 중 하나여야 처음부터 선택돼 보인다. D-131 — 요청에서 턴 제한을 뺀 방도 이 값으로 열린다
+     * ({@code RoomService} 의 생략 기본).
      */
     default int defaultTurnSeconds() {
         return 0;
```

`client/src/features/lobby/CreateRoomModal.tsx`:

```diff
           (_, i) => game.minPlayers + i,
         )
       : [];
-  // D-130 — 게임이 선언한 인원을 처음 선택으로(원카드 4, 나머지는 maxPlayers). 서버의 capacity 생략 기본(maxPlayers)과
-  // 다를 수 있지만 인원 가변 게임에서는 늘 capacity 를 보내므로 실제로 갈리지 않는다.
+  // D-130 — 게임이 선언한 인원을 처음 선택으로(원카드 4, 나머지는 maxPlayers). D-131 — 서버의 capacity 생략 기본도 같은
+  // 선언값이라(RoomService) 모달과 인원을 뺀 요청이 같은 방을 연다.
   const selectedSeats = capacity ?? game?.defaultPlayers ?? game?.maxPlayers ?? 0;
   // D-130 — 턴 제한도 게임이 선언한 값이 처음 선택이다(원카드 30초 — 자리 비운 사람이 판을 멈추지 않게, 나머지는 끔).
   const selectedTurnSeconds = turnSeconds ?? game?.defaultTurnSeconds ?? 0;
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.infra.bot.TurnRemainingTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerResyncLockTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerLeaveTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerCreateDefaultsTest" --tests "com.mirboard.domain.lobby.room.RoomServiceCreateDefaultsTest" --tests "com.mirboard.infra.ws.GameStompControllerGuardTest" --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest" --tests "com.mirboard.infra.ws.DesertionServiceTest" --tests "com.mirboard.domain.game.core.RoomCreationDefaultsTest"`
Expected: PASS — 59건(TurnRemaining 6 · ResyncLock 8 · Leave 5 · ControllerCreateDefaults 2 · ServiceCreateDefaults 4 · GuardTest 5 · EngineTimerScheduler 16 ·
DesertionService 9 · RoomCreationDefaults 4).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.rest.rooms.RoomResyncIntegrationTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerIntegrationTest" --tests "com.mirboard.infra.rest.rooms.RoomCapacityIntegrationTest" --tests "com.mirboard.infra.bot.TurnTimeoutSchedulerIT" --tests "com.mirboard.infra.ws.GameStompControllerIntegrationTest" --tests "com.mirboard.infra.scheduling.DistributedInfraIT" --tests "com.mirboard.infra.bot.FinishedRoomStopsProgressIT" --tests "com.mirboard.infra.bot.OneCardRaceIT" --tests "com.mirboard.infra.ws.TichuEventStreamIT"`
Expected: PASS — 53건(`RoomResyncIntegrationTest` 7 — 턴 30초 티츄 방 resync 의 `0 < turnRemainingMs ≤ 30000` 포함 · `RoomControllerIntegrationTest` 13 ·
`RoomCapacityIntegrationTest` 8 · `TurnTimeoutSchedulerIT` 1 · `GameStompControllerIntegrationTest` 2 · `DistributedInfraIT` 11 · `FinishedRoomStopsProgressIT` 4 ·
`OneCardRaceIT` 6 · `TichuEventStreamIT` 1).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.infra.bot.*Test" --tests "com.mirboard.infra.ws.*Test" --tests "com.mirboard.domain.lobby.*Test"`
Expected: PASS — 167건(infra.bot 57 · infra.ws 64 · domain.lobby 46).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음(`CreateRoomModal.tsx` 는 주석만), 마지막 두 줄 `Test Files  61 passed (61)` · `Tests  642 passed (642)`.

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/infra/bot/TurnRemainingTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerResyncLockTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerLeaveTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerCreateDefaultsTest.java \
  server/src/test/java/com/mirboard/domain/lobby/room/RoomServiceCreateDefaultsTest.java \
  server/src/test/java/com/mirboard/infra/ws/GameStompControllerGuardTest.java \
  server/src/test/java/com/mirboard/infra/bot/EngineTimerSchedulerTest.java \
  server/src/test/java/com/mirboard/infra/ws/DesertionServiceTest.java \
  server/src/test/java/com/mirboard/domain/game/core/RoomCreationDefaultsTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomResyncIntegrationTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomCapacityIntegrationTest.java \
  server/src/main/java/com/mirboard/infra/scheduling/DeadlineQueue.java \
  server/src/main/java/com/mirboard/infra/bot/TurnTimeoutScheduler.java \
  server/src/main/java/com/mirboard/infra/bot/EngineTimerScheduler.java \
  server/src/main/java/com/mirboard/infra/ws/GameStompController.java \
  server/src/main/java/com/mirboard/infra/ws/DesertionService.java \
  server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java \
  server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java \
  server/src/main/java/com/mirboard/domain/game/core/GameDefinition.java \
  client/src/features/lobby/CreateRoomModal.tsx
git commit -m "feat(D-131): resync 남은 턴 시간(turnRemainingMs) — 재무장을 락 안으로 + 방 만들기 생략 기본을 게임 선언과 정렬

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 클라 — 원카드 턴 카운트다운

**Files:**
- Create: `client/src/features/onecard/OneCardTurnCountdown.tsx`
- Modify: `client/src/features/onecard/onecardStore.test.ts`, `client/src/features/onecard/OneCardTable.test.tsx`, `client/src/types/stomp.ts`, `client/src/features/onecard/onecardStore.ts`, `client/src/features/onecard/OneCardCenter.tsx`, `client/src/features/onecard/OneCardTable.tsx`, `client/src/styles/parts/19-onecard-table.css`

**Interfaces:**
- Consumes: Task 4 의 resync `turnRemainingMs`, 원카드 엔진의 공개 이벤트 순서(`TURN_CHANGED` = 서버가 턴 데드라인을 다시 거는 순간, 경쟁 창 동안 다음 차례는
  멈춤 §4.4, 탈주는 창이 열려 있으면 `RACE_RESOLVED`(CANCELLED) → `PLAYER_ELIMINATED` → `TURN_CHANGED`), 방의 `turnSeconds`.
- Produces: `ResyncEnvelope.turnRemainingMs?: number | null`, 스토어 `turnClock: TurnClock | null`(`{ since, remainingMs }` — `remainingMs` null 이면 `since`
  부터 한 턴 전체, 예전 `turnStartedAt` 대체). 스냅샷은 `turnRemainingMs` 로 맞추고(없으면 null = 세지 않음), `TURN_CHANGED`·차례가 남은 `PLAYER_ELIMINATED`
  에서 한 턴 전체로 다시 세며, 경쟁 창이 열렸거나 매치가 끝나면 null. `OneCardTurnCountdown({ turnSeconds })`(`.oc-badge.oc-countdown`, 5초 이하
  `.oc-countdown-urgent`, `aria-live="off"`, 기준이 바뀐 첫 그리기는 지금 시각으로) — `OneCardCenter` 의 `countdown` 자리, 턴 제한을 끈 방은 안 그린다.
  "턴 30초" 배지는 그대로 둔다. N-3(`onecardStore.test.ts` 끝 빈 줄)도 이 파일을 고치며 정리한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/onecard/onecardStore.test.ts`:

```diff
   handVersion: 4,
 };
 
 const snapshot = (
-  over: Partial<{ tableView: OneCardTableView; privateHand: OneCardPrivateView | null }> = {},
+  over: Partial<{
+    tableView: OneCardTableView;
+    privateHand: OneCardPrivateView | null;
+    turnRemainingMs: number | null;
+  }> = {},
 ) => ({
   roomId: 'r-1',
   phase: 'PLAYING',
   eventSeq: 10,
```

```diff
     expect(store().resyncNonce).toBe(0);
   });
 });
 
+describe('턴 카운트다운 기준 (D-131)', () => {
+  it('resync 의 남은 시간으로 맞춘다 — 재접속 직후에도 서버 데드라인과 같다', () => {
+    store().applySnapshot(snapshot({ turnRemainingMs: 12_345 }));
+
+    expect(store().turnClock).toEqual({ since: NOW, remainingMs: 12_345 });
+  });
+
+  it('서버가 남은 시간을 주지 않으면(턴 제한 끔·기다리는 좌석 없음) 세지 않는다', () => {
+    store().applySnapshot(snapshot({ turnRemainingMs: 12_345 }));
+    store().applySnapshot(snapshot({ turnRemainingMs: null }));
+    expect(store().turnClock).toBeNull();
+
+    store().applySnapshot(snapshot());
+    expect(store().turnClock).toBeNull();
+  });
+
+  it('TURN_CHANGED 는 서버가 턴 데드라인을 다시 거는 순간이다 — 한 턴 전체로 다시 센다', () => {
+    store().applySnapshot(snapshot({ turnRemainingMs: 2_000 }));
+    vi.setSystemTime(NOW + 5_000);
+
+    store().applyEvent(ev('TURN_CHANGED', { seat: 2, direction: 1, attackStack: 0 }, 11));
+
+    expect(store().turnClock).toEqual({ since: NOW + 5_000, remainingMs: null });
+  });
+
+  it('카드가 놓이고 경쟁 창이 열린 동안은 세지 않는다 — 다음 차례는 창이 닫힌 뒤 시작된다', () => {
+    store().applySnapshot(snapshot({ turnRemainingMs: 20_000 }));
+
+    store().applyEvent(
+      ev('CARD_PLAYED', { seat: 1, card: card('HEART', 5), handCount: 1, attackStack: 0, direction: 1 }, 11),
+    );
+    expect(store().turnClock).toBeNull();
+
+    store().applyEvent(
+      ev('RACE_OPENED', { raceId: 3, ownerSeat: 1, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 12),
+    );
+    store().applyEvent(ev('RACE_RESOLVED', { raceId: 3, outcome: 'CALLED', bySeat: 1 }, 13));
+    expect(store().turnClock).toBeNull();
+
+    vi.setSystemTime(NOW + 2_000);
+    store().applyEvent(ev('TURN_CHANGED', { seat: 2, direction: 1, attackStack: 0 }, 14));
+    expect(store().turnClock).toEqual({ since: NOW + 2_000, remainingMs: null });
+  });
+
+  it('탈락(탈주)도 서버가 지금 차례의 데드라인을 다시 거는 순간이다 — 차례가 그대로여도 다시 센다', () => {
+    store().applySnapshot(snapshot({ turnRemainingMs: 3_000 }));
+    vi.setSystemTime(NOW + 1_000);
+
+    // 좌석 2 가 나갔다 — 차례(좌석 1)는 그대로지만 서버는 탈주 처리 뒤 턴 데드라인을 처음부터 다시 건다.
+    store().applyEvent(ev('PLAYER_ELIMINATED', { seat: 2, reason: 'DESERTED', drawPileCount: 35 }, 11));
+
+    expect(store().turnClock).toEqual({ since: NOW + 1_000, remainingMs: null });
+  });
+
+  it('창이 열린 채 나가면 해소(취소) → 탈락 → TURN_CHANGED 순서로 오고, 다음 차례는 TURN_CHANGED 부터 센다', () => {
+    // 서버 순서(OneCardEngine.desert): 창이 열려 있으면 먼저 RACE_RESOLVED(CANCELLED, -1)로 닫고, 그다음 PLAYER_ELIMINATED,
+    // 그다음 TURN_CHANGED(또는 MATCH_ENDED). 해소는 차례를 정하지 않으므로(turnSeat −1 그대로) 그 사이의 탈락은 세지 않는다.
+    const race = { raceId: 3, ownerSeat: 1, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000, remainingMillis: 2000 };
+    store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'RACE', turnSeat: -1, race } }));
+
+    store().applyEvent(ev('RACE_RESOLVED', { raceId: 3, outcome: 'CANCELLED', bySeat: -1 }, 11));
+    expect(store().turnClock).toBeNull();
+
+    store().applyEvent(ev('PLAYER_ELIMINATED', { seat: 2, reason: 'DESERTED', drawPileCount: 35 }, 12));
+    expect(store().turnSeat).toBe(-1);
+    expect(store().turnClock).toBeNull();
+
+    vi.setSystemTime(NOW + 500);
+    store().applyEvent(ev('TURN_CHANGED', { seat: 0, direction: 1, attackStack: 0 }, 13));
+    expect(store().turnClock).toEqual({ since: NOW + 500, remainingMs: null });
+  });
+
+  it('매치가 끝나면 세지 않는다', () => {
+    store().applySnapshot(snapshot({ turnRemainingMs: 20_000 }));
+
+    store().applyEvent(ev('MATCH_ENDED', { reason: 'FINISHED', standings: [] }, 11));
+
+    expect(store().turnClock).toBeNull();
+  });
+});
```

`client/src/features/onecard/OneCardTable.test.tsx`:

```diff
 import { act, fireEvent, render, screen, within } from '@testing-library/react';
 import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
 import { LATE_NOTICE_MS, OneCardTable, PRESS_RETRY_DELAY_MS, STALE_RACE_GRACE_MS } from './OneCardTable';
+import { TURN_URGENT_SECONDS } from './OneCardTurnCountdown';
 import { onecardRoomSink } from './onecardRoomSink';
 import { useOneCardStore } from './onecardStore';
 import { useAuthStore } from '@/features/auth/authStore';
```

```diff
   seats?: OneCardSeatView[];
   race?: OneCardRaceView | null;
   result?: OneCardMatchResult | null;
+  /** D-131 — resync 의 남은 턴 시간(ms). */
+  turnRemainingMs?: number | null;
 }
 
 function seed(opts: SeedOptions) {
```

```diff
       opts.mySeat >= 0 ? { seat: opts.mySeat, hand: opts.hand ?? [], handVersion: 1 } : null,
     disconnectedSeats: [],
     chips: null,
+    turnRemainingMs: opts.turnRemainingMs ?? null,
   };
 }
 
```

```diff
     expect(screen.getByText('미르보드 원카드에 오신 걸 환영합니다')).toBeInTheDocument();
   });
 });
+
+describe('OneCardTable — 턴 카운트다운 (D-131)', () => {
+  const countdown = () => document.querySelector('.oc-countdown');
+
+  it('resync 의 남은 시간으로 맞춰 보이고, 시간이 흐르면 줄어든다(올림 초)', () => {
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 12_345 });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
+
+    expect(countdown()).toHaveTextContent('13초');
+    act(() => {
+      vi.advanceTimersByTime(345);
+    });
+    expect(countdown()).toHaveTextContent('12초');
+    act(() => {
+      vi.advanceTimersByTime(5_000);
+    });
+    expect(countdown()).toHaveTextContent('7초');
+  });
+
+  it('TURN_CHANGED 가 오면 한 턴 전체(방의 턴 제한)부터 다시 센다', () => {
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 4_000 });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
+    act(() => {
+      vi.advanceTimersByTime(3_000);
+    });
+    expect(countdown()).toHaveTextContent('1초');
+
+    act(() => {
+      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 2, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
+    });
+
+    expect(countdown()).toHaveTextContent('30초');
+  });
+
+  it('먹기 뒤 TURN_CHANGED 로 기준이 바뀌는 순간에도 방의 턴 제한보다 큰 값을 그리지 않는다', () => {
+    // 먹기(CARDS_DRAWN)는 차례를 지우지 않아 카운트다운이 마운트된 채 기준만 바뀐다. 첫 그리기가 지난 틱의 시각으로 세면
+    // '31초'가 한 프레임 보였다. act 는 effect 까지 비운 뒤의 DOM 만 보여 주므로 텍스트 노드의 변화를 직접 기록한다.
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 4_000 });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
+    act(() => {
+      vi.advanceTimersByTime(3_400);
+    });
+    expect(countdown()).toHaveTextContent('1초');
+    const records: MutationRecord[] = [];
+    const observer = new MutationObserver((batch) => records.push(...batch));
+    observer.observe(countdown()!, { characterData: true, characterDataOldValue: true, subtree: true });
+
+    act(() => {
+      onecardRoomSink.applyEvent({
+        type: 'CARDS_DRAWN',
+        seq: 2,
+        payload: { seat: 1, count: 1, reason: 'TURN', handCount: 8, drawPileCount: 29 },
+      } as never);
+      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 3, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
+    });
+    records.push(...observer.takeRecords());
+    observer.disconnect();
+
+    const shown = records
+      .flatMap((r) => [r.oldValue, r.target.nodeValue])
+      .filter((v): v is string => v !== null && /^\d+$/.test(v))
+      .map(Number);
+    expect(countdown()).toHaveTextContent('30초');
+    expect(shown.length).toBeGreaterThan(0);
+    expect(Math.max(...shown)).toBeLessThanOrEqual(30);
+  });
+
+  it('턴 제한이 꺼진 방은 보이지 않는다', () => {
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 12_345 });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 0 });
+
+    expect(countdown()).toBeNull();
+  });
+
+  it('서버가 남은 시간을 주지 않으면(데드라인 없음) 보이지 않는다', () => {
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: null });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
+
+    expect(countdown()).toBeNull();
+  });
+
+  it('경쟁 창이 열린 동안은 보이지 않고, 창이 닫힌 뒤 다음 차례부터 다시 센다', () => {
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 20_000 });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
+    expect(countdown()).not.toBeNull();
+
+    act(() => {
+      onecardRoomSink.applyEvent({
+        type: 'CARD_PLAYED',
+        seq: 2,
+        payload: { seat: 1, card: c('HEART', 5), handCount: 1, attackStack: 0, direction: 1 },
+      } as never);
+      onecardRoomSink.applyEvent({ type: 'RACE_OPENED', seq: 3, payload: { ...RACE, raceId: 8 } } as never);
+    });
+    expect(countdown()).toBeNull();
+
+    act(() => {
+      vi.advanceTimersByTime(1_500);
+      onecardRoomSink.applyEvent({ type: 'RACE_RESOLVED', seq: 4, payload: { raceId: 8, outcome: 'EXPIRED', bySeat: -1 } } as never);
+      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 5, payload: { seat: 2, direction: 1, attackStack: 0 } } as never);
+    });
+    expect(countdown()).toHaveTextContent('30초');
+  });
+
+  it(`${TURN_URGENT_SECONDS}초 이하면 강조하고, 0 에서 멈춘다 — 매초 낭독하지 않는다`, () => {
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, turnRemainingMs: 6_000 });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30 });
+
+    expect(countdown()).not.toHaveClass('oc-countdown-urgent');
+    expect(countdown()).toHaveAttribute('aria-live', 'off');
+    act(() => {
+      vi.advanceTimersByTime(1_000);
+    });
+    expect(countdown()).toHaveClass('oc-countdown-urgent');
+    act(() => {
+      vi.advanceTimersByTime(10_000);
+    });
+    expect(countdown()).toHaveTextContent('0초');
+  });
+
+  it('방은 끝났는데 결과가 없으면(강제 종료) 세지 않는다', () => {
+    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, turnRemainingMs: 20_000 });
+    renderTable({ playerIds: [100, 101, 102], turnSeconds: 30, roomFinished: true });
+
+    expect(countdown()).toBeNull();
+  });
+});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- onecard`
Expected: FAIL — `Test Files  2 failed | 7 passed (9)` · `Tests  7 failed | 131 passed (138)`(`Error: Failed to resolve import "./OneCardTurnCountdown" from
"src/features/onecard/OneCardTable.test.tsx"`, `× 턴 카운트다운 기준 (D-131) > …` 7건 — 스토어에 `turnClock` 없음).

- [ ] **Step 3: 구현**

`client/src/types/stomp.ts`:

```diff
   privateHand: TPrivate | null;
   disconnectedSeats?: number[];
   chips?: Record<number, number> | null;
+  /**
+   * D-131 — 지금 턴의 남은 시간(ms, 0 이상). 서버가 실제로 발화할 턴 데드라인 기준이라 재접속 직후에도 맞다. 턴 제한 끔·기다리는
+   * 좌석 없음(경쟁 창·끝난 매치)·걸린 데드라인 없음이면 null. 게임 중립 — 쓰는 게임판만 읽는다.
+   */
+  turnRemainingMs?: number | null;
 }
```

`client/src/features/onecard/onecardStore.ts`:

```diff
 
 export type PressAction = 'CALL_ONE_CARD' | 'CATCH';
 
+/**
+ * D-131 — 턴 카운트다운의 기준. 서버가 턴 데드라인을 다시 거는 순간의 공개 이벤트(`TURN_CHANGED`·`PLAYER_ELIMINATED`)에서
+ * "지금부터 한 턴 전체"로, resync 에서는 서버가 알려 준 남은 시간으로 잡는다. 표시 쪽이 방의 턴 제한으로 마감 시각을 계산한다.
+ */
+export interface TurnClock {
+  /** 이 클라 시계로 기준을 잡은 시각(ms). */
+  since: number;
+  /** 그때 남은 시간(ms). null 이면 한 턴 전체(방의 턴 제한) — 서버가 바로 그 순간 데드라인을 처음부터 다시 걸었다. */
+  remainingMs: number | null;
+}
+
 /** 내가 보낸 누름 — 응답(창 해소·거절)을 기다리는 동안만 있다. */
 export interface PendingPress {
   raceId: number;
```

```diff
   // ── 메타 ──
   disconnectedSeats: Set<number>;
   errorMessage: string | null;
-  turnStartedAt: number;
+  /**
+   * D-131 — 턴 카운트다운 기준. null 이면 세지 않는다 — 경쟁 창이 열렸거나(다음 차례는 창이 닫힌 뒤 시작된다) 카드가 막 놓였거나
+   * (다음 차례 미정) 매치가 끝났거나 서버가 남은 시간을 주지 않았다(턴 제한 끔·데드라인 없음).
+   */
+  turnClock: TurnClock | null;
 }
 
 export interface OneCardActions {
```

```diff
   resyncNonce: 0,
   disconnectedSeats: new Set(),
   errorMessage: null,
-  turnStartedAt: 0,
+  turnClock: null,
 };
 
 /** 좌석 하나만 갱신한 새 배열. */
```

```diff
       press: null,
       disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
       errorMessage: null,
-      turnStartedAt: Date.now(),
+      // D-131 — 서버가 아는 남은 시간이 기준이다(재접속 직후에도 맞다). 없으면 세지 않는다.
+      turnClock:
+        typeof snap.turnRemainingMs === 'number'
+          ? { since: Date.now(), remainingMs: snap.turnRemainingMs }
+          : null,
     });
   },
 
```

```diff
           attackStack: p.attackStack,
           direction: p.direction,
           seats: patchSeat(state.seats, p.seat, { handCount: p.handCount }),
-          // 다음 차례는 TURN_CHANGED(또는 경쟁 창)가 정한다 — 그 사이 '내 차례'를 잘못 세우지 않는다.
+          // 다음 차례는 TURN_CHANGED(또는 경쟁 창)가 정한다 — 그 사이 '내 차례'를 잘못 세우지 않는다. 카운트다운도 멈춘다.
           turnSeat: -1,
+          turnClock: null,
           lastRace: null,
           // 경쟁 중에는 내기가 막혀 있어(requireTurn) 카드는 항상 창이 닫힌 뒤에 놓인다 — 거절이 끝내 안 온 진 누름이
           // 남아 있다면 여기서 정리한다(안 하면 내 카드 내기의 BUSY 가 "늦었어요"로 잘못 읽힌다).
```

```diff
           turnSeat: p.seat,
           direction: p.direction,
           attackStack: p.attackStack,
-          turnStartedAt: Date.now(),
+          // D-131 — 서버는 이 이벤트를 낸 진행 직후(같은 락 안) 턴 데드라인을 처음부터 다시 건다.
+          turnClock: { since: Date.now(), remainingMs: null },
           // 매 플레이 resync 가 지워 주던 거절 문구를 차례가 바뀔 때 지운다(D-126 의 티츄와 같은 처리).
           errorMessage: null,
         });
```

```diff
           phase: 'RACE',
           race: toClientRace(p, p.windowMillis),
           turnSeat: -1,
+          // D-131 — 창이 열린 동안 다음 차례는 멈춰 있다(§4.4 — 기다리는 좌석 없음). 창이 닫히면 TURN_CHANGED 가 다시 센다.
+          turnClock: null,
           lastRace: null,
           press: null,
           raceNotice: null,
```

```diff
           // 탈락자 손패는 뽑을 더미 맨 아래로 간다(§10) — 장수는 0, 더미 장수는 최종값.
           seats: patchSeat(state.seats, p.seat, { eliminated: p.reason, handCount: 0 }),
           drawPileCount: p.drawPileCount,
+          // D-131 — 탈락(탈주·파산) 처리 뒤 서버는 지금 차례의 턴 데드라인을 처음부터 다시 건다 — 차례가 그대로여도(다른
+          // 사람이 나갔다). 차례가 정해지지 않았으면(−1 — 카드 직후·경쟁 창) 세지 않는다. 창이 열리면 서버가 turnSeat 를 −1 로
+          // 두고, 창이 열린 채 나가면 해소(취소)를 탈락보다 먼저 보내므로 창 여부는 따로 볼 필요가 없다(다음 TURN_CHANGED 가 센다).
+          ...(state.turnSeat >= 0
+            ? { turnClock: { since: Date.now(), remainingMs: null } }
+            : {}),
         });
         return 'applied';
       }
 
       case 'MATCH_ENDED': {
         const p = payload as MatchEndedPayload;
-        set({ phase: 'ENDED', result: p, turnSeat: -1, race: null, press: null });
+        set({ phase: 'ENDED', result: p, turnSeat: -1, race: null, press: null, turnClock: null });
         return 'applied';
       }
 
```

`client/src/features/onecard/OneCardTurnCountdown.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { useOneCardStore } from './onecardStore';

/** 이 초 이하로 남으면 강조한다. */
export const TURN_URGENT_SECONDS = 5;

/**
 * D-131 — 지금 차례의 남은 시간. 기준은 서버다: resync 의 `turnRemainingMs` 로 맞추고, 서버가 턴 데드라인을 다시 거는 순간의
 * 공개 이벤트(`TURN_CHANGED`·`PLAYER_ELIMINATED`)에서 방의 턴 제한부터 다시 센다(스토어 `turnClock`). 0 이 되면 서버가 시간
 * 초과를 처리해(먹기) 이벤트가 새 기준을 준다 — 여기서는 0 에서 멈출 뿐이다.
 *
 * <p>턴 제한이 꺼진 방·경쟁 창이 열린 동안(다음 차례가 멈춰 있다)·끝난 판에는 보이지 않는다. 매초 낭독하지 않는다
 * (`aria-live="off"` — 시간 초과가 가까운 것은 강조 색으로만). 다음 정수 초가 바뀌는 순간에만 다시 그린다.
 */
export function OneCardTurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const clock = useOneCardStore((s) => s.turnClock);
  const deadline =
    turnSeconds > 0 && clock !== null ? clock.since + (clock.remainingMs ?? turnSeconds * 1000) : null;
  // 마지막 틱의 시각과 그때의 기준. 기준이 바뀐 첫 그리기는 지난 틱의 시각이 아니라 지금 시각으로 센다 — 먹기 뒤
  // TURN_CHANGED 처럼 마운트된 채 기준만 바뀌면, 낡은 시각으로는 방의 턴 제한보다 큰 값(31초)이 한 프레임 보였다.
  const [tick, setTick] = useState(() => ({ deadline, now: Date.now() }));

  useEffect(() => {
    if (deadline === null) return;
    let timer = 0;
    const run = () => {
      const current = Date.now();
      setTick({ deadline, now: current });
      const left = deadline - current;
      if (left <= 0) return;
      // 표시는 올림 초라 다음 정수 초 경계에서만 바뀐다.
      timer = window.setTimeout(run, left % 1000 || 1000);
    };
    run();
    return () => window.clearTimeout(timer);
  }, [deadline]);

  if (deadline === null) return null;
  const now = tick.deadline === deadline ? tick.now : Date.now();
  const seconds = Math.ceil(Math.max(0, deadline - now) / 1000);
  const urgent = seconds <= TURN_URGENT_SECONDS;
  return (
    <span
      className={`oc-badge oc-countdown${urgent ? ' oc-countdown-urgent' : ''}`}
      role="timer"
      aria-live="off"
      aria-label={`이번 차례 남은 시간 ${seconds}초`}
      title="0초가 되면 자동으로 먹습니다"
    >
      <span aria-hidden>⏱ </span>
      {seconds}초
    </span>
  );
}
```

`client/src/features/onecard/OneCardCenter.tsx`:

```diff
+import type { ReactNode } from 'react';
 import { SUIT_LABEL, SUIT_SYMBOL, type OneCardCard, type OneCardSuit } from '@/types/onecard';
 import type { LastRace } from './onecardStore';
 import { OneCardCardChip } from './OneCardCardChip';
```

```diff
   lastRace: LastRace | null;
   late: boolean;
   nameOf: (seat: number) => string;
+  /** D-131 — 차례 옆에 붙는 남은 시간(경쟁 중에는 그리지 않는다). */
+  countdown?: ReactNode;
 }
 
 /** 가운데 — 뽑을 더미, 맨 위 카드와 지정 무늬, 공격 누적, 진행 방향, 차례, 경쟁 결과. 모두 공개 정보다. */
```

```diff
   lastRace,
   late,
   nameOf,
+  countdown,
 }: Props) {
   return (
     <section className="oc-center" aria-label="테이블">
```

```diff
         {raceOpen ? (
           <span className="oc-badge oc-race-status">원카드 경쟁 중</span>
         ) : (
-          turnName && <span className="oc-badge">{turnName} 차례</span>
+          turnName && (
+            <>
+              <span className="oc-badge">{turnName} 차례</span>
+              {countdown}
+            </>
+          )
         )}
       </div>
 
```

`client/src/features/onecard/OneCardTable.tsx`:

```diff
 import { OneCardCenter, raceResultText } from './OneCardCenter';
 import { OneCardHand } from './OneCardHand';
 import { OneCardMatchEnd } from './OneCardMatchEnd';
+import { OneCardTurnCountdown } from './OneCardTurnCountdown';
 import { RaceButton } from './RaceButton';
 import { ONE_CARD_TUTORIAL } from './tutorial/onecardTutorial';
 
```

```diff
         lastRace={s.lastRace}
         late={s.raceNotice === 'LATE'}
         nameOf={nameOf}
+        // D-131 — 서버 남은 시간 기준 카운트다운. 방이 끝났으면(강제 종료 — 결과 없음) 세지 않는다.
+        countdown={roomFinished ? null : <OneCardTurnCountdown turnSeconds={turnSeconds} />}
       />
 
       {s.result && (
```

`client/src/styles/parts/19-onecard-table.css`:

```diff
   font-weight: 700;
 }
 
+/* D-131 — 차례 옆 남은 시간. 숫자 폭이 흔들리지 않게 고정폭 숫자, 5초 이하는 경고 테두리·배경(글자색은 그대로 — 대비). */
+.oc-countdown {
+  font-variant-numeric: tabular-nums;
+  font-weight: 600;
+}
+
+.oc-countdown-urgent {
+  border-color: var(--oc-race-catch);
+  background: rgba(217, 72, 61, 0.15);
+  font-weight: 700;
+}
+
 .oc-race-result,
 .oc-race-late {
   margin: 0;
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- onecard`
Expected: PASS — `Test Files  9 passed (9)` · `Tests  190 passed (190)`.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  61 passed (61)` · `Tests  657 passed (657)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/onecard/onecardStore.test.ts \
  client/src/features/onecard/OneCardTable.test.tsx \
  client/src/types/stomp.ts \
  client/src/features/onecard/onecardStore.ts \
  client/src/features/onecard/OneCardTurnCountdown.tsx \
  client/src/features/onecard/OneCardCenter.tsx \
  client/src/features/onecard/OneCardTable.tsx \
  client/src/styles/parts/19-onecard-table.css
git commit -m "feat(D-131): 원카드 턴 카운트다운 — resync 남은 시간 + 재무장 이벤트로 다시 세기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: 서버 — 원카드 공개(공개 상태 기본값 AVAILABLE)

**Files:**
- Create: `server/src/test/java/com/mirboard/infra/rest/games/OneCardClosedIntegrationTest.java`
- Modify: `server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java`, `server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java`, `server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerIntegrationTest.java`, `server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`, `server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `OneCardGameDefinition` 의 공개 상태 설정(`mirboard.onecard.status` ← `MIRBOARD_ONECARD_STATUS`, 잘못된 값은 기동 실패), `RoomService` 의 AVAILABLE 만
  허용(그 밖은 `GAME_NOT_AVAILABLE`), Task 4 의 생략 기본 정렬, 카탈로그 정렬(상태 → 표시 이름).
- Produces: `application.yml` `${MIRBOARD_ONECARD_STATUS:AVAILABLE}`, `@Value` 기본값 AVAILABLE·Javadoc. 카탈로그 순서가 스컬킹·원카드·티츄가 되고, 생략한 원카드
  방이 4명·30초로 열린다. 닫는 설정(COMING_SOON)의 거절 경로는 `OneCardClosedIntegrationTest` 가 지킨다.

- [ ] **Step 1: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java`:

```diff
 import com.mirboard.domain.game.core.GameStatus;
 import com.mirboard.domain.game.onecard.action.OneCardAction;
 import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
+import java.lang.reflect.Parameter;
 import java.time.Clock;
+import java.util.Arrays;
 import java.util.List;
+import java.util.Properties;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
 import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Value;
+import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
+import org.springframework.core.io.ClassPathResource;
 
 /** D-128 — 카탈로그 메타데이터, 설정에서 오는 공개 상태·경쟁 창, 엔진 팩토리. */
 class OneCardGameDefinitionTest {
```

```diff
     }
 
     private static OneCardGameDefinition defaults() {
-        return definition(GameStatus.COMING_SOON, 3_000, 1_000, 2_500, 1_000, 2_500);
+        return definition(GameStatus.AVAILABLE, 3_000, 1_000, 2_500, 1_000, 2_500);
     }
 
     @Test
```

```diff
         assertThat(def.displayName()).isEqualTo("원카드");
         assertThat(def.minPlayers()).isEqualTo(2);
         assertThat(def.maxPlayers()).isEqualTo(6);
-        assertThat(def.status()).isEqualTo(GameStatus.COMING_SOON);
+        assertThat(def.status()).isEqualTo(GameStatus.AVAILABLE);
         assertThat(def.supportedRoomOptions()).isEmpty();
         assertThat(def.supportsRematch()).isFalse();
     }
```

```diff
         assertThat(def.defaultTurnSeconds()).isEqualTo(30);
     }
 
+    /**
+     * D-131 — 원카드는 공개됐다: 설정을 주지 않으면 {@code AVAILABLE}. 기본값은 두 곳에 있다 — {@code application.yml} 의 환경
+     * 변수 기본값({@code ${MIRBOARD_ONECARD_STATUS:…}}, 운영이 실제로 타는 값)과 정의 생성자의 {@code @Value} 기본값(설정
+     * 파일이 키를 빼먹은 경우). 둘이 갈리면 어느 쪽으로 뜨느냐에 따라 공개 여부가 달라진다. 실제 컨텍스트에서의 값은
+     * {@code GameCatalogIntegrationTest} 가, 닫는 설정(COMING_SOON)의 거절 경로는 {@code OneCardClosedIntegrationTest} 가 본다.
+     */
+    @Test
+    void with_no_setting_the_game_is_available() {
+        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
+        yaml.setResources(new ClassPathResource("application.yml"));
+        Properties properties = yaml.getObject();
+        Parameter statusParameter = Arrays.stream(OneCardGameDefinition.class.getConstructors()[0].getParameters())
+                .filter(parameter -> parameter.getType() == GameStatus.class)
+                .findFirst().orElseThrow();
+
+        assertThat(fallbackOf(properties.getProperty("mirboard.onecard.status")))
+                .as("application.yml 의 MIRBOARD_ONECARD_STATUS 기본값").isEqualTo("AVAILABLE");
+        assertThat(fallbackOf(statusParameter.getAnnotation(Value.class).value()))
+                .as("@Value 기본값").isEqualTo("AVAILABLE");
+    }
+
+    /** {@code ${KEY:기본값}} 의 기본값. */
+    private static String fallbackOf(String placeholder) {
+        Matcher matcher = Pattern.compile("\\$\\{[^:}]+:([^}]*)}").matcher(placeholder);
+        assertThat(matcher.matches()).as(placeholder).isTrue();
+        return matcher.group(1);
+    }
+
     @Test
     void status_and_race_timing_come_from_configuration_with_the_protocol_slot_count() {
-        OneCardGameDefinition def = definition(GameStatus.AVAILABLE, 2_000, 100, 200, 300, 400);
+        OneCardGameDefinition def = definition(GameStatus.COMING_SOON, 2_000, 100, 200, 300, 400);
 
-        assertThat(def.status()).isEqualTo(GameStatus.AVAILABLE);
+        assertThat(def.status()).isEqualTo(GameStatus.COMING_SOON);
         assertThat(def.raceSettings()).isEqualTo(new RaceSettings(2_000, 100, 200, 300, 400, 8));
     }
 
```

`server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java`:

```diff
     }
 
     /**
-     * D-102 — 정렬은 status → displayName 이라 "스컬킹" 이 먼저다. D-128 — 원카드는 클라 게임판(S4) 전까지
-     * COMING_SOON 이라 AVAILABLE 두 게임 뒤에 온다.
+     * D-102 — 정렬은 status → displayName 이다. D-131 — 원카드가 공개돼(AVAILABLE) 세 게임이 모두 같은 상태라 이름순 —
+     * 스컬킹 · 원카드 · 티츄. (닫는 설정의 원카드는 {@code OneCardClosedIntegrationTest}.)
      */
     @Test
     void catalog_lists_every_game_when_authenticated() throws Exception {
```

```diff
                 // D-130 — 방 만들기의 처음 선택. 재정의하지 않은 게임은 최대 인원·턴 제한 끔(지금까지와 같다).
                 .andExpect(jsonPath("$.games[0].defaultPlayers").value(8))
                 .andExpect(jsonPath("$.games[0].defaultTurnSeconds").value(0))
-                .andExpect(jsonPath("$.games[1].id").value("TICHU"))
-                .andExpect(jsonPath("$.games[1].displayName").value("티츄"))
-                .andExpect(jsonPath("$.games[1].minPlayers").value(4))
-                .andExpect(jsonPath("$.games[1].maxPlayers").value(4))
+                .andExpect(jsonPath("$.games[1].id").value("ONE_CARD"))
+                .andExpect(jsonPath("$.games[1].displayName").value("원카드"))
+                .andExpect(jsonPath("$.games[1].minPlayers").value(2))
+                .andExpect(jsonPath("$.games[1].maxPlayers").value(6))
+                .andExpect(jsonPath("$.games[1].supportedRoomOptions.length()").value(0))
+                // D-131 — 공개. 설정을 주지 않은 컨텍스트의 기본값이다.
+                .andExpect(jsonPath("$.games[1].status").value("AVAILABLE"))
+                // D-130 — 원카드만 4명·30초를 선언한다(설계 §3.1 기본 4, 버티기 대응).
+                .andExpect(jsonPath("$.games[1].defaultPlayers").value(4))
+                .andExpect(jsonPath("$.games[1].defaultTurnSeconds").value(30))
+                .andExpect(jsonPath("$.games[2].id").value("TICHU"))
+                .andExpect(jsonPath("$.games[2].displayName").value("티츄"))
+                .andExpect(jsonPath("$.games[2].minPlayers").value(4))
+                .andExpect(jsonPath("$.games[2].maxPlayers").value(4))
                 // D-106 — 배열 순서는 RoomOption 선언 순서다. 클라가 인덱스가 아니라
                 // includes() 로 읽으므로 순서 자체가 기능은 아니지만, 카탈로그는 계약이라
                 // 필드명·값 집합·순서가 말없이 바뀌면 클라가 조용히 어긋난다.
-                .andExpect(jsonPath("$.games[1].supportedRoomOptions.length()").value(3))
-                .andExpect(jsonPath("$.games[1].supportedRoomOptions[0]").value("TARGET_SCORE"))
-                .andExpect(jsonPath("$.games[1].supportedRoomOptions[1]").value("TEAMS"))
-                .andExpect(jsonPath("$.games[1].supportedRoomOptions[2]").value("BETTING"))
-                .andExpect(jsonPath("$.games[1].status").value("AVAILABLE"))
-                .andExpect(jsonPath("$.games[1].defaultPlayers").value(4))
-                .andExpect(jsonPath("$.games[1].defaultTurnSeconds").value(0))
-                .andExpect(jsonPath("$.games[2].id").value("ONE_CARD"))
-                .andExpect(jsonPath("$.games[2].displayName").value("원카드"))
-                .andExpect(jsonPath("$.games[2].minPlayers").value(2))
-                .andExpect(jsonPath("$.games[2].maxPlayers").value(6))
-                .andExpect(jsonPath("$.games[2].supportedRoomOptions.length()").value(0))
-                .andExpect(jsonPath("$.games[2].status").value("COMING_SOON"))
-                // D-130 — 원카드만 4명·30초를 선언한다(설계 §3.1 기본 4, 버티기 대응).
+                .andExpect(jsonPath("$.games[2].supportedRoomOptions.length()").value(3))
+                .andExpect(jsonPath("$.games[2].supportedRoomOptions[0]").value("TARGET_SCORE"))
+                .andExpect(jsonPath("$.games[2].supportedRoomOptions[1]").value("TEAMS"))
+                .andExpect(jsonPath("$.games[2].supportedRoomOptions[2]").value("BETTING"))
+                .andExpect(jsonPath("$.games[2].status").value("AVAILABLE"))
                 .andExpect(jsonPath("$.games[2].defaultPlayers").value(4))
-                .andExpect(jsonPath("$.games[2].defaultTurnSeconds").value(30));
+                .andExpect(jsonPath("$.games[2].defaultTurnSeconds").value(0));
     }
 
     @Test
```

`server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerIntegrationTest.java`:

```diff
                 .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
     }
 
+    /**
+     * D-131 — 원카드는 공개됐다: 설정 없이 방을 만들 수 있고, 인원·턴 제한을 생략하면 게임 선언(4명·30초)으로 열린다 —
+     * 방 만들기 모달의 처음 선택과 같다. (닫는 설정의 거절은 {@code OneCardClosedIntegrationTest}.)
+     */
+    @Test
+    void a_one_card_room_opens_with_its_declared_choices_when_they_are_omitted() throws Exception {
+        String token = registerAndLogin("one_card_host", "validpass1");
+
+        mockMvc.perform(post("/api/rooms")
+                        .header("Authorization", "Bearer " + token)
+                        .contentType(MediaType.APPLICATION_JSON)
+                        .content(objectMapper.writeValueAsString(Map.of("name", "원카드 방", "gameType", "ONE_CARD"))))
+                .andExpect(status().isCreated())
+                .andExpect(jsonPath("$.gameType").value("ONE_CARD"))
+                .andExpect(jsonPath("$.capacity").value(4))
+                .andExpect(jsonPath("$.turnSeconds").value(30));
+    }
+
     @Test
     void create_with_unavailable_game_returns_404() throws Exception {
         String token = registerAndLogin("bad_game_user", "validpass1");
```

`server/src/test/java/com/mirboard/infra/rest/games/OneCardClosedIntegrationTest.java`:

```java
package com.mirboard.infra.rest.games;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-131 — 원카드를 다시 닫는 설정(`MIRBOARD_ONECARD_STATUS=COMING_SOON`, 운영 런북 `docs/deploy.md` 의 되돌리기)이 그대로
 * 듣는지 고정한다. 공개 전에는 기본값이 이 경로를 늘 지켰지만, 공개 뒤에는 설정을 준 이 테스트만 지킨다 — 카탈로그에는
 * "준비 중"으로 공개 게임 뒤에 오고, 방은 만들 수 없다(`RoomService` 가 AVAILABLE 만 허용).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=onecard-closed-test-secret-must-be-32-bytes-or-more",
        "mirboard.onecard.status=COMING_SOON"
})
class OneCardClosedIntegrationTest {

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void a_closed_one_card_is_listed_as_coming_soon_after_the_open_games() throws Exception {
        String token = authenticate("closed_catalog_u");

        mockMvc.perform(get("/api/games").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games.length()").value(3))
                .andExpect(jsonPath("$.games[0].id").value("SKULL_KING"))
                .andExpect(jsonPath("$.games[1].id").value("TICHU"))
                .andExpect(jsonPath("$.games[2].id").value("ONE_CARD"))
                .andExpect(jsonPath("$.games[2].status").value("COMING_SOON"));
    }

    @Test
    void a_closed_one_card_room_cannot_be_created() throws Exception {
        String token = authenticate("closed_room_u");

        mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "원카드 방", "gameType", "ONE_CARD"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GAME_NOT_AVAILABLE"));
    }

    private String authenticate(String username) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of("username", username, "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.OneCardGameDefinitionTest" --tests "com.mirboard.infra.rest.games.GameCatalogIntegrationTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerIntegrationTest" --tests "com.mirboard.infra.rest.games.OneCardClosedIntegrationTest"`
Expected: FAIL — `27 tests completed, 3 failed`: `OneCardGameDefinitionTest > with_no_setting_the_game_is_available()`(`[application.yml 의 MIRBOARD_ONECARD_STATUS 기본값]
expected: "AVAILABLE" but was: "COMING_SOON"`), `GameCatalogIntegrationTest > catalog_lists_every_game_when_authenticated()`(`JSON path "$.games[1].id" expected:<ONE_CARD>
but was:<TICHU>`), `RoomControllerIntegrationTest > a_one_card_room_opens_with_its_declared_choices_when_they_are_omitted()`(`Status expected:<201> but was:<404>`).
`OneCardClosedIntegrationTest` 2건은 닫는 설정의 고정 테스트라 처음부터 통과한다.

- [ ] **Step 3: 구현**

`server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java`:

```diff
  * 원카드의 카탈로그 메타데이터 + 엔진 팩토리 (D-128). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·
  * 디스패치 수정 없이 이 Bean 등록으로 인게임까지 연결된다(D-102 가 실증한 약속).
  *
- * <p><b>공개 상태는 설정이다</b>({@code mirboard.onecard.status}, 기본 {@code COMING_SOON}). 클라 게임판(S4)
- * 전에는 카탈로그에 "준비 중"으로만 보이고 방을 만들 수 없다({@code RoomService} 가 AVAILABLE 만 허용).
- * 통합 테스트는 AVAILABLE 로 켜서 방을 만든다.
+ * <p><b>공개 상태는 설정이다</b>({@code mirboard.onecard.status} = 환경 변수 {@code MIRBOARD_ONECARD_STATUS}, 기본
+ * {@code AVAILABLE} — D-131 공개). {@code COMING_SOON} 을 주면 카탈로그에 "준비 중"으로만 보이고 방을 만들 수 없다
+ * ({@code RoomService} 가 AVAILABLE 만 허용), {@code DISABLED} 면 카탈로그에서도 빠진다 — 운영의 되돌리기 손잡이다
+ * ({@code docs/deploy.md}). 기본값은 {@code application.yml} 과 아래 {@code @Value} 두 곳에 있고 같아야 한다.
  *
  * <p>경쟁 창 길이와 봇 반응 구간도 설정에서 읽는다(룰 §9). 슬롯 수는 클라와 맞춘 프로토콜 상수라 열지 않는다.
  * {@code supportedRoomOptions()} 는 재정의하지 않는다 — 목표 점수·팀·내기를 쓰지 않는 개인전이다(D-106).
```

```diff
             OneCardStateStore stateStore,
             Clock clock,
             ApplicationEventPublisher publisher,
-            @Value("${mirboard.onecard.status:COMING_SOON}") GameStatus status,
+            @Value("${mirboard.onecard.status:AVAILABLE}") GameStatus status,
             @Value("${mirboard.onecard.race-window-millis:3000}") long raceWindowMillis,
             @Value("${mirboard.onecard.bot-reaction-owner-min-millis:1000}") long ownerMinMillis,
             @Value("${mirboard.onecard.bot-reaction-owner-max-millis:2500}") long ownerMaxMillis,
```

`server/src/main/resources/application.yml`:

```diff
         limit: ${MIRBOARD_RATELIMIT_STOMP_LIMIT:60}
         window: 10s
   onecard:
-    # D-128 — 원카드. 클라 게임판(S4) 전에는 카탈로그에 "준비 중"으로만 보이고 방을 만들 수 없다.
-    # S4 에서 AVAILABLE 로 바꾼다(통합 테스트는 AVAILABLE 로 켜서 방을 만든다).
-    status: ${MIRBOARD_ONECARD_STATUS:COMING_SOON}
+    # D-128 — 원카드 공개 상태. D-131 — 공개(기본 AVAILABLE). 되돌리기는 시크릿 MIRBOARD_ONECARD_STATUS 로 —
+    # COMING_SOON(카탈로그 "준비 중", 방 생성 거절)·DISABLED(카탈로그에서도 뺌). 런북은 docs/deploy.md.
+    status: ${MIRBOARD_ONECARD_STATUS:AVAILABLE}
     # "원카드!/잡기!" 경쟁 창 길이와 봇 반응 시간 구간(ms) — 창마다 봇별로 균등 추첨(룰 §9).
     race-window-millis: 3000
     bot-reaction-owner-min-millis: 1000
```

- [ ] **Step 4: 통과 확인**

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.OneCardGameDefinitionTest" --tests "com.mirboard.infra.rest.games.GameCatalogIntegrationTest" --tests "com.mirboard.infra.rest.rooms.RoomControllerIntegrationTest" --tests "com.mirboard.infra.rest.games.OneCardClosedIntegrationTest"`
Expected: PASS — 27건(7 + 4 + 14 + 2).

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 187건.

- [ ] **Step 5: 커밋**

```bash
git add server/src/test/java/com/mirboard/domain/game/onecard/OneCardGameDefinitionTest.java \
  server/src/test/java/com/mirboard/infra/rest/games/GameCatalogIntegrationTest.java \
  server/src/test/java/com/mirboard/infra/rest/rooms/RoomControllerIntegrationTest.java \
  server/src/test/java/com/mirboard/infra/rest/games/OneCardClosedIntegrationTest.java \
  server/src/main/java/com/mirboard/domain/game/onecard/OneCardGameDefinition.java \
  server/src/main/resources/application.yml
git commit -m "feat(D-131): 원카드 공개 — 공개 상태 기본값 AVAILABLE

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: 클라 — 재리뷰 Minor 정리(N-2 본인 큐 가드 테스트, N-4 RoomPage 블록 주석)

N-3 은 Task 5, N-5·N-6 은 Task 8 이 같은 파일을 고치며 처리한다(한 파일 한 태스크).

**Files:**
- Modify: `client/src/ws/staleSocket.test.tsx`, `client/src/pages/RoomPage.tsx`

**Interfaces:**
- Consumes: S5 앞부분 `useStompRoom` 의 정리된 소켓 가드(`disposed` — 본인 큐 핸들러 첫 줄 `if (disposed) return;`), `staleSocket.test.tsx` 의 진짜 stompjs 재현.
- Produces: 테스트 1건 — 방 전환 뒤 이전 소켓에 닿은 이전 방의 본인 큐 프레임이 새 방 sink 로 가지 않는다(헬퍼 `queueFrame`). `RoomPage.tsx` 의 붙어 있던 블록
  주석 둘을 하나로(동작 무관).

- [ ] **Step 1: 고정 테스트 작성**

`client/src/ws/staleSocket.test.tsx`:

```diff
   });
 }
 
+/** 본인 큐 프레임 한 건(비공개 — 손패·ERROR)이 그 소켓에 닿는다. */
+function queueFrame(ws: FakeWS, room: string, type: string) {
+  const id = subscriptionId(ws, `/user/queue/room/${room}`);
+  const body = JSON.stringify({ type, payload: { code: 'NO_RACE', message: 'late' } });
+  act(() => {
+    ws.onmessage?.({
+      data: `MESSAGE\nsubscription:${id}\nmessage-id:q-${room}\ndestination:/user/queue/room/${room}\n\n${body}\0`,
+    });
+  });
+}
+
 /** cleanup 이 보낸 DISCONNECT 의 영수증이 뒤늦게 닿는다 — 이때에야 stompjs 가 onDisconnect 를 부른다. */
 function lateDisconnectReceipt(ws: FakeWS) {
   const receiptId = /receipt:(close-\d+)/.exec(ws.sent.join(''))?.[1];
```

```diff
     expect(sink.applyEvent.mock.calls.length - eventsBefore).toBe(1);
   });
 
+  /**
+   * 재리뷰 N-2 — 본인 큐 핸들러의 가드(`if (disposed) return`)를 고정한다. 이전 방 A 의 소켓이 영수증을 기다리는 틈에 A 의
+   * 비공개 프레임(손패·ERROR)이 닿아도 새 방 B 의 sink 로 가지 않는다 — 가면 B 판에 A 의 손패가 깔리거나 A 의 거절 문구가
+   * 뜬다. 대조로 B 의 본인 큐 프레임은 그대로 간다.
+   */
+  it('방 소켓 — 전환 뒤 이전 소켓에 닿은 이전 방의 본인 큐 프레임이 새 방 sink 로 가지 않는다', async () => {
+    const sink = makeSink();
+    const { rerender } = renderHook(({ room }) => useStompRoom(room, 'tok', sink), {
+      initialProps: { room: 'A' },
+    });
+    await waitFor(() => expect(sockets).toHaveLength(1));
+    accept(sockets[0]);
+
+    rerender({ room: 'B' });
+    await waitFor(() => expect(sockets).toHaveLength(2));
+    accept(sockets[1]);
+    sink.applyPrivateEvent.mockClear();
+
+    queueFrame(sockets[0], 'A', 'ERROR');
+    expect(sink.applyPrivateEvent).not.toHaveBeenCalled();
+
+    queueFrame(sockets[1], 'B', 'ERROR');
+    expect(sink.applyPrivateEvent).toHaveBeenCalledTimes(1);
+  });
+
   it('로비 소켓 — 토큰이 바뀐 뒤 늦게 닿은 이전 소켓의 DISCONNECT 영수증이 새 소켓의 connected 를 내리지 않는다', async () => {
     const { result, rerender } = renderHook(({ token }) => useLobbyStomp(token), {
       initialProps: { token: 'tok-a' },
```

- [ ] **Step 2: 통과 확인(고정 테스트 — 가드가 이미 있다)**

Run: `npm --prefix client run test -- staleSocket`
Expected: PASS — `Test Files  1 passed (1)` · `Tests  4 passed (4)`.

- [ ] **Step 3: 판별력 확인 — 가드를 지우면 빨개지나**

본인 큐 핸들러의 가드를 잠시 지운다:

`client/src/ws/useStompRoom.ts`:

```diff
         // 게임마다 에러 코드가 달라 라벨링 위치가 게임 쪽이어야 하고, 같은 큐인데
         // HAND_DEALT 는 게임이 ERROR 는 훅이 처리하는 비대칭도 없어진다.
         client.subscribe(`/user/queue/room/${roomId}`, (frame) => {
-          if (disposed) return;
           const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
           sinkRef.current.applyPrivateEvent(env);
         });
```

Run: `npm --prefix client run test -- staleSocket`
Expected: FAIL — `Test Files  1 failed (1)` · `Tests  1 failed | 3 passed (4)`(`× 정리된 이전 소켓의 콜백 (D-130) > 방 소켓 — 전환 뒤 이전 소켓에 닿은 이전 방의
본인 큐 프레임이 새 방 sink 로 가지 않는다`).

되돌린다:

`client/src/ws/useStompRoom.ts`:

```diff
         // 게임마다 에러 코드가 달라 라벨링 위치가 게임 쪽이어야 하고, 같은 큐인데
         // HAND_DEALT 는 게임이 ERROR 는 훅이 처리하는 비대칭도 없어진다.
         client.subscribe(`/user/queue/room/${roomId}`, (frame) => {
+          if (disposed) return;
           const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
           sinkRef.current.applyPrivateEvent(env);
         });
```

Run: `npm --prefix client run test -- staleSocket`
Expected: PASS — 4건. `git status` 에 `client/src/ws/useStompRoom.ts` 이 보이지 않아야 한다.

- [ ] **Step 4: 블록 주석 정리(N-4)**

`client/src/pages/RoomPage.tsx`:

```diff
 } from '@/components/ui/select';
 
 /**
- * 대기실 + 게임 테이블 컨테이너. Phase 20d(D-76): 대기실/에러/로딩 셸을
- * shadcn 으로 재디자인. IN_GAME 의 GameTable 은 20e 범위라 레거시 레이아웃
- * 유지(.app-shell 밖에 둬 스코프 base 영향 없음). 상태/WS/핸들러 불변.
- */
-/**
  * D-120·D-129 — 종료 화면을 가진 게임. 이 세션이 IN_GAME→FINISHED 전이를 보면 게임판을 내리지 않고 결과를
  * 보여 준다. 두 게임판은 같은 props 계약(`roomFinished` 포함)을 따른다.
  */
```

```diff
   botSeats: number[];
 }
 
+/**
+ * 대기실 + 게임 테이블 컨테이너. Phase 20d(D-76): 대기실/에러/로딩 셸을
+ * shadcn 으로 재디자인. IN_GAME 의 GameTable 은 20e 범위라 레거시 레이아웃
+ * 유지(.app-shell 밖에 둬 스코프 base 영향 없음). 상태/WS/핸들러 불변.
+ */
 export function RoomPage() {
   const { roomId = '' } = useParams<{ roomId: string }>();
   const token = useAuthStore((s) => s.token);
```

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  61 passed (61)` · `Tests  658 passed (658)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/ws/staleSocket.test.tsx \
  client/src/pages/RoomPage.tsx
git commit -m "test(D-131): 재리뷰 Minor 정리 — 본인 큐 가드 테스트(N-2)·RoomPage 블록 주석(N-4)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: 문서 — 게임 3종·수치, 계약·런북·QA, 설계 §8·§9 + 실측·Phase Gate

코드는 바꾸지 않는다. 아래 diff 는 Task 1~7 을 마친 뒤의 파일 기준이다(이 문서들은 앞 태스크가 건드리지 않았다). 수치는 Task 7 뒤 트리의 실측이다 —
서버 1324 / Docker 불필요 1118(84%), 클라 658·61파일. N-5(`deploy.md` 중복 불릿)·N-6(`onecard.md` §8 진행 표 7행 허브 상태 라벨)을 여기서 처리한다.

**Files:**
- Modify: `docs/game-port.md`, `docs/api.md`, `docs/stomp-protocol.md`, `docs/redis-keys.md`, `docs/architecture.md`, `docs/deploy.md`, `.env.example`, `CLAUDE.md`, `README.md`, `docs/implementation-status.md`, `docs/case-study-multi-game.md`, `docs/plans/mvp-roadmap.md`, `docs/qa-scenarios.md`, `docs/plans/onecard.md`

**Interfaces:**
- Consumes: Task 1~7 의 동작·이름(`turnRemainingMs`·재무장 위치·기록 격리 위치·킥 합치기·생략 기본·공개 기본값·카운트다운), 마지막 실측.

- [ ] **Step 1: 계약 문서(포트·REST·STOMP·Redis·구조)**

`docs/game-port.md`:

```diff
 게임은 `stake=0` 으로 시작하므로 지금 일반화하면 **쓰이지 않는 추상**이 된다. 스컬킹에
 내기를 붙이는 시점에 "승자 집합"만 다루는 게임 중립 정산 이벤트를 별건으로 검토한다.
 
-### 매치 기록기 계약 (D-115·D-117)
+### 매치 기록기 계약 (D-115·D-117·D-131)
 
 ELO·전적은 포트 밖이지만, 새 게임의 매치 기록기(`MatchResultRecorder`·`SkullKingMatchRecorder`
-같은, 게임별 매치 종료 이벤트 리스너)는 다음 두 가지를 지켜야 한다.
+같은, 게임별 매치 종료 이벤트 리스너)는 다음 세 가지를 지켜야 한다.
 
 1. **ELO 적용 여부는 `RatedMatchPolicy.eloApplies(playerIds)` 하나로 판정한다.** 봇(D-71)이나
    게스트(D-117)가 한 명이라도 낀 매치는 전원 미적용이다(승패·탈주는 기록). 조건을 기록기마다
```

```diff
    `newRating=null`). 게스트 정리(`GuestAccountSweeper`)가 이 행을 "매치를 끝낸 증거"로 쓴다.
    참가자 테이블에만 쓰고 stats 를 빠뜨리면, 그 게스트는 48h 뒤 정리 대상이 됐다가 참가자 FK
    때문에 삭제가 매번 실패한다(그 행만 건너뛰고 warn — 정리 자체는 커서로 계속 진행).
+3. **기록 실패는 진행 경로로 새지 않는다(D-130·D-131).** 매치 종료 이벤트는 동기 로컬 발행(D-116)이라 리스너
+   예외가 그대로 발행자(액션·봇·타이머·탈주 경로)로 올라가 마지막 방송·`markFinished`·턴 데드라인 취소(티츄는
+   리매치 대기·칩 정산)를 건너뛴다 — 방이 IN_GAME 에 남는다. 리스너가 **하나**면 엔진 어댑터의 발행 지점에서
+   `RuntimeException` 을 잡아 결과를 실은 ERROR(스택 포함)로 남긴다(원카드·스컬킹). **둘 이상**이면 기록기가
+   `TransactionTemplate` 으로 트랜잭션 경계를 직접 감싸 본문·커밋 예외를 삼킨다(티츄 — 발행 지점에서 잡으면
+   멀티캐스터가 첫 예외에서 멈춰, 리스너 순서에 따라 칩 정산이 빠진다. `@Transactional` 본문 안의 try 로는 커밋
+   실패를 못 잡는다). 고정: `infra.ws.*RecorderFailureTest`.
 
 `users` 를 FK 로 참조하는 테이블을 **매치와 무관한 용도**(신고·역할처럼)로 새로 만들면
 `UserRepository.findExpiredGuestIds` 에 `NOT EXISTS` 를 더할 것. 매치 참가자 테이블은 2번이
```

````diff
 ```
 
 - **기본이 지금까지의 동작**(최대 인원·끔)이라 티츄·스컬킹은 한 줄도 바꾸지 않았다. 원카드만 4·30.
-- 카탈로그(`GET /api/games`)에 실려 모달의 **처음 선택**으로만 쓰인다 — 사용자는 바꿀 수 있고, 서버는 강제하지 않는다.
-  서버의 `capacity` 생략 기본(`RoomService` — maxPlayers)은 따로다(인원 가변 게임은 클라가 늘 `capacity` 를 보낸다).
+- 카탈로그(`GET /api/games`)에 실려 모달의 **처음 선택**이 된다 — 사용자는 바꿀 수 있고, 서버는 범위만 검증한다.
+  **D-131 부터 `POST /api/rooms` 에서 `capacity`·`turnSeconds` 를 생략한 요청의 서버 기본값도 이 값이다**
+  (`RoomService.createRoom` — `RoomServiceCreateDefaultsTest`). 기본 구현이 maxPlayers·0 이라 티츄·스컬킹은 예전
+  생략 기본과 같다.
 
 ### 시간이 지나면 일어나는 전이도 게임이 선언한다 (D-128)
 
````

```diff
   이하일 때만** `onTimer` 를 부른다. 비어 있으면(낡은 발화) 아무것도 하지 않고, 0 보다 크면 같은 세대로 그 시간
   뒤에 다시 걸고 끝낸다(저장·브로드캐스트·재무장 없음). 만기라고 답했는데 `onTimer` 가 비어 있으면(계약 위반)
   WARN 만 남기고 버린다.
-  - 왜 필요한가: 세대 번호는 대부분의 낡은 발화를 거르지만, 진행 경로(액션·매치가 이어지는 탈주·턴 타임아웃·엔진 타이머 발화)는
-    락을 푼 *뒤에* 세대를 올려(봇 경로와 매치를 끝내는 탈주는 락 안) **락 해제와 세대 상승 사이에 틈**이 있다. 그 틈에 만기된 옛
-    타이머는 락 앞뒤의 세대 검사를 모두 통과해 이미 넘어간 상태를 만난다. 세대 번호가 "상태 무변경"을 보장한다고
-    믿으면 안 된다.
+  - 왜 필요한가: 세대 번호는 대부분의 낡은 발화를 거르지만, 세대를 올리는 쪽과 상태를 바꾸는 쪽이 같은 락 안에 있지 않으면
+    **락 해제와 세대 상승 사이에 틈**이 생긴다. 그 틈에 만기된 옛 타이머는 락 앞뒤의 세대 검사를 모두 통과해 이미 넘어간
+    상태를 만난다. 세대 번호가 "상태 무변경"을 보장한다고 믿으면 안 된다. *(D-131: 방 액션 락을 쥔 진행 경로 — 액션·봇·
+    턴 타임아웃·엔진 타이머 발화·매치가 이어지는 탈주 — 는 이제 락을 풀기 전에 재무장한다(resync 의 남은 턴 시간이 같은 락
+    안에서 읽혀야 해서). 락 밖에서 재무장하는 매치 시작(라운드 스타터)에는 틈이 남고, 이 재확인은 그대로 마지막 방어선이다.)*
   - 게임에 주는 뜻: 불린 시점의 상태는 만기라서 `onTimer` 가 시각을 다시 볼 필요는 없다. 대신 `timer` 는 같은
     상태에서 시계가 흐르면 줄어 결국 0 이하가 돼야 하고, 0 이하일 때 `onTimer` 는 전이를 줘야 한다.
   - *처음 설계는 "세대 번호가 보장하므로 `onTimer` 는 시각을 다시 보지 않는다"였다 — S3 최종 리뷰에서 바로잡았다.
-    턴 타임아웃에도 같은 틈이 원래부터 있으나 이번엔 고치지 않았다(`docs/plans/onecard.md` §7 후속).*
+    턴 타임아웃에도 같은 틈이 원래부터 있었다 — D-131 에서 락을 쥔 진행 경로의 재무장을 락 안으로 옮겨 닫았다.*
 - **해상도**: 폴링 주기(`mirboard.scheduling.poll-interval-millis`, 기본 250ms) 단위다. 타이머는 만기 시각에
   정확히 깨지 않고 다음 폴링에서 발견되며(최대 한 주기), 락 경합이면 200ms 뒤 재시도한다. 폴러가 단일 스레드로
   종류(`turn`·`game`·`desertion`)를 차례로 처리하므로 다른 핸들러가 느리면(탈주 확정의 락 대기는 최대 약 3초)
   그만큼 더 늦는다. 게임은 이 지연을 설정으로 흡수한다 — 원카드는 창 끝·봇 시각 직후에 처리된 누름도 인정한다.
 - **취소**: 세대가 오르면(누가 행동함) 두 종류가 함께 지워진다. `cancel()`(D-122)도 마찬가지다.
+- **턴의 남은 시간(D-131)** — 포트가 아니라 인프라가 답한다: resync 응답의 `turnRemainingMs` 는 지금 세대의
+  `deadlines:turn` 항목 − 지금이다(`TurnTimeoutScheduler.turnRemaining`). 게임에 새로 요구하는 것은 `pendingSeats` 뿐이다 —
+  비어 있으면(원카드 경쟁 창·끝난 매치) 걸린 데드라인이 있어도 발화해 봐야 아무 일이 없으므로 null 이다.
+  대기 좌석이 여럿인 단계(티츄 Dealing·Passing, 스컬킹 BIDDING)에서는 **첫 대기 좌석(`pendingSeat`)의 자동 처리까지**
+  남은 시간이지 각 좌석의 남은 시간이 아니다 — 시간 초과는 그 좌석 하나만 처리하고 처음부터 다시 걸며, 다른 좌석의
+  행동도 처음부터 다시 건다.
 - **왜 `GameAction` 이 아니라 `Result` 인가**: 액션은 클라 JSON 에서 역직렬화된다. "창 닫기" 같은 시스템
   전이를 그 계층에 두면 클라가 위조해 보낼 수 있다.
 - 시간 전이가 진행 중인 동안 게임은 `pendingSeats` 를 비워 두면 된다(원카드 경쟁 창) — 봇 루프와 턴
```

```diff
 스컬킹 방은 항상 8인이 되어 4인 게임을 만들 수 없었다.
 
 **변경 범위** (계약 슬라이스 — 서버 DTO + 클라 미러 + docs 한 커밋):
-- `POST /api/rooms` 에 `capacity` 선택 필드. 미지정 시 `def.maxPlayers()`(현행 호환).
+- `POST /api/rooms` 에 `capacity` 선택 필드. 미지정 시 `def.maxPlayers()`(현행 호환). *(D-131: 미지정 시
+  `def.defaultPlayers()` — 기본 구현이 maxPlayers 라 원카드만 4.)*
 - 검증: `def.minPlayers() <= capacity <= def.maxPlayers()`. 위반 시 `INVALID_CAPACITY`.
 - 클라 방 만들기 모달에 인원 선택(게임이 가변일 때만 노출).
 - 티츄는 `min=max=4` 라 UI 가 안 바뀐다 — **기존 동작 무변경**.
```

```diff
    `engine.actionType()` 이 대상 타입을 주고 컨트롤러가 변환한다. 목적지는 하나로 유지되어
    클라 계약은 바뀌지 않았다. 알 수 없는 판별자는 `ERROR(INVALID_ACTION)`.
 3. **봇 정책은 포트 메서드 + 게임별 override 로 분리.** `botAction(state, seat, random)` 의
-   기본 구현이 "합법 액션 균등 분포"다. 두 게임 모두 이를 결정적 휴리스틱으로 override 한다 —
-   티츄는 `HeuristicBotPolicy`(D-118), 스컬킹은 `SkullKingBotPolicy`(D-119). 둘 다 공개 정보만
+   기본 구현이 "합법 액션 균등 분포"다. 세 게임 모두 이를 결정적 휴리스틱으로 override 한다 —
+   티츄는 `HeuristicBotPolicy`(D-118), 스컬킹은 `SkullKingBotPolicy`(D-119), 원카드는 `OneCardBotPolicy`(D-128 — 경쟁 창의
+   누름은 정책이 아니라 창을 열 때 추첨한 반응 시간으로 엔진 타이머가 한다). 셋 다 공개 정보만
    보고 `random` 인자를 쓰지 않으므로 같은 상태면 같은 수다(`rules-tichu.md` §16 ·
    `rules-skullking.md` §16). 시드 `Random` 은 스케줄러가 계속 보유하므로 포트 기본 봇을 쓰는
    새 게임에는 `mirboard.bot.seed` 재현성이 그대로 남는다.
```

`docs/api.md`:

````diff
 ```json
 {
   "games": [
-    {
-      "id": "TICHU",
-      "displayName": "티츄",
-      "shortDescription": "4인 파트너 카드 게임. 56장 덱과 4장의 특수카드.",
-      "minPlayers": 4,
-      "maxPlayers": 4,
-      "status": "AVAILABLE",
-      "supportedRoomOptions": ["TARGET_SCORE", "TEAMS", "BETTING"],
-      "defaultPlayers": 4,
-      "defaultTurnSeconds": 0
-    },
     {
       "id": "SKULL_KING",
       "displayName": "스컬킹",
````

````diff
       "shortDescription": "2~6인 손패 털기. 공격을 쌓아 넘기고, 한 장 남으면 누구보다 먼저 \"원카드!\"를 외친다.",
       "minPlayers": 2,
       "maxPlayers": 6,
-      "status": "COMING_SOON",
+      "status": "AVAILABLE",
       "supportedRoomOptions": [],
       "defaultPlayers": 4,
       "defaultTurnSeconds": 30
+    },
+    {
+      "id": "TICHU",
+      "displayName": "티츄",
+      "shortDescription": "4인 파트너 카드 게임. 56장 덱과 4장의 특수카드.",
+      "minPlayers": 4,
+      "maxPlayers": 4,
+      "status": "AVAILABLE",
+      "supportedRoomOptions": ["TARGET_SCORE", "TEAMS", "BETTING"],
+      "defaultPlayers": 4,
+      "defaultTurnSeconds": 0
     }
   ]
 }
 ```
 - `status` 값: `AVAILABLE` (플레이 가능) / `COMING_SOON` (UI에서 비활성화 표시) /
   `DISABLED` (응답에서 제외).
-- 정렬: `AVAILABLE` 우선, 그 안에서 displayName 가나다 순.
+- 정렬: `AVAILABLE` 우선, 그 안에서 displayName 가나다 순 — 세 게임이 모두 공개라(D-131) 스컬킹·원카드·티츄.
 - `supportedRoomOptions`(D-106): 이 게임이 **실제로 쓰는** 방 설정. 값은
   `TARGET_SCORE`·`TEAMS`·`BETTING` 이고 enum 선언 순서로 정렬된다. 방 만들기 UI 와
   대기실은 여기 있는 것만 노출하고, 서버도 같은 집합으로 검증한다
````

```diff
   들어가지 않는다.
 - `defaultPlayers`·`defaultTurnSeconds`(D-130): 방 만들기 모달의 **처음 선택**(사용자는 바꿀 수 있다). 게임이
   선언하며(`GameDefinition.defaultPlayers()`·`defaultTurnSeconds()`) 기본은 `maxPlayers`·`0`(끔) — 티츄·스컬킹은
-  지금까지와 같다. 원카드만 4명·30초다. 서버의 `capacity`·`turnSeconds` 생략 기본(`maxPlayers`·0, 아래 방 만들기)과는
-  별개다 — 인원 가변 게임은 클라가 늘 `capacity` 를 보낸다.
+  지금까지와 같다. 원카드만 4명·30초다. **D-131 — 방 만들기에서 `capacity`·`turnSeconds` 를 생략해도 서버가 이 값을
+  쓴다**(아래 방 만들기) — 모달의 처음 선택과 생략한 요청이 같은 방을 연다.
 
 ### GET `/api/games/{gameId}`
 단일 게임 상세. 응답은 위 항목 형식과 동일하되 룰 요약 등 추가 필드가 들어갈 수 있다
```

```diff
 - `gameType` 은 `GameRegistry` 에 등록되고 `status==AVAILABLE` 인 ID여야 한다.
 - 선택: `teamPolicy`, `fillWithBots`, `targetScore`, `turnSeconds`, `stake`(D-81),
   `capacity`(D-99).
-- `capacity`(D-99): 방 인원. **생략하면 `GameDefinition.maxPlayers()`**. 게임이 정한
+- `capacity`(D-99): 방 인원. **생략하면 게임 선언 `defaultPlayers`**(D-131 — 카탈로그와 같은 값, 티츄 4·스컬킹 8·원카드 4. 예전
+  생략 기본 `maxPlayers` 와는 원카드만 다르다). 게임이 정한
   `minPlayers() <= capacity <= maxPlayers()` 를 벗어나면 `INVALID_CAPACITY`. 인원이
   고정된 게임(티츄는 `min=max=4`)은 그 값 하나만 통과하므로 사실상 생략과 동일하다 —
   클라는 `minPlayers === maxPlayers` 인 게임에서 이 필드를 아예 보내지 않는다.
   `fillWithBots` 의 좌석 채우기도 확정된 `capacity` 를 따른다(시드 봇은 4명뿐이므로
   `capacity - 1 > 4` 인 방은 봇으로 채울 수 없다 — 봇 풀 확장은 스컬킹 통합 시 별건).
+- `turnSeconds`(Phase 13D): 개인 턴 제한(초, 0=끔). **생략하면 게임 선언 `defaultTurnSeconds`**(D-131 — 티츄·스컬킹 0,
+  원카드 30). 턴 제한이 있는 방의 resync 는 지금 턴의 남은 시간을 싣는다(`turnRemainingMs`, 아래 resync).
 - `stake`(D-81): 판돈(가상 칩). 허용값 `{0,10,50,100,500}`(기본 0=내기 없음). 생성 시
   고정·불변. **stake>0 이면 `fillWithBots` 불가**(봇=무한 잔액 → 칩 파밍 방지).
 - **게임별 옵션 게이팅(D-106)**: `targetScore`·`stake` 는 게임의 `supportedRoomOptions`
```

```diff
   "좌석 순서")만 가른다.
 
 응답 `201` — Room (위 형식과 동일, 본인이 host로 자동 join 됨).
-에러: `INVALID_INPUT` (gameType 미등록 또는 COMING_SOON/DISABLED 상태),
+에러: `GAME_NOT_AVAILABLE` 404 (gameType 미등록 또는 COMING_SOON/DISABLED 상태 — `OneCardClosedIntegrationTest`),
 `INVALID_CAPACITY` (게임 허용 인원 범위 밖 — `details` 에 `capacity`/`minPlayers`/
 `maxPlayers`), `INVALID_STAKE` (허용값 외 판돈), `STAKED_ROOM_NO_BOTS` (판돈 방 + 봇
 동시 요청), `UNSUPPORTED_ROOM_OPTION` (게임이 안 쓰는 방 설정 — `TARGET_SCORE`/`BETTING`
```

````diff
     { "suit": null, "rank": 0, "special": "PHOENIX" }
   ]},
   "disconnectedSeats": [3],
-  "chips": { "17": 1000, "18": 900, "19": 1100, "20": 1000 }
+  "chips": { "17": 1000, "18": 900, "19": 1100, "20": 1000 },
+  "turnRemainingMs": 17450
 }
 ```
 - 좌석 식별은 **seat(0~3, playerIds 인덱스)**, `handCounts`/`declarations` 키도 seat.
````

```diff
   그 방의 참가자·관전자일 때만이다(`docs/stomp-protocol.md`).
 - `disconnectedSeats`: 현재 끊긴 플레이어 좌석(재접속 배지 즉시 반영, D-75).
 - `chips`: D-82 방 단위 테이블 칩(userId→칩). 내기 없는 방은 빈 맵.
+- `turnRemainingMs`(D-131, 게임 중립): 지금 턴이 시간 초과로 끝나기까지 남은 ms(0 이상) — 서버가 실제로 발화할 턴 데드라인
+  (지금 세대의 `deadlines:turn` 항목) − 지금. 재접속 직후에도 카운트다운이 맞게 하려는 값이다. **null** 이면 세지 않는다: 턴
+  제한 끔(`turnSeconds` 0)·IN_GAME 아님·기다리는 좌석 없음(원카드 경쟁 창·끝난 매치 — 걸린 데드라인이 발화해도 아무 일이
+  없다)·지금 세대의 데드라인 없음. 스냅샷과 **같은 락 안에서** 읽는다 — 진행 경로가 저장·방송·다음 턴 데드라인 재무장을 모두
+  그 락 안에서 끝내므로 함께 읽은 상태의 턴 값이다(락을 약 3초 못 잡아 잠금 없이 읽은 응답과 매치 시작 직후 ms 단위 틈은
+  예외 — null 이나 이전 값일 수 있고 다음 `TURN_CHANGED` 가 바로잡는다). 대기 좌석이 여럿인 단계(티츄 Dealing·Passing,
+  스컬킹 BIDDING)에서는 첫 대기 좌석(`pendingSeat`)의 자동 처리까지 남은 시간이다 — 시간 초과는 그 좌석 하나만 처리하고
+  다시 처음부터 걸며, 다른 좌석의 행동도 다시 처음부터 건다(각 좌석의 남은 시간으로 보이면 안 된다). 지금은 원카드 게임판만
+  쓴다(원카드는 대기 좌석이 늘 0~1개).
 - `completedRounds`: D-108 **끝난 라운드들의** 점수(순서 = 라운드 1..N). 바로 위
   `roundScores` 와 혼동 주의 — 그쪽은 **현재 라운드**의 팀별 점수다. 클라는 이 값으로
   라운드 내역을 통째로 교체하므로(append 아님), 재접속·새 기기에서도 내역이 온전하다.
```

```diff
 - `result`: 끝났으면 `MATCH_ENDED` payload 와 같은 `{ reason, standings: [{seat, rank, cardsLeft, status}] }`.
 - `privateHand` = `{ "seat": 0, "hand": [{ "suit": "CLUB", "rank": 3, "joker": null }], "handVersion": 41 }` —
   손패 전체와 상태 버전. 클라는 가진 것보다 낮은 `handVersion` 의 손패를 버린다(비공개 `HAND_UPDATED` 와 같은 축).
-  `chips` 는 늘 `{}`(내기 미지원).
+  `chips` 는 늘 `{}`(내기 미지원). 위 예시(경쟁 창)의 봉투 `turnRemainingMs` 는 null 이다 — 창이 열린 동안은 기다리는 좌석이
+  없다. 창이 닫히면 `TURN_CHANGED` 와 함께 다음 차례의 턴 데드라인이 처음부터 걸린다(카운트다운 — `docs/stomp-protocol.md`).
 
 - **`privateHand` 는 요청자가 실제로 앉은 좌석에만**(D-122 심층 방어, 모든 게임 공통). 좌석은
   `playerIds` 의 인덱스인데, 게임이 시작된 뒤 목록이 정원(`capacity`)보다 줄었다면 인덱스가
```

`docs/stomp-protocol.md`:

```diff
 6. 새 상태 저장, `engine.advance(...)` 로 라운드/매치 진행 이벤트 합류,
    순번을 쓰는 이벤트마다 `room:{id}:seq` INCR(D-126 — `sequenced()` false 면 건너뜀),
    envelope 을 공개/비공개로 분기 전송.
-7. 락 해제 후 봇 스케줄(`BotScheduler`)·턴 타임아웃(`TurnTimeoutScheduler`) 트리거.
+7. 재무장(`TurnTimeoutScheduler.onTurnAdvanced` — 세대를 올려 이전 항목을 지우고, 엔진 타이머는 턴 제한과 무관하게(D-128),
+   다음 턴 데드라인은 턴 제한이 있을 때만 건다) — D-131 부터
+   **락 안**(방송 뒤). resync 가 같은 락 안에서 `turnRemainingMs` 를 읽는다. 재무장이 던져도 잡아 ERROR 로 남기고 다음
+   단계로 간다(액션은 이미 저장·방송됐다).
+8. 락 해제 후 봇 스케줄(`BotScheduler`) — 쥔 채 걸면 봇 루프가 이 락과 부딪쳐 재시도한다.
 
 > **D-122 — 끝난 방은 진행하지 않는다.** 봇 루프와 턴 타임아웃 발화(D-128 부터 엔진 타이머 발화도)도 위
 > 1·3 과 같은 방 상태 가드(락 전·락 안)를 거친다 — 게임 중립 판정이라 엔진 상태로는 매치가 안 끝난 강제
```

```diff
 ## 원카드 (gameType=ONE_CARD, D-128)
 
 같은 목적지·같은 envelope 를 쓴다. 좌석은 **0 ~ seatCount−1** (2~6). 룰 정본은 `docs/rules-onecard.md`.
-클라 게임판(S4, D-129)은 준비됐고, 카탈로그 상태는 열림 전환(별건) 전까지 `COMING_SOON` 이다(`mirboard.onecard.status`).
+D-131 부터 공개(`AVAILABLE`)다 — 되돌리기는 설정 `mirboard.onecard.status`(운영 런북 `docs/deploy.md`).
 
 **클라 → 서버 `@action`**:
 
```

```diff
   채 `NO_RACE` 로 거절되면 **창마다 한 번** resync 한다 — 서버 resync 는 진행 킥으로 사라진 타이머를 다시 건다.
   resync 스냅샷을 받으면 기다리던 누름 표식은 같은 창이라도 비운다(끊긴 사이 보낸 누름은 버려졌을 수 있다). 그 뒤
   늦게 온 그 누름의 `BUSY` 는 창이 열려 있으면 조용히 삼킨다(창 동안 내기·먹기는 막혀 있어 그 `BUSY` 는 누름의 것이다).
+- **D-131 — 턴 카운트다운.** 턴 제한이 있는 방이면 게임판이 차례 옆에 남은 시간을 센다. 기준은 서버다 — resync 의
+  `turnRemainingMs`(지금 세대 턴 데드라인까지, `docs/api.md`)로 맞추고, 서버가 턴 데드라인을 처음부터 다시 거는 순간의 공개
+  이벤트에서 방의 턴 제한부터 다시 센다: `TURN_CHANGED`, 그리고 `PLAYER_ELIMINATED`(차례가 그대로인 탈주 뒤에도 서버는
+  데드라인을 다시 건다 — 창이 열려 있지 않을 때). `CARD_PLAYED` 에서 멈추고 창이 열린 동안은 숨긴다(다음 차례가 멈춰 있다 —
+  서버도 기다리는 좌석이 없으면 `turnRemainingMs` 를 null 로 준다). 0 이 되면 서버의 시간 초과(먹기)가 다음 이벤트를 낸다.
 - **D-130 — 경쟁 결과 로그.** 창이 닫힐 때마다 서버가 INFO 한 줄(`OneCard race resolved: … outcome via owner… by…
   latencyMs windowMs lateMs`)을 남긴다. 사용자별 값이라 메트릭이 아니라 로그로만 둔다. PRESS·TIMER 줄은 저장 전에 찍혀(DESERTION
   은 저장 뒤) 저장 실패 뒤 같은 창이 두 번 찍힐 수 있으므로 `room`+`raceId` 의 마지막 줄이 정본이다(`docs/deploy.md`).
```

`docs/redis-keys.md`:

```diff
 | `room:{roomId}:lock` | STRING | 2s | 액션 직렬화 락 | `SET key NX EX 2` |
 | `presence:room:{roomId}` | HASH | 6h | `userId` → 해당 방을 보고 있는 **세션 수** | D-96(D-111 보정). `RoomPresence`. 방 토픽 SUBSCRIBE 시 `presence_join.lua` 로 **세션당 1회만** `HINCRBY +1`, DISCONNECT 시 `presence_leave.lua` 로 −1(0 이면 `HDEL`, 빈 HASH 면 키 자체 `DEL`). **boolean 이 아니라 카운터** — 탭 두 개 중 하나만 닫아도 접속 중이어야 탈주 오판이 없다. 탈주 유예 만료 시 "재접속했는가"(`hasLiveSession`) 판정의 근거 |
 | `presence:session:{sessionId}` | STRING | 6h | `"{userId}:{roomId}"` | D-96(D-111 보정). `RoomPresence`. 역할 둘: ① DISCONNECT 이벤트는 sessionId 만 주므로 역방향 조회, ② **"이 세션을 이미 셌는가" 표식** — `SET NX` 성공 시에만 카운터를 올려 한 세션의 구독 여러 개가 중복 계수되지 않게 한다. leave 시 `DEL` |
-| `deadlines:{kind}` | ZSET | 12h | member=페이로드, score=만료 `epochMillis` | D-96. `DeadlineQueue`. `kind`=`turn`(member `{roomId}#{generation}`, `TurnTimeoutScheduler`) · `game`(D-128 엔진 타이머 — 같은 member 형식·같은 세대 번호, 무장은 `TurnTimeoutScheduler`, 발화는 `EngineTimerScheduler`) · `desertion`(member `{roomId}:{userId}`, `DesertionGraceScheduler`). 모든 인스턴스가 폴링(`mirboard.scheduling.poll-interval-millis`, 기본 250ms)하고 만료분 pop 은 `deadline_poll.lua` 로 원자화 — 한 항목은 정확히 한 인스턴스에만 간다. 같은 member 재등록 = score 갱신(= 기존 타이머 취소+재등록). `schedule()` 마다 EXPIRE 갱신 |
+| `deadlines:{kind}` | ZSET | 12h | member=페이로드, score=만료 `epochMillis` | D-96. `DeadlineQueue`. `kind`=`turn`(member `{roomId}#{generation}`, `TurnTimeoutScheduler`) · `game`(D-128 엔진 타이머 — 같은 member 형식·같은 세대 번호, 무장은 `TurnTimeoutScheduler`, 발화는 `EngineTimerScheduler`) · `desertion`(member `{roomId}:{userId}`, `DesertionGraceScheduler`). 모든 인스턴스가 폴링(`mirboard.scheduling.poll-interval-millis`, 기본 250ms)하고 만료분 pop 은 `deadline_poll.lua` 로 원자화 — 한 항목은 정확히 한 인스턴스에만 간다. 같은 member 재등록 = score 갱신(= 기존 타이머 취소+재등록). `schedule()` 마다 EXPIRE 갱신. D-131 — resync 가 `turn` 의 지금 세대 항목을 `ZSCORE` 로 읽어 남은 턴 시간(`turnRemainingMs`)을 준다(읽기만) |
 | `login:fail:{username}` | STRING(INTEGER) | 윈도(기본 15m) | 로그인 연속 실패 횟수 | D-84. `INCR`+첫 실패 시 EXPIRE. 임계 초과 시 lock 설정, 성공 시 DEL |
 | `lock:login:{username}` | STRING | 잠금(기본 15m) | 잠금 마커 | D-84. 존재 시 423 ACCOUNT_LOCKED. users 스키마 비침범(휘발) |
 | `ratelimit:{bucket}:{subject}` | STRING(INTEGER) | 윈도(TTL) | 버킷별 요청 카운터 | D-90(D-84 확장). `bucket`=`auth`·`guest`(D-117, 24h)·`api-default`·`room-create`·`expensive-write`·`game-action`·`chat`·`reaction`·`stomp-default`. `subject`=인증 시 `u:{userId}`, 아니면 `ip:{ip}`(NAT 오탐 회피). **D-117: `/api/auth/**`(`auth`·`guest`)는 Bearer 를 실어도 항상 `ip:` 키**. IP 는 신뢰 헤더 `mirboard.ratelimit.client-ip-header`(운영 `Fly-Client-IP`, 없으면 remoteAddr)에서 읽고 IPv6 는 `x:x:x:x::/64` 로 묶는다(예: `ratelimit:guest:ip:2001:db8:1:2::/64`). `bucket` 은 원본 URI 가 아니라 MVC·Security 가 매칭하는 정규화 경로(디코딩·contextPath/`X-Forwarded-Prefix` 제외)로 고른다 — 인코딩 변형으로 버킷을 갈아타지 못하게(D-117 보정). Lua 원자 고정 윈도(`INCR`+`EXPIRE`). HTTP 초과=429(`Retry-After`=윈도 초), STOMP 초과=드롭(액션만 본인 큐 `ERROR(RATE_LIMITED)`). 클라 IP 는 휘발 카운터 키(영속 로그 아님) |
```

`docs/architecture.md`:

````diff
        · 공개 이벤트 → /topic/room/{id}        (PLAYED, TURN_CHANGED, TRICK_TAKEN, ...)
        · 비공개 이벤트 → /user/queue/room/{id}  (HAND_DEALT, CARDS_RECEIVED, 원카드 HAND_UPDATED — seq 없음, D-126·D-129)
        · 모든 메시지는 { eventId, seq, type, ts, payload } envelope 로 래핑
-  8. lock 해제 — 저장·순번 발급이 모두 락 안이라, resync 가 같은 락 안에서 읽으면
-     스냅샷과 eventSeq 가 같은 시점이다(D-126)
-  9. 클라 리듀서(tichuStore)가 이벤트 적용 → 리렌더
+  8. 다음 턴 데드라인·엔진 타이머 재무장(TurnTimeoutScheduler.onTurnAdvanced — D-131, 락 안.
+     실패해도 잡아 ERROR — 액션은 이미 저장·방송됐다)
+  9. lock 해제 — 저장·순번 발급·재무장이 모두 락 안이라, resync 가 같은 락 안에서 읽으면
+     스냅샷·eventSeq·turnRemainingMs 가 같은 시점이다(D-126·D-131)
+ 10. 락 해제 뒤 봇 스케줄(BotScheduler — 봇 차례면 비동기 루프)
+ 11. 클라 리듀서(게임별 스토어 — tichuStore 등)가 이벤트 적용 → 리렌더
 ```
 
 envelope 규약과 토픽·큐·이벤트 카탈로그는 `docs/stomp-protocol.md` 가 단일 진실 공급원.
````

```diff
 `GameRegistry` 는 Spring 컨텍스트의 모든 `GameDefinition` Bean을 자동 수집한다.
 방 생성·카탈로그·엔진 인스턴스화가 전부 이 레지스트리를 경유하므로, 새 게임은
 `GameDefinition @Component` 등록만으로 연결된다(로비/REST 코드 무변경). 현재 등록:
-`TichuGameDefinition` (id `TICHU`, 4인, `AVAILABLE`).
+`TichuGameDefinition`(id `TICHU`, 4인)·`SkullKingGameDefinition`(id `SKULL_KING`, 2~8인)·
+`OneCardGameDefinition`(id `ONE_CARD`, 2~6인 — 공개 상태는 설정 `mirboard.onecard.status`, 기본 `AVAILABLE`, D-131).
 
 ### 4.4 봇 / 타임아웃 스케줄러
 
```

- [ ] **Step 2: 운영 런북과 환경 변수 예시**

`docs/deploy.md`:

```diff
 
 ## 원카드 공개 상태 (`MIRBOARD_ONECARD_STATUS`, D-128)
 
-원카드의 카탈로그 상태는 설정이다(`mirboard.onecard.status`, 코드 기본값은 `application.yml`). 운영에서는 시크릿으로만
-덮어쓴다 — `fly.toml` 의 `[env]` 에 같은 키를 두지 않는다(두 곳에 있으면 어느 값이 이기는지 헷갈린다).
+원카드의 카탈로그 상태는 설정이다(`mirboard.onecard.status`, 코드 기본값은 `application.yml` — **D-131 부터 `AVAILABLE`**, 공개).
+운영에서는 시크릿으로만 덮어쓴다 — `fly.toml` 의 `[env]` 에 같은 키를 두지 않는다(두 곳에 있으면 어느 값이 이기는지 헷갈린다).
+평소에는 시크릿을 **두지 않는다**(코드 기본값 그대로 공개). 공개 전(D-131 이전)에 `AVAILABLE` 로 열어 둔 시크릿이 남아 있으면
+지운다 — 아래 "지금 값 확인".
 
 | 값 | 허브 카탈로그 | 새 원카드 방 | 진행 중인 원카드 방 |
 | --- | --- | --- | --- |
```

````diff
 ```bash
 # 끄기(되돌리기) — 머신이 재시작된다
 flyctl secrets set MIRBOARD_ONECARD_STATUS=COMING_SOON -a mirboard
-# 다시 열기 — 시크릿을 지우면 코드 기본값으로 돌아간다(공개 전환으로 코드 기본값이 AVAILABLE 이 된 뒤 — 그 전에는
-# `secrets set MIRBOARD_ONECARD_STATUS=AVAILABLE`)
+# 다시 열기 — 시크릿을 지우면 코드 기본값(AVAILABLE)으로 돌아간다. 이것도 머신 재시작이다
 flyctl secrets unset MIRBOARD_ONECARD_STATUS -a mirboard
+# 지금 값 확인 — 이름과 digest 만 보인다(값은 안 보인다). 목록에 MIRBOARD_ONECARD_STATUS 가 없으면 코드 기본값(공개)이다.
+# 있으면 실제 상태는 허브 카탈로그로 본다(로그인 뒤 원카드 카드에 'Coming Soon' 이 붙었는가). 공개 전에 AVAILABLE 로
+# 열어 둔 것이면 위 unset 으로 지워 코드 기본값을 따르게 한다.
+flyctl secrets list -a mirboard
 ```
 
 - **`DISABLED` 는 진행 중인 원카드 방도 대기 중인 방도 없을 때만.** 경과 시간으로는 보장되지 않는다 — `COMING_SOON` 뒤에도 남은
````

```diff
   된다. `COMING_SOON` 은 새 방을 막으므로 `IN_GAME`·`WAITING` 줄은 늘지 않는다 — 비워질 때까지 기다린다.
 - **잘못된 값은 앱 전체 기동 실패다**(빈 값·오타 — 의도된 fail-fast). 사고 중에 쓰는 손잡이이므로 값은 위 명령을
   그대로 복사한다(대문자).
-- 시크릿을 바꾸면 머신이 재시작된다. 재시작 순간 봇 차례였던 방은 클라가 다시 붙을 때(resync·게임 토픽 구독) 진행
-  킥이 봇 루프와 사라진 경쟁 타이머를 다시 건다(D-130) — 판이 멈춘 채 남지 않는다.
 - **시크릿 변경은 앱 전체 재시작이다** — 다른 게임의 진행 중 방도 끊긴다. 콜드 스타트 ~100초가 탈주 유예 120초에 가깝다
-  (`fly.toml`·`mirboard.desertion.grace-seconds`) — 진행 중인 방이 많으면 피하고, 바꾼 뒤 재접속을 확인한다.
+  (`fly.toml`·`mirboard.desertion.grace-seconds`) — 진행 중인 방이 많으면 피하고, 바꾼 뒤 재접속을 확인한다. 재시작 순간 봇
+  차례였던 방은 클라가 다시 붙을 때(resync·게임 토픽 구독) 진행 킥이 봇 루프와 사라진 경쟁 타이머를 다시 건다(D-130) — 판이
+  멈춘 채 남지 않는다.
+- **머신은 1대로 둔다**(`fly scale count 1`). 진행 킥은 **이 인스턴스의** 봇 루프만 보고(다른 인스턴스의 루프 위에 겹쳐 걸면
+  봇이 지연 없이 연달아 둔다), 같은 방 킥 합치기(D-131)도 인스턴스 메모리다. 엔진 타이머·턴 데드라인은 Redis 라 인계되지만,
+  원카드 경쟁 창의 정확도는 단일 폴러 지연을 그대로 받는다(`docs/plans/onecard.md` §8 남은 후속 — 봇 차례도 데드라인으로).
 
 **경쟁 튜닝**(룰 §9). 환경 변수 이름은 설정 키에서 나온다(Spring relaxed binding). 잘못된 조합(최소 > 최대, 창 ≤ 0)도
 기동 실패다(`RaceSettings`). 봇 반응 구간을 바꾸면 튜토리얼의 "1.0~2.5초"(`onecardTutorialSteps.tsx`·`ReactionPractice.tsx`),
```

```diff
 의심한다), `lateMs` 는 타이머 경로가 정해 둔 마감보다 늦게 처리된 시간(단일 폴러 지연)이다. **집계는 `room`+`raceId` 로
 묶어 마지막 줄을 정본으로 센다** — PRESS·TIMER 줄은 저장 전에 찍히므로(DESERTION 은 어댑터가 저장한 뒤에 찍는다), 저장이
 실패한 뒤 같은 창이 다른 경로(타이머·킥이 다시 건 타이머)로 닫히면 같은 창이 두 번(다른 결과로) 찍힐 수 있다.
+
+**매치 기록 실패**(D-130·D-131, 세 게임 공통). 매치가 끝날 때 전적·ELO 기록(DB)이 실패해도 판은 정상으로 끝난다(마지막 방송·
+FINISHED 전이·티츄 칩 정산과 리매치 대기). 대신 Sentry 에 ERROR 가 남는다 —
+`Tichu|SkullKing|OneCard match record failed, the match still ends: room=… players=… …`(결과값과 스택 포함). 이 줄이 보이면 그 매치의
+`*_match_results`·참가자 행과 `user_game_stats`(승패·레이팅·탈주)가 **통째로 빠졌다**(한 트랜잭션이라 일부만 남지 않는다).
+자동 재시도는 없다 — 로그의 room·players·결과로 영향 범위(누구의 전적이 빠졌는가)를 확인하고, 같은 시각의 DB 장애 원인부터
+본다. 수동 복구(빠진 행 다시 넣기)는 별건이다.
 
 ---
 
```

`.env.example`:

```diff
 # application-prod.yml 에서만 읽는다. 로컬은 remoteAddr.
 
 # ── 원카드 (D-128, 런북 docs/deploy.md "원카드 공개 상태") ────────────────
-# 카탈로그 공개 상태: AVAILABLE | COMING_SOON | DISABLED. 쓰지 않으면 이 줄을 빼 둔다(코드 기본값) —
+# 카탈로그 공개 상태: AVAILABLE | COMING_SOON | DISABLED. 쓰지 않으면 이 줄을 빼 둔다(코드 기본값 AVAILABLE — D-131 공개) —
 # 빈 값·오타는 앱 전체 기동 실패다. 운영의 되돌리기는 COMING_SOON 으로만(진행 중인 판은 끝까지 간다).
 # DISABLED 는 진행 중·대기 중인 원카드 방을 멈추므로(대기 방도 시작하는 순간 멈춘다) 그런 방이 0 일 때만 — 경과 시간으로는
 # 보장되지 않아 Redis 로 확인한다(런북).
```

- [ ] **Step 3: 저장소 안내·현황·쇼케이스·로드맵·QA**

`CLAUDE.md`:

```diff
 
 ## 프로젝트 현황
 
-**Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + **게임 2종**: 티츄(4인 2:2 팀전), 스컬킹(2~8인 개인전).
-
-현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M5 전부 완료, 결정 이력 D-129까지). 로비/방 → 두 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소.
-
-- **서버** `server/` (Spring Boot 4 / Java 25, Gradle): 도메인 `domain.lobby`·`domain.game.{core,tichu,scoring}`, 인프라 `infra.{rest,ws,bot,messaging,metrics,config,web}`.
+**Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + **게임 3종**: 티츄(4인 2:2 팀전), 스컬킹(2~8인 개인전), 원카드(2~6인 개인전 + "원카드!/잡기!" 실시간 경쟁, D-123~D-131).
+
+현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M7 전부 완료, 결정 이력 D-131까지). 로비/방 → 세 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소. 세 번째 게임 원카드는 포트 확장 1건(엔진 타이머 `timer`/`onTimer`, D-128)과 D-126 의 `sequenced()` 재정의만으로 붙었고(인프라 grep `onecard` 0건) D-131 에서 공개됐다(`MIRBOARD_ONECARD_STATUS` 로 되돌림 — `docs/deploy.md`).
+
+- **서버** `server/` (Spring Boot 4 / Java 25, Gradle): 도메인 `domain.lobby`·`domain.game.{core,tichu,skullking,onecard,scoring}`, 인프라 `infra.{rest,ws,bot,messaging,metrics,config,web}`.
 - **클라이언트** `client/` (Vite + React 18 + TS, Zustand, @stomp/stompjs, Tailwind+shadcn).
 - **계약 문서(정본)**: `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP), `docs/redis-keys.md`(Redis), `docs/rules-tichu.md`(룰), `docs/game-port.md`(`GameEngine` 포트), `server/src/main/resources/db/migration/V*.sql`(Flyway V1~).
 - **현황 단일 진실원**: `docs/implementation-status.md`(기능별 ✅ 표). 이력 `docs/decisions.md`, 로드맵 `docs/plans/mvp-roadmap.md`.
```

```diff
   `TEAMS` 는 예외적으로 게이트가 아니라 **라벨 결정자**다(팀전 "팀 배정" / 개인전
   "좌석 순서"). `teamPolicy` 는 게임을 가리지 않으며 서버도 거절하지 않는다.
 - 새 게임 추가 절차: `domain.game.{newgame}` 패키지 + `GameDefinition @Component` Bean 등록
-  (+ `GameEngine` 구현, `GameStartingEvent` 리스너로 라운드 시작) → 카탈로그/방 생성/인게임
+  (+ `GameEngine` 구현, `GameStartingEvent` 리스너로 라운드 시작, 매치 기록 실패 격리 — `docs/game-port.md`
+  "매치 기록기 계약" 3) → 카탈로그/방 생성/인게임
   디스패치/봇/타임아웃/resync 가 자동 연결됨. 로비·허브 컨트롤러와 스케줄러 수정 불필요.
   (D-102 스컬킹이 이 약속을 실증 — REST/WS 컨트롤러·스케줄러·브로드캐스터·로비 **수정 0**.
   인프라 변경은 포트 계약 확장 1건(`desert` boolean→3치 `DesertOutcome`)뿐이고, 인프라에
```

```diff
 ./gradlew :server:test --tests "com.mirboard.domain.game.skullking.bot.*"   # 스컬킹 봇 정책·강도 평가 (D-119, Docker 불필요)
 ./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"     # 원카드 엔진·어댑터·봇 (D-127/D-128, Docker 불필요)
 ./gradlew :server:test --tests "com.mirboard.infra.bot.EngineTimerSchedulerTest"   # 포트 엔진 타이머 무장·발화 (D-128)
+./gradlew :server:test --tests "com.mirboard.infra.bot.TurnRemainingTest"   # resync 남은 턴 시간·재무장을 락 안에서 (D-131)
+./gradlew :server:test --tests "com.mirboard.infra.ws.*RecorderFailureTest"   # 매치 기록 실패 격리 3게임 (D-130·D-131)
 ./gradlew :server:test --tests "com.mirboard.infra.bot.OneCardRaceIT"   # 원카드 경쟁 창 서버 경로 (D-128, Docker)
 ./gradlew :server:test --tests "com.mirboard.infra.ws.TichuEventStreamIT"   # 티츄 공개 순번 무구멍 + 플레이당 resync 측정 → server/build/d126-resync-stats.txt (D-126, Docker)
 MIRBOARD_BOT_EVAL=1 ./gradlew :server:test --rerun --tests "com.mirboard.domain.game.tichu.bot.HeuristicBotEvaluationTest"   # 티츄 봇 대형 평가 (~1m30s)
```

```diff
 이벤트가 항상 구멍이 된다(티츄는 카드를 낼 때마다 전원 resync 했다). 티츄·원카드(D-129)는 `!isPrivate()`, 스컬킹은
 아직 true. 공개 상태가 바뀌면 **반드시 공개 이벤트로** 알릴 것 — resync 가 우연히 고쳐 주는 것에 기대지
 말 것(소원 해제가 그랬다 → `WISH_CLEARED`). resync 는 방 액션 락 안에서 상태·`eventSeq` 를 함께 읽는다.
+D-131 — 남은 턴 시간(`turnRemainingMs`)도 같은 락 안에서 읽으므로 진행 경로는 다음 턴 데드라인 재무장(`onTurnAdvanced`)을
+락을 풀기 전에 한다(실패는 잡아 ERROR — 봇 스케줄은 해제 뒤 그대로).
 
 - 서버 → 클라 공개 `/topic/room/{roomId}`
   - 티츄: `PLAYED`, `PASSED`, `TURN_CHANGED`, `TRICK_TAKEN`, `TICHU_DECLARED`, `WISH_MADE`, `WISH_CLEARED`(D-126), `ROUND_ENDED`, `MATCH_ENDED` 등
   - 스컬킹(D-102): `BIDDING_STARTED`, `BID_SUBMITTED`(값 없음), `BIDS_REVEALED`, `PLAYING_STARTED`, `CARD_PLAYED`, `TURN_CHANGED`, `TRICK_TAKEN`, `ROUND_ENDED`, `SEAT_DESERTED`, `MATCH_ENDED`
-  - 원카드(D-128·D-129, 열림 전환 전 COMING_SOON): `MATCH_STARTED`(타입만 정의 — 시작 때 발행하지 않는다, 시작 상태는 resync 로), `CARD_PLAYED`, `CARDS_DRAWN`(장수만), `PILE_RESHUFFLED`, `TURN_CHANGED`, `RACE_OPENED`, `RACE_RESOLVED`, `PLAYER_ELIMINATED`, `MATCH_ENDED` — payload 는 결과값
+  - 원카드(D-128·D-129, D-131 공개): `MATCH_STARTED`(타입만 정의 — 시작 때 발행하지 않는다, 시작 상태는 resync 로), `CARD_PLAYED`, `CARDS_DRAWN`(장수만), `PILE_RESHUFFLED`, `TURN_CHANGED`, `RACE_OPENED`, `RACE_RESOLVED`, `PLAYER_ELIMINATED`, `MATCH_ENDED` — payload 는 결과값
 - 서버 → 클라 비공개 `/user/queue/room/{roomId}`
   - 티츄: `HAND_DEALT`, `CARDS_RECEIVED`, `ERROR`
   - 스컬킹: `HAND_DEALT`, `ERROR`
```

`README.md`:

```diff
 # Mirboard
 
-**티츄·스컬킹 두 개의 턴제 보드게임이 실제로 돌아가는 서버 권위(server-authoritative)
+**티츄·스컬킹·원카드 세 개의 턴제 보드게임이 실제로 돌아가는 서버 권위(server-authoritative)
 실시간 웹 플랫폼.** 셔플·족보 판별·점수 계산·차례 결정은 전부 서버가 하고, 클라이언트는
 입력기와 뷰어입니다.
 
```

```diff
 [![Deploy](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml/badge.svg)](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml)
 
 Spring Boot 4 / Java 25 · PostgreSQL · Redis · React + TypeScript ·
-서버 테스트 1288건 / 클라 642건
+서버 테스트 1324건 / 클라 658건
 
 **라이브**: https://mirboard.fly.dev — 로그인 화면 「게스트로 바로 체험하기」로 가입 없이 들어갈 수 있습니다.
 유휴 시 머신이 멈춰 첫 접속에 약 30초 걸립니다(콜드 스타트).
```

```diff
 
 ## 무엇이 되는가
 
-| | 티츄 | 스컬킹 |
-| --- | --- | --- |
-| 인원 | 4인 고정, 2:2 팀전 | **2~8인 가변**, 개인전 |
-| 덱 | 56장 + 특수 카드 4종 | 70장(4색 × 1~14 + 특수 5종) |
-| 매치 | 목표 점수(기본 1000점) | 10라운드 고정 |
-| 특징 | 족보 조합, 폭탄 인터럽트, 카드 패스 | 승수 예측 후 동시 공개, 비추이적 트릭 판정 |
+| | 티츄 | 스컬킹 | 원카드 |
+| --- | --- | --- | --- |
+| 인원 | 4인 고정, 2:2 팀전 | **2~8인 가변**, 개인전 | 2~6인 가변(처음 선택 4), 개인전 |
+| 덱 | 56장 + 특수 카드 4종 | 70장(4색 × 1~14 + 특수 5종) | 54장(트럼프 52 + 조커 2) |
+| 매치 | 목표 점수(기본 1000점) | 10라운드 고정 | 한 판(먼저 다 낸 사람 · 20장 파산) |
+| 특징 | 족보 조합, 폭탄 인터럽트, 카드 패스 | 승수 예측 후 동시 공개, 비추이적 트릭 판정 | 공격 누적·반격, 1장 남으면 **"원카드!/잡기!" 실시간 경쟁**(무작위 위치 버튼, 서버 엔진 타이머), 턴 카운트다운 |
 
 - **로비/방** — 방 생성(인원 선택)·입장·관전·랭킹·채팅. 게임 시작은 정원 충족 + **전원 준비**
-- **봇** — 빈 좌석을 자동 충족. 두 게임 모두 공개 정보만 보는 결정적 휴리스틱이다(티츄 D-118 —
-  랜덤 봇 상대 100전 100승, 스컬킹 D-119 — 1:무작위 2~8인 승률 0.92~1.00). 재현:
+- **봇** — 빈 좌석을 자동 충족. 세 게임 모두 공개 정보만 보는 결정적 휴리스틱이다(티츄 D-118 —
+  랜덤 봇 상대 100전 100승, 스컬킹 D-119 — 1:무작위 2~8인 승률 0.92~1.00, 원카드 D-128 — 무작위 봇 대비 4인 승률 0.92).
+  원카드 봇은 경쟁 창마다 추첨한 반응 시간(기본 1.0~2.5초)에 누른다. 재현:
   `./gradlew :server:test --tests "com.mirboard.domain.game.*.bot.*"` (Docker 불필요)
 - **재접속·탈주** — 끊김 유예 후 미복귀 시 게임별 규칙으로 처리
 - **UI** — 라이트/다크 토글, 모바일 반응형, 색약 모드
 
-ELO·전적 영속은 **티츄 전용**입니다. 스컬킹은 `users.rating` 을 게임별로 분리할지가
-선행 결정이라 의도적으로 보류했습니다 ([D-102](docs/decisions.md)).
+ELO·전적은 **게임별**로 쌓입니다(`user_game_stats`, [D-115](docs/decisions.md)) — 봇·게스트가 낀 매치는 승패만, ELO 는 제외.
 
 전체 기능 표: [docs/implementation-status.md](docs/implementation-status.md)
 
```

```diff
 
 ### 1. 두 번째 게임이 추상화의 결함을 정확히 한 곳 찾아냈다
 
-`GameEngine` 포트(149줄) 뒤에 티츄 4,042줄과 스컬킹 2,919줄이 꽂힙니다. 스컬킹을 붙일 때
+`GameEngine` 포트(지금 184줄) 뒤에 티츄 6,007줄·스컬킹 3,766줄·원카드 2,612줄이 꽂힙니다(재현 명령은 케이스 스터디
+부록 (8)). 스컬킹을 붙일 때
 **REST/WS 컨트롤러·스케줄러·브로드캐스터·로비는 한 줄도 바뀌지 않았고**, 인프라에
 `skullking` 참조는 0건입니다.
 
```

```diff
 완벽해 보였습니다.
 
 **추상이 어디서 샜는지 말할 수 있다는 것**이 "처음부터 완벽했다"보다 강한 증거라고 봅니다.
-
-→ [케이스 스터디: 두 번째 게임을 붙이기까지](docs/case-study-multi-game.md) ·
+세 번째 게임 원카드("원카드!/잡기!" 실시간 경쟁)도 포트 확장 1건 — 시간이 지나면 일어나는 전이(`timer`/`onTimer`) — 으로
+붙었고 인프라의 `onecard` 참조는 0건입니다. 대신 처음으로 시간에 기대는 게임이 인프라의 시간 가정 셋을 드러냈습니다.
+
+→ [케이스 스터디: 두 번째 게임을 붙이기까지](docs/case-study-multi-game.md)(세 번째 게임은 §8) ·
 [포트 계약](docs/game-port.md)
 
 ### 2. 비추이적 트릭 판정을 6단 사다리로
```

```diff
 [Redis 키](docs/redis-keys.md) ·
 [티츄 룰](docs/rules-tichu.md) ·
 [스컬킹 룰](docs/rules-skullking.md) ·
+[원카드 룰](docs/rules-onecard.md) ·
 [구현 현황](docs/implementation-status.md)
 
 **돌려보기** — [기여 가이드](CONTRIBUTING.md) ·
```

`docs/implementation-status.md`:

```diff
 # Mirboard 구현 현황
 
 > 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
-> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-129),
+> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-131),
 > 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
 > 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
 > `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`·
```

```diff
 
 ## 0. 한눈에 보기
 
-플랫폼은 **동작하는 MVP** 상태다. 로비/방 → **게임 2종**(티츄 4인 2:2 팀전 / 스컬킹
-2~8인 개인전) 풀게임 → 점수·ELO 영속(티츄) → 봇 자동 채움 → 재접속/탈주 처리 →
-UI(라이트/다크) 까지 end-to-end로 연결되어 있다.
+플랫폼은 **동작하는 MVP** 상태다. 로비/방 → **게임 3종**(티츄 4인 2:2 팀전 / 스컬킹
+2~8인 개인전 / 원카드 2~6인 개인전 + 실시간 경쟁) 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 →
+재접속/탈주 처리 → UI(라이트/다크) 까지 end-to-end로 연결되어 있다.
 
 | # | 기능 | 상태 | 핵심 위치 |
 |---|------|------|-----------|
```

```diff
 | 4 | WebSocket/STOMP 실시간 | ✅ | `infra.ws`, `infra.config.WebSocketConfig` |
 | 5 | 티츄 룰 엔진 (전 페이즈 + 특수 카드) | ✅ | `domain.game.tichu` |
 | 5b | 스컬킹 (룰 엔진 + 배선 + 클라 게임판) | ✅ | `domain.game.skullking`, `features/skullking` (§16) |
-| 5c | 원카드 (룰 엔진 + 서버 배선·봇·기록 + 클라 게임판·경쟁 버튼·튜토리얼 — 카탈로그 열림 전환(D-116 병합 뒤 별건)과 S5 통합·배포가 남음, 그때까지 COMING_SOON) | 🟡 | `domain.game.onecard`, `infra.bot.EngineTimerScheduler`, `client/src/features/onecard`, `docs/rules-onecard.md` (D-127·D-128·D-129) |
+| 5c | 원카드 (룰 엔진 + 서버 배선·봇·기록 + 클라 게임판·경쟁 버튼·튜토리얼·턴 카운트다운 — D-131 공개, `MIRBOARD_ONECARD_STATUS` 로 되돌림) | ✅ | `domain.game.onecard`, `infra.bot.EngineTimerScheduler`, `client/src/features/onecard`, `docs/rules-onecard.md` (D-127~D-131, §17) |
 | 6 | 봇 플레이어 (빈 좌석 자동 채움) | ✅ | `infra.bot`, `domain.game.tichu.bot` |
 | 7 | 재접속 동기화 (resync) | ✅ | `RoomService`, `GET /rooms/{id}/resync` |
 | 8 | 탈주/끊김 처리 (유예→패널티) | ✅ | `infra.ws` 탈주 핸들러, `DesertionService` |
```

```diff
 - resync 는 방 액션 락 안에서 상태·`eventSeq`·뷰를 함께 읽는다(D-126) — 액션 사이에 끼어
   스냅샷과 순번이 어긋나던(이벤트 이중 적용·누락) 경합 제거. 락을 약 3초 못 잡으면 잠금 없이 읽는다.
 - 손패는 항상 `/user/queue` 로만 복원(공개 토픽 누출 금지).
+- **남은 턴 시간(D-131)**: 응답의 `turnRemainingMs` = 지금 세대 턴 데드라인 − 지금(턴 제한 끔·기다리는 좌석 없음·데드라인
+  없음이면 null). 같은 락 안에서 읽고, 진행 경로는 다음 턴 데드라인을 락을 풀기 전에 건다 — 새 상태와 이전 턴의 남은 시간이
+  섞이지 않는다. 원카드 게임판 카운트다운이 쓴다(재접속 직후에도 맞다).
 
 관련 테스트: `RoomResyncIntegrationTest`, `RoomJoinOrReconnectIntegrationTest`,
-`RoomControllerResyncLockTest`(D-126).
+`RoomControllerResyncLockTest`(D-126·D-131), `TurnRemainingTest`(D-131).
 
 ---
 
```

```diff
 
 ## 13. 테스트 현황
 
-- **서버**: **1288건** (D-116 병합 시점 실측, 실패 0, 그중 Docker 불필요 1086건, 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 게임별 내역은 D-129 시점: 스컬킹 도메인 375건(그중 Docker 불필요 371건), 원카드 도메인 182건(그중 Docker 불필요 178건) + 원카드 서버 경로 IT 10건·엔진 타이머 단위 15건. 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
+- **서버**: **1324건** (D-131 공개 전환 시점 실측, 실패 0, 그중 Docker 불필요 1118건(84%), 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 게임별 내역(같은 실측): 티츄 도메인 312건(그중 Docker 불필요 304건), 스컬킹 도메인 375건(371건), 원카드 도메인 191건(187건) + 원카드 서버 경로 IT 10건(`OneCardRaceIT`·`OneCardBotMatchSimulationIT`)·엔진 타이머 단위 16건. 매치 기록 실패 격리는 세 게임 모두 `infra.ws.*RecorderFailureTest`(8건, D-130·D-131). 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
+  집계 방식: `./gradlew :server:test --rerun` 뒤 `server/build/test-results/test/TEST-*.xml` 을 합한다. 클래스는 **파일 이름의 바깥 클래스**로
+  묶는다 — `@Nested` 는 `$` 앞, `@DisplayName` 을 단 `@Nested` 는 스위트 이름이 표시 이름이라 이름 속성으로는 패키지에 안 묶인다(스컬킹이
+  그렇다). 게임 도메인 = `com.mirboard.domain.game.{게임}.` 아래, Docker 불필요 = 바깥 클래스 이름이 `IT`·`IntegrationTest` 로 끝나지 않는 것.
+  재현 스크립트는 `docs/case-study-multi-game.md` 부록 (6).
   단위(룰 엔진·족보·ELO·JWT·카탈로그·포트 어댑터) + 통합(Testcontainers PostgreSQL 16/
   Redis — auth/rooms/STOMP/봇/동시성/매치 영속/2-인스턴스 인계).
 - 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 세 봇
   평가, ~20s). 스컬킹·원카드 매치 기록 IT(D-115·D-128)는 Docker 가 필요해 `rules` 에서 뺐다.
-- **클라이언트**: **642건 / 61파일** (D-116 병합 시점 실측 — S4 로 늘어난 162건·S5 앞부분 포함, 실패 0). Vitest + RTL — 스토어
+- **클라이언트**: **658건 / 61파일** (D-131 공개 전환 시점 실측 — 원카드 턴 카운트다운 15건·본인 큐 가드 1건 포함, 실패 0). Vitest + RTL — 스토어
   리듀서, 족보 타입, 카드 에셋 매핑 등.
 - 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.
 - **밀폐성(D-113)**: IT 는 Testcontainers 로 자기 Postgres/Redis 를 띄우고 compose 에 기대지
```

```diff
 
 ---
 
+## 17. 원카드 (S0~S5, D-123~D-131)
+
+`domain.game.onecard` — 순수 룰 엔진(D-127) + 포트 어댑터·정의·라운드 시작·휴리스틱 봇·기록기 V12(D-128), 클라 게임판
+`features/onecard`(D-129). 룰 정본 `docs/rules-onecard.md`, 설계·단계 `docs/plans/onecard.md`.
+
+- **2~6인 개인전**, 54장(조커 2), 공격 누적·반격, 7 무늬 지정, K 한 번 더, 20장 파산, 1판 = 1라운드.
+- **실시간 경쟁 창**: 1장 남으면 서버가 "원카드!/잡기!" 창(기본 3초)을 열고 전원에게 같은 무작위 위치를 준다. 판정은 방 액션
+  락을 먼저 잡은 누름. 봇 누름·창 만료는 **포트 엔진 타이머**(`timer`/`onTimer`, D-128 — 유일한 포트 확장)가 서버에서 처리한다.
+  인프라에 게임 이름 0건.
+- **공개 전 보강(D-130)**: 진행 킥(재기동·타이머 유실 뒤 resync·구독 때 봇 루프·엔진 타이머 재무장), 경쟁 결과 로그, 기록 실패
+  격리, 방 만들기 처음 선택 4명·턴 30초.
+- **공개(D-131)**: 기본 `AVAILABLE`(되돌리기는 시크릿 `MIRBOARD_ONECARD_STATUS=COMING_SOON` — `docs/deploy.md`). 턴 카운트다운
+  (resync `turnRemainingMs` + `TURN_CHANGED`·`PLAYER_ELIMINATED` 로 다시 세기, 경쟁 창 동안 숨김). 방 만들기에서 인원·턴 제한을
+  생략하면 게임 선언(4명·30초).
+- **검증**: 도메인 191건(Docker 불필요 187) — 2~6인 무작위 시뮬레이션·매 전이 불변식, 서버 경로 IT 10건(경쟁 창 6건·봇 풀매치 4건),
+  엔진 타이머 단위 16건, 클라 게임판·스토어·튜토리얼(`npm --prefix client run test -- onecard`).
+- **남은 것**: `docs/plans/onecard.md` §8·§9 남은 후속(메시지 순서, 강제 종료 기록 정책, 장기 정지 탈주화, 누름 하한 등).
+
+---
+
 > 최신 결정/번복은 `docs/decisions.md`, 진행 단계는 `docs/plans/mvp-roadmap.md` 가 정본.
 > 본 문서와 어긋날 경우 그쪽을 신뢰한다.
 
```

`docs/case-study-multi-game.md`:

```diff
 `SkullKingInvariantChecker` 를 통과 케이스뿐 아니라 **고의로 위반시킨 상태 8건**
 (+ 오탐 방지 통과 2건)으로 검출 능력 자체를 테스트했습니다.
 
-**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1288건 중 1086건(84%)이 Docker 불필요**합니다.
+**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1324건 중 1118건(84%)이 Docker 불필요**합니다(D-131 시점, 부록 (6)).
 자랑이 아니라 §2 의 2계층 분리가 값을 냈다는 인과 증거입니다.
 
 `코드:` `skullking/trick/TrickResolver.java` · `skullking/invariant/SkullKingInvariantChecker.java` ·
```

```diff
 
 ## §7 남은 것 — 스스로 공개하는 부채
 
-포트가 **완성됐다고 주장하지 않습니다.** 두 번째 게임까지만 검증됐습니다. 세 번째 게임은 원카드로
-정했고(D-123) 아직 구현 전입니다. 원래 후보였던 요트는 `game-port.md` §4 가 "종이 통과도 남은 과제"로
-적어 둔 채 뒤로 밀렸습니다.
+포트가 **완성됐다고 주장하지 않습니다.** 세 번째 게임 원카드가 붙어(§8 — D-131 공개) 검증한 게임이 셋이 됐을 뿐입니다.
+원래 후보였던 요트는 `game-port.md` §4 가 "종이 통과도 남은 과제"로 적어 둔 채 뒤로 밀렸습니다.
 
 - ~~**`MirboardMetrics:33` 의 `.tag("gameType","TICHU")` 하드코딩**~~ — §5 에서 실코드 잔여를
   "2파일"이라고 쓴 이유입니다. 칩 정산과 달리 이건 문서화된 예외가 아니라 잔재였습니다.
```

````diff
 
 ---
 
+## §8 세 번째 게임 — 시간이 흐르는 게임 (원카드, D-123~D-131)
+
+**문제** — 원카드의 "원카드!/잡기!"는 **아무도 행동하지 않아도** 상태가 바뀝니다. 1장 남은 순간 서버가 경쟁 창을 열고,
+봇은 추첨한 반응 시간에 누르고, 아무도 안 누르면 창이 저절로 닫힙니다. 포트는 "누가 행동하면 상태가 바뀐다"만 말할 수
+있었습니다 — 시간은 인프라(턴 타임아웃)의 것이었고, 게임은 `timeoutAction` 으로 "무엇을"만 답했습니다.
+
+**후보와 배제**
+
+- 시스템 전이를 액션(`CloseRace`)으로 — 액션 계층은 클라 JSON 에서 역직렬화되므로 **클라가 창 닫기를 위조**할 수 있다.
+- 게임이 자기 스케줄러를 소유 — 인프라를 우회해 다중 인스턴스 인계(D-96 데드라인 큐)·세대 가드·IN_GAME 가드를 다시 만든다.
+- **선택: 포트에 `timer(state)`/`onTimer(state)`**(기본 없음 — 티츄·스컬킹 0줄). 무장은 이미 액션·봇·타임아웃·탈주·라운드
+  시작 5곳이 부르는 `onTurnAdvanced` 에 얹어 **호출 지점이 늘지 않았고**, 발화는 턴 타임아웃과 같은 가드를 공유합니다.
+
+**결과** — 서버 통합 머지(`36d99e0`)에서 바뀐 공용 코드는 포트 `GameEngine.java` 와 인프라 3파일(`EngineTimerScheduler` 신규,
+`TurnTimeoutScheduler` 무장 한 곳, `DeadlineQueue`)뿐입니다. 비공개 손패 이벤트의 순번 문제는 티츄 결함 수정(D-126)이 같은 포트
+확장(`GameEvent.sequenced()`)을 먼저 들여와 원카드는 재정의만 했습니다. 클라 머지(`27910d1`)는 37파일 +4,948/−45 인데 공용 파일은
+`RoomPage`(게임판 분기)·게임 id 매핑 두 곳(튜토리얼·위키 링크 각 한 항목)·CSS 진입점(`@import` 와 `17-responsive` 의 터치 하한)·좌석
+순서 공용화뿐이고(원카드 전용 신규 파일 `types/onecard.ts`·`19-onecard-table.css` 와 테스트는 빼고) `useStompRoom` 은 0줄입니다.
+
+- `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra` → **0건**.
+- 포트 `domain/game/core` 515줄(그중 `GameEngine.java` 184줄) 뒤에 티츄 6,007줄 · 스컬킹 3,766줄 · 원카드 2,612줄
+  (`wc -l`, D-131 기준 — 부록 (8). §5 의 수치는 스컬킹을 붙인 시점).
+
+**대가 — 시간은 인프라의 가정을 건드렸습니다.** "포트 확장 1건"은 맞지만, 처음으로 *시간에 기대는* 게임이 붙자 기존 가정 셋이
+드러났습니다.
+
+1. **세대 번호는 "상태 무변경"을 보장하지 않는다**(S3 최종 리뷰). 진행 경로가 락을 푼 뒤에 세대를 올려 그 틈에 만기된 옛 타이머가
+   가드를 통과했습니다 → 발화 쪽이 락 안에서 `timer(state)` 를 다시 묻는 것이 마지막 방어선입니다(D-128).
+2. **메모리의 봇 루프와 유실될 수 있는 타이머에만 기대면 멈춘다**(D-130). 재기동 뒤 봇 차례에서 매치가 영구 정지하는 결함은
+   티츄·스컬킹에도 있었지만, 경쟁 창은 타이머가 한 번 사라지면 영원히 열려 있어 드러났습니다 → 클라가 판을 다시 볼 때의 진행 킥.
+3. **resync 와 재무장의 순서**(D-131). 턴 카운트다운을 위해 resync 에 남은 턴 시간을 싣자, 상태는 락 안에서 읽는데 다음 턴
+   데드라인은 락을 푼 뒤에 걸려 **새 상태 + 이전 턴의 남은 시간**이 나갈 수 있었습니다 → 락을 쥔 진행 경로의 재무장을 락 안으로.
+   덤으로 1번의 틈도 그 경로에서는 닫혔습니다.
+
+셋 다 게임 중립으로 고쳤고(인프라에 게임 이름 0), 티츄·스컬킹도 같은 수정의 덕을 봅니다. 두 번째 게임이 계약의 **모양**
+(2치 → 3치)을 고쳤다면 세 번째 게임은 인프라의 **시간 모델**을 고쳤습니다.
+
+`코드:` `domain/game/core/GameEngine.java`(`timer`·`onTimer`) · `infra/bot/EngineTimerScheduler.java` · `infra/bot/GameProgressKick.java` ·
+`infra/bot/TurnTimeoutScheduler.java`(`turnRemaining`) · `테스트:` `EngineTimerSchedulerTest` · `OneCardRaceIT`(경쟁 창 서버 경로) ·
+`GameProgressKickScenarioTest` · `TurnRemainingTest` · `결정:` D-123 · D-126 · D-128 · D-130 · D-131
+
+---
+
 ## 부록 — 재현 명령
 
 ```bash
 # (1) 인프라가 두 번째 게임의 이름을 아는가
 grep -rniE 'skullking' server/src/main/java/com/mirboard/infra   # → 0건
 
-# (2) 인프라의 티츄 잔여 참조 → 6파일 11행, 그중 실코드 2파일 6행
+# (2) 인프라의 티츄 잔여 참조 → 스컬킹 통합 시점 6파일 11행, 그중 실코드 2파일 6행
 #     (-i 필수: MirboardMetrics 는 대문자 문자열 "TICHU" 라 대소문자 구분 grep 이 놓친다)
+#     D-131 시점 → 7파일 16행, 실코드 2파일 6행: RoomChipService 5행(문서화된 예외) + UserController 의
+#     LEGACY_GAME="TICHU" 1행(D-115 구 클라 호환). MirboardMetrics 의 하드코딩은 D-107 에서 해소돼 javadoc 만 남았다
 grep -rniE 'tichu' server/src/main/java/com/mirboard/infra --include='*.java'
 
 # (3) 스컬킹 통합에서 실제로 바뀐 파일
````

````diff
 
 # (5) 룰 테스트는 Docker 없이 돈다
 ./scripts/check.sh rules
+
+# (6) 서버 테스트 수·Docker 불필요 비율(§3). check.sh server 는 빌드 캐시로 FROM-CACHE 에 끝나 실측이 안 될 수 있어 --rerun.
+#     클래스는 XML 파일 이름의 바깥 클래스로 센다 — @DisplayName 을 단 @Nested 는 스위트 이름이 표시 이름이 된다.
+./gradlew :server:test --rerun
+python3 - <<'EOF'
+import glob, os, re, xml.etree.ElementTree as ET
+t = s = f = d = 0
+for p in glob.glob('server/build/test-results/test/TEST-*.xml'):
+    r = ET.parse(p).getroot(); n = int(r.get('tests'))
+    outer = os.path.basename(p)[5:-4].split('$')[0]
+    t += n; s += int(r.get('skipped')); f += int(r.get('failures')) + int(r.get('errors'))
+    if not re.search(r'(IT|IntegrationTest)$', outer): d += n
+print(f"tests={t} skipped={s} failed={f} dockerfree={d} ({d/t:.0%})")
+EOF
+# → tests=1324 skipped=5 failed=0 dockerfree=1118 (84%)   (D-131 시점)
+
+# (7) 세 번째 게임(§8) — 인프라가 이름을 아는가 / 서버 통합에서 바뀐 공용 파일 / 클라 통합 규모
+grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra   # → 0건
+git diff --name-status 36d99e0^1 36d99e0 -- server/src/main/java/com/mirboard/infra server/src/main/java/com/mirboard/domain/game/core
+git diff --stat 27910d1^1 27910d1 -- client/src | tail -1
+git diff --name-only 27910d1^1 27910d1 -- client/src | grep -v features/onecard   # 공용 파일(원카드 전용 신규·테스트 포함 목록)
+
+# (8) 포트 뒤 게임별 코드 규모(§8) — main 소스 줄 수
+for d in core tichu skullking onecard; do echo $d $(find server/src/main/java/com/mirboard/domain/game/$d -name '*.java' | xargs cat | wc -l); done
+wc -l server/src/main/java/com/mirboard/domain/game/core/GameEngine.java
+# → core 515 · tichu 6007 · skullking 3766 · onecard 2612, GameEngine.java 184   (D-131 시점)
 ```
 
 관련 문서: [game-port.md](game-port.md)(포트 계약 정본) ·
 [rules-skullking.md](rules-skullking.md)(룰 ↔ 코드 1:1) ·
-[decisions.md](decisions.md)(D-97 ~ D-104)
+[rules-onecard.md](rules-onecard.md) · [plans/onecard.md](plans/onecard.md)(세 번째 게임 설계·단계) ·
+[decisions.md](decisions.md)(D-97 ~ D-104, D-123 ~ D-131)
````

`docs/plans/mvp-roadmap.md`:

````diff
 
 > **아래는 최초 설계안이고 현행이 아니다** — Phase 16 에서 게임별 로비 페이지가 폐지되어
 > 허브·로비가 미르보드카페(`/games`) 한 화면으로 합쳐졌고(#1), 게임 시작 조건이 "정원
-> 자동 시작"에서 "정원 + 전원 준비"로 바뀌었으며(#2), 게임은 티츄·스컬킹 2종이다.
+> 자동 시작"에서 "정원 + 전원 준비"로 바뀌었으며(#2), 게임은 티츄·스컬킹·원카드 3종이다(D-131).
 > 현행 플로우는 `CLAUDE.md` §사용자 플로우 가 정본. 이 절은 이력으로 남긴다.
 
 ```
````

```diff
 | M4 | G | 쇼케이스 마감: README 리뉴얼·데모 GIF·케이스 스터디·데모 계정·라이브 배포·CD | ✅ D-105: README·케이스 스터디·스크린샷·데모 계정 시더·CD 워크플로. **라이브 배포 2026-10-03**(https://mirboard.fly.dev) — 첫 재배포에서 5월의 Upstash Redis 소멸을 발견해 Fly 자체 Redis 로 교체(D-114), `FLY_API_TOKEN` 등록으로 main 푸시 = 자동 배포. GIF 는 정적 스크린샷으로 대체 |
 | M5 | E | 멀티게임: 포트 졸업 → 디스패치 seam 포트화 → 스컬킹(2~8인) | ✅ S0~S6 완료(D-97~D-104): 포트·인원 가변·룰 명세·순수 엔진(305건)·탈주(유령 좌석)·인게임 배선(봇 풀매치 IT)·클라 게임판(Row-Flow, 실측 완료). 실행 단위 `docs/plans/multi-game-sessions.md`. **잔여 별건**: 스컬킹 매치 영속·ELO(D-02 게임별 rating 분리 선행), 끊김 유예 구간 정지(D-104 한계), 요트/할리갈리 |
 | M6 | E·A | 스컬킹 완성도: ① 게임별 전적·레이팅(매치 영속·개인전 ELO·게임별 랭킹) ② 라운드 점수표 ③ 봇 휴리스틱 ④ 튜토리얼 | ✅ ① D-115 게임별 전적·레이팅 · ② D-120 라운드 결과(다음 라운드 예측 중 비차단)·점수표·종료 후 게임판 유지 · ③ D-119 봇 휴리스틱(공개 정보 뷰 + `TrickResolver` 승률) · ④ D-121 게임별 튜토리얼 레지스트리 + 스컬킹 13단계·퀴즈 (2026-10-03). 후속: 봇 상대 모델링(8인 대 최약수 대등), 티츄 봇 방 종료 화면 유지 |
-| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | 🟡 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`) · **S2 완료**(D-127 순수 엔진, 테스트 134건) · **S3 완료**(D-128 포트 엔진 타이머 + 어댑터·휴리스틱 봇·기록 V12, 경쟁 창 IT, 서버 1219건 — 클라 전까지 COMING_SOON). **S4 완료**(D-129 클라 게임판·경쟁 버튼·튜토리얼 + 비공개 이벤트 비순번, 클라 594건). 다음: 카탈로그 열림 전환(D-116 병합 뒤 별건) → S5 통합·배포 |
+| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | ✅ 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`) · **S2 완료**(D-127 순수 엔진, 테스트 134건) · **S3 완료**(D-128 포트 엔진 타이머 + 어댑터·휴리스틱 봇·기록 V12, 경쟁 창 IT, 서버 1219건). **S4 완료**(D-129 클라 게임판·경쟁 버튼·튜토리얼 + 비공개 이벤트 비순번, 클라 594건). **S5 완료**(D-130 공개 전 보강 — 진행 킥·훅 정리·경쟁 결과 로그·기록 격리·방 만들기 처음 선택 4명·30초 / D-131 **공개(AVAILABLE)** — resync 남은 턴 시간·원카드 턴 카운트다운·매치 기록 실패 격리 3게임·같은 방 킥 합치기·생략 기본 정렬, 서버 1324건·클라 658건). 배포·운영 스모크는 병합 뒤. 후속은 `docs/plans/onecard.md` §8·§9 남은 후속 |
 
 **M0 상세(완료)**: D-83(`SecurityConfig`/`WebSocketConfig` origin 화이트리스트+헤더),
 D-84(`LoginAttemptService`·`AuthRateLimiter`·`rate_limit_fixed_window.lua`, 전부 Redis 휘발 —
```

`docs/qa-scenarios.md`:

````diff
 ```bash
 # 카탈로그 조회 (인증 필요)
 curl -s http://localhost:8080/api/games -H "Authorization: Bearer $TOKEN"
-# → {"games":[{"id":"TICHU","displayName":"티츄",
+# → {"games":[{"id":"SKULL_KING","displayName":"스컬킹",
+#              "shortDescription":"2~8인 트릭테이킹. 매 라운드 자기 승수를 예측하고...",
+#              "minPlayers":2,"maxPlayers":8,"status":"AVAILABLE", ...},
+#             {"id":"ONE_CARD","displayName":"원카드",
+#              "minPlayers":2,"maxPlayers":6,"status":"AVAILABLE","defaultPlayers":4,"defaultTurnSeconds":30, ...},
+#             {"id":"TICHU","displayName":"티츄",
 #              "shortDescription":"4인 파트너 카드 게임. 56장 덱과 4장의 특수 카드...",
-#              "minPlayers":4,"maxPlayers":4,"status":"AVAILABLE"},
-#             {"id":"SKULL_KING","displayName":"스컬킹",
-#              "shortDescription":"2~8인 트릭테이킹. 매 라운드 자기 승수를 예측하고...",
-#              "minPlayers":2,"maxPlayers":8,"status":"AVAILABLE"}]}
-# 순서는 보장하지 않는다 — GameRegistry 가 GameDefinition Bean 을 모아 만든다.
+#              "minPlayers":4,"maxPlayers":4,"status":"AVAILABLE", ...}]}
+# 정렬은 상태 → 표시 이름(가나다) — GameRegistry 가 GameDefinition Bean 을 모아 만든다(필드 전체는 docs/api.md).
 
 # 단일 게임
 curl -s http://localhost:8080/api/games/TICHU -H "Authorization: Bearer $TOKEN"
````

````diff
 복귀 → `useStompRoom` 이 `/resync` 호출 → 게임 상태 (TableView + 본인 손패) 즉시
 복원. 다른 플레이어 상태는 변하지 않음.
 
-## 원카드 게임판 확인 (D-129)
-
-카탈로그 열림 전환 전에도 로컬에서는 환경 변수로 켜서 볼 수 있다 — `MIRBOARD_ONECARD_STATUS=AVAILABLE ./gradlew :server:bootRun`.
-경쟁 버튼을 눈으로 보려면 같은 줄에 창 길이와 봇 반응 구간(주인·잡는 쪽의 최소·최대 4개)을 늘려 얹는다. 설정 파일은 만들지
-않는다 — `.gitignore` 밖에 두면 `git add -A` 에 딸려 간다.
-
-```bash
-MIRBOARD_ONECARD_STATUS=AVAILABLE \
+## 원카드 게임판 확인 (D-129·D-131)
+
+D-131 부터 원카드는 기본 공개(`AVAILABLE`)라 그냥 `./gradlew :server:bootRun` 으로 보인다. 경쟁 버튼을 눈으로 보려면 창 길이와
+봇 반응 구간(주인·잡는 쪽의 최소·최대 4개)을 늘려 얹는다. 설정 파일은 만들지 않는다 — `.gitignore` 밖에 두면 `git add -A` 에
+딸려 간다.
+
+```bash
 MIRBOARD_ONECARD_RACE_WINDOW_MILLIS=20000 \
 MIRBOARD_ONECARD_BOT_REACTION_OWNER_MIN_MILLIS=18000 \
 MIRBOARD_ONECARD_BOT_REACTION_OWNER_MAX_MILLIS=19000 \
````

```diff
 6. 모바일 폭(375px)과 라이트 테마에서 가로 스크롤 없이 손패가 여러 줄로 감기고, 헤더 버튼이 44px 인지 본다. 조커·경쟁 버튼·
    내기/먹기에 마우스를 올려도(모바일은 탭한 뒤) 글자와 면이 읽히고, 비활성 "내 차례 아님" 라벨도 읽힌다.
 7. 브라우저 개발자 도구 네트워크: 카드를 내거나 먹어도 `/resync` 가 다시 불리지 않는다(입장·재연결·탭 복귀(`visibilitychange`·
-   `online`) 때만, D-129).
+   `online`) 때만, D-129 — 해소 이벤트가 끝내 안 오는 창의 창당 1회는 예외, D-130).
+8. **방 만들기 처음 선택 되돌림(D-130)**: 새 방 만들기에서 티츄 → 턴 제한 60초 → 원카드로 바꾸면 인원 4·턴 30초, 다시
+   스컬킹으로 바꾸면 인원 8·턴 '끔'으로 보인다(자동 테스트 `CreateRoomModal.test.tsx` 도 있다).
+9. **턴 카운트다운(D-131)**: 턴 30초 방(원카드 기본)에서 가운데 "○○ 차례" 옆에 `⏱ N초`가 1초씩 준다. 5초 이하면 빨간 테두리·
+   배경으로 바뀌고 0초에서 멈춘 뒤 곧 자동 먹기(`CARDS_DRAWN`)와 다음 차례로 다시 30초가 된다. 누군가 1장이 되어 경쟁 버튼이
+   뜬 동안은 카운트다운이 사라지고, 창이 닫힌 뒤 다음 차례부터 30초로 다시 센다. 내 차례 중간에 새로고침(F5)해도 30초로 돌아가지
+   않고 남은 시간에서 이어진다(resync `turnRemainingMs`). 턴 제한 '끔'으로 만든 방에서는 보이지 않는다. 스크린리더가 매초 읽지
+   않는다(`aria-live="off"`).
+10. **경쟁 버튼·늦은 누름**: 4 의 "늦었어요"는 빨간 오류 줄 없이 잠깐만 뜨고, 버튼에 마우스를 올려도(모바일은 탭한 뒤) 글자가
+   읽힌다(전역 `button:hover` 배경에 덮이지 않는지 — 라이트·다크 둘 다).
 
 ## 분산 시연 (멀티 인스턴스, Phase 6D)
 
```

- [ ] **Step 4: 설계 §6·§8·§9**

`docs/plans/onecard.md`:

```diff
 # 원카드 — 세 번째 게임 (D-123) — 설계
 
-> 상태: **설계 승인** · 2026-10-04 · 결정 이력 `docs/decisions.md` D-123
+> 상태: **공개(AVAILABLE)** · 2026-10-06 · 결정 이력 `docs/decisions.md` D-123~D-131 (설계 승인 2026-10-04, S0~S5 완료 — §6·§8·§9)
 > 근거: `docs/game-port.md`(포트 계약) · `docs/plans/multi-game.md`(멀티게임 원칙, §7 할리갈리
 > 판정 모델) · 스컬킹 선례 `docs/plans/multi-game-sessions.md`
 > 룰 정본은 S1 에서 만들 `docs/rules-onecard.md` 다. 이 문서의 §3 은 그 입력이다.
```

````diff
 
 ```
 onecard/
-  OneCardGameDefinition   @Component · ID "ONE_CARD" · 2~6인 · COMING_SOON → AVAILABLE 은 열림 전환에서 (S3 · D-129: S4 와 분리, D-116 병합 뒤 별건)
+  OneCardGameDefinition   @Component · ID "ONE_CARD" · 2~6인 · COMING_SOON → AVAILABLE 은 열림 전환에서 (S3 · D-129: S4 와 분리, D-116 병합 뒤 별건) *(D-131: 공개 — 기본 AVAILABLE, §9)*
   OneCardEngine           순수 룰 엔진 — 저장소·시계 없음, 난수는 주입 (S2)
   OneCardGameEngine       포트 어댑터 — 상태 저장(Redis)·시계·로컬 발행 (S3)
   Dealer · RaceSettings   분배·시작 카드 / 창 길이·봇 반응 구간·슬롯 수 (S2)
````

```diff
 | S2 | 순수 엔진 + 불변식 + 시뮬레이션 | S1 | 서버 신규 패키지 | 룰 단위 테스트, 2~6인 시뮬레이션 전부 종료, 54장 보존 |
 | S3 | 포트 확장 1건(엔진 타이머 — 비순번은 D-126 에 맡김) + 어댑터 + 봇 + 기록기(V12) + 라운드 시작 | S2 | 서버(인프라 1건) | 경쟁 통합 테스트 5종, 봇 풀매치 IT, 인프라 grep 0건, 티츄·스컬킹 회귀 |
 | S4 | 클라 게임판 + 경쟁 버튼 + 튜토리얼 + 비공개 이벤트 비순번 — `AVAILABLE` 전환은 D-116 병합 뒤 별건(D-129) | S0·S3·D-126 | 클라(+서버 재정의 1건) | Vitest, 브라우저 실측(데스크톱·모바일) |
-| S5 | 공개 전 보강(§8) → 문서 수치 → 배포 | S4 | 서버·클라 | `check.sh` 전체, 운영 스모크 |
+| S5 | 공개 전 보강(§8, D-130) → 공개 전환(§9, D-131 — 기본 AVAILABLE·턴 카운트다운·기록 격리 3게임·킥 합치기·생략 기본 정렬) → 문서 수치 → 배포 | S4 | 서버·클라 | `check.sh` 전체, 운영 스모크 — **완료**(배포·운영 스모크는 병합 뒤) |
 
 - S0 과 S1 은 서로 독립이라 병행할 수 있다(worktree 분리). S2 는 신규 패키지라 S0 과 병행 가능.
 - S3 병합 때는 정의를 `COMING_SOON` 으로 둔다. 서버만 배포돼도 방을 만들 수 없다(`RoomService` 가
```

```diff
     갈라졌으므로 같은 과제로 다룬다(동작 무변경 리팩터 — D-122 로 다듬은 가드라 회귀 테스트를 먼저).
   - **턴 타임아웃에도 같은 틈**(락 해제 → 세대 상승)이 원래부터 있다. 옛 턴 타이머가 막 차례를 받은 좌석에 `timeoutAction` 을
     즉시 적용할 수 있다. 근본 수정은 세대 상승을 락 안으로 옮기는 것(봇 경로처럼)이며 컨트롤러·탈주·두 스케줄러 4곳의 순서를
-    바꾸므로 별도 과제다.
+    바꾸므로 별도 과제다. *(D-131: 락을 쥔 진행 경로의 재무장을 락 안으로 옮겨 닫았다. 매치 시작(라운드 스타터)만 남음, §9)*
   - 단일 폴러라 탈주 확정(`acquireWaiting` 최대 약 3초)이나 턴 타임아웃이 끝낸 매치의 기록기 DB 트랜잭션이 도는 동안 모든 방의
     엔진 타이머(봇 누름 1.0~2.5초·창 만료 3초)가 밀린다. 팝한 항목의 `handle` 을 가상 스레드로 넘긴다(게임 중립).
   - 발화 중 `saveState` 뒤 `advance`/`broadcast` 가 던지면 창은 닫혔는데 봇·타이머 재무장이 없다(턴 타임아웃과 같은 기존
```

```diff
   `board` 스프레드는 오타 방어가 없다 → `Map<gameType, ComponentType<GameBoardProps>>` 한 표로(다음 게임 전에). 원카드 RoomPage 시나리오도 스컬킹과 `it.each` 로.
 - **서버 쪽 낡은 주석**(T8-M6) — `server/src/main/resources/application.yml` 의 onecard 절("클라 게임판(S4) 전에는 … S4 에서 AVAILABLE 로 바꾼다")과
   `OneCardGameDefinition` Javadoc 이 D-129("전환은 별건")와 어긋난다. 열림 전환 때 읽는 주석이라 그때 고친다(이번 묶음은 서버 파일을 건드리지 않았다).
+  *(D-131: 고쳤다, §9 진행 6)*
 - **케이스 스터디 재현 명령**(T8-M7) — `docs/case-study-multi-game.md` 의 "1238건 중 1039건(84%)" 재현 명령이 §부록에 없다(기존 공백). 서버 집계를 (6)으로
-  싣고 `check.sh server` 는 Gradle 캐시로 `FROM-CACHE` 에 끝날 수 있으니 `--rerun` 을 적는다.
+  싣고 `check.sh server` 는 Gradle 캐시로 `FROM-CACHE` 에 끝날 수 있으니 `--rerun` 을 적는다. *(D-131: 부록 (6)으로 실었다)*
 - **턴 카운트다운**(N-4) — `turnStartedAt` 은 쓰기만 하고 읽지 않는다. 원카드 게임판엔 카운트다운이 없는데, 턴 제한(`turnSeconds`)이 있는 방은 시간
   초과가 자동 먹기라 남은 시간 표시가 필요할 수 있다. 붙이거나 필드를 지운다. *(D-130: 원카드 방 만들기의 턴 제한 처음 선택이 30초가 되어 기본 방마다 안 보이는 시간 초과가 생겼다 — 공개 전환 전에 재검토, §8.)*
+  *(D-131: 붙였다 — resync `turnRemainingMs` + 재무장 이벤트로 다시 세기, `turnStartedAt` 은 `turnClock` 으로 바뀌었다. §9.)*
 - **원카드 순번 스트림 IT**(N-5) — "내거나 먹어도 resync 없음"은 `sequenced()` 단위 테스트와 공용 브로드캐스터(D-126 테스트)에만 기댄다.
   `TichuEventStreamIT` 의 원카드판(봇 매치에서 공개 seq 연속·비공개 seq 부재)을 S5 에서(선택).
 - **같은 세션 프레임 순서**(N-6) — `WebSocketConfig` 가 `preservePublishOrder` 를 켜지 않아 같은 세션의 프레임 순서가 보장되지 않는다(실측 안 함). 클라는
```

```diff
   원카드 방 만들기의 인원 처음 선택 = **4명**.
 - **30초의 대가**(결정 문안에 한 줄로): 게임판에 턴 카운트다운이 없어(N-4) 기본 방마다 **안 보이는 시간 초과**가 생긴다.
   시간 초과는 먹기라 공격 누적 중이면 그 장수를 통째로(파산 20장까지) 먹고, 끊긴 사람은 유예 120초 동안 30초마다 자동으로
-  먹는다(지금은 "턴 30초" 배지뿐). 카운트다운은 공개 전환 전에 재검토한다(아래 후속).
+  먹는다(지금은 "턴 30초" 배지뿐). 카운트다운은 공개 전환 전에 재검토한다(아래 후속). *(D-131: 카운트다운을 넣었다, §9)*
 
 ### 진행
 
```

```diff
 | 4 | `GameDefinition.defaultPlayers()`·`defaultTurnSeconds()`(기본 최대 인원·끔) — 원카드만 4·30, 카탈로그에 싣고 방 만들기 모달의 처음 선택으로. 게임을 바꾸면 인원·턴 제한 둘 다 그 게임의 처음 선택으로 되돌아간다(예전엔 티츄에서 고른 60초가 스컬킹으로 바꿔도 남았다 — 이제 '끔'으로 돌아간다). 선언값이 모달이 고를 수 있는 값인지 불변식 | P-F4·O-Minor·사용자 결정·PR-M-11 |
 | 5 | 방 STOMP 훅 — 기준점보다 낡은 resync 응답 버림(방 전환 세대 표식으로 이전 방 응답도), 접속 때 구독을 보낸 뒤 resync(틈을 좁힐 뿐 보장 아님), `onWebSocketClose` 로 끊김 표시(로비 훅도), 게임판용 `requestResync`, 정리된 이전 소켓의 콜백 무시(`disposed` — 언마운트도 세대를 올린다) | P-F1·P-F2·PR-I-1·PR-M-8·최종 리뷰 I-1 |
 | 6 | 원카드 클라 — resync 스냅샷이 오면 기다리던 누름을 비움(그 뒤 늦게 온 그 누름의 `BUSY` 는 창이 열려 있으면 삼킴), 해소 이벤트가 끝내 안 오는 창(마감 + 1.5초 초과·창이 열린 채 그 창의 누름이 `NO_RACE`)은 창마다 한 번 resync | P-F2·C-I2·PR-M-6·PR-M-7 |
-| 7 | 공용 인프라 거절 문구(`ws/errorLabels` — 원카드 sink 적용), 허브 방 목록의 게임 표시 이름 | P-F3·P-F6·M-3·M-5 |
+| 7 | 공용 인프라 거절 문구(`ws/errorLabels` — 원카드 sink 적용), 허브 방 목록의 게임 표시 이름·방 상태 한글 라벨(`features/lobby/roomStatusLabel` — 허브·대기실 공용) | P-F3·P-F6·M-3·M-5·S5T7-M2 |
 | 8 | 문서 — `deploy.md` 원카드 공개 상태 런북·경쟁 튜닝·결과 로그, `.env.example`, `stomp-protocol.md`, `api.md`, `game-port.md`, 이 절 | O-I2·P-F5·P-F6 |
 
 ### 설계에서 판단한 것
```

```diff
   30초 처음 선택으로 대부분 풀리지만 사용자가 '끔'을 고른 방은 그대로다.
 - **누름 하한**(S-I1 B) — 경쟁 결과 로그의 `latencyMs` 분포를 본 뒤 판단. 늦은 누름 인정의 상한(C-I2 선택)도 같은 데이터로.
 - **티츄·스컬킹 sink 의 공용 거절 문구 적용**, resync 실패(REST)·'나가기' 실패의 영문 서버 메시지(M-5 나머지).
-- **`RoomService` 의 생략 기본을 게임 선언과 정렬**(D-116 뒤) — `capacity` 생략은 maxPlayers(원카드 선언 4), `turnSeconds`
+- ~~**`RoomService` 의 생략 기본을 게임 선언과 정렬**~~ *해소(D-131, §9).* (D-116 뒤) — `capacity` 생략은 maxPlayers(원카드 선언 4), `turnSeconds`
   생략은 0(원카드 선언 30). 지금 클라는 턴 제한은 늘, 인원은 인원 가변 게임에서 늘 보내므로(티츄는 생략하지만 maxPlayers 와
   같다) 실제로 갈리지 않는다.
-- **턴 카운트다운**(N-4, §7) — **공개 전환 전에 재검토**한다(공개 직후가 아니라). 원카드 기본 30초가 되어 기본 방마다 안 보이는
+- ~~**턴 카운트다운**(N-4, §7)~~ *해소(D-131, §9 — 서버 남은 시간 + 카운트다운).* — **공개 전환 전에 재검토**한다(공개 직후가 아니라). 원카드 기본 30초가 되어 기본 방마다 안 보이는
   시간 초과가 생겼다(위 "30초의 대가") — 게스트 첫 판은 봇 채우기가 기본이라 처음 하는 사람이 경고 없이 시간 초과를 겪는다(공격
   누적이면 통째로 먹기, 끊긴 동안은 30초마다). 카운트다운을 먼저 넣지 못하면 공개 전환 결정 문안에 이 대가를 그대로 적는다.
   남은 시간을 resync 에 실어야 재접속 직후에도 맞는다.
```

```diff
   사이에 프로세스가 죽으면 그대로 멈춘다(기존, ms 틈). 원카드는 1판 = 1라운드라 결과 화면으로 끝나 무해하다.
 - **Hikari `connection-timeout` 을 수 초로**, 매치 기록을 폴러 스레드 밖으로(C-M2 의 남은 절반 — DB 장애 때 폴러가 30초 멈춘다).
 - 다중 인스턴스에서 킥이 다른 인스턴스의 봇 루프를 못 보는 것 — 장기안은 봇 차례도 데드라인(D-96)으로 옮기는 것.
-- **스컬킹 `recordIfEnded` 기록 실패 격리 — 우선순위 높음**(최종 리뷰, 이 브랜치 범위 밖). `SkullKingGameEngine` 의 `recordIfEnded`
+- ~~**스컬킹 `recordIfEnded` 기록 실패 격리 — 우선순위 높음**~~ *해소(D-131, §9) — 스컬킹은 발행 지점, 티츄는 기록기가 자기
+  트랜잭션 예외를 삼킨다.* (최종 리뷰, 이 브랜치 범위 밖이었다.) `SkullKingGameEngine` 의 `recordIfEnded`
   (`:223`·`:284`·`:317`)가 `SkullKingMatchRecorder`(동기 `@Transactional`)의 예외를 감싸지 않아 진행 경로로 전파된다 — DB 장애면
   마지막 이벤트가 방송되지 않고 `markFinished` 가 빠져 방이 IN_GAME 에 남는다(매치가 끝난 상태라 진행 킥 대상도 아니다. resync 하면
   `matchResult` 는 보인다). 이미 공개 중인 게임의 기존 결함이고 원카드(위 진행 3)와 같은 격리가 필요하다. 티츄는 D-116 뒤.
-- **방별 "진행 중 킥" 합치기**(S5T2-M4 — 공개 전 검토). 관전 한 번이면(`spectate` 는 로그인한 누구에게나 열려 있다) 참가자·관전자
+- ~~**방별 "진행 중 킥" 합치기**~~ *부분 해소(D-131, §9) — 참가 확인을 지난 킥이 방별 진행 중 집합에 들어, 겹친 킥의 상태 GET·봇
+  루프·타이머 확인을 합친다. 킥마다 가상 스레드 1개와 방 읽기 1번은 그대로다(아래 "킥 비용").* (S5T2-M4 — 공개 전 검토.) 관전 한 번이면(`spectate` 는 로그인한 누구에게나 열려 있다) 참가자·관전자
   게이트를 지나고 SUBSCRIBE 는 레이트리밋 밖이라, 구독 폭주가 킥마다 상태 GET·가상 스레드로 커진다. 참가 확인 뒤 방별 진행 중 킥
   집합(`ConcurrentHashMap.newKeySet()`)으로 같은 방 킥이 돌면 건너뛴다.
 - **폴러 ERROR 폭주 억제·OOM 종료**(S5T1-M4 나머지). 지속적인 `Error` 면 기본 250ms × kind 3종으로 초당 최대 12건의 ERROR(+스택,
```

```diff
   폴러가 밀린 순간에는 정상 창도 마감 + 1.5초를 넘겨 그 창을 보는 모든 클라(참가자·관전자)가 동시에 resync 한다(NX 라 정합성은
   무해, 창당 1회). 유예에 지터(+0~1초)를 두거나 "팝한 항목을 가상 스레드로"(단일 폴러) 과제와 함께.
 - **킥 비용**(N-3). 봇 없는 티츄·스컬킹 방도 resync·게임 토픽 구독마다 방 해시 + 상태 JSON(티츄는 수 KB)을 한 번 더 읽는다 —
-  `room.botSeats().isEmpty()` 이고 게임이 엔진 타이머를 쓰지 않으면 건너뛴다(포트에 "타이머를 쓰는가"가 없으니 측정 뒤).
+  `room.botSeats().isEmpty()` 이고 게임이 엔진 타이머를 쓰지 않으면 건너뛴다(포트에 "타이머를 쓰는가"가 없으니 측정 뒤). 구독 폭주 때
+  킥마다의 가상 스레드 1개·방 해시 읽기(합치기 앞단 — 참가 확인)도 남는다(D-131 합치기는 그 뒤의 일만 합친다).
 - **작은 테스트·정리**(S5T1-M1·M3, S5T2-M2·M5(c), S5T3-M4, S5T4-M3(IT)·M5, S5T5-M2). `LogCapture` 캡처 동안 `setAdditive(false)`(통과
   테스트의 ERROR 스택 소음), 폴러 kind 간 격리·pop `RuntimeException`=WARN 비대칭 고정 테스트, `scheduleBotsIfIdle` 동시 호출 래치
   테스트, 봇 루프 계수 `+1` 관례 3곳을 `start` 쪽으로, `OneCardTables` 공용화(`OneCardRecorderFailureTest` 의 13-인자 생성자),
   `GameCatalogIntegrationTest` 에서 등록된 전체 게임의 `min ≤ defaultPlayers ≤ max`·턴 선택지 불변식, D-116 병합 때
   `new TichuGameDefinition(null, …)` 호출부(이 브랜치의 `RoomCreationDefaultsTest` +3) 컴파일 확인·모달 Radix `aria-describedby`
   경고(기존), `requestResync` 참조 안정 테스트(선택).
-- **공개 전환 때 확인**(S5 후반). D-116 병합 뒤 `decisions.md` D-130 을 첫 커밋으로(코드 주석이 이미 D-130 을 가리킨다), 머신 1대
+- ~~**공개 전환 때 확인**(S5 후반)~~ *해소(D-131, §9).* D-116 병합 뒤 `decisions.md` D-130 을 첫 커밋으로(코드 주석이 이미 D-130 을 가리킨다), 머신 1대
   유지(킥이 다른 인스턴스의 봇 루프를 못 본다), 기본값을 AVAILABLE 로 바꾼 뒤 `deploy.md` "다시 열기"(시크릿 지우기) 문구 재확인,
   `api.md` 카탈로그 예시의 ONE_CARD 상태·정렬 순서(상태 → displayName), `RoomService` 생략 기본 정렬(위).
+
+## 9. S5 후반 — 공개 전환 (2026-10-06, D-131)
+
+D-116 병합(997b246) 뒤 §8 의 남은 후속 가운데 공개 전에 하기로 한 것(사용자 결정 2026-10-06)과 공개 전환을 묶었다.
+
+### 결정 요약 (사용자, 2026-10-06)
+
+- **턴 카운트다운 = 서버 남은 시간 + 카운트다운.** resync 에 서버가 아는 남은 턴 시간을 싣고(게임 중립 — 티츄·스컬킹도 나중에
+  쓸 수 있게) 원카드 게임판에 카운트다운을 붙인다. 재접속 직후에도 맞아야 한다 — D-130 "30초의 대가"를 갚는다.
+- 공개 전에 같이 고친다: 스컬킹 매치 기록 실패 격리, 같은 방 진행 킥 합치기, 티츄 매치 기록 실패 격리(필요한지 확인 후).
+- 원카드 공개(AVAILABLE 기본값), `RoomService` 생략 기본을 게임 선언과 정렬, 문서 "게임 3종"·수치, 재리뷰 Minor N-2~N-6.
+
+### 진행
+
+| # | 내용 | 근거 |
+| --- | --- | --- |
+| 1 | `decisions.md` D-130(S5 앞부분)·D-131(이 묶음) | 문서 선행 |
+| 2 | 매치 기록 실패 격리 — 스컬킹은 원카드처럼 발행 지점에서(리스너 1), 티츄는 `MatchResultRecorder` 가 `TransactionTemplate` 으로 트랜잭션 경계를 감싸 본문·커밋 예외를 삼킨다(리스너 2 — 칩 정산 보호) | §8 남은 후속·재현 테스트 |
+| 3 | 같은 방 진행 킥 합치기 — 참가 확인 뒤 방별 진행 중 집합, finally 로 뺀다(상태 GET·봇 루프·타이머 확인을 합친다) | S5T2-M4 |
+| 4 | resync `turnRemainingMs`(게임 중립) + 진행 경로의 턴 재무장을 방 액션 락 안으로 + 방 만들기 생략 기본 = 게임 선언 | N-4·§8 |
+| 5 | 원카드 게임판 턴 카운트다운(`OneCardTurnCountdown`, 스토어 `turnClock`) | N-4·사용자 결정 |
+| 6 | 원카드 공개 — `@Value`·`application.yml` 기본값 AVAILABLE, 닫는 설정의 거절 경로 IT(`OneCardClosedIntegrationTest`) | T8-M6 |
+| 7 | 재리뷰 Minor — N-2 본인 큐 가드 테스트, N-3 테스트 파일 끝 빈 줄, N-4 RoomPage 블록 주석, N-5 `deploy.md` 중복 불릿, N-6 위 §8 진행 표 7행 | `s5-final-fix-review.md` |
+| 8 | 문서 — 게임 3종·수치·런북·QA·API·프로토콜·포트 | — |
+
+### 설계에서 판단한 것
+
+- **티츄 기록 격리는 기록기 쪽이다.** 재현 테스트로 확인했다 — 동기 발행이라 기록 예외가 진행 경로로 새고(두 리스너 순서
+  모두), 기록이 먼저 불리면 스프링 멀티캐스터가 첫 예외에서 멈춰 **칩 정산이 빠졌다**. 발행 지점에서 잡으면 진행만 살고 칩
+  정산은 순서 운에 맡겨진다. 기록기 본문에서 잡는 것도 모자라다 — `@Transactional` 프록시의 커밋(연결 끊김·롤백 전용 표시)은
+  본문이 돌아온 뒤라 그 try 에 걸리지 않는다. 그래서 `@Transactional` 을 떼고 `TransactionTemplate` 으로 경계를 직접 감싸
+  본문·커밋 예외를 모두 ERROR 로 남기고 삼킨다. 스컬킹·원카드는 리스너가 하나라 발행 지점에서 잡는다(같은 기준 — 기록 실패가
+  다른 리스너·진행을 막지 않는다).
+- **남은 턴 시간이 새 상태와 어긋나지 않게 재무장을 락 안으로 옮겼다.** resync 는 방 액션 락 안에서 상태를 읽는데(D-126),
+  진행 경로(컨트롤러·턴 타임아웃·엔진 타이머·탈주 계속)는 락을 푼 **뒤에** `onTurnAdvanced` 를 불렀다. 그 틈에 들어온 resync —
+  특히 액션 동안 락을 기다리던 resync 는 거의 항상 그 자리다 — 는 새 상태와 **이전 턴의 남은 시간**(또는 세대만 오르고 항목이
+  없는 순간의 null)을 받았다. 읽는 쪽만 락 안으로 옮겨서는 못 고친다(재무장이 락 밖이다). 락 안에서 방송 뒤·해제 전에 재무장하면
+  락 안에서 읽은 값은 늘 함께 읽은 상태의 턴 것이다. 봇 루프는 계속 락을 푼 뒤에 건다(쥔 채 걸면 루프가 락과 부딪쳐 재시도).
+  BotScheduler 는 원래 락 안에서 재무장했다. 덤으로 D-128 이 적어 둔 "락 해제와 세대 상승 사이 틈"도 락을 쥔 경로에서는 닫혔다.
+  재무장이 던지면(Redis 순간 장애) 컨트롤러·탈주 계속은 잡아 ERROR(스택 포함)로 남기고 해제 뒤 봇 스케줄을 그대로 건다 — 예전
+  순서(봇 → 재무장)에서는 재무장이 실패해도 봇이 걸렸는데, 그대로 던지게 두면 봇 차례로 넘어간 판이 화면만 보고 기다리는 동안
+  멈췄다(진행 킥은 resync·구독 때만 돈다). 두 스케줄러는 원래 자기 catch 가 잡는다.
+  기각한 대안: 데드라인에 무장 시점의 순번을 실어 낡음을 감지 — 감지해도 줄 값이 없다(null 이면 그 턴 내내 카운트다운이 없다).
+- **기다리는 좌석이 없으면 null.** 원카드 경쟁 창 동안에도 카드를 낸 직후 건 턴 데드라인이 살아 있지만, 발화해도 아무 일이
+  없다(`pendingSeat` < 0). 그 값을 주면 클라가 아무도 기다리지 않는 시간을 센다 — 포트의 `pendingSeats` 로 게임 중립하게 거른다.
+- **창이 열린 동안 카운트다운은 숨긴다.** 다음 차례는 창이 닫힌 뒤 시작되고(§4.4), 그때 서버가 데드라인을 처음부터 다시 건다.
+  창 동안 이전 턴의 남은 시간을 멈춰 보이거나 다음 턴 30초를 미리 보이는 것은 둘 다 서버와 다르다. 창에는 자기 진행 막대가 있다.
+- **다시 세는 이벤트는 `TURN_CHANGED` 와 `PLAYER_ELIMINATED`.** 서버는 모든 성공 진행 뒤 데드라인을 처음부터 다시 거는데, 원카드
+  진행이 내는 공개 이벤트 가운데 그 순간을 알리는 것은 `TURN_CHANGED` 이고, 차례가 그대로인 탈주(다른 사람이 나감)만
+  `PLAYER_ELIMINATED` 하나로 끝난다 — 그때도 서버는 지금 차례의 데드라인을 다시 건다. 차례가 정해지지 않았으면(−1 — 카드 직후·
+  경쟁 창) 세지 않는다. 창이 열린 채 나가면 서버가 해소(`RACE_RESOLVED` CANCELLED) → 탈락 → `TURN_CHANGED` 순서로 보내므로
+  (`OneCardEngine.desert`) 창 여부를 따로 볼 필요가 없다.
+- **"턴 30초" 배지는 둔다.** 방 설정(헤더의 "4인"과 같은 줄)이라 창 동안·관전 중에도 규칙을 알려 주고, 카운트다운은 차례 옆의
+  살아 있는 값이다. 0 에서 멈춘다 — 시간 초과 처리(먹기)가 다음 이벤트로 새 기준을 준다. 기준이 바뀐 첫 그리기는 지금 시각으로
+  센다 — 먹기 뒤 `TURN_CHANGED` 처럼 마운트된 채 기준만 바뀌면, 지난 틱의 시각으로는 턴 제한보다 큰 값(31초)이 한 프레임 보였다.
+- **생략 기본 정렬은 Task 4 로 옮겼다**(스파이크 경계). 턴 제한 생략을 서비스까지 넘기려면 `RoomController.create` 를 고쳐야 하는데
+  같은 파일을 resync 가 고친다 — 한 파일 한 태스크. 자바 API 의 짧은 오버로드는 턴 제한을 명시적으로 0 으로 넘기는 그대로다
+  (생략 = REST 요청에서 필드를 뺀 것). 인원은 짧은 오버로드도 `null` 이라 게임 선언을 탄다(원카드 테스트는 모두 인원을 준다).
+- **닫는 설정의 거절 경로는 설정을 준 IT 로 남겼다.** 공개 뒤에는 기본값이 그 경로를 지키지 않는다 — 되돌리기 런북이 기대는
+  동작(카탈로그 "준비 중", 방 생성 404)을 `OneCardClosedIntegrationTest` 가 고정한다. 기본값 두 곳(`application.yml`·`@Value`)이
+  갈리지 않는지는 단위 테스트가 본다.
+
+### 남은 후속
+
+- **매치 시작 직후 남은 턴 시간**: 라운드 스타터는 방 액션 락 밖에서 상태를 저장하고 재무장한다. 방 메타 이벤트(IN_GAME)는
+  스타터보다 먼저 나가고(`RoomService.setReady` → `RoomChangedEvent` 뒤 `GameStartingEvent`), 게임판은 마운트 즉시 소켓을 기다리지
+  않고 resync 한다(`useStompRoom`). 그 응답이 스타터의 재무장 전에 읽히면 null(저장 전이면 `RESYNC_NOT_AVAILABLE`)이라 첫 차례
+  카운트다운이 안 보일 수 있다. 드문 이유는 둘이다 — 메타 이벤트 수신 → 렌더 → REST 왕복이 스타터의 Redis 왕복 몇 번(ms)보다 대개
+  길고, 소켓 연결 뒤 onConnect resync(같은 `eventSeq` 라 적용된다)가 한 번 더 고친다. 남는 것은 두 응답이 모두 틈에 들어가거나
+  거꾸로 도착하는 경우뿐이다. 고치려면 시작 경로도 방 락 안에서(게임 중립 — `GameStartingEvent` 처리).
+- **락을 못 잡은 resync**(약 3초 대기 뒤 잠금 없이 읽음): 남은 시간이 이전 턴 값일 수 있다 — 다음 `TURN_CHANGED` 가 바로잡는다.
+- **티츄·스컬킹 카운트다운**: 티츄의 `TurnCountdown` 은 `TURN_CHANGED` 수신 시각 기준이라 재접속 직후 처음부터 센다 — 같은
+  `turnRemainingMs` 로 맞출 수 있다(스컬킹은 카운트다운 없음). 단 대기 좌석이 여럿인 단계(티츄 Dealing·Passing, 스컬킹 BIDDING)에서
+  이 값은 첫 대기 좌석(`pendingSeat`)의 자동 처리까지 남은 시간이다 — 시간 초과는 그 좌석 하나만 처리하고 처음부터 다시 걸며 다른
+  좌석의 행동도 다시 건다. 각 좌석의 남은 시간으로 보이면 안 된다(`docs/api.md` resync).
+- 진행 경로 재무장이 락 안으로 들어와 락 점유가 Redis 왕복 몇 번(세대 INCR·ZREM 2·상태 GET·ZADD)만큼 길어졌다 — 봇 경로는
+  원래 그랬다. 락 TTL 2초 대비 무시할 수준이지만 Redis 지연이 커지면 `BUSY` 가 조금 늘 수 있다.
+- §8 남은 후속 가운데 이 묶음이 닫지 않은 것(메시지 순서, 강제 종료 기록 정책, 장기 정지 탈주화, 누름 하한, 폴러 ERROR 폭주,
+  낡은 창 유예 지터, 킥 비용, 작은 정리)은 그대로다.
```

- [ ] **Step 5: 실측**

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  61 passed (61)` · `Tests  658 passed (658)`.

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --rerun`(약 4분 — `./scripts/check.sh server` 는 빌드 캐시로 `FROM-CACHE` 에 끝나 실측이 안 될 수 있다) → 집계:

```bash
python3 - <<'EOF'
import glob, os, re, xml.etree.ElementTree as ET
t = s = f = d = 0
for p in glob.glob('server/build/test-results/test/TEST-*.xml'):
    r = ET.parse(p).getroot(); n = int(r.get('tests'))
    outer = os.path.basename(p)[5:-4].split('$')[0]   # @Nested 는 바깥 클래스로 — 파일 이름에서(@DisplayName 단 @Nested 는 name 속성이 표시 이름)
    t += n; s += int(r.get('skipped')); f += int(r.get('failures')) + int(r.get('errors'))
    if not re.search(r'(IT|IntegrationTest)$', outer): d += n
print(f"tests={t} skipped={s} failed={f} dockerfree={d} ({d/t:.0%})")
EOF
```

Expected: `tests=1324 skipped=5 failed=0 dockerfree=1118 (84%)`. 기준은 main `997b246` 의 1288건(Docker 불필요 1086건)이고 이 계획이 서버 테스트 36건
(Docker 불필요 32건)을 더한다. main 이 그사이 바뀌었다면 바뀐 값에 증가분을 더해 위 문서 수치를 고친다.

Run: `grep -rniE 'onecard|one_card|원카드|skullking|tichu' server/src/main/java/com/mirboard/infra | wc -l` 와 `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra`
Expected: `16`, 둘째 명령 출력 없음.

- [ ] **Step 6: 커밋**

```bash
git add docs/game-port.md \
  docs/api.md \
  docs/stomp-protocol.md \
  docs/redis-keys.md \
  docs/architecture.md \
  docs/deploy.md \
  .env.example \
  CLAUDE.md \
  README.md \
  docs/implementation-status.md \
  docs/case-study-multi-game.md \
  docs/plans/mvp-roadmap.md \
  docs/qa-scenarios.md \
  docs/plans/onecard.md
git commit -m "docs(D-131): 게임 3종·수치 — 원카드 공개 문서, resync 남은 턴 시간, 런북·QA, 케이스 스터디 §8

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Phase Gate (컨트롤러)**

구현 에이전트가 아니라 컨트롤러가 한다. `git log --oneline main..HEAD` 로 태스크 커밋 8개(리뷰 수정 커밋이 있으면 그만큼 더)를 확인하고 최종 리뷰를 거친다.
사용자 지시(2026-10-06)로 테스트 전체 통과·최종 리뷰 Critical/Important 0 이면 묻지 않고 main 에 병합·푸시한다 — **푸시하면 자동 배포되고 원카드가 열린다**
(운영에 `MIRBOARD_ONECARD_STATUS` 시크릿 없음). 배포 뒤 운영에서 원카드 방 한 판(봇 채우기)을 확인한다.
