# 방 초대 링크 구현 계획 — 링크 복사·휴대폰 공유창, 방마다 다른 카톡 미리보기, 로그인 뒤 그 방으로 (D-133)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대기실의 "초대 링크" 버튼 하나로 방 주소(`/rooms/<방ID>`)를 카카오톡 등으로 보내고, 받은 친구가 링크를 누르면 — 가입하지 않았어도 — 로그인 화면의
"게스트로 바로 입장"을 거쳐 그 방에 앉으며, 카톡 말풍선에는 그 방의 미리보기(게임·방 이름·초대한 사람·정원)가 뜬다. 자리가 없으면 묻지 않고 관전으로 받아 안내한다.

**Architecture:** 클라는 두 순수 함수(`safeReturnPath` — 사이트 안 경로만, `shareRoomLink` — 공유창 → 복사 → 수동)를 두고, 보호 경로가 가려던 주소를
`state.from` 으로 실어 로그인·게스트·가입 뒤 그리로 보낸다. 방 화면은 서버 `join-or-reconnect` 응답(`mode`)과 방 상태로 초대 버튼·관전 안내·경합 패널을 그린다.
서버는 빌드된 `static/index.html` 의 `<!--og:start-->`…`<!--og:end-->` 구간을 `GET /rooms/{roomId}` 에서 그 방의 Open Graph 로 바꿔 준다(카톡 스크래퍼는
자바스크립트를 실행하지 않는다). 서버 입장 스크립트는 바꾸지 않는다 — 같은 사람의 동시 이중 요청만 `RoomService` 가 재접속으로 받는다.

**Tech Stack:** React 18 + TypeScript + react-router, Vitest + RTL(jsdom) / Spring Boot 4 (Java 25) MVC, JUnit 5 + Mockito + standalone MockMvc, Testcontainers(IT).

**Spec:** `docs/plans/room-invite.md`(설계 정본 — 계획과 함께 읽는다. 스파이크 뒤 결정 A·봇 비켜주기 기각은 아래 Global Constraints 에 있고, 설계 문서에는 Task 6 이 반영한다).

**참고:** 이 계획의 코드는 검증용 스파이크(사전 리뷰 지적 6건(IP-1~6 — Important 3 · Minor 3) 반영 + 사용자 결정 A, 봇과 무관한 봇 교체 리뷰 지적(경합 고정·동시 이중 요청 재접속) 반영, 봇 자리 비켜주기는 리뷰 뒤 기각해 걷어 냄)에서 옮겼다. 스파이크에서 태스크 N
까지만 적용한 트리가 N=1..6 모두 RED→GREEN·tsc·클라 전체(서버 파일이 있는 태스크는 서버 집중·컴파일까지)를 통과하는 것과 아래 각 단계의 실패·통과·테스트 수를
확인했다(클라 72파일 865건).

## Global Constraints

- 응답·주석·문서·테스트 이름은 한국어.
- 작업 위치: 브랜치 `feat/room-invite`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan`(설계 커밋 `919097f` 기반 — main `49eae78` + 설계 문서).
  메인 체크아웃(`/Users/yupchang/Developer/mirboard`)과 다른 워크트리는 건드리지 않는다. Bash 는 매 호출 작업 폴더가 바뀔 수 있으니 **명령마다 워크트리 절대경로로
  `cd` 하거나 `git -C` 를 쓴다.** `git add` 는 파일을 콕 집어서 한다.
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` — 모델이 무엇이든, 하니스가 다른 줄을 권해도 이 줄 그대로.
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 컴파일)를 돈다. `--no-verify` 금지. 그래서 **매 태스크가 끝난 트리는 타입 검사·클라 전체
  테스트·서버 컴파일을 통과해야 한다.**
- 개발 서버(`bootRun`·`npm run dev`)·브라우저·운영 접속(`flyctl` 포함)·푸시를 하지 않는다. `.env` 를 읽지 않는다.
- **한 파일은 정확히 한 태스크가 고친다**(아래 파일 지도). 다른 태스크의 파일이 필요해 보이면 멈추고 보고한다.
- **사용자 결정(2026-10-12)** — 결정 번호 **D-133**:
  - 공유 = 링크 복사 + 휴대폰 공유창(Web Share API). 공유창은 굵은 포인터(`matchMedia('(pointer: coarse)')`) 기기에서만 — PC 는 공유창이 있어도 복사. 카카오 SDK 는 쓰지 않는다.
  - 공유 링크 = **방 주소 그대로** `/rooms/<방ID>`. 미리보기는 방마다 다르게 — 제목 "{게임 표시 이름} · {방 이름}", 설명 "{방장}님이 초대했어요 · {정원}인 방 · 누르면
    바로 입장". **현재 인원은 넣지 않는다**(정원만). 요청의 Accept 와 무관하게 HTML(`produces` 를 두지 않는다 — 스크래퍼 Accept 가 제각각이라 406 이 난다).
  - 처음 온 친구 = 로그인 화면("방에 초대받았어요" 안내 + 폼 **앞** "게스트로 바로 입장") → 게스트·로그인·가입 뒤 그 방으로. 일반 진입 배치는 그대로.
  - 자리 없음 = **바로 관전 + 안내**(결정 A) — 꽉 찬 대기실(봇 자리 포함)·진행 중 방은 서버가 이미 관전으로 받는다. 방 화면 맨 위 안내: 진행 중 "게임이 이미 시작돼
    관전으로 들어왔어요", 꽉 찬 대기실 "자리가 없어 관전으로 들어왔어요". 허브에서 고른 관전·새로고침에는 띄우지 않는다. "자리가 없어요 — 관전할까요?" 패널은 확인과 입장
    사이에 자리가 찬 경합(`ROOM_FULL`·`GAME_ALREADY_STARTED`)용, 방 없음(`ROOM_NOT_FOUND`)은 "방이 없어졌어요".
  - **봇은 사람에게 자리를 비켜 주지 않는다**(기각 — 사용자: "사람들이랑 게임할 때는 봇을 다 없앨 거야. 방 만들 때도 앞으로 봇 없앨 예정이야"). 서버 입장 스크립트
    (`room_join.lua`)는 바꾸지 않는다. 봇까지 다 찬 대기실의 초대 설명은 "자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요"(버튼은 둔다).
- 저장소 규칙: 정적·HTML 서빙은 `/api/**` 밖(`SecurityConfig` 는 `/api` 밖 기본 허용 — 바꾸지 않는다), 인프라 컨트롤러는 도메인 서비스 호출만, 게임 디스패치는
  `GameRegistry`. 인프라 게임 이름 grep `grep -rniE 'onecard|one_card|원카드|skullking|tichu' server/src/main/java/com/mirboard/infra | wc -l` → **16**(기준 그대로).
- 테스트 명령: 클라 특정 `npm --prefix client run test -- <이름 일부...>`, 클라 전체 `npm --prefix client run test`, 타입 검사 `npm --prefix client run build:check`. 서버 단위 `./gradlew :server:test --rerun
  --tests "<클래스>"`(실측은 `--rerun` — 빌드 캐시가 테스트를 건너뛴다). 서버는 성공하면 개수를 찍지 않으니 **서버 집계** `awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ (failures|errors)=$/)f+=$(i+1)}} END{print "tests="t" failed="f}' server/build/test-results/test/TEST-*.xml` 로 센다(방금 돈 클래스들의 합).
  Docker IT 는 `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤에 돌린다(Testcontainers — OrbStack).
- 테스트 수(누적, 클라 `npm --prefix client run test` 마지막 두 줄): 기준 65파일·748건 → Task 2 67·785 → Task 3 69·809 → Task 4 71·861 → Task 5 72·865(Task 1·6 은 문서 — 불변).
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를 잡는 문맥이다(지우지 않는다). 세 경우 모두 첫 글자 하나를
  떼고 읽는다(나머지 들여쓰기는 파일 그대로). `+` 뒤가 비어 있으면 빈 줄을 넣는다. 한 블록은 파일에서 정확히 한 곳과 맞고, 블록은 적힌 순서대로 적용한다. 안에 ```
  가 든 블록은 네 개짜리 펜스(````)로 감쌌다 — 그 ``` 줄은 파일 내용이다. 새 파일 블록은 그대로 쓰고 끝에 줄바꿈 하나.

## Review Focus

설계가 요구하지만 직접 말하지 않은, 사람이 쓰다가 가장 먼저 부딪칠 다섯 가지 — 각 줄의 테스트는 소유 태스크에 들어 있다.

1. **오픈 리다이렉트 시도** — `from` 이 `//evil.com`, `/\evil.com`, `https://evil.com`, `javascript:…`, 제어 문자, 인코딩된 `/%2F%2Fevil.com` 이어도 사이트 밖으로 가지 않는다
   (Task 2 `returnPath.test.ts` "오픈 리다이렉트 시도는 null" · Task 3 `LoginPage.test.tsx` "from 이 … 이면 안내 없이 로그인 뒤 허브로").
2. **방 이름에 `"`·`<`·`&`·이모지·아주 긴 문자열** — 메타 속성이 깨지지 않고(이스케이프, 표식 주입도 무력), 40 코드 포인트에서 말줄임(이모지를 반으로 가르지 않음)
   (Task 5 `RoomPreviewRendererTest`).
3. **공유창·클립보드가 없는 앱 내 브라우저(카톡)** — 링크가 선택된 수동 복사 입력란이 보인다(Task 2 `shareRoomLink.test.ts` 'manual' · Task 4 `InviteLinkButton.test.tsx`
   "공유창·클립보드가 없는 앱 내 브라우저면 링크가 선택된 입력란을 보인다").
4. **이미 로그인한 사람이 초대 링크를 엶** — 로그인 화면 없이 바로 그 방(Task 3 `App.test.tsx` "이미 로그인한 사람은 로그인 화면 없이 바로 그 방으로"), 로그인 상태로
   `/login`(from 있음)에 오면 from 으로(Task 3 `LoginPage.test.tsx`).
5. **공유 뒤 방이 사라짐** — 서버는 공통 미리보기의 SPA(Task 5 `RoomPagePreviewControllerTest` "없는 방이면 틀 그대로"), 클라는 서버 문구가 아니라 "방이 없어졌어요"
   (Task 4 `RoomPage.test.tsx` `ROOM_NOT_FOUND 면 "방이 없어졌어요"와 메인으로 …`).

## 파일 지도

| 파일 | 태스크 | 책임 |
| --- | --- | --- |
| `docs/decisions.md` | 1 | D-133 결정 기록(D-132 위), D-122 에 *변경 → D-133* |
| `client/src/features/auth/returnPath.ts`, `client/src/features/lobby/shareRoomLink.ts` | 2 | `safeReturnPath`(사이트 안 경로만) · `shareRoomLink`(공유창 → 복사 → 수동) |
| `client/src/App.tsx`, `client/src/i18n/messages.ts`, `client/src/pages/LoginPage.tsx`, `client/src/pages/RegisterPage.tsx` | 3 | 보호 경로 `state.from`, 로그인·게스트·가입 뒤 그 방으로, 초대 안내 |
| `client/src/features/lobby/InviteLinkButton.tsx`, `JoinBlockedPanel.tsx`, `SpectateNotice.tsx`, `client/src/pages/RoomPage.tsx`, `client/src/pages/GameHubPage.tsx`, `server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java` | 4 | 초대 버튼, 관전 안내(결정 A), 경합·방 없음 패널, 허브 관전 기록, 동시 이중 요청 재접속 |
| `client/index.html`, `server/src/main/java/com/mirboard/infra/web/RoomPreviewRenderer.java`, `IndexHtmlTemplate.java`, `RoomPagePreviewController.java` | 5 | 공통 미리보기·표식, 방마다 다른 미리보기 `GET /rooms/{roomId}` |
| 문서 7개(아래 Task 6) | 6 | 설계 상태·API·QA·현황·README·케이스 스터디·CLAUDE.md |

---

### Task 1: 문서 — 결정 기록 D-133 (문서 선행)

코드보다 결정 기록이 먼저다(저장소 규칙). 테스트는 없다.

**Files:**
- Modify: `docs/decisions.md`

**Interfaces:**
- Produces: `## D-133 (2026-10-12) — 방 초대 링크: 링크 복사·휴대폰 공유창, 방마다 다른 카톡 미리보기, 로그인 뒤 그 방으로`(D-132 바로 위), D-122 끝에 `*변경 → D-133*`.
  뒤 태스크의 코드 주석·문서는 `D-133` 으로 이 항목을 가리킨다.

- [ ] **Step 1: 결정 항목 추가**

`docs/decisions.md`:

```diff
 보류 3건: ① 스컬킹 매치 결과 영속·ELO·desert_count 는 `users.rating` 단일 컬럼(D-02)의
 게임별 분리 결정이 선행이라 별건. ② 봇은 포트 기본(합법 균등 분포)으로 시작 — 휴리스틱은
 후속. ③ 카탈로그에 노출되지만 클라 게임판은 S6(D-103) — 직후 과제.
+
+## D-133 (2026-10-12) — 방 초대 링크: 링크 복사·휴대폰 공유창, 방마다 다른 카톡 미리보기, 로그인 뒤 그 방으로
+
+대기실의 "초대 링크" 버튼이 방 주소 그대로(`/rooms/<방ID>`)를 휴대폰 공유창(Web Share API — 굵은 포인터 기기만, PC 는 공유창이 있어도 복사)으로
+보내고, 공유창이 없으면 복사, 복사도 막히면 링크가 선택된 입력란을 보인다(카카오 SDK 공유는 앱 키 등록이 필요해 후속). 로그인 안 된 친구는 로그인
+화면에서 "방에 초대받았어요" 안내와 폼 앞의 "게스트로 바로 입장"을 보고, 게스트·로그인·가입 뒤 그 방으로 돌아간다 — 돌아갈 주소는 사이트 안 경로만
+받는다(`safeReturnPath`, 오픈 리다이렉트 금지). 꽉 찬 대기실(봇 자리 포함)·시작된 방이면 서버 `join-or-reconnect` 가 지금처럼 묻지 않고 관전으로
+받고(응답 `mode=SPECTATING`) 방 화면 위에 "…관전으로 들어왔어요" 안내를 띄우며(허브에서 고른 관전·새로고침에는 띄우지 않는다), 그런 대기실의 초대
+버튼은 "링크를 받은 친구는 관전으로 들어와요"라고 미리 알린다. 확인과 입장 사이 자리가 찬 경합(`ROOM_FULL`·`GAME_ALREADY_STARTED`)만 "관전할까요?"
+패널, 방이 없으면 "방이 없어졌어요"다(사용자 결정 A, 2026-10-12). 같은 사람의 동시 이중 요청(두 탭)은 늦은 쪽도 오류 대신 재접속이다(D-74 멱등
+보강). 봇이 사람에게 자리를 비켜 주는 안은 기각했다(사용자: 사람과 할 때는 봇을 다 없앤다, 방 만들 때 봇은 앞으로 없앨 예정). 카톡 스크래퍼는 자바스크립트를 실행하지
+않으므로 서버가 `GET /rooms/{roomId}`(`/api` 밖, 인증 없음, Accept 무관)에서 빌드된 `index.html` 의 `<!--og:start-->`…`<!--og:end-->` 구간을 그 방의
+Open Graph(게임·방 이름·방장·정원 — 현재 인원은 미리보기가 캐시돼 낡으므로 뺀다)로 바꿔 주고, 방이나 틀이 없으면 사이트 공통 미리보기의 SPA 를 그대로
+준다(초대 전용 주소 `/invite/<방ID>` 는 기각 — 설계 `docs/plans/room-invite.md`).
 
 ## D-132 (2026-10-12) — 테이블 좌석 배치: 원카드·스컬킹 테이블형 좌석 + 다음 차례 표시, 티츄 시계 방향
 
```

```diff
 마지막 준비가 몇 ms 안에 겹치면 막 시작된 게임에서 `LREM` 이 실행될 수 있다(기존 경합 — 컨트롤러 검사가
 원자적이지 않다). 후속은 `room_leave.lua` 가 IN_GAME 에서 거절하고 리매치 대기 폴백만 플래그로 허용하는
 것이다. 두 경로 모두 resync 의 비공개 뷰는 위 방어가 막는다.
+
+*변경 → D-133* (로그인 상태로 `/login` 에 오면 허브 대신, 보호 경로에서 왔으면(`state.from` — 사이트 안 경로만) 그 경로로 보낸다. 로그인·게스트 입장 뒤도 같다. `replace` 는 그대로.)
 
 ## D-121 (2026-10-03) — 게임별 튜토리얼 레지스트리 + 스컬킹 튜토리얼 (M6-④)
 
```

- [ ] **Step 2: 확인**

Run: `grep -c "^## D-133 " docs/decisions.md` 뒤 `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: `1`, 타입 오류 없음, 마지막 두 줄 `Test Files  65 passed (65)` · `Tests  748 passed (748)`.

- [ ] **Step 3: 커밋**

```bash
git add docs/decisions.md
git commit -m "docs(D-133): 방 초대 링크 결정 기록 — 링크 복사·공유창, 방마다 다른 미리보기, 로그인 뒤 그 방으로, 자리 없으면 바로 관전

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 클라 — 순수 함수 `safeReturnPath`·`shareRoomLink`

**Files:**
- Create: `client/src/features/auth/returnPath.test.ts`, `client/src/features/lobby/shareRoomLink.test.ts`, `client/src/features/auth/returnPath.ts`, `client/src/features/lobby/shareRoomLink.ts`

**Interfaces:**
- Produces: `safeReturnPath(from: unknown): string | null`(`features/auth/returnPath.ts` — `/` 로 시작하고 `//`·`/\` 가 아니며 제어 문자가 없고 `/login`·`/register`
  (대소문자·`?`·`#`·끝 슬래시 무시)가 아니면 그대로, 아니면 null). `shareRoomLink(url: string, meta: ShareMeta, env: ShareEnv): Promise<ShareResult>`(`features/lobby/shareRoomLink.ts`
  — `ShareResult = 'shared' | 'copied' | 'manual' | 'cancelled'`, `ShareEnv = { share?, clipboard? }`, `share` 는 메서드로 부른다, `AbortError` 만 취소).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/auth/returnPath.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { safeReturnPath } from './returnPath';

/**
 * D-133 — 로그인 뒤 돌아갈 주소. 보호 경로가 `/login` 으로 보낼 때 `state.from` 에 가려던 경로를 싣고,
 * 로그인·게스트 입장·가입 뒤 그 경로로 돌려보낸다. 그 값은 화면 밖에서 조작될 수 있으므로 **사이트 안
 * 경로만** 받는다 — 외부로 튕기는 오픈 리다이렉트와 로그인 화면으로의 되돌이를 막는다.
 */

const ORIGIN = 'https://mirboard.test';

/** 브라우저 URL 파서 기준으로 이 값이 사이트 안에 머무는가. */
function staysOnSite(path: string): boolean {
  return new URL(path, ORIGIN).origin === ORIGIN;
}

describe('safeReturnPath — 사이트 안 경로는 그대로 (D-133)', () => {
  it.each(['/rooms/abc-123', '/rooms/abc?tab=1#chat', '/games', '/profile', '/'])(
    '%s 를 그대로 돌려준다',
    (path) => {
      expect(safeReturnPath(path)).toBe(path);
      expect(staysOnSite(path)).toBe(true);
    },
  );

  it('인코딩된 `/%2F%2Fevil.com` 은 사이트 안 경로로만 남는다 — 브라우저가 풀어 `//` 로 읽지 않는다', () => {
    const out = safeReturnPath('/%2F%2Fevil.com');

    expect(out).toBe('/%2F%2Fevil.com');
    expect(staysOnSite(out!)).toBe(true);
  });
});

describe('safeReturnPath — 오픈 리다이렉트 시도는 null (D-133)', () => {
  it.each([
    ['//evil.com', '프로토콜 상대 주소'],
    ['/\\evil.com', '역슬래시 — 브라우저가 `//` 로 읽는다'],
    ['\\\\evil.com', '역슬래시 두 개'],
    ['https://evil.com', '절대 주소'],
    ['http://evil.com/rooms/abc', '방 경로를 흉내 낸 절대 주소'],
    ['javascript:alert(1)', '스크립트 주소'],
    ['evil.com', '슬래시로 시작하지 않는 값'],
    ['rooms/abc', '상대 경로'],
    ['', '빈 문자열'],
    ['/\t/evil.com', '탭 — URL 파서가 지워 `//evil.com` 이 된다'],
    ['/\n/evil.com', '줄바꿈 — URL 파서가 지운다'],
  ])('%j (%s)', (from) => {
    expect(safeReturnPath(from)).toBeNull();
  });

  it('위 값 중 슬래시로 시작하는 것들은 실제로 사이트 밖으로 나간다 — 거절해야 하는 이유', () => {
    expect(staysOnSite('//evil.com')).toBe(false);
    expect(staysOnSite('/\\evil.com')).toBe(false);
    expect(staysOnSite('/\t/evil.com')).toBe(false);
  });
});

describe('safeReturnPath — 되돌이·비문자열은 null (D-133)', () => {
  it.each(['/login', '/register', '/login?next=1', '/register/', '/login#top', '/LOGIN'])(
    '로그인·가입 화면 %s 로는 돌아가지 않는다',
    (from) => {
      expect(safeReturnPath(from)).toBeNull();
    },
  );

  it.each([undefined, null, 42, {}, ['/rooms/abc']])('문자열이 아닌 %j 는 null', (from) => {
    expect(safeReturnPath(from)).toBeNull();
  });
});
```

`client/src/features/lobby/shareRoomLink.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';
import { shareRoomLink, type ShareEnv } from './shareRoomLink';

/**
 * D-133 — 초대 링크 공유 순서. ① 휴대폰 공유창(Web Share API) ② 없거나 실패하면 클립보드 복사 ③ 복사도
 * 막히면(권한·비보안 문맥·카톡 같은 앱 내 브라우저) 수동 복사. 공유창 취소는 조용히 끝낸다.
 * `navigator` 를 주입받는 순수 함수라 버튼은 결과만 그린다.
 */

const URL_ = 'https://mirboard.test/rooms/abc';
const META = { title: '원카드 같이 해요', text: '금요일 한 판 — 미르보드카페' };

function abortError(): Error {
  return new DOMException('Share canceled', 'AbortError');
}

describe('shareRoomLink — 공유창 (D-133)', () => {
  it('공유창이 있으면 제목·문구·링크로 공유하고 복사하지 않는다', async () => {
    const env: ShareEnv = {
      share: vi.fn().mockResolvedValue(undefined),
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('shared');

    expect(env.share).toHaveBeenCalledWith({ title: META.title, text: META.text, url: URL_ });
    expect(env.clipboard!.writeText).not.toHaveBeenCalled();
  });

  it('공유창을 navigator 에 묶어 부른다 — 떼어 부르면 브라우저가 Illegal invocation 을 던진다', async () => {
    const env = {
      share(this: unknown) {
        if (this !== env) return Promise.reject(new TypeError('Illegal invocation'));
        return Promise.resolve();
      },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('shared');
  });

  it('공유창에서 취소(AbortError)하면 cancelled — 복사로 넘어가지 않는다', async () => {
    const env: ShareEnv = {
      share: vi.fn().mockRejectedValue(abortError()),
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('cancelled');
    expect(env.clipboard!.writeText).not.toHaveBeenCalled();
  });

  it('공유창이 다른 이유로 실패(NotAllowedError)하면 복사로 넘어간다', async () => {
    const env: ShareEnv = {
      share: vi.fn().mockRejectedValue(new DOMException('no gesture', 'NotAllowedError')),
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('copied');
    expect(env.clipboard!.writeText).toHaveBeenCalledWith(URL_);
  });
});

describe('shareRoomLink — 복사·수동 (D-133)', () => {
  it('공유창이 없으면 링크를 복사한다', async () => {
    const env: ShareEnv = { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('copied');
    expect(env.clipboard!.writeText).toHaveBeenCalledWith(URL_);
  });

  it('복사가 거절되면 manual — 링크를 직접 복사하게 한다', async () => {
    const env: ShareEnv = {
      clipboard: { writeText: vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError')) },
    };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('manual');
  });

  it('공유창도 클립보드도 없으면(카톡 앱 내 브라우저) manual', async () => {
    await expect(shareRoomLink(URL_, META, {})).resolves.toBe('manual');
  });

  it('공유창이 실패하고 클립보드가 없으면 manual', async () => {
    const env: ShareEnv = { share: vi.fn().mockRejectedValue(new TypeError('boom')) };

    await expect(shareRoomLink(URL_, META, env)).resolves.toBe('manual');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- features/auth/returnPath features/lobby/shareRoomLink`
Expected: FAIL — `Test Files  2 failed (2)` · `Tests  no tests`(`Error: Failed to resolve import "./returnPath" from "src/features/auth/returnPath.test.ts". Does the file exist?` ·
`… "./shareRoomLink" …` — 새 모듈이라 import 실패가 정상 RED).

- [ ] **Step 3: 구현**

`client/src/features/auth/returnPath.ts`:

```ts
/**
 * D-133 — 로그인 뒤 돌아갈 주소(`state.from`)를 사이트 안 경로로만 받는다.
 *
 * <p>보호 경로(`ProtectedRoute`)가 `/login` 으로 보낼 때 가려던 경로를 싣고, 로그인·게스트 입장·가입 뒤 이
 * 함수가 통과시킨 값으로만 이동한다(아니면 호출 측이 `/games`). 거절하는 것:
 * <ul>
 *   <li>문자열이 아니거나 `/` 로 시작하지 않는 값 — 절대 주소(`https://…`)·`javascript:`·상대 경로.</li>
 *   <li>`//`·`/\` 로 시작하는 값 — 브라우저는 역슬래시를 슬래시로 읽어 둘 다 다른 사이트 주소가 된다.</li>
 *   <li>제어 문자 — URL 파서가 탭·줄바꿈을 지워 `/\t/evil.com` 이 `//evil.com` 이 된다.</li>
 *   <li>로그인·가입 화면 — 되돌이(라우트는 대소문자를 가리지 않는다).</li>
 * </ul>
 * 인코딩된 `/%2F%2Fevil.com` 은 사이트 안 경로 그대로라 받는다(라우터가 없는 경로로 보고 허브로 보낸다).
 */
const AUTH_PATHS: ReadonlySet<string> = new Set(['/login', '/register']);

// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

export function safeReturnPath(from: unknown): string | null {
  if (typeof from !== 'string' || !from.startsWith('/')) return null;
  if (from.startsWith('//') || from.startsWith('/\\')) return null;
  if (CONTROL_CHARS.test(from)) return null;
  const path = from.replace(/[?#].*$/, '').replace(/\/+$/, '').toLowerCase();
  if (AUTH_PATHS.has(path)) return null;
  return from;
}
```

`client/src/features/lobby/shareRoomLink.ts`:

```ts
/**
 * D-133 — 방 초대 링크 공유. 버튼은 결과만 그린다.
 *
 * <p>순서: ① 휴대폰 공유창(Web Share API — 카톡 포함 설치된 앱 목록) ② 없거나 실패하면 클립보드 복사 ③ 복사도
 * 막히면(권한·비보안 문맥·일부 앱 내 브라우저) `'manual'` — 화면이 링크가 선택된 입력란을 보인다. 공유창에서
 * 사용자가 취소하면(`AbortError`) 조용히 끝낸다(`'cancelled'`). 카카오 SDK 공유는 앱 키 등록이 필요해 후속.
 */
export type ShareResult = 'shared' | 'copied' | 'manual' | 'cancelled';

export interface ShareMeta {
  title: string;
  text: string;
}

/** `navigator` 의 필요한 부분만. 테스트와 앱 내 브라우저(둘 다 없음)를 같은 모양으로 다룬다. */
export interface ShareEnv {
  share?: (data: ShareData) => Promise<void>;
  clipboard?: { writeText(text: string): Promise<void> };
}

function isAbort(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'AbortError';
}

export async function shareRoomLink(url: string, meta: ShareMeta, env: ShareEnv): Promise<ShareResult> {
  if (typeof env.share === 'function') {
    try {
      // 메서드로 부른다 — `navigator.share` 를 떼어 부르면 브라우저가 Illegal invocation 을 던진다.
      await env.share({ title: meta.title, text: meta.text, url });
      return 'shared';
    } catch (err) {
      if (isAbort(err)) return 'cancelled';
      // 그 밖의 실패(사용자 제스처 없음·미지원 데이터 등)는 복사로 넘어간다.
    }
  }
  if (env.clipboard && typeof env.clipboard.writeText === 'function') {
    try {
      await env.clipboard.writeText(url);
      return 'copied';
    } catch {
      // 권한 거절·비보안 문맥 — 수동 복사로.
    }
  }
  return 'manual';
}
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- features/auth/returnPath features/lobby/shareRoomLink`
Expected: PASS — `Test Files  2 passed (2)` · `Tests  37 passed (37)`.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  67 passed (67)` · `Tests  785 passed (785)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/auth/returnPath.test.ts \
  client/src/features/lobby/shareRoomLink.test.ts \
  client/src/features/auth/returnPath.ts \
  client/src/features/lobby/shareRoomLink.ts
git commit -m "feat(D-133): 클라 순수 함수 — 돌아갈 주소 safeReturnPath(사이트 안 경로만)·초대 링크 공유 shareRoomLink(공유창→복사→수동)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 클라 — 로그인 뒤 그 방으로

**Files:**
- Create: `client/src/App.test.tsx`, `client/src/pages/RegisterPage.test.tsx`
- Modify: `client/src/pages/LoginPage.test.tsx`, `client/src/App.tsx`, `client/src/i18n/messages.ts`, `client/src/pages/LoginPage.tsx`, `client/src/pages/RegisterPage.tsx`

**Interfaces:**
- Consumes: Task 2 `safeReturnPath`.
- Produces: `ProtectedRoute` 가 `/login` 으로 보낼 때 `state.from = pathname + search`. `LoginPage` 의 목적지 = `safeReturnPath(from) ?? '/games'`(로그인·게스트 뒤·이미
  로그인 상태 리다이렉트 모두 `replace`), 거른 `from` 이 `/rooms/` 로 시작하면 `Alert role="status"` "방에 초대받았어요 — …" + 기본 버튼 "게스트로 바로 입장"을 폼 앞에.
  "회원가입"·"로그인" 링크와 게스트 실패 안내의 가입 링크가 `state.from`(거른 값)을 넘긴다. 메시지 키 `auth.invite.notice`·`auth.invite.guestCta`.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/App.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { useAuthStore } from '@/features/auth/authStore';

/**
 * D-133 — 초대 링크(`/rooms/<방ID>`)로 들어온 사람의 라우팅. 보호 경로가 `/login` 으로 보낼 때 가려던 경로를
 * `state.from` 에 싣고, 로그인 화면은 게스트 입장·로그인·가입 뒤 그 경로로 돌려보낸다. 실제 `App` 의 라우트
 * 구성(BrowserRouter)을 그대로 쓰고, 소켓·API 를 여는 화면만 스텁으로 바꾼다.
 */

const { guest, login, register } = vi.hoisted(() => ({
  guest: vi.fn(),
  login: vi.fn(),
  register: vi.fn(),
}));

vi.mock('@/api/auth', () => ({
  authApi: { guest, login, register, me: vi.fn() },
}));

vi.mock('@/pages/RoomPage', async () => {
  const router = await import('react-router-dom');
  return {
    RoomPage: () => {
      const { roomId } = router.useParams();
      const location = router.useLocation();
      return <div>{`ROOM_PAGE ${roomId}${location.search}`}</div>;
    },
  };
});
vi.mock('@/pages/GameHubPage', () => ({ GameHubPage: () => <div>GAMES_PAGE</div> }));
vi.mock('@/pages/ProfilePage', () => ({ ProfilePage: () => <div>PROFILE_PAGE</div> }));

const SESSION_RESPONSE = {
  accessToken: 'tok',
  tokenType: 'Bearer',
  expiresAt: Date.now() + 3_600_000,
};

function openAt(path: string) {
  window.history.replaceState(null, '', path);
  render(<App />);
}

describe('App — 초대 링크로 들어온 사람 (D-133)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
  });

  it('로그인 안 된 채 방 링크를 열면 로그인 화면 → 게스트로 바로 입장 → 그 방(쿼리 포함)', async () => {
    guest.mockResolvedValue({
      ...SESSION_RESPONSE,
      user: { userId: 99, username: 'guest-abcd2345', guest: true },
    });
    openAt('/rooms/abc-123?from=kakao');

    expect(window.location.pathname).toBe('/login');
    expect(screen.getByText(/방에 초대받았어요/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '게스트로 바로 입장' }));

    expect(await screen.findByText('ROOM_PAGE abc-123?from=kakao')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/rooms/abc-123');
    expect(guest).toHaveBeenCalledTimes(1);
  });

  it('비밀번호로 로그인해도 그 방으로 간다', async () => {
    login.mockResolvedValue({ ...SESSION_RESPONSE, user: { userId: 3, username: 'alice' } });
    openAt('/rooms/abc-123');

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('ROOM_PAGE abc-123')).toBeInTheDocument();
  });

  it('회원가입으로 넘어가 가입해도 그 방으로 간다', async () => {
    register.mockResolvedValue({ userId: 5, username: 'newbie' });
    login.mockResolvedValue({ ...SESSION_RESPONSE, user: { userId: 5, username: 'newbie' } });
    openAt('/rooms/abc-123');

    fireEvent.click(screen.getByRole('link', { name: '회원가입' }));
    fireEvent.change(await screen.findByLabelText(/아이디 \(영문/), { target: { value: 'newbie' } });
    fireEvent.change(screen.getByLabelText(/비밀번호 \(8~64자\)/), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '회원가입 후 로그인' }));

    expect(await screen.findByText('ROOM_PAGE abc-123')).toBeInTheDocument();
    expect(register).toHaveBeenCalledWith('newbie', 'password1');
  });

  it('이미 로그인한 사람은 로그인 화면 없이 바로 그 방으로 들어간다', () => {
    useAuthStore.setState({
      token: 'tok',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 7, username: 'alice' },
    });
    openAt('/rooms/abc-123');

    expect(screen.getByText('ROOM_PAGE abc-123')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/rooms/abc-123');
  });

  it('방이 아닌 보호 경로(허브)로 왔으면 초대 안내 없이 로그인 뒤 그 경로로', async () => {
    login.mockResolvedValue({ ...SESSION_RESPONSE, user: { userId: 3, username: 'alice' } });
    openAt('/profile');

    expect(window.location.pathname).toBe('/login');
    expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('PROFILE_PAGE')).toBeInTheDocument();
  });
});
```

`client/src/pages/LoginPage.test.tsx`:

```diff
   MemoryRouter,
   Route,
   Routes,
+  useLocation,
   useNavigate,
   useNavigationType,
 } from 'react-router-dom';
```

```diff
     expect(guestButton()).toBeTruthy();
   });
 });
+
+/**
+ * D-133 — 초대 링크로 온 사람. 보호 경로가 `state.from` 에 가려던 경로를 싣고 보낸다. 방 경로(`/rooms/…`)면
+ * "방에 초대받았어요" 안내와 "게스트로 바로 입장"을 로그인 폼 **앞**에 두고, 게스트·로그인 뒤(그리고 이미
+ * 로그인 상태로 왔을 때) 그 경로로 `replace` 한다. 돌아갈 주소는 사이트 안 경로만(`safeReturnPath`).
+ */
+describe('LoginPage — 초대 링크로 온 사람 (D-133)', () => {
+  /** 도착한 화면이 어떻게 왔는지(PUSH/REPLACE)와 넘겨받은 state.from 을 보인다. */
+  function Landing({ name }: { name: string }) {
+    const navigationType = useNavigationType();
+    const location = useLocation();
+    const from = (location.state as { from?: unknown } | null)?.from;
+    return (
+      <>
+        <div>{`${name} ${location.pathname}`}</div>
+        <div data-testid="nav-type">{navigationType}</div>
+        <div data-testid="state-from">{String(from)}</div>
+      </>
+    );
+  }
+
+  function renderWithFrom(from: unknown) {
+    render(
+      <MemoryRouter initialEntries={[{ pathname: '/login', state: { from } }]}>
+        <Routes>
+          <Route path="/login" element={<LoginPage />} />
+          <Route path="/games" element={<Landing name="GAMES_PAGE" />} />
+          <Route path="/profile" element={<Landing name="PROFILE_PAGE" />} />
+          <Route path="/rooms/:roomId" element={<Landing name="ROOM_PAGE" />} />
+          <Route path="/register" element={<Landing name="REGISTER_PAGE" />} />
+        </Routes>
+      </MemoryRouter>,
+    );
+  }
+
+  const GUEST_SESSION = {
+    accessToken: 'guest-tok',
+    tokenType: 'Bearer',
+    expiresAt: Date.now() + 3_600_000,
+    user: { userId: 99, username: 'guest-abcd2345', guest: true },
+  };
+
+  beforeEach(() => {
+    vi.clearAllMocks();
+    localStorage.clear();
+    useAuthStore.setState({ token: null, expiresAt: null, user: null });
+  });
+
+  it('방 경로에서 왔으면 초대 안내와 "게스트로 바로 입장"을 로그인 폼보다 앞에 둔다', () => {
+    renderWithFrom('/rooms/r1');
+
+    const notice = screen.getByText('방에 초대받았어요 — 가입하지 않아도 게스트로 바로 들어갈 수 있어요');
+    const guestCta = screen.getByRole('button', { name: '게스트로 바로 입장' });
+    const username = screen.getByLabelText('아이디');
+    const follows = (a: Node, b: Node) =>
+      (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
+
+    expect(follows(notice, guestCta)).toBe(true);
+    expect(follows(guestCta, username)).toBe(true);
+    expect(screen.queryByRole('button', { name: '게스트로 바로 체험하기' })).toBeNull();
+  });
+
+  it('일반 진입이면 지금 배치 그대로 — 안내가 없고 게스트 버튼은 폼 뒤', () => {
+    renderLogin();
+
+    expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
+    const username = screen.getByLabelText('아이디');
+    expect(
+      username.compareDocumentPosition(guestButton()) & Node.DOCUMENT_POSITION_FOLLOWING,
+    ).toBeTruthy();
+    expect(screen.queryByRole('button', { name: '게스트로 바로 입장' })).toBeNull();
+  });
+
+  it('방이 아닌 보호 경로(/profile)에서 왔으면 배치는 그대로, 로그인 뒤 그 경로로', async () => {
+    login.mockResolvedValue({ ...GUEST_SESSION, user: { userId: 3, username: 'alice' } });
+    renderWithFrom('/profile');
+
+    expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
+    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
+    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
+    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
+
+    expect(await screen.findByText('PROFILE_PAGE /profile')).toBeInTheDocument();
+  });
+
+  it('게스트로 바로 입장하면 그 방으로 replace 한다', async () => {
+    guest.mockResolvedValue(GUEST_SESSION);
+    renderWithFrom('/rooms/r1');
+
+    fireEvent.click(screen.getByRole('button', { name: '게스트로 바로 입장' }));
+
+    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
+    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
+  });
+
+  it('비밀번호로 로그인해도 그 방으로 replace 한다', async () => {
+    login.mockResolvedValue({ ...GUEST_SESSION, user: { userId: 3, username: 'alice' } });
+    renderWithFrom('/rooms/r1');
+
+    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
+    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
+    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
+
+    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
+    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
+  });
+
+  it('이미 로그인 상태로 /login(from 있음)에 오면 로그인 화면 없이 그 방으로', async () => {
+    useAuthStore.setState({
+      token: 'tok',
+      expiresAt: Date.now() + 3_600_000,
+      user: { userId: 7, username: 'alice' },
+    });
+    renderWithFrom('/rooms/r1');
+
+    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
+    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
+  });
+
+  it.each(['//evil.com', '/\\evil.com', 'https://evil.com/rooms/r1', 'javascript:alert(1)', '/login'])(
+    'from 이 %j 이면 안내 없이 로그인 뒤 허브로 — 사이트 밖·로그인 화면으로 가지 않는다',
+    async (from) => {
+      guest.mockResolvedValue(GUEST_SESSION);
+      renderWithFrom(from);
+
+      expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
+      fireEvent.click(guestButton());
+
+      expect(await screen.findByText('GAMES_PAGE /games')).toBeInTheDocument();
+    },
+  );
+
+  it('"회원가입" 링크가 돌아갈 주소를 넘긴다', async () => {
+    renderWithFrom('/rooms/r1');
+
+    fireEvent.click(screen.getByRole('link', { name: '회원가입' }));
+
+    expect(await screen.findByText('REGISTER_PAGE /register')).toBeInTheDocument();
+    expect(screen.getByTestId('state-from').textContent).toBe('/rooms/r1');
+  });
+
+  it('게스트 생성 실패 안내의 회원가입 길도 돌아갈 주소를 넘긴다', async () => {
+    guest.mockRejectedValue(
+      new ApiError(429, { error: { code: 'TOO_MANY_REQUESTS', message: 'x' } }),
+    );
+    renderWithFrom('/rooms/r1');
+
+    fireEvent.click(screen.getByRole('button', { name: '게스트로 바로 입장' }));
+    fireEvent.click(await screen.findByRole('link', { name: '회원가입하고 시작하기' }));
+
+    expect(await screen.findByText('REGISTER_PAGE /register')).toBeInTheDocument();
+    expect(screen.getByTestId('state-from').textContent).toBe('/rooms/r1');
+  });
+});
```

`client/src/pages/RegisterPage.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RegisterPage } from './RegisterPage';
import { useAuthStore } from '@/features/auth/authStore';

/**
 * D-133 — 가입 뒤 목적지. 로그인 화면의 "회원가입" 링크가 넘긴 `state.from`(초대받은 방 등)으로 가입·로그인 뒤
 * `replace` 한다. 규칙은 로그인 화면과 같다 — 사이트 안 경로만(`safeReturnPath`), 아니면 허브.
 */

const { login, register } = vi.hoisted(() => ({ login: vi.fn(), register: vi.fn() }));

vi.mock('@/api/auth', () => ({
  authApi: { guest: vi.fn(), login, register, me: vi.fn() },
}));

function Landing({ name }: { name: string }) {
  const navigationType = useNavigationType();
  const location = useLocation();
  const from = (location.state as { from?: unknown } | null)?.from;
  return (
    <>
      <div>{`${name} ${location.pathname}`}</div>
      <div data-testid="nav-type">{navigationType}</div>
      <div data-testid="state-from">{String(from)}</div>
    </>
  );
}

function renderRegister(from?: unknown) {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/register', state: from === undefined ? null : { from } }]}>
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<Landing name="LOGIN_PAGE" />} />
        <Route path="/games" element={<Landing name="GAMES_PAGE" />} />
        <Route path="/rooms/:roomId" element={<Landing name="ROOM_PAGE" />} />
      </Routes>
    </MemoryRouter>,
  );
}

function submitSignup() {
  fireEvent.change(screen.getByLabelText(/아이디 \(영문/), { target: { value: 'newbie' } });
  fireEvent.change(screen.getByLabelText(/비밀번호 \(8~64자\)/), { target: { value: 'password1' } });
  fireEvent.click(screen.getByRole('button', { name: '회원가입 후 로그인' }));
}

describe('RegisterPage — 가입 뒤 목적지 (D-133)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
    register.mockResolvedValue({ userId: 5, username: 'newbie' });
    login.mockResolvedValue({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 5, username: 'newbie' },
    });
  });

  it('초대받은 방에서 왔으면 가입 뒤 그 방으로 replace 한다', async () => {
    renderRegister('/rooms/r1');

    submitSignup();

    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
    expect(register).toHaveBeenCalledWith('newbie', 'password1');
    expect(login).toHaveBeenCalledWith('newbie', 'password1');
  });

  it('돌아갈 주소가 없으면 지금처럼 허브로', async () => {
    renderRegister();

    submitSignup();

    expect(await screen.findByText('GAMES_PAGE /games')).toBeInTheDocument();
  });

  it.each(['//evil.com', 'https://evil.com/rooms/r1', '/login'])(
    '돌아갈 주소가 %j 이면 허브로 — 사이트 밖·로그인 화면으로 가지 않는다',
    async (from) => {
      renderRegister(from);

      submitSignup();

      expect(await screen.findByText('GAMES_PAGE /games')).toBeInTheDocument();
    },
  );

  it('"로그인" 링크로 돌아가도 돌아갈 주소를 넘긴다 — 초대 안내가 사라지지 않게', async () => {
    renderRegister('/rooms/r1');

    fireEvent.click(screen.getByRole('link', { name: '로그인' }));

    expect(await screen.findByText('LOGIN_PAGE /login')).toBeInTheDocument();
    expect(screen.getByTestId('state-from').textContent).toBe('/rooms/r1');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- src/App pages/LoginPage pages/RegisterPage`
Expected: FAIL — `Test Files  3 failed (3)` · `Tests  13 failed | 22 passed (35)`(`Unable to find an element with the text: /방에 초대받았어요/` ·
`Unable to find an accessible element with the role "button" and name "게스트로 바로 입장"` · `Unable to find an element with the text: ROOM_PAGE abc-123` 등). 처음부터 통과하는
11건은 지금 동작을 지키는 고정 테스트다(일반 진입 배치, 오픈 리다이렉트 시도 → 허브, 이미 로그인한 사람은 바로 방).

- [ ] **Step 3: 구현**

`client/src/App.tsx`:

```diff
-import { Navigate, Route, BrowserRouter as Router, Routes } from 'react-router-dom';
+import { Navigate, Route, BrowserRouter as Router, Routes, useLocation } from 'react-router-dom';
 import { useAuthStore } from '@/features/auth/authStore';
 import { LoginPage } from '@/pages/LoginPage';
 import { RegisterPage } from '@/pages/RegisterPage';
```

```diff
 import { RoomPage } from '@/pages/RoomPage';
 import { ProfilePage } from '@/pages/ProfilePage';
 
+/**
+ * 토큰이 없으면 로그인 화면으로. D-133 — 가려던 경로(pathname + search)를 `state.from` 에 실어 보내, 초대 링크
+ * (`/rooms/<방ID>`)로 온 사람이 로그인·게스트 입장·가입 뒤 그 방으로 돌아가게 한다. `state` 는 주소에 드러나지
+ * 않고 새로고침하면 사라질 수 있다 — 그때는 지금처럼 허브(`LoginPage` 가 `safeReturnPath` 로 거른다).
+ */
 function ProtectedRoute({ children }: { children: React.ReactNode }) {
   const token = useAuthStore((s) => s.token);
-  return token ? <>{children}</> : <Navigate to="/login" replace />;
+  const location = useLocation();
+  if (token) return <>{children}</>;
+  return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
 }
 
 export function App() {
```

`client/src/i18n/messages.ts`:

```diff
   'hub.guest.logoutConfirm':
     '게스트 계정은 로그아웃하면 다시 들어올 수 없어요. 로그아웃할까요?',
   'profile.guest.noPassword': '게스트 계정은 비밀번호가 없어요 — 회원가입하면 전적이 남아요',
+
+  // --- 초대 링크 (D-133) ---
+  'auth.invite.notice': '방에 초대받았어요 — 가입하지 않아도 게스트로 바로 들어갈 수 있어요',
+  'auth.invite.guestCta': '게스트로 바로 입장',
 } as const;
 
 export type MessageKey = keyof typeof KO;
```

`client/src/pages/LoginPage.tsx`:

```diff
 import { useState } from 'react';
-import { Link, Navigate, useNavigate } from 'react-router-dom';
+import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
 import { ApiError } from '@/api/client';
 import { useAuthStore } from '@/features/auth/authStore';
+import { safeReturnPath } from '@/features/auth/returnPath';
 import {
   Card,
   CardContent,
```

```diff
     (s) => !!s.token && !!s.user && (s.expiresAt == null || s.expiresAt > Date.now()),
   );
   const navigate = useNavigate();
+  // D-133 — 보호 경로가 실어 보낸 가려던 경로. 사이트 안 경로만 받고, 없으면 지금처럼 허브.
+  const location = useLocation();
+  const from = (location.state as { from?: unknown } | null)?.from;
+  const returnPath = safeReturnPath(from);
+  const destination = returnPath ?? '/games';
+  const invited = returnPath?.startsWith('/rooms/') ?? false;
   const [username, setUsername] = useState('');
   const [password, setPassword] = useState('');
   const [error, setError] = useState<string | null>(null);
```

```diff
     try {
       await login(username, password);
       // D-122 — replace: 뒤로가기로 로그인 화면에 돌아오지 않게.
-      navigate('/games', { replace: true });
+      navigate(destination, { replace: true });
     } catch (err) {
       const message = err instanceof ApiError ? err.message : '로그인 실패';
       setError(message);
```

```diff
     try {
       await loginAsGuest();
       // D-122 — replace: 뒤로가기로 로그인 화면에 돌아오지 않게.
-      navigate('/games', { replace: true });
+      navigate(destination, { replace: true });
     } catch (err) {
       setGuestError(guestErrorMessage(err));
     } finally {
```

```diff
   // 신원을 영영 잃었다 — 허브 로그아웃에만 건 확인(D-117)을 우회하는 경로였다. 만료된 토큰과
   // 사용자 정보 없는 토큰은 보내지 않는다: 다시 로그인할 길을 막거나, 허브의 /login 가드
   // (`!token || !user`)와 서로 튕기는 왕복이 된다.
-  if (signedIn) return <Navigate to="/games" replace />;
+  // D-133 — 보호 경로에서 왔으면(초대 링크 등) 허브 대신 그 경로로. 이미 로그인한 사람이 초대 링크를 열면 이 화면을 거치지 않는다.
+  if (signedIn) return <Navigate to={destination} replace />;
+
+  // D-133 — 회원가입으로 넘어가도 돌아갈 주소를 잃지 않게 그대로 넘긴다.
+  const registerState = returnPath ? { from: returnPath } : undefined;
+
+  const loginForm = (
+    <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
+      <div className="flex flex-col gap-1.5">
+        <Label htmlFor="login-username">아이디</Label>
+        <Input
+          id="login-username"
+          type="text"
+          value={username}
+          autoComplete="username"
+          onChange={(e) => setUsername(e.target.value)}
+          required
+        />
+      </div>
+      <div className="flex flex-col gap-1.5">
+        <Label htmlFor="login-password">비밀번호</Label>
+        <Input
+          id="login-password"
+          type="password"
+          value={password}
+          autoComplete="current-password"
+          onChange={(e) => setPassword(e.target.value)}
+          required
+        />
+      </div>
+      {error && (
+        <Alert variant="destructive">
+          <AlertDescription>{error}</AlertDescription>
+        </Alert>
+      )}
+      <Button type="submit" disabled={submitting || guestSubmitting}>
+        {submitting ? '로그인 중...' : '로그인'}
+      </Button>
+    </form>
+  );
+
+  const divider = (
+    <div className="my-4 flex items-center gap-3 text-xs text-muted-foreground">
+      <Separator className="flex-1" />
+      <span>{t('auth.guest.or')}</span>
+      <Separator className="flex-1" />
+    </div>
+  );
+
+  // D-117 — 가입 없는 체험. 처음 온 리뷰어가 가입 화면에서 이탈하지 않게.
+  // D-133 — 초대받은 사람에게는 같은 버튼을 "게스트로 바로 입장"으로 부른다(폼 앞에 둔다).
+  const guestEntry = (
+    <>
+      <Button
+        type="button"
+        variant={invited ? 'default' : 'outline'}
+        className="w-full"
+        onClick={handleGuest}
+        disabled={guestSubmitting || submitting}
+      >
+        {guestSubmitting
+          ? t('auth.guest.creating')
+          : invited
+            ? t('auth.invite.guestCta')
+            : t('auth.guest.cta')}
+      </Button>
+      <p className="mt-2 text-center text-xs text-muted-foreground">
+        {t('auth.guest.caption')}
+      </p>
+      {guestError && (
+        <Alert className="mt-3">
+          <AlertDescription className="flex flex-col items-start gap-2">
+            <span>{guestError}</span>
+            <Button asChild size="sm">
+              <Link to="/register" state={registerState}>
+                {t('auth.guest.signupCta')}
+              </Link>
+            </Button>
+          </AlertDescription>
+        </Alert>
+      )}
+    </>
+  );
 
   return (
     <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
```

```diff
           <CardDescription>미르보드카페에 오신 것을 환영합니다.</CardDescription>
         </CardHeader>
         <CardContent>
-          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
-            <div className="flex flex-col gap-1.5">
-              <Label htmlFor="login-username">아이디</Label>
-              <Input
-                id="login-username"
-                type="text"
-                value={username}
-                autoComplete="username"
-                onChange={(e) => setUsername(e.target.value)}
-                required
-              />
-            </div>
-            <div className="flex flex-col gap-1.5">
-              <Label htmlFor="login-password">비밀번호</Label>
-              <Input
-                id="login-password"
-                type="password"
-                value={password}
-                autoComplete="current-password"
-                onChange={(e) => setPassword(e.target.value)}
-                required
-              />
-            </div>
-            {error && (
-              <Alert variant="destructive">
-                <AlertDescription>{error}</AlertDescription>
+          {invited ? (
+            <>
+              {/* D-133 — 초대 링크로 온 사람: 안내 → 게스트로 바로 입장 → (또는) 로그인 폼. */}
+              <Alert role="status" className="mb-4">
+                <AlertDescription>{t('auth.invite.notice')}</AlertDescription>
               </Alert>
-            )}
-            <Button type="submit" disabled={submitting || guestSubmitting}>
-              {submitting ? '로그인 중...' : '로그인'}
-            </Button>
-          </form>
-
-          {/* D-117 — 가입 없는 체험. 처음 온 리뷰어가 가입 화면에서 이탈하지 않게. */}
-          <div className="my-4 flex items-center gap-3 text-xs text-muted-foreground">
-            <Separator className="flex-1" />
-            <span>{t('auth.guest.or')}</span>
-            <Separator className="flex-1" />
-          </div>
-          <Button
-            type="button"
-            variant="outline"
-            className="w-full"
-            onClick={handleGuest}
-            disabled={guestSubmitting || submitting}
-          >
-            {guestSubmitting ? t('auth.guest.creating') : t('auth.guest.cta')}
-          </Button>
-          <p className="mt-2 text-center text-xs text-muted-foreground">
-            {t('auth.guest.caption')}
-          </p>
-          {guestError && (
-            <Alert className="mt-3">
-              <AlertDescription className="flex flex-col items-start gap-2">
-                <span>{guestError}</span>
-                <Button asChild size="sm">
-                  <Link to="/register">{t('auth.guest.signupCta')}</Link>
-                </Button>
-              </AlertDescription>
-            </Alert>
+              {guestEntry}
+              {divider}
+              {loginForm}
+            </>
+          ) : (
+            <>
+              {loginForm}
+              {divider}
+              {guestEntry}
+            </>
           )}
 
           <p className="mt-4 text-center text-sm text-muted-foreground">
             계정이 없나요?{' '}
             <Link
               to="/register"
+              state={registerState}
               className="text-primary underline-offset-4 hover:underline"
             >
               회원가입
```

`client/src/pages/RegisterPage.tsx`:

```diff
 import { useState } from 'react';
-import { Link, useNavigate } from 'react-router-dom';
+import { Link, useLocation, useNavigate } from 'react-router-dom';
 import { ApiError } from '@/api/client';
 import { useAuthStore } from '@/features/auth/authStore';
+import { safeReturnPath } from '@/features/auth/returnPath';
 import {
   Card,
   CardContent,
```

```diff
   const register = useAuthStore((s) => s.register);
   const login = useAuthStore((s) => s.login);
   const navigate = useNavigate();
+  // D-133 — 로그인 화면이 넘긴 돌아갈 주소(초대받은 방 등). 규칙은 로그인 화면과 같다 — 사이트 안 경로만, 아니면 허브.
+  const location = useLocation();
+  const returnPath = safeReturnPath((location.state as { from?: unknown } | null)?.from);
   const [username, setUsername] = useState('');
   const [password, setPassword] = useState('');
   const [error, setError] = useState<string | null>(null);
```

```diff
     try {
       await register(username, password);
       await login(username, password);
-      navigate('/games', { replace: true });
+      navigate(returnPath ?? '/games', { replace: true });
     } catch (err) {
       const message = err instanceof ApiError ? err.message : '회원가입 실패';
       setError(message);
```

```diff
             이미 계정이 있나요?{' '}
             <Link
               to="/login"
+              state={returnPath ? { from: returnPath } : undefined}
               className="text-primary underline-offset-4 hover:underline"
             >
               로그인
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- src/App pages/LoginPage pages/RegisterPage`
Expected: PASS — `Test Files  3 passed (3)` · `Tests  35 passed (35)`.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  69 passed (69)` · `Tests  809 passed (809)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/App.test.tsx \
  client/src/pages/LoginPage.test.tsx \
  client/src/pages/RegisterPage.test.tsx \
  client/src/App.tsx \
  client/src/i18n/messages.ts \
  client/src/pages/LoginPage.tsx \
  client/src/pages/RegisterPage.tsx
git commit -m "feat(D-133): 로그인 뒤 그 방으로 — 보호 경로가 가려던 경로를 싣고, 초대받은 사람에게 안내·게스트로 바로 입장을 폼 앞에

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 클라 + 서버 — 방 화면(초대 버튼·관전 안내·경합 패널·방 없음), 허브 관전 기록, 동시 이중 요청 재접속

**Files:**
- Create: `client/src/features/lobby/InviteLinkButton.test.tsx`, `client/src/features/lobby/SpectateNotice.test.tsx`, `server/src/test/java/com/mirboard/domain/lobby/room/RoomServiceJoinOrReconnectTest.java`, `client/src/features/lobby/InviteLinkButton.tsx`, `client/src/features/lobby/JoinBlockedPanel.tsx`, `client/src/features/lobby/SpectateNotice.tsx`
- Modify: `client/src/pages/RoomPage.test.tsx`, `client/src/pages/GameHubPage.test.tsx`, `client/src/pages/RoomPage.tsx`, `client/src/pages/GameHubPage.tsx`, `server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java`

**Interfaces:**
- Consumes: Task 2 `shareRoomLink`·`ShareEnv`, `roomsApi.joinOrReconnect`(응답 `{ room, mode: 'JOINED' | 'RECONNECTED' | 'SPECTATING' }`)·`roomsApi.spectate`, 오류 코드
  `ROOM_FULL`·`GAME_ALREADY_STARTED`·`ROOM_NOT_FOUND`(`GlobalExceptionHandler`), 서버 `RoomRepository.join`(Lua — 이미 이 방 플레이어면 `AlreadyInRoomException`).
- Produces: `InviteLinkButton({ roomId, roomName, gameName, full?, env?, origin? })`(`aria-label="친구 초대"` 섹션, 알림 `role="status"` 상주, 수동 입력란 전체 선택,
  기본 `browserShareEnv()` 는 굵은 포인터에서만 공유창), `JoinBlockedPanel({ kind: JoinBlock, onSpectate?, spectating? })`(`JoinBlock = 'full' | 'gone'`),
  `spectateNoticeOf(mode, room): SpectateNoticeKind | null`(`'started' | 'full'`, full = WAITING + `playerCount >= capacity`)·`markSpectateNoticeSeen(roomId)`·`spectateNoticeSeen(roomId)`
  (sessionStorage `mirboard.spectateNotice.<roomId>`)·`SpectateNotice({ kind, onClose })`(`z-[120]`, [닫기], 8초). `RoomPage` 는 WAITING 에만 초대 블록(`full = playerCount >= capacity`
  — 봇 자리 포함), 세 렌더 분기 맨 위에 안내. `GameHubPage` "구경하기"는 spectate 성공 뒤 `markSpectateNoticeSeen`. 서버 `RoomService.joinOrReconnect` 는 빈자리 입장에서
  `AlreadyInRoomException` 만 잡아 다시 읽고 앉아 있으면 `RECONNECTED`(경합 `ROOM_FULL`·`GAME_ALREADY_STARTED` 는 잡지 않는다 — 클라 패널 경로).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/lobby/InviteLinkButton.test.tsx`:

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InviteLinkButton } from './InviteLinkButton';
import type { ShareEnv } from './shareRoomLink';

/**
 * D-133 — 대기실 "초대 링크" 버튼. 공유·복사는 `shareRoomLink` 가 하고 버튼은 결과만 그린다:
 * 공유됨·복사됨은 짧은 알림, 둘 다 안 되면(카톡 같은 앱 내 브라우저) 링크가 **선택된** 입력란, 취소는 아무것도 안 보인다.
 */

const ORIGIN = 'https://mirboard.test';
const LINK = `${ORIGIN}/rooms/r1`;

function renderButton(env: ShareEnv) {
  render(
    <InviteLinkButton roomId="r1" roomName="금요일 한 판" gameName="원카드" env={env} origin={ORIGIN} />,
  );
  return screen.getByRole('button', { name: '초대 링크' });
}

describe('InviteLinkButton — 결과별 표시 (D-133)', () => {
  it('공유창이 있으면 방 주소 그대로를 게임·방 이름과 함께 공유하고 "보냈어요"를 알린다', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    fireEvent.click(renderButton({ share }));

    expect(await screen.findByText('초대 링크를 보냈어요')).toHaveAttribute('role', 'status');
    expect(share).toHaveBeenCalledWith({
      title: '원카드 같이 해요',
      text: '금요일 한 판 — 미르보드카페',
      url: LINK,
    });
  });

  it('공유창이 없으면 복사하고 "복사했어요"를 알린다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    fireEvent.click(renderButton({ clipboard: { writeText } }));

    expect(await screen.findByText('링크를 복사했어요')).toHaveAttribute('role', 'status');
    expect(writeText).toHaveBeenCalledWith(LINK);
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('공유창·클립보드가 없는 앱 내 브라우저면 링크가 선택된 입력란을 보인다', async () => {
    fireEvent.click(renderButton({}));

    const input = (await screen.findByLabelText('직접 복사해 주세요')) as HTMLInputElement;
    expect(input.value).toBe(LINK);
    expect(input.readOnly).toBe(true);
    expect(document.activeElement).toBe(input);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(LINK.length);
  });

  it('복사가 거절돼도 수동 입력란으로 넘어간다', async () => {
    const writeText = vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError'));
    fireEvent.click(renderButton({ clipboard: { writeText } }));

    expect(((await screen.findByLabelText('직접 복사해 주세요')) as HTMLInputElement).value).toBe(LINK);
  });

  it('공유창에서 취소하면 아무것도 알리지 않는다', async () => {
    const share = vi.fn().mockRejectedValue(new DOMException('canceled', 'AbortError'));
    const button = renderButton({ share });
    fireEvent.click(button);

    await waitFor(() => expect(share).toHaveBeenCalled());
    await waitFor(() => expect(button).not.toBeDisabled());
    expect(screen.getByRole('status').textContent).toBe('');
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  // 라이브 영역은 내용이 바뀌기 전에 DOM 에 있어야 스크린 리더가 읽는다 — 내용과 함께 삽입하면 일부가 무시한다(IP-4).
  it('클릭 전에도 알림 영역(status)이 비어 있는 채로 있다', () => {
    renderButton({});

    expect(screen.getByRole('status').textContent).toBe('');
  });

  it('방 ID 는 경로 조각으로 인코딩한다', () => {
    render(
      <InviteLinkButton roomId="a/b?c" roomName="방" gameName="티츄" env={{}} origin={ORIGIN} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '초대 링크' }));

    return screen.findByLabelText('직접 복사해 주세요').then((input) => {
      expect((input as HTMLInputElement).value).toBe(`${ORIGIN}/rooms/a%2Fb%3Fc`);
    });
  });
});

/**
 * D-133 — 자리가 다 찬 대기실(봇 자리 포함)의 초대는 관전 초대다. 버튼은 그대로 두고 설명만 사실대로 바꾼다(IP-3).
 */
describe('InviteLinkButton — 자리가 다 찬 방 (D-133)', () => {
  it('full 이면 "링크를 받은 친구는 관전으로 들어와요"', () => {
    render(<InviteLinkButton roomId="r1" roomName="방" gameName="원카드" full env={{}} origin={ORIGIN} />);

    expect(screen.getByText('자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요')).toBeInTheDocument();
    expect(screen.queryByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeNull();
    expect(screen.getByRole('button', { name: '초대 링크' })).toBeInTheDocument();
  });

  it('자리가 있으면 "친구가 바로 이 방에 들어와요"', () => {
    render(<InviteLinkButton roomId="r1" roomName="방" gameName="원카드" env={{}} origin={ORIGIN} />);

    expect(screen.getByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeInTheDocument();
  });
});

/**
 * D-133 — 주입 없이(실제 브라우저 경로) 공유창은 휴대폰·태블릿(굵은 포인터)에서만 연다. Web Share API 는 데스크톱 Chrome·Edge(Windows)·
 * Safari(macOS)에도 있어 그대로 두면 PC 에서 "링크 복사" 대신 OS 공유창이 뜬다(IP-2). jsdom 에는 `navigator.share`·`matchMedia` 가 없어 넣어 준다.
 */
describe('InviteLinkButton — 기본 경로는 휴대폰만 공유창 (D-133)', () => {
  const share = vi.fn().mockResolvedValue(undefined);
  const writeText = vi.fn().mockResolvedValue(undefined);

  function installBrowser(coarse: boolean) {
    Object.defineProperty(window, 'matchMedia', {
      value: vi.fn((query: string) => ({ matches: coarse && query === '(pointer: coarse)', media: query })),
      configurable: true,
    });
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  }

  afterEach(() => {
    share.mockClear();
    writeText.mockClear();
    delete (window as { matchMedia?: unknown }).matchMedia;
    delete (navigator as { share?: unknown }).share;
    delete (navigator as { clipboard?: unknown }).clipboard;
  });

  it('데스크톱(마우스)이면 공유창이 있어도 링크를 복사한다', async () => {
    installBrowser(false);
    render(<InviteLinkButton roomId="r1" roomName="금요일 한 판" gameName="원카드" origin={ORIGIN} />);

    fireEvent.click(screen.getByRole('button', { name: '초대 링크' }));

    expect(await screen.findByText('링크를 복사했어요')).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith(LINK);
    expect(share).not.toHaveBeenCalled();
  });

  it('휴대폰(굵은 포인터)이면 공유창을 연다', async () => {
    installBrowser(true);
    render(<InviteLinkButton roomId="r1" roomName="금요일 한 판" gameName="원카드" origin={ORIGIN} />);

    fireEvent.click(screen.getByRole('button', { name: '초대 링크' }));

    expect(await screen.findByText('초대 링크를 보냈어요')).toBeInTheDocument();
    expect(share).toHaveBeenCalledWith({ title: '원카드 같이 해요', text: '금요일 한 판 — 미르보드카페', url: LINK });
    expect(writeText).not.toHaveBeenCalled();
  });
});
```

`client/src/features/lobby/SpectateNotice.test.tsx`:

```tsx
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SpectateNotice,
  markSpectateNoticeSeen,
  spectateNoticeOf,
  spectateNoticeSeen,
} from './SpectateNotice';
import type { Room } from '@/types/api';

/**
 * D-133 결정 A — 서버 `join-or-reconnect` 는 꽉 찬 대기실·진행 중 방이면 묻지 않고 관전으로 받는다(`mode=SPECTATING`). 그렇게 들어온
 * 사람에게 방 화면 위에 이유를 알린다. 빈자리가 있는 대기실 관전은 본인이 고른 것이고, 끝난 방은 종료 카드가 설명한다.
 */

function room(over: Partial<Room>): Room {
  return {
    roomId: 'r1',
    name: '방',
    gameType: 'ONE_CARD',
    hostId: 2,
    status: 'WAITING',
    capacity: 2,
    playerCount: 2,
    playerIds: [2, 3],
    spectatorIds: [1],
    teamPolicy: 'SEQUENTIAL',
    createdAt: 0,
    fillWithBots: false,
    botSeats: [],
    targetScore: 1000,
    turnSeconds: 0,
    stake: 0,
    readyUserIds: [],
    ...over,
  };
}

describe('spectateNoticeOf (D-133 결정 A)', () => {
  it('관전으로 받아졌고 게임이 시작된 방이면 started', () => {
    expect(spectateNoticeOf('SPECTATING', room({ status: 'IN_GAME' }))).toBe('started');
  });

  it('관전으로 받아졌고 꽉 찬 대기실이면 full', () => {
    expect(spectateNoticeOf('SPECTATING', room({}))).toBe('full');
  });

  it('빈자리가 있는 대기실 관전은 본인이 고른 것이라 없다', () => {
    expect(spectateNoticeOf('SPECTATING', room({ capacity: 4 }))).toBeNull();
  });

  it('끝난 방은 종료 카드가 설명하므로 없다', () => {
    expect(spectateNoticeOf('SPECTATING', room({ status: 'FINISHED' }))).toBeNull();
  });

  it.each(['JOINED', 'RECONNECTED'] as const)('%s 면 없다', (mode) => {
    expect(spectateNoticeOf(mode, room({ status: 'IN_GAME' }))).toBeNull();
  });
});

describe('관전 안내 열람 기록 (D-133 결정 A)', () => {
  beforeEach(() => sessionStorage.clear());

  it('방마다 탭 안에서 기록한다', () => {
    expect(spectateNoticeSeen('r1')).toBe(false);
    markSpectateNoticeSeen('r1');
    expect(spectateNoticeSeen('r1')).toBe(true);
    expect(spectateNoticeSeen('r2')).toBe(false);
  });

  it('저장소가 막혀도(사생활 보호 창) 던지지 않고 본 적 없는 것으로 본다', () => {
    const original = globalThis.sessionStorage;
    Object.defineProperty(globalThis, 'sessionStorage', {
      get() {
        throw new DOMException('blocked', 'SecurityError');
      },
      configurable: true,
    });
    try {
      expect(() => markSpectateNoticeSeen('r1')).not.toThrow();
      expect(spectateNoticeSeen('r1')).toBe(false);
    } finally {
      Object.defineProperty(globalThis, 'sessionStorage', { value: original, configurable: true, writable: true });
    }
  });
});

describe('SpectateNotice (D-133 결정 A)', () => {
  afterEach(() => vi.useRealTimers());

  it.each([
    ['started', '게임이 이미 시작돼 관전으로 들어왔어요'],
    ['full', '자리가 없어 관전으로 들어왔어요'],
  ] as const)('%s 문구', (kind, text) => {
    render(<SpectateNotice kind={kind} onClose={() => {}} />);

    expect(screen.getByRole('status')).toHaveTextContent(text);
  });

  it('[닫기]를 누르면 onClose', () => {
    const onClose = vi.fn();
    render(<SpectateNotice kind="full" onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('8초 뒤 저절로 내린다', () => {
    vi.useFakeTimers();
    const onClose = vi.fn();
    render(<SpectateNotice kind="started" onClose={onClose} />);

    act(() => vi.advanceTimersByTime(7_999));
    expect(onClose).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
```

`client/src/pages/RoomPage.test.tsx`:

```diff
 import { MemoryRouter, Route, Routes } from 'react-router-dom';
 import { beforeEach, describe, expect, it, vi } from 'vitest';
 import { RoomPage } from './RoomPage';
+import { ApiError } from '@/api/client';
+import type { JoinOrReconnectResponse } from '@/api/rooms';
 import { useAuthStore } from '@/features/auth/authStore';
+import { markSpectateNoticeSeen } from '@/features/lobby/SpectateNotice';
 import { GAME_TUTORIALS } from '@/features/tutorial/gameTutorials';
 import { markTutorialSeen } from '@/features/tutorial/useTutorialGate';
 import { useRoomMeta } from '@/ws/useRoomMeta';
```

```diff
 
 vi.mock('@/ws/useRoomMeta', () => ({ useRoomMeta: vi.fn() }));
 
-const { joinOrReconnect, loadGame, names } = vi.hoisted(() => ({
+const { joinOrReconnect, spectate, loadGame, names } = vi.hoisted(() => ({
   joinOrReconnect: vi.fn(),
+  spectate: vi.fn(),
   loadGame: vi.fn(),
   names: vi.fn(),
 }));
```

```diff
 vi.mock('@/api/rooms', () => ({
   roomsApi: {
     joinOrReconnect,
+    spectate,
     leave: vi.fn(),
     setReady: vi.fn(),
     updateTeamPolicy: vi.fn(),
```

```diff
   };
 }
 
-function renderRoom(room: Partial<Room> = {}) {
-  joinOrReconnect.mockResolvedValue({ mode: 'JOINED', room: { ...ROOM, ...room } });
+function renderRoom(room: Partial<Room> = {}, mode: JoinOrReconnectResponse['mode'] = 'JOINED') {
+  joinOrReconnect.mockResolvedValue({ mode, room: { ...ROOM, ...room } });
   return render(
     <MemoryRouter initialEntries={['/rooms/r1']}>
       <Routes>
```

```diff
     expect(screen.queryByRole('dialog')).toBeNull();
   });
 });
+
+/**
+ * D-133 — 대기실 "초대 링크". 방 주소 그대로(`/rooms/<방ID>`)를 공유창·복사·수동 입력란으로 건넨다. 초대는 시작 전에만
+ * 의미가 있어(시작하면 자리가 없다) 대기실(WAITING)에만 둔다. 결과별 표시는 `InviteLinkButton.test` 가 자세히 본다.
+ */
+describe('RoomPage — 초대 링크 (D-133)', () => {
+  beforeEach(() => {
+    vi.clearAllMocks();
+    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
+    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
+    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), displayName: '원카드' });
+    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
+  });
+
+  it('대기실에는 "초대 링크" 버튼이 있다', async () => {
+    renderRoom({ gameType: 'ONE_CARD' });
+
+    expect(await screen.findByRole('button', { name: '초대 링크' })).toBeInTheDocument();
+  });
+
+  it('게임 중(IN_GAME) 화면에는 없다', async () => {
+    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME' });
+
+    await screen.findByTestId('onecard-table');
+    expect(screen.queryByRole('button', { name: '초대 링크' })).toBeNull();
+  });
+
+  it('끝난 방(FINISHED) 화면에도 없다', async () => {
+    loadGame.mockResolvedValue(game('TICHU', ['TEAMS']));
+    renderRoom({ gameType: 'TICHU', status: 'FINISHED' });
+
+    await screen.findByText('게임이 종료되었습니다.');
+    expect(screen.queryByRole('button', { name: '초대 링크' })).toBeNull();
+  });
+
+  it('공유창·클립보드가 없으면(앱 내 브라우저) 이 방 주소가 선택된 입력란을 보인다', async () => {
+    renderRoom({ gameType: 'ONE_CARD' });
+
+    fireEvent.click(await screen.findByRole('button', { name: '초대 링크' }));
+
+    const input = (await screen.findByLabelText('직접 복사해 주세요')) as HTMLInputElement;
+    expect(input.value).toBe(`${window.location.origin}/rooms/r1`);
+    expect(document.activeElement).toBe(input);
+    expect(input.selectionEnd).toBe(input.value.length);
+  });
+
+  it('클립보드가 있으면 이 방 주소를 복사하고 알린다', async () => {
+    const writeText = vi.fn().mockResolvedValue(undefined);
+    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
+    try {
+      renderRoom({ gameType: 'ONE_CARD' });
+
+      fireEvent.click(await screen.findByRole('button', { name: '초대 링크' }));
+
+      expect(await screen.findByText('링크를 복사했어요')).toBeInTheDocument();
+      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/rooms/r1`);
+    } finally {
+      delete (navigator as { clipboard?: unknown }).clipboard;
+    }
+  });
+
+  // IP-3 — 사람으로 꽉 찬 대기실의 링크를 연 친구는 관전으로 들어온다. "바로 이 방에 들어와요"는 거짓이다.
+  it('사람으로 꽉 찬 대기실이면 "링크를 받은 친구는 관전으로 들어와요"', async () => {
+    renderRoom({ gameType: 'ONE_CARD', capacity: 2, playerCount: 2, playerIds: [1, 2], botSeats: [] });
+
+    expect(
+      await screen.findByText('자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요'),
+    ).toBeInTheDocument();
+    expect(screen.queryByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeNull();
+  });
+
+  it('빈자리가 있으면 "친구가 바로 이 방에 들어와요"', async () => {
+    renderRoom({ gameType: 'ONE_CARD', capacity: 4, playerCount: 1, playerIds: [1] });
+
+    expect(await screen.findByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeInTheDocument();
+  });
+
+  // 게스트 방은 기본 봇 채우기라 만들자마자 꽉 찬다. 봇은 자리를 비켜 주지 않으므로(봇 자리 비켜주기는 기각 — 사람과 할 때는 봇을
+  // 없앤다) 링크를 받은 친구는 관전으로 들어온다 — 봇 자리도 찬 자리다.
+  it('봇까지 다 찬 대기실(게스트 봇 채우기 방)도 "링크를 받은 친구는 관전으로 들어와요"', async () => {
+    renderRoom({ gameType: 'ONE_CARD', capacity: 2, playerCount: 2, playerIds: [1, 900], botSeats: [1], fillWithBots: true });
+
+    expect(
+      await screen.findByText('자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요'),
+    ).toBeInTheDocument();
+    expect(screen.queryByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeNull();
+  });
+});
+
+/**
+ * D-133 — 방 주소로 처음 연 탭은 서버가 넣은 방 제목("{게임} · {방 이름} — 미르보드카페")으로 시작한다. SPA 는 제목을 관리하지 않으므로
+ * 방 화면을 떠날 때 기본 제목으로 되돌린다 — 허브로 나가거나 다른 방에 가도 처음 방 이름이 탭·방문 기록에 남지 않게(IP-5).
+ */
+describe('RoomPage — 탭 제목 (D-133)', () => {
+  beforeEach(() => {
+    vi.clearAllMocks();
+    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
+    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
+    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), displayName: '원카드' });
+    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
+  });
+
+  it('방 화면을 떠나면 서버가 넣은 방 제목을 기본 제목으로 되돌린다', async () => {
+    document.title = '원카드 · 금요일 한 판 — 미르보드카페';
+    const view = renderRoom({ gameType: 'ONE_CARD' });
+    await screen.findByText('테스트 방');
+
+    view.unmount();
+
+    expect(document.title).toBe('Mirboard');
+  });
+});
+
+/**
+ * D-133 — 초대 링크로 들어왔는데 자리가 없거나 방이 사라졌을 때. 서버 오류 문구("Room capacity exceeded" 등)를
+ * 그대로 보이지 않고 다음 행동을 제안한다 — 자리 없음은 관전, 방 없음은 메인으로. (서버 `join-or-reconnect` 는 꽉 찬
+ * 대기실·진행 중 방이면 이미 관전으로 받으므로 이 패널은 확인과 입장 사이에 자리가 찬 경합에서 뜬다.)
+ */
+describe('RoomPage — 자리 없음·방 없음 (D-133)', () => {
+  function renderRejected(err: ApiError) {
+    joinOrReconnect.mockRejectedValue(err);
+    return render(
+      <MemoryRouter initialEntries={['/rooms/r1']}>
+        <Routes>
+          <Route path="/rooms/:roomId" element={<RoomPage />} />
+          <Route path="/games" element={<div>GAMES_PAGE</div>} />
+        </Routes>
+      </MemoryRouter>,
+    );
+  }
+
+  const apiError = (status: number, code: string, message: string) =>
+    new ApiError(status, { error: { code, message } });
+
+  beforeEach(() => {
+    vi.clearAllMocks();
+    skullProps.length = 0;
+    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
+    names.mockResolvedValue({ names: [{ userId: 2, username: 'host' }] });
+    loadGame.mockResolvedValue({ ...game('SKULL_KING', []), displayName: '스컬킹' });
+    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'friend' } as never });
+  });
+
+  it.each([
+    ['ROOM_FULL', 'Room capacity exceeded'],
+    ['GAME_ALREADY_STARTED', 'Game already started'],
+  ])('%s 면 오류 문구 대신 "자리가 없어요 — 관전할까요?"와 관전하기·메인으로', async (code, message) => {
+    renderRejected(apiError(409, code, message));
+
+    expect(await screen.findByText('자리가 없어요 — 관전할까요?')).toBeInTheDocument();
+    expect(screen.getByRole('button', { name: '관전하기' })).toBeInTheDocument();
+    expect(screen.getByRole('link', { name: '메인으로' })).toHaveAttribute('href', '/games');
+    expect(screen.queryByText(message)).toBeNull();
+  });
+
+  it('관전하기를 누르면 관전으로 들어가 그 방을 다시 그린다 — 진행 중이면 관전자 게임판', async () => {
+    renderRejected(apiError(409, 'GAME_ALREADY_STARTED', 'Game already started'));
+    spectate.mockResolvedValue({
+      ...ROOM,
+      gameType: 'SKULL_KING',
+      status: 'IN_GAME',
+      hostId: 2,
+      playerIds: [2, 3],
+      playerCount: 2,
+      spectatorIds: [1],
+    } as Room);
+
+    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));
+
+    expect(await screen.findByTestId('skullking-table')).toBeInTheDocument();
+    expect(spectate).toHaveBeenCalledWith('tok', 'r1');
+    expect(skullProps.at(-1)!.spectator).toBe(true);
+    expect(screen.queryByText('자리가 없어요 — 관전할까요?')).toBeNull();
+    // D-133 결정 A — 패널에서 본인이 고른 관전이라 "…관전으로 들어왔어요" 안내는 없다.
+    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
+  });
+
+  it('꽉 찬 대기실에서 관전하기를 누르면 관전 중 대기실을 본다', async () => {
+    renderRejected(apiError(409, 'ROOM_FULL', 'Room capacity exceeded'));
+    spectate.mockResolvedValue({
+      ...ROOM,
+      gameType: 'SKULL_KING',
+      hostId: 2,
+      capacity: 2,
+      playerIds: [2, 3],
+      playerCount: 2,
+      spectatorIds: [1],
+    } as Room);
+
+    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));
+
+    expect(await screen.findByText('관전 중 — 본인 손패는 표시되지 않습니다.')).toBeInTheDocument();
+  });
+
+  it('메인으로를 누르면 허브로 간다', async () => {
+    renderRejected(apiError(409, 'ROOM_FULL', 'Room capacity exceeded'));
+
+    fireEvent.click(await screen.findByRole('link', { name: '메인으로' }));
+
+    expect(await screen.findByText('GAMES_PAGE')).toBeInTheDocument();
+  });
+
+  it('ROOM_NOT_FOUND 면 "방이 없어졌어요"와 메인으로 — 관전하기는 없고 서버 문구도 보이지 않는다', async () => {
+    renderRejected(apiError(404, 'ROOM_NOT_FOUND', 'Room not found'));
+
+    expect(await screen.findByText('방이 없어졌어요')).toBeInTheDocument();
+    expect(screen.getByRole('link', { name: '메인으로' })).toHaveAttribute('href', '/games');
+    expect(screen.queryByRole('button', { name: '관전하기' })).toBeNull();
+    expect(screen.queryByText('Room not found')).toBeNull();
+  });
+
+  it('관전하기 사이에 방이 사라지면 "방이 없어졌어요"로 바뀐다', async () => {
+    renderRejected(apiError(409, 'ROOM_FULL', 'Room capacity exceeded'));
+    spectate.mockRejectedValue(apiError(404, 'ROOM_NOT_FOUND', 'Room not found'));
+
+    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));
+
+    expect(await screen.findByText('방이 없어졌어요')).toBeInTheDocument();
+  });
+
+  it('그 밖의 오류는 지금처럼 서버 문구를 보인다', async () => {
+    renderRejected(apiError(409, 'ALREADY_IN_ROOM', 'User already in room'));
+
+    expect(await screen.findByText('User already in room')).toBeInTheDocument();
+    expect(screen.queryByText('자리가 없어요 — 관전할까요?')).toBeNull();
+  });
+});
+
+/**
+ * D-133 결정 A — 서버 `join-or-reconnect` 는 꽉 찬 대기실·진행 중 방이면 묻지 않고 관전으로 받는다(`mode=SPECTATING`, 서버 무변경).
+ * 그렇게 들어온 사람에게 방 화면 위에 이유를 알린다. 진행 중 방은 대기실 셸이 아니라 게임판을 바로 그리므로 세 렌더 분기(스컬킹·원카드
+ * 게임판, 티츄 게임판, 대기실·종료 셸) 모두에 있어야 한다. 허브에서 고른 관전·새로고침한 관전자에게는 띄우지 않는다(탭 안 기록).
+ */
+describe('RoomPage — 관전으로 받아졌을 때 안내 (D-133 결정 A)', () => {
+  const STARTED = '게임이 이미 시작돼 관전으로 들어왔어요';
+  const FULL = '자리가 없어 관전으로 들어왔어요';
+  const watching = { hostId: 2, playerIds: [2, 3], playerCount: 2, spectatorIds: [1] } as Partial<Room>;
+
+  beforeEach(() => {
+    vi.clearAllMocks();
+    sessionStorage.clear();
+    skullProps.length = 0;
+    tichuProps.length = 0;
+    oneCardProps.length = 0;
+    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
+    names.mockResolvedValue({ names: [{ userId: 2, username: 'host' }] });
+    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), displayName: '원카드' });
+    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'friend' } as never });
+  });
+
+  it('진행 중인 원카드 방에 관전으로 받아지면 게임판 위에 안내한다', async () => {
+    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');
+
+    expect(await screen.findByTestId('onecard-table')).toBeInTheDocument();
+    expect(await screen.findByRole('status')).toHaveTextContent(STARTED);
+    expect(oneCardProps.at(-1)!.spectator).toBe(true);
+  });
+
+  it('진행 중인 티츄 방도 게임판 위에 안내한다 — 티츄 분기', async () => {
+    loadGame.mockResolvedValue(game('TICHU', ['TEAMS']));
+    renderRoom({ ...watching, gameType: 'TICHU', status: 'IN_GAME', capacity: 2 }, 'SPECTATING');
+
+    expect(await screen.findByTestId('tichu-table')).toBeInTheDocument();
+    expect(await screen.findByRole('status')).toHaveTextContent(STARTED);
+  });
+
+  it('꽉 찬 대기실에 관전으로 받아지면 안내하고 "관전 중" 알림도 그대로 둔다', async () => {
+    renderRoom({ ...watching, gameType: 'ONE_CARD', capacity: 2 }, 'SPECTATING');
+
+    expect(await screen.findByText(FULL)).toBeInTheDocument();
+    expect(screen.getByText('관전 중 — 본인 손패는 표시되지 않습니다.')).toBeInTheDocument();
+  });
+
+  it('빈자리가 있는 대기실 관전은 안내하지 않는다 — 본인이 고른 관전', async () => {
+    renderRoom({ ...watching, gameType: 'ONE_CARD', capacity: 4 }, 'SPECTATING');
+
+    await screen.findByText('관전 중 — 본인 손패는 표시되지 않습니다.');
+    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
+  });
+
+  it('끝난 방은 안내하지 않는다 — 종료 카드가 설명한다', async () => {
+    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'FINISHED' }, 'SPECTATING');
+
+    await screen.findByText('게임이 종료되었습니다.');
+    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
+  });
+
+  it.each(['JOINED', 'RECONNECTED'] as const)('%s 면 안내하지 않는다', async (mode) => {
+    useAuthStore.setState({ token: 'tok', user: { userId: 2, username: 'host' } as never });
+    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, mode);
+
+    await screen.findByTestId('onecard-table');
+    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
+  });
+
+  it('[닫기]를 누르면 내린다', async () => {
+    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');
+
+    fireEvent.click(await screen.findByRole('button', { name: '닫기' }));
+
+    expect(screen.queryByText(STARTED)).toBeNull();
+    expect(screen.getByTestId('onecard-table')).toBeInTheDocument();
+  });
+
+  it('새로고침하면(같은 탭에서 다시 관전으로 받아짐) 다시 띄우지 않는다', async () => {
+    const first = renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');
+    expect(await screen.findByText(STARTED)).toBeInTheDocument();
+    first.unmount();
+
+    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');
+
+    await screen.findByTestId('onecard-table');
+    expect(screen.queryByText(STARTED)).toBeNull();
+  });
+
+  it('허브에서 고른 관전(기록을 남기고 들어옴)이면 띄우지 않는다', async () => {
+    markSpectateNoticeSeen('r1');
+    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');
+
+    await screen.findByTestId('onecard-table');
+    expect(screen.queryByText(STARTED)).toBeNull();
+  });
+
+  it('경합 패널에서 관전을 고르면 그 뒤 새로고침에도 띄우지 않는다', async () => {
+    joinOrReconnect.mockRejectedValue(new ApiError(409, { error: { code: 'GAME_ALREADY_STARTED', message: 'x' } }));
+    spectate.mockResolvedValue({ ...ROOM, ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' } as Room);
+    const first = render(
+      <MemoryRouter initialEntries={['/rooms/r1']}>
+        <Routes>
+          <Route path="/rooms/:roomId" element={<RoomPage />} />
+        </Routes>
+      </MemoryRouter>,
+    );
+    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));
+    await screen.findByTestId('onecard-table');
+    first.unmount();
+
+    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');
+
+    await screen.findByTestId('onecard-table');
+    expect(screen.queryByText(STARTED)).toBeNull();
+  });
+});
```

`client/src/pages/GameHubPage.test.tsx`:

```diff
 import { fireEvent, render, screen, within } from '@testing-library/react';
-import { MemoryRouter } from 'react-router-dom';
+import { MemoryRouter, Route, Routes } from 'react-router-dom';
 import { beforeEach, describe, expect, it, vi } from 'vitest';
 import { GameHubPage } from './GameHubPage';
 import { useAuthStore } from '@/features/auth/authStore';
+import { spectateNoticeSeen } from '@/features/lobby/SpectateNotice';
 import type { GameSummary, Room } from '@/types/api';
 
 /**
```

```diff
  * 첫 방문 자동 노출을 하지 않는다(자동 노출은 그 게임 대기실 첫 입장, RoomPage).
  */
 
-const { catalog, list, stats } = vi.hoisted(() => ({
+const { catalog, list, stats, spectate } = vi.hoisted(() => ({
   catalog: vi.fn(),
   list: vi.fn(),
   stats: vi.fn(),
+  spectate: vi.fn(),
 }));
 
 vi.mock('@/api/games', () => ({ gamesApi: { catalog } }));
-vi.mock('@/api/rooms', () => ({ roomsApi: { list, joinOrReconnect: vi.fn(), spectate: vi.fn() } }));
+vi.mock('@/api/rooms', () => ({ roomsApi: { list, joinOrReconnect: vi.fn(), spectate } }));
 vi.mock('@/api/users', () => ({ usersApi: { stats } }));
 vi.mock('@/ws/useLobbyStomp', () => ({
   useLobbyStomp: () => ({ messages: [], connected: false, send: vi.fn() }),
```

```diff
     expect(screen.queryByText(/WAITING/)).toBeNull(); // enum 원문이 아니라 한글 상태 라벨
   });
 });
+
+/**
+ * D-133 결정 A — 서버가 관전으로 받으면 방 화면 위에 "…관전으로 들어왔어요"를 띄운다. 허브 "구경하기"(방 ID 로 관전)는 본인이 고른
+ * 관전이라 탭에 관전 기록을 남기고 들어가 방 화면이 안내를 띄우지 않게 한다.
+ */
+describe('GameHubPage — 허브에서 고른 관전 (D-133)', () => {
+  beforeEach(() => {
+    vi.clearAllMocks();
+    localStorage.clear();
+    sessionStorage.clear();
+    catalog.mockResolvedValue({ games: CATALOG });
+    list.mockResolvedValue({ rooms: [] });
+    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
+    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
+  });
+
+  it('방 ID 로 구경하기를 고르면 탭에 관전 기록을 남기고 그 방으로 간다', async () => {
+    spectate.mockResolvedValue(room('r9', 'TICHU', 4));
+    render(
+      <MemoryRouter initialEntries={['/games']}>
+        <Routes>
+          <Route path="/games" element={<GameHubPage />} />
+          <Route path="/rooms/:roomId" element={<div>ROOM_PAGE</div>} />
+        </Routes>
+      </MemoryRouter>,
+    );
+
+    fireEvent.change(await screen.findByPlaceholderText('방 ID 로 관전 진입'), { target: { value: 'r9' } });
+    fireEvent.click(screen.getByRole('button', { name: '구경하기' }));
+
+    expect(await screen.findByText('ROOM_PAGE')).toBeInTheDocument();
+    expect(spectate).toHaveBeenCalledWith('tok', 'r9');
+    expect(spectateNoticeSeen('r9')).toBe(true);
+  });
+});
```

`server/src/test/java/com/mirboard/domain/lobby/room/RoomServiceJoinOrReconnectTest.java`:

```java
package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-133 — 초대 링크가 기대는 {@code join-or-reconnect} 의 입장 갈래(저장소는 모의 — Docker 불필요).
 * <ul>
 *   <li>봇까지 꽉 찬 대기실은 지금처럼 관전이다 — 봇은 사람에게 자리를 비켜 주지 않는다(그 안은 기각). 대기실 초대 버튼의
 *       "링크를 받은 친구는 관전으로 들어와요"가 이 갈래를 전제로 한다.</li>
 *   <li>방을 읽은 뒤 앉기 전에 마지막 자리가 차거나 게임이 시작된 경합({@code ROOM_FULL}·{@code GAME_ALREADY_STARTED})은
 *       관전으로 삼키지 않고 그대로 던진다 — 클라 방 화면이 그 코드로 "자리가 없어요 — 관전할까요?" 패널을 띄운다(결정 A).</li>
 *   <li>같은 사람의 동시 이중 요청(두 탭·개발 StrictMode 의 이중 effect)은 둘 다 "플레이어 아님"을 읽고 입장으로 온다. 늦은 쪽의
 *       {@code ALREADY_IN_ROOM} 은 오류가 아니라 재접속이다(D-74 멱등).</li>
 * </ul>
 */
class RoomServiceJoinOrReconnectTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;
    private static final long FRIEND = 10L;
    private static final List<Long> BOTS = List.of(900L, 901L, 902L);

    private final RoomRepository repository = mock(RoomRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RoomService service = new RoomService(repository, mock(GameRegistry.class), Clock.systemUTC(),
            events, mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    private static Room room(RoomStatus status, Long... players) {
        List<Long> ids = List.of(players);
        List<Integer> botSeats = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (BOTS.contains(ids.get(i))) {
                botSeats.add(i);
            }
        }
        return new Room(ROOM_ID, "방", "ONE_CARD", HOST, status, 4, ids.size(), ids, Set.of(), TeamPolicy.SEQUENTIAL,
                0L, !botSeats.isEmpty(), List.copyOf(botSeats), 1000, 0, 0, Set.of());
    }

    private void stored(Room... states) {
        var stub = when(repository.findById(ROOM_ID));
        for (Room state : states) {
            stub = stub.thenReturn(Optional.of(state));
        }
    }

    @Test
    @DisplayName("봇까지 꽉 찬 대기실(게스트 봇 채우기 방)은 관전 — 봇은 자리를 비켜 주지 않는다")
    void full_waiting_room_with_bots_spectates() {
        Room full = room(RoomStatus.WAITING, HOST, 900L, 901L, 902L);
        stored(full, full);

        JoinOrReconnectResult result = service.joinOrReconnect(ROOM_ID, FRIEND);

        assertThat(result.mode()).isEqualTo(JoinOrReconnectResult.Mode.SPECTATING);
        verify(repository, never()).join(anyString(), anyLong(), anyLong());
        verify(repository).addSpectator(ROOM_ID, FRIEND);
    }

    @Test
    @DisplayName("읽은 뒤 마지막 자리가 찬 경합(ROOM_FULL)은 관전으로 삼키지 않고 그대로 던진다 — 클라가 '관전할까요?' 패널")
    void race_room_full_is_not_swallowed() {
        stored(room(RoomStatus.WAITING, HOST, 2L, 3L));
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new RoomFullException(ROOM_ID));

        assertThatThrownBy(() -> service.joinOrReconnect(ROOM_ID, FRIEND)).isInstanceOf(RoomFullException.class);
        verify(repository, never()).addSpectator(anyString(), anyLong());
    }

    @Test
    @DisplayName("읽은 뒤 게임이 시작된 경합(GAME_ALREADY_STARTED)도 그대로 던진다")
    void race_game_started_is_not_swallowed() {
        stored(room(RoomStatus.WAITING, HOST, 2L, 3L));
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new GameAlreadyStartedException(ROOM_ID));

        assertThatThrownBy(() -> service.joinOrReconnect(ROOM_ID, FRIEND))
                .isInstanceOf(GameAlreadyStartedException.class);
        verify(repository, never()).addSpectator(anyString(), anyLong());
    }

    @Test
    @DisplayName("같은 사람의 동시 이중 요청 — 먼저 간 요청이 앉혔으면 늦은 쪽은 ALREADY_IN_ROOM 대신 재접속(RECONNECTED)")
    void concurrent_duplicate_request_reconnects() {
        Room before = room(RoomStatus.WAITING, HOST, 2L);
        Room after = room(RoomStatus.WAITING, HOST, 2L, FRIEND);
        stored(before, after);
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new AlreadyInRoomException(ROOM_ID));

        JoinOrReconnectResult result = service.joinOrReconnect(ROOM_ID, FRIEND);

        assertThat(result.mode()).isEqualTo(JoinOrReconnectResult.Mode.RECONNECTED);
        assertThat(result.room().playerIds()).containsExactly(HOST, 2L, FRIEND);
        verify(repository, never()).addSpectator(anyString(), anyLong());
    }

    @Test
    @DisplayName("ALREADY_IN_ROOM 인데 다시 읽어도 앉아 있지 않으면(그사이 나감) 재접속으로 꾸미지 않고 그대로 던진다")
    void already_in_room_but_not_seated_rethrows() {
        Room before = room(RoomStatus.WAITING, HOST, 2L);
        stored(before, before);
        when(repository.join(eq(ROOM_ID), eq(FRIEND), anyLong())).thenThrow(new AlreadyInRoomException(ROOM_ID));

        assertThatThrownBy(() -> service.joinOrReconnect(ROOM_ID, FRIEND)).isInstanceOf(AlreadyInRoomException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- features/lobby/InviteLinkButton features/lobby/SpectateNotice pages/RoomPage pages/GameHubPage`
Expected: FAIL — `Test Files  4 failed (4)` · `Tests  no tests`(`Error: Failed to resolve import "./InviteLinkButton" from "src/features/lobby/InviteLinkButton.test.tsx"` ·
`… "./SpectateNotice" …` · `… "@/features/lobby/SpectateNotice" from "src/pages/RoomPage.test.tsx"` · `… from "src/pages/GameHubPage.test.tsx"` — 새 모듈이라 import 실패가 정상 RED).

Run: `./gradlew :server:test --rerun --tests "com.mirboard.domain.lobby.room.*Test"`
Expected: FAIL — `17 tests completed, 1 failed`(`RoomServiceJoinOrReconnectTest > 같은 사람의 동시 이중 요청 — 먼저 간 요청이 앉혔으면 늦은 쪽은 ALREADY_IN_ROOM 대신 재접속(RECONNECTED)
FAILED` · `com.mirboard.domain.lobby.room.AlreadyInRoomException`). 처음부터 통과하는 4건(봇까지 꽉 찬 대기실 → 관전, 경합 `ROOM_FULL`·`GAME_ALREADY_STARTED` 그대로, 다시 읽어도
앉아 있지 않으면 그대로 던짐)은 기준 동작을 지키는 고정 테스트다.

- [ ] **Step 3: 구현**

`client/src/features/lobby/InviteLinkButton.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { shareRoomLink, type ShareEnv, type ShareResult } from './shareRoomLink';

interface InviteLinkButtonProps {
  roomId: string;
  roomName: string;
  /** 게임 표시 이름(카탈로그) — 공유창 제목 "{게임} 같이 해요". */
  gameName: string;
  /** 정원이 다 찼다(봇 자리 포함) — 링크를 받은 친구는 관전으로 들어온다. 버튼은 그대로 둔다. */
  full?: boolean;
  /** 공유창·클립보드. 기본은 브라우저 `navigator` — 테스트가 주입한다. */
  env?: ShareEnv;
  /** 링크의 사이트 주소. 기본은 지금 주소의 origin. */
  origin?: string;
}

/** 공유됨·복사됨 알림이 떠 있는 시간. */
const NOTICE_MS = 3000;

/**
 * 브라우저 기본 공유 환경. 공유창은 휴대폰·태블릿(굵은 포인터)에서만 쓴다 — Web Share API 는 데스크톱 Chrome·Edge(Windows)·
 * Safari(macOS)에도 있어 그대로 두면 PC 에서 "링크 복사" 대신 OS 공유창(메일·근처 공유 — 카톡 PC 는 대상이 아님)이 뜬다.
 */
function browserShareEnv(): ShareEnv {
  const coarse = typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;
  return {
    // 메서드로 부른다 — 떼어 부르면 브라우저가 Illegal invocation 을 던진다.
    share: coarse && typeof navigator.share === 'function' ? (data) => navigator.share(data) : undefined,
    clipboard: navigator.clipboard,
  };
}

/**
 * D-133 — 대기실의 "초대 링크". 방 주소 그대로(`/rooms/<방ID>`)를 건넨다 — 서버가 그 주소의 HTML 에 방 미리보기를 넣고,
 * 로그인 안 된 친구는 로그인 화면을 거쳐 이 방으로 돌아온다. 공유창 → 복사 → 수동 순서는 `shareRoomLink` 가 정하고 여기는
 * 결과만 그린다. 수동(앱 내 브라우저)이면 링크를 선택해 둔 입력란을 보여 길게 눌러 복사하게 한다.
 */
export function InviteLinkButton({ roomId, roomName, gameName, full = false, env, origin }: InviteLinkButtonProps) {
  const [result, setResult] = useState<ShareResult | null>(null);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const url = `${origin ?? window.location.origin}/rooms/${encodeURIComponent(roomId)}`;

  async function handleClick() {
    setBusy(true);
    setResult(null);
    try {
      const outcome = await shareRoomLink(
        url,
        { title: `${gameName} 같이 해요`, text: `${roomName} — 미르보드카페` },
        env ?? browserShareEnv(),
      );
      setResult(outcome);
    } finally {
      setBusy(false);
    }
  }

  // 수동 복사 — 입력란이 뜨면 링크 전체를 선택해 둔다.
  useEffect(() => {
    if (result !== 'manual') return;
    inputRef.current?.focus();
    inputRef.current?.select();
  }, [result]);

  // 공유됨·복사됨은 짧은 알림 — 잠시 뒤 내린다.
  useEffect(() => {
    if (result !== 'shared' && result !== 'copied') return;
    const timer = setTimeout(() => setResult(null), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [result]);

  return (
    <section aria-label="친구 초대" className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          {full ? '자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요' : '링크를 보내면 친구가 바로 이 방에 들어와요.'}
        </p>
        <Button type="button" onClick={handleClick} disabled={busy}>
          초대 링크
        </Button>
      </div>
      {/* 라이브 영역은 늘 두고 내용만 바꾼다 — 영역과 내용을 함께 삽입하면 일부 스크린 리더가 읽지 않는다. */}
      <p role="status" className="min-h-5 text-sm">
        {result === 'shared' ? '초대 링크를 보냈어요' : result === 'copied' ? '링크를 복사했어요' : ''}
      </p>
      {result === 'manual' && (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="invite-link-manual">직접 복사해 주세요</Label>
          <Input
            id="invite-link-manual"
            ref={inputRef}
            readOnly
            value={url}
            onFocus={(e) => e.currentTarget.select()}
          />
        </div>
      )}
    </section>
  );
}
```

`client/src/features/lobby/JoinBlockedPanel.tsx`:

```tsx
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/** 방 입장이 막힌 이유 — `full` 은 자리 없음(꽉 참·이미 시작), `gone` 은 방 없음. */
export type JoinBlock = 'full' | 'gone';

interface JoinBlockedPanelProps {
  kind: JoinBlock;
  /** `full` 에서만 — 관전으로 들어가기. */
  onSpectate?: () => void;
  spectating?: boolean;
}

/**
 * D-133 — 초대 링크로 들어왔는데 입장이 막혔을 때. 서버 오류 문구를 그대로 보이지 않고 다음 행동을 제안한다:
 * 자리가 없으면 관전, 방이 없으면 메인으로.
 */
export function JoinBlockedPanel({ kind, onSpectate, spectating = false }: JoinBlockedPanelProps) {
  const full = kind === 'full';
  return (
    <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">{full ? '자리가 없어요 — 관전할까요?' : '방이 없어졌어요'}</CardTitle>
          <CardDescription>
            {full
              ? '방이 꽉 찼거나 게임이 이미 시작됐어요. 관전하면 손패 없이 게임을 지켜볼 수 있어요.'
              : '초대받은 방이 끝났거나 모두 나갔어요. 메인에서 다른 방을 찾아보세요.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {full && (
            <Button type="button" onClick={onSpectate} disabled={spectating}>
              관전하기
            </Button>
          )}
          <Button asChild variant="outline">
            <Link to="/games">메인으로</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
```

`client/src/features/lobby/SpectateNotice.tsx`:

```tsx
import { useEffect, useRef } from 'react';
import type { JoinOrReconnectResponse } from '@/api/rooms';
import type { Room } from '@/types/api';
import { Button } from '@/components/ui/button';

/** 관전으로 받아진 이유 — `started` 는 게임이 이미 시작됨, `full` 은 시작 전인데 정원이 다 참(봇 자리 포함). */
export type SpectateNoticeKind = 'started' | 'full';

/**
 * D-133 결정 A — 서버 `join-or-reconnect` 가 관전으로 받았을 때(`mode=SPECTATING`) 방 상태로 이유를 고른다. 이미 관전자여도
 * `SPECTATING` 이 오므로 상태 기준 문구로 쓴다(새로 받아졌든 원래 관전자든 사실). 빈자리가 있는 대기실 관전은 본인이 고른 것이라,
 * 끝난 방은 종료 카드가 설명하므로 안내하지 않는다.
 */
export function spectateNoticeOf(
  mode: JoinOrReconnectResponse['mode'],
  room: Pick<Room, 'status' | 'playerCount' | 'capacity'>,
): SpectateNoticeKind | null {
  if (mode !== 'SPECTATING') return null;
  if (room.status === 'IN_GAME') return 'started';
  if (room.status === 'WAITING' && room.playerCount >= room.capacity) return 'full';
  return null;
}

const SEEN_PREFIX = 'mirboard.spectateNotice.';

/**
 * 이 탭에서 이 방의 관전을 이미 알고 있다 — 안내를 띄웠거나, 허브·"관전할까요?" 패널에서 본인이 관전을 골랐다. 새로고침·허브 관전에
 * 다시 띄우지 않으려는 탭 단위(sessionStorage) 기록이다. 저장소가 막히면(사생활 보호 창 등) 조용히 넘어간다 — 안내가 한 번 더 뜰 뿐이다.
 */
export function markSpectateNoticeSeen(roomId: string): void {
  try {
    sessionStorage.setItem(SEEN_PREFIX + roomId, '1');
  } catch {
    // 기록 못 하면 다음에 한 번 더 뜬다.
  }
}

export function spectateNoticeSeen(roomId: string): boolean {
  try {
    return sessionStorage.getItem(SEEN_PREFIX + roomId) === '1';
  } catch {
    return false;
  }
}

/** 안내가 떠 있는 시간. */
const NOTICE_MS = 8000;

const MESSAGES: Record<SpectateNoticeKind, string> = {
  started: '게임이 이미 시작돼 관전으로 들어왔어요',
  full: '자리가 없어 관전으로 들어왔어요',
};

interface SpectateNoticeProps {
  kind: SpectateNoticeKind;
  onClose: () => void;
}

/**
 * D-133 결정 A — "…관전으로 들어왔어요" 안내. 방 화면 맨 위에 떠 있다(게임판 채팅 100 위, 원카드 경쟁 레이어 150·패스 받은 카드 200
 * 아래). 게임판은 `.app-shell` 밖이라 카드 자체에 `app-shell` 을 붙여 색·테두리 토큰을 쓴다(바깥 줄에 붙이면 전체 폭이 칠해진다).
 * 바깥 줄은 클릭을 통과시킨다. 8초 뒤 저절로 내린다.
 */
export function SpectateNotice({ kind, onClose }: SpectateNoticeProps) {
  // 부모가 렌더마다 새 함수를 넘겨도 타이머를 다시 걸지 않는다(방 메타·이름 갱신으로 자주 다시 그린다).
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const timer = setTimeout(() => closeRef.current(), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [kind]);

  return (
    <div className="pointer-events-none fixed inset-x-0 top-3 z-[120] flex justify-center px-4">
      <div
        role="status"
        className="app-shell pointer-events-auto flex max-w-md items-center gap-3 rounded-lg border bg-background px-4 py-2 text-sm shadow-lg"
      >
        <span>{MESSAGES[kind]}</span>
        <Button type="button" size="sm" variant="ghost" onClick={onClose}>
          닫기
        </Button>
      </div>
    </div>
  );
}
```

`client/src/pages/RoomPage.tsx`:

```diff
 import { SkullKingTable } from '@/features/skullking/SkullKingTable';
 import { OneCardTable } from '@/features/onecard/OneCardTable';
 import { ROOM_STATUS_LABEL } from '@/features/lobby/roomStatusLabel';
+import { InviteLinkButton } from '@/features/lobby/InviteLinkButton';
+import { JoinBlockedPanel, type JoinBlock } from '@/features/lobby/JoinBlockedPanel';
+import {
+  SpectateNotice,
+  markSpectateNoticeSeen,
+  spectateNoticeOf,
+  spectateNoticeSeen,
+  type SpectateNoticeKind,
+} from '@/features/lobby/SpectateNotice';
 import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
 import { tutorialFor } from '@/features/tutorial/gameTutorials';
 import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
```

```diff
  */
 const BOARD_HELD_GAMES: ReadonlySet<string> = new Set(['SKULL_KING', 'ONE_CARD']);
 
+/**
+ * D-133 — 첫 입장(`join-or-reconnect`)이 막힌 이유. 자리 없음은 관전을, 방 없음은 메인으로를 제안한다. 그 밖의 오류는
+ * 지금처럼 서버 문구를 보인다. 서버는 꽉 찬 대기실·진행 중 방이면 이미 관전으로 받으므로 `full` 은 그 사이 자리가 찬 경합이다.
+ */
+function joinBlockOf(code: string): JoinBlock | null {
+  if (code === 'ROOM_FULL' || code === 'GAME_ALREADY_STARTED') return 'full';
+  if (code === 'ROOM_NOT_FOUND') return 'gone';
+  return null;
+}
+
 /** D-122 — 유지된 게임판이 쓰는 좌석 목록 스냅샷 (FINISHED 전이 직전 IN_GAME 메타). */
 interface HeldSeats {
   playerIds: number[];
```

```diff
   const navigate = useNavigate();
   const [room, setRoom] = useState<Room | null>(null);
   const [error, setError] = useState<string | null>(null);
+  // D-133 — 초대 링크로 왔는데 입장이 막혔다(자리 없음·방 없음). 오류 문구 대신 패널.
+  const [joinBlock, setJoinBlock] = useState<JoinBlock | null>(null);
+  const [spectating, setSpectating] = useState(false);
+  // D-133 결정 A — 서버가 묻지 않고 관전으로 받았을 때(꽉 찬 대기실·진행 중 방) 방 화면 위 안내. 탭에서 처음 한 번만.
+  const [spectateNotice, setSpectateNotice] = useState<SpectateNoticeKind | null>(null);
   const [autoJoinAttempted, setAutoJoinAttempted] = useState(false);
   const [usernames, setUsernames] = useState<Record<number, string>>({});
   // D-106 — 이 게임이 쓰는 방 옵션. 대기실에서는 좌석 정책 **라벨**을 고르는 데만 쓴다.
```

```diff
     (async () => {
       try {
         const r = await roomsApi.joinOrReconnect(token, roomId);
-        if (!cancelled) setRoom(r.room);
+        if (!cancelled) {
+          setRoom(r.room);
+          // D-133 결정 A — 관전으로 받아졌으면 이유를 알린다. 이 탭에서 이 방 관전을 이미 알면(안내를 봤거나 허브·패널에서 관전을
+          // 골랐다) 띄우지 않는다 — 새로고침에도 서버는 SPECTATING 을 다시 준다.
+          if (r.mode === 'SPECTATING') {
+            if (!spectateNoticeSeen(roomId)) setSpectateNotice(spectateNoticeOf(r.mode, r.room));
+            markSpectateNoticeSeen(roomId);
+          }
+        }
       } catch (err) {
         if (cancelled) return;
-        if (err instanceof ApiError) setError(err.message);
+        if (err instanceof ApiError) {
+          const block = joinBlockOf(err.code);
+          if (block) setJoinBlock(block);
+          else setError(err.message);
+        }
       } finally {
         if (!cancelled) setAutoJoinAttempted(true);
       }
```

```diff
       navigate('/login');
     }
   }, [token, navigate]);
+
+  // D-133 — 방 주소로 처음 연 탭은 서버가 넣은 방 제목으로 시작한다. SPA 는 제목을 관리하지 않으므로 방 화면을 떠날 때
+  // 기본 제목으로 되돌린다(허브로 나가거나 다른 방에 가도 처음 방 이름이 탭·방문 기록에 남지 않게).
+  useEffect(
+    () => () => {
+      document.title = 'Mirboard';
+    },
+    [],
+  );
 
   // D-120 — 이 세션에서 IN_GAME→FINISHED 전이를 봤는가. 봇 방은 매치가 끝나면 서버가
   // 방을 FINISHED 로 바꾸는데, 그 메타가 마지막 게임 이벤트보다 먼저 올 수 있다. 그때
```

```diff
     }
   }
 
+  // D-133 — 자리 없음 패널의 [관전하기]. 허브 관전과 같은 `spectate` 를 부르고 그 응답의 방으로 다시 그린다.
+  async function handleSpectate() {
+    if (!token) return;
+    setSpectating(true);
+    try {
+      const spectated = await roomsApi.spectate(token, roomId);
+      // 본인이 고른 관전 — 이 탭에서는 새로고침해도 "관전으로 들어왔어요"를 띄우지 않는다(결정 A).
+      markSpectateNoticeSeen(roomId);
+      setJoinBlock(null);
+      setRoom(spectated);
+    } catch (err) {
+      if (err instanceof ApiError) {
+        if (err.code === 'ROOM_NOT_FOUND') {
+          setJoinBlock('gone');
+        } else {
+          setJoinBlock(null);
+          setError(err.message);
+        }
+      }
+    } finally {
+      setSpectating(false);
+    }
+  }
+
   async function handleToggleReady(next: boolean) {
     if (!token) return;
     try {
```

```diff
     if (!waiting) hideTutorial();
   }, [waiting, hideTutorial]);
 
+  if (joinBlock) {
+    return (
+      <JoinBlockedPanel kind={joinBlock} onSpectate={handleSpectate} spectating={spectating} />
+    );
+  }
   if (error) {
     return (
       <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
```

```diff
       </div>
     );
   }
+
+  // D-133 결정 A — 아래 세 분기(스컬킹·원카드 게임판, 티츄 게임판, 대기실·종료 셸) 모두의 맨 위. 진행 중 방은 셸 없이 게임판을 바로 그린다.
+  const notice = spectateNotice && (
+    <SpectateNotice kind={spectateNotice} onClose={() => setSpectateNotice(null)} />
+  );
 
   // IN_GAME — 게임판은 레거시 레이아웃이라 .app-shell 밖이다.
   // D-103: 게임 분기는 **이 한 곳**뿐이다. 각 게임판이 자기 소켓·sink 를 소유하므로
```

```diff
     };
     return (
       <main className="room-page">
+        {notice}
         {room.gameType === 'ONE_CARD' ? <OneCardTable {...board} /> : <SkullKingTable {...board} />}
       </main>
     );
```

```diff
   if (room.status === 'IN_GAME') {
     return (
       <main className="room-page">
+        {notice}
         <GameTable
           roomId={room.roomId}
           playerIds={room.playerIds}
```

```diff
 
   return (
     <div className="app-shell min-h-screen bg-background text-foreground">
+      {notice}
       <div className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-6">
         <header className="flex flex-wrap items-center justify-between gap-4">
           <div>
```

```diff
           </div>
         </header>
 
+        {/* D-133 — 초대는 시작 전에만(시작하면 자리가 없다). 방 주소 그대로를 공유·복사한다. 정원이 다 찼으면(봇 자리
+            포함 — 봇은 자리를 비켜 주지 않는다) 받은 친구는 관전으로 들어오므로 설명을 그렇게 바꾼다. */}
+        {room.status === 'WAITING' && (
+          <InviteLinkButton
+            roomId={room.roomId}
+            roomName={room.name}
+            gameName={gameName ?? room.gameType}
+            full={room.playerCount >= room.capacity}
+          />
+        )}
+
         {iAmSpectator && (
           <Alert>
             <AlertDescription>
```

`client/src/pages/GameHubPage.tsx`:

```diff
 import { CreateRoomModal } from '@/features/lobby/CreateRoomModal';
 import { gameWikiUrl } from '@/features/lobby/gameWiki';
 import { ROOM_STATUS_LABEL } from '@/features/lobby/roomStatusLabel';
+import { markSpectateNoticeSeen } from '@/features/lobby/SpectateNotice';
 import { t } from '@/i18n/messages';
 import type { GameSummary, Room } from '@/types/api';
 import { Button } from '@/components/ui/button';
```

```diff
     const roomId = spectateInput.trim();
     try {
       await roomsApi.spectate(token, roomId);
+      // D-133 결정 A — 본인이 고른 관전이라 방 화면의 "…관전으로 들어왔어요" 안내를 띄우지 않는다(탭 기록).
+      markSpectateNoticeSeen(roomId);
       navigate(`/rooms/${roomId}`);
     } catch (err) {
       if (err instanceof ApiError) setError(err.message);
```

`server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java`:

```diff
             return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.SPECTATING, room);
         }
         if (room.status() == RoomStatus.WAITING && room.playerCount() < room.capacity()) {
-            Room joined = joinRoom(roomId, userId);
-            return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.JOINED, joined);
+            try {
+                Room joined = joinRoom(roomId, userId);
+                return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.JOINED, joined);
+            } catch (AlreadyInRoomException race) {
+                // D-133 — 같은 사람의 동시 이중 요청(두 탭·개발 StrictMode 의 이중 effect)이 둘 다 "플레이어 아님"을 읽고
+                // 입장으로 왔다. 먼저 간 요청이 앉혔으면 이 요청은 재접속이다(D-74 멱등). 경합 ROOM_FULL·
+                // GAME_ALREADY_STARTED 는 잡지 않는다 — 클라 방 화면이 그 코드로 "관전할까요?" 패널을 띄운다(결정 A).
+                Room seated = getRoom(roomId);
+                if (!seated.playerIds().contains(userId)) {
+                    throw race;
+                }
+                log.info("Room reconnect (concurrent join): roomId={} userId={}", roomId, userId);
+                return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.RECONNECTED, seated);
+            }
         }
         Room spectated = spectate(roomId, userId);
         return new JoinOrReconnectResult(JoinOrReconnectResult.Mode.SPECTATING, spectated);
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- features/lobby/InviteLinkButton features/lobby/SpectateNotice pages/RoomPage pages/GameHubPage`
Expected: PASS — `Test Files  4 passed (4)` · `Tests  76 passed (76)`(기존 RoomPage·GameHubPage 단언 무변경으로 통과).

Run: `./gradlew :server:test --rerun --tests "com.mirboard.domain.lobby.room.*Test"` 뒤 `awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ (failures|errors)=$/)f+=$(i+1)}} END{print "tests="t" failed="f}' server/build/test-results/test/TEST-*.xml`
Expected: `BUILD SUCCESSFUL`, `tests=17 failed=0`(JoinOrReconnect 5 · 옵션 게이팅 7 · 생성 기본값 4 · 리매치 1).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --rerun --tests "com.mirboard.infra.rest.rooms.RoomJoinOrReconnectIntegrationTest" --tests "com.mirboard.domain.lobby.room.RoomServiceConcurrencyIT"` 뒤 `awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ (failures|errors)=$/)f+=$(i+1)}} END{print "tests="t" failed="f}' server/build/test-results/test/TEST-*.xml`
Expected: `BUILD SUCCESSFUL`, `tests=12 failed=0`(입장 갈래 IT 9 · 동시성 IT 3 — 이 경로에 `ALREADY_IN_ROOM` 을 기대하는 단언은 없다).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  71 passed (71)` · `Tests  861 passed (861)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/lobby/InviteLinkButton.test.tsx \
  client/src/features/lobby/SpectateNotice.test.tsx \
  client/src/pages/RoomPage.test.tsx \
  client/src/pages/GameHubPage.test.tsx \
  server/src/test/java/com/mirboard/domain/lobby/room/RoomServiceJoinOrReconnectTest.java \
  client/src/features/lobby/InviteLinkButton.tsx \
  client/src/features/lobby/JoinBlockedPanel.tsx \
  client/src/features/lobby/SpectateNotice.tsx \
  client/src/pages/RoomPage.tsx \
  client/src/pages/GameHubPage.tsx \
  server/src/main/java/com/mirboard/domain/lobby/room/RoomService.java
git commit -m "feat(D-133): 방 화면 — 대기실 초대 링크(휴대폰 공유창·복사·수동), 자리 없으면 바로 관전 + 안내, 경합은 관전 제안, 방 없음, 같은 사람 동시 입장은 재접속

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 클라 + 서버 — 방마다 다른 링크 미리보기

**Files:**
- Create: `client/src/indexHtml.test.ts`, `server/src/test/java/com/mirboard/infra/web/RoomPreviewRendererTest.java`, `server/src/test/java/com/mirboard/infra/web/IndexHtmlTemplateTest.java`, `server/src/test/java/com/mirboard/infra/web/RoomPagePreviewControllerTest.java`, `server/src/test/java/com/mirboard/infra/web/RoomPagePreviewIntegrationTest.java`, `server/src/main/java/com/mirboard/infra/web/RoomPreviewRenderer.java`, `server/src/main/java/com/mirboard/infra/web/IndexHtmlTemplate.java`, `server/src/main/java/com/mirboard/infra/web/RoomPagePreviewController.java`
- Modify: `client/index.html`

**Interfaces:**
- Consumes: `RoomService.getRoom(roomId)`(없으면 `RoomNotFoundException`), `UserRepository`(방장 이름), `GameRegistry`(게임 표시 이름), 빌드 산출물 `classpath:static/index.html`.
- Produces: `client/index.html` 의 `<!--og:start-->`…`<!--og:end-->` 구간(사이트 공통 미리보기 + `<title>Mirboard</title>` — 구간 밖에는 `og:` 없음, `og:url` 없음).
  `RoomPreviewRenderer.render(String template, RoomPreview room): String`(순수 — `RoomPreview(gameName, roomName, hostName, capacity, url)`, `HtmlUtils.htmlEscape`, 방 이름 40·방장 20
  코드 포인트, 표식 없거나 어긋나면 틀 그대로), `IndexHtmlTemplate.html(): Optional<String>`(시작할 때 한 번 읽음), `RoomPagePreviewController` `GET /rooms/{roomId}`
  (`text/html;charset=UTF-8`, `Cache-Control: no-cache`, `produces` 없음, 방 없음·저장소 장애·이상한 ID → 틀 그대로, 틀 없음 → 404).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/indexHtml.test.ts`:

```ts
/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다
// (styles/cssSources.ts 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * D-133 — 링크 미리보기(Open Graph)의 화면 틀. 카톡 같은 스크래퍼는 자바스크립트를 실행하지 않으므로 미리보기 메타는
 * `index.html` 원문에 있어야 한다. 서버(`RoomPagePreviewController`)는 `GET /rooms/{roomId}` 에서 빌드된 이 파일의
 * `<!--og:start-->`…`<!--og:end-->` 구간을 그 방의 미리보기로 바꾼다 — 그래서 표식과 구간 안의 내용을 원문으로 고정한다.
 */

/** 이 파일 기준 상대 경로의 원문. 경로를 인자로 받는 이유: `new URL('리터럴', import.meta.url)` 은 Vite 가 자산 주소로 바꿔 버린다. */
function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

const html = readSource('../index.html');

const START = '<!--og:start-->';
const END = '<!--og:end-->';

function count(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1;
}

function block(): string {
  return html.slice(html.indexOf(START) + START.length, html.indexOf(END));
}

function outside(): string {
  return html.slice(0, html.indexOf(START)) + html.slice(html.indexOf(END) + END.length);
}

describe('index.html — 사이트 공통 미리보기와 표식 (D-133)', () => {
  it('표식이 <head> 안에 한 쌍, 시작이 끝보다 앞에 있다', () => {
    expect(count(html, START)).toBe(1);
    expect(count(html, END)).toBe(1);
    expect(html.indexOf(START)).toBeLessThan(html.indexOf(END));
    expect(html.indexOf(END)).toBeLessThan(html.indexOf('</head>'));
  });

  it('구간 안에 사이트 공통 Open Graph 가 있다 — 제목은 "미르보드카페 — 티츄·스컬킹·원카드"', () => {
    const og = block();
    expect(og).toContain('<meta property="og:title" content="미르보드카페 — 티츄·스컬킹·원카드" />');
    expect(og).toContain('<meta property="og:site_name" content="미르보드카페" />');
    expect(og).toContain('<meta property="og:type" content="website" />');
    expect(og).toContain('<meta property="og:locale" content="ko_KR" />');
    expect(og).toContain('<meta name="twitter:card" content="summary" />');
    expect(og).toMatch(/<meta property="og:description" content="[^"]+" \/>/);
    expect(og).toMatch(/<meta name="description" content="[^"]+" \/>/);
  });

  it('<title> 은 구간 안에 하나뿐이다 — 서버가 방 제목으로 바꿔도 제목이 둘이 되지 않게', () => {
    expect(count(block(), '<title>')).toBe(1);
    expect(count(outside(), '<title>')).toBe(0);
  });

  it('구간 밖에는 미리보기 메타가 없고, 공통 미리보기에 사이트 주소(og:url)를 박지 않는다', () => {
    expect(outside()).not.toMatch(/og:|twitter:|name="description"/);
    expect(block()).not.toContain('og:url');
  });
});
```

`server/src/test/java/com/mirboard/infra/web/RoomPreviewRendererTest.java`:

```java
package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mirboard.infra.web.RoomPreviewRenderer.RoomPreview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-133 — 방 미리보기 렌더러(순수). 빌드된 {@code index.html} 의 {@code <!--og:start-->}…{@code <!--og:end-->} 구간을 그 방의
 * Open Graph 로 바꾼다. 카톡 스크래퍼는 자바스크립트를 실행하지 않으므로 이 HTML 이 곧 말풍선 미리보기다.
 *
 * <p>사용자 입력(방 이름·방장 이름)은 메타 속성 안에 들어가므로 HTML 이스케이프가 핵심이다 — 따옴표 하나로 속성이 끊기면
 * 미리보기가 깨지고, 꺾쇠는 마크업 주입이 된다. 현재 인원은 넣지 않는다(카톡이 미리보기를 캐시해 낡은 숫자가 보인다).
 */
class RoomPreviewRendererTest {

    static final String TEMPLATE = """
            <!doctype html>
            <html lang="ko">
              <head>
                <meta charset="UTF-8" />
                <!--og:start-->
                <title>Mirboard</title>
                <meta property="og:title" content="미르보드카페 — 공통" />
                <!--og:end-->
              </head>
              <body><div id="root"></div></body>
            </html>
            """;

    private static RoomPreview room(String roomName, String host) {
        return new RoomPreview("원카드", roomName, host, 4, "https://mirboard.test/rooms/r1");
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("구간을 그 방의 미리보기로 바꾼다 — 제목·설명·주소·사이트 이름·로케일·카드 종류·<title>")
    void replaces_block_with_room_preview() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("금요일 한 판", "alice"));

        assertThat(html)
                .contains("<title>원카드 · 금요일 한 판 — 미르보드카페</title>")
                .contains("<meta property=\"og:title\" content=\"원카드 · 금요일 한 판\" />")
                .contains("<meta property=\"og:description\" content=\"alice님이 초대했어요 · 4인 방 · 누르면 바로 입장\" />")
                .contains("<meta name=\"description\" content=\"alice님이 초대했어요 · 4인 방 · 누르면 바로 입장\" />")
                .contains("<meta property=\"og:url\" content=\"https://mirboard.test/rooms/r1\" />")
                .contains("<meta property=\"og:type\" content=\"website\" />")
                .contains("<meta property=\"og:site_name\" content=\"미르보드카페\" />")
                .contains("<meta property=\"og:locale\" content=\"ko_KR\" />")
                .contains("<meta name=\"twitter:card\" content=\"summary\" />")
                .doesNotContain("미르보드카페 — 공통")
                .doesNotContain("<title>Mirboard</title>");
        assertThat(count(html, "<title>")).isEqualTo(1);
    }

    @Test
    @DisplayName("구간 밖(문서 앞뒤)은 한 글자도 바꾸지 않고 표식은 남긴다")
    void keeps_everything_outside_the_block() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("방", "alice"));

        String before = TEMPLATE.substring(0, TEMPLATE.indexOf(RoomPreviewRenderer.START));
        String after = TEMPLATE.substring(TEMPLATE.indexOf(RoomPreviewRenderer.END));
        assertThat(html).startsWith(before + RoomPreviewRenderer.START).endsWith(after);
    }

    @Test
    @DisplayName("방 이름의 따옴표·꺾쇠·앰퍼샌드는 이스케이프돼 메타 속성이 깨지지 않는다")
    void escapes_room_name() {
        String html = RoomPreviewRenderer.render(TEMPLATE,
                room("\"><script>alert('x')</script> & 친구들", "alice"));

        assertThat(html)
                .contains("content=\"원카드 · &quot;&gt;&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; 친구들\"")
                .doesNotContain("<script>")
                .doesNotContain("\"><script");
    }

    @Test
    @DisplayName("방 이름에 표식을 넣어도 구간이 늘지 않는다 — 꺾쇠가 이스케이프된다")
    void marker_in_room_name_is_inert() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("<!--og:end--><!--og:start-->", "alice"));

        assertThat(count(html, RoomPreviewRenderer.START)).isEqualTo(1);
        assertThat(count(html, RoomPreviewRenderer.END)).isEqualTo(1);
    }

    @Test
    @DisplayName("방장 이름도 이스케이프한다")
    void escapes_host_name() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("방", "a\"b<c>"));

        assertThat(html).contains("a&quot;b&lt;c&gt;님이 초대했어요").doesNotContain("a\"b");
    }

    @Test
    @DisplayName("이모지는 그대로 둔다(UTF-8) — 숫자 문자 참조로 바꾸지 않는다")
    void keeps_emoji() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("🃏 한 판 🎉", "alice"));

        assertThat(html).contains("원카드 · 🃏 한 판 🎉");
    }

    @Test
    @DisplayName("방 이름이 40자를 넘으면 40자에서 자르고 말줄임표 — 이모지(서로게이트 쌍)를 반으로 가르지 않는다")
    void clips_long_room_name_by_code_points() {
        String longName = "🎲".repeat(39) + "가나다라";
        String html = RoomPreviewRenderer.render(TEMPLATE, room(longName, "alice"));

        String expected = "🎲".repeat(39) + "가…";
        assertThat(html).contains("<meta property=\"og:title\" content=\"원카드 · " + expected + "\" />");
        assertThat(html).doesNotContain("가나");
        // 반쪽 서로게이트가 섞이면 UTF-8 로 인코딩할 때 '?' 가 된다.
        assertThat(new String(html.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(html);
    }

    @Test
    @DisplayName("딱 40자인 방 이름은 자르지 않는다")
    void keeps_room_name_of_exactly_forty() {
        String name = "가".repeat(40);
        String html = RoomPreviewRenderer.render(TEMPLATE, room(name, "alice"));

        assertThat(html).contains("원카드 · " + name + "\" />").doesNotContain("…");
    }

    @Test
    @DisplayName("방 이름의 줄바꿈·연속 공백은 공백 하나로 접는다")
    void collapses_whitespace() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("  금요일\n\t한   판 ", "alice"));

        assertThat(html).contains("content=\"원카드 · 금요일 한 판\"");
    }

    @Test
    @DisplayName("방 이름이 비면 \"{방장}님의 방\"")
    void blank_room_name_falls_back_to_host() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("   ", "guest-abcd2345"));

        assertThat(html).contains("<meta property=\"og:title\" content=\"원카드 · guest-abcd2345님의 방\" />");
    }

    @Test
    @DisplayName("방장을 모르면(계정 정리 등) 초대한 사람 없이 정원만 — 방 이름이 비면 \"초대받은 방\"")
    void unknown_host() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("", null));

        assertThat(html)
                .contains("<meta property=\"og:title\" content=\"원카드 · 초대받은 방\" />")
                .contains("<meta property=\"og:description\" content=\"4인 방 · 누르면 바로 입장\" />")
                .doesNotContain("null");
    }

    @Test
    @DisplayName("현재 인원은 넣지 않는다 — 렌더러 입력에 인원이 없고 결과에 \"명\"·\"/\" 인원 표기가 없다")
    void excludes_current_player_count() {
        String html = RoomPreviewRenderer.render(TEMPLATE, room("방", "alice"));

        assertThat(RoomPreview.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("gameName", "roomName", "hostName", "capacity", "url");
        assertThat(html).doesNotContain("명").doesNotContain("1/4").doesNotContain("참가");
    }

    @Test
    @DisplayName("표식이 없으면 틀 그대로")
    void no_markers_returns_template() {
        String plain = "<html><head><title>Mirboard</title></head></html>";

        assertThat(RoomPreviewRenderer.render(plain, room("방", "alice"))).isSameAs(plain);
    }

    @Test
    @DisplayName("표식 순서가 어긋나거나 한쪽만 있으면 틀 그대로")
    void broken_markers_return_template() {
        String reversed = "<head><!--og:end--><title>x</title><!--og:start--></head>";
        String onlyStart = "<head><!--og:start--><title>x</title></head>";

        assertThat(RoomPreviewRenderer.render(reversed, room("방", "alice"))).isSameAs(reversed);
        assertThat(RoomPreviewRenderer.render(onlyStart, room("방", "alice"))).isSameAs(onlyStart);
    }

    @Test
    @DisplayName("실제 client/index.html 의 표식과 맞물린다 — 공통 제목이 방 제목으로 바뀌고 <title> 은 하나")
    void works_with_real_client_index_html() throws IOException {
        Path index = Path.of("../client/index.html");
        assumeTrue(Files.exists(index), "저장소 밖에서 돌면 건너뛴다(클라 원문 없음)");
        String template = Files.readString(index, StandardCharsets.UTF_8);

        String html = RoomPreviewRenderer.render(template, room("금요일 한 판", "alice"));

        assertThat(html).isNotEqualTo(template)
                .contains("<meta property=\"og:title\" content=\"원카드 · 금요일 한 판\" />")
                .doesNotContain("미르보드카페 — 티츄·스컬킹·원카드\"")
                .contains("<div id=\"root\"></div>");
        assertThat(count(html, "<title>")).isEqualTo(1);
    }
}
```

`server/src/test/java/com/mirboard/infra/web/IndexHtmlTemplateTest.java`:

```java
package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

/**
 * D-133 — 방 미리보기의 화면 틀. 빌드된 {@code classpath:static/index.html}(Dockerfile 이 클라 번들을 복사)을 시작할 때 한 번
 * 읽어 둔다. 개발·테스트처럼 틀이 없으면 비어 있고, 컨트롤러는 그때 기존 리소스 처리와 같게 404 를 준다.
 */
class IndexHtmlTemplateTest {

    @Test
    @DisplayName("틀이 있으면 UTF-8 원문을 그대로 들고 있다")
    void loads_template_as_utf8() {
        String html = "<html><head><!--og:start--><title>미르보드카페 🃏</title><!--og:end--></head></html>";

        IndexHtmlTemplate template = new IndexHtmlTemplate(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));

        assertThat(template.html()).contains(html);
    }

    @Test
    @DisplayName("틀이 없으면 비어 있다 — 기동은 막지 않는다")
    void missing_template_is_empty() {
        IndexHtmlTemplate template = new IndexHtmlTemplate(new ClassPathResource("static/no-such-index.html"));

        assertThat(template.html()).isEmpty();
    }

    @Test
    @DisplayName("읽다가 실패해도 비어 있다 — 예외로 기동을 막지 않는다")
    void unreadable_template_is_empty() {
        AbstractResource broken = new AbstractResource() {
            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public String getDescription() {
                return "broken";
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("disk gone");
            }
        };

        assertThat(new IndexHtmlTemplate(broken).html()).isEmpty();
    }

    @Test
    @DisplayName("기본 생성자는 classpath:static/index.html 을 본다 — 클라 번들을 동봉하지 않은 빌드에서는 비어 있다")
    void default_location_is_static_index() {
        assertThat(IndexHtmlTemplate.LOCATION).isEqualTo("static/index.html");
        assertThat(new IndexHtmlTemplate().html().isPresent())
                .isEqualTo(new ClassPathResource(IndexHtmlTemplate.LOCATION).exists());
    }
}
```

`server/src/test/java/com/mirboard/infra/web/RoomPagePreviewControllerTest.java`:

```java
package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * D-133 — {@code GET /rooms/{roomId}} 방 미리보기. 공유 링크가 방 주소 그대로라, 카톡 스크래퍼가 그 주소를 긁으면 이 컨트롤러가
 * 빌드된 {@code index.html} 의 표식 구간을 그 방의 Open Graph 로 바꿔 준다. 브라우저에게는 같은 SPA 다(클라 라우터가 방 화면을 그린다).
 *
 * <p>스프링 컨텍스트 없이 컨트롤러만 MockMvc 로 세운다(Docker 불필요). 보안 필터 체인·SPA 대체 리소스와의 공존은
 * {@code RoomPagePreviewIntegrationTest} 가 본다.
 */
class RoomPagePreviewControllerTest {

    static final String TEMPLATE = """
            <!doctype html>
            <html lang="ko">
              <head>
                <!--og:start-->
                <title>Mirboard</title>
                <meta property="og:title" content="미르보드카페 — 공통" />
                <!--og:end-->
              </head>
              <body><div id="root"></div></body>
            </html>
            """;

    private static final String ROOM_ID = "3f2c9a4e-1b7d-4c55-9e0a-0f6b8d2a7c11";
    private static final long HOST_ID = 2L;

    private final RoomService rooms = mock(RoomService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final GameDefinition game = mock(GameDefinition.class);
    private GameRegistry registry;

    @BeforeEach
    void setUp() {
        when(game.id()).thenReturn("ONE_CARD");
        when(game.displayName()).thenReturn("원카드");
        registry = new GameRegistry(List.of(game));
        when(users.findById(HOST_ID)).thenReturn(Optional.of(User.create("alice", "x", Clock.systemUTC())));
    }

    private static Room room(String name, String gameType, int playerCount) {
        List<Long> players = List.of(HOST_ID, 3L, 4L).subList(0, playerCount);
        return new Room(ROOM_ID, name, gameType, HOST_ID, RoomStatus.WAITING, 4, playerCount, players,
                Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    private MockMvc mvc(IndexHtmlTemplate template) {
        return MockMvcBuilders
                .standaloneSetup(new RoomPagePreviewController(template, rooms, registry, users))
                .build();
    }

    private static IndexHtmlTemplate templateOf(String html) {
        return new IndexHtmlTemplate(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));
    }

    private static IndexHtmlTemplate noTemplate() {
        return new IndexHtmlTemplate(new ClassPathResource("static/no-such-index.html"));
    }

    @Test
    @DisplayName("방이 있으면 그 방의 미리보기 HTML — text/html UTF-8, 공유 캐시 금지(no-cache)")
    void renders_room_preview() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("금요일 한 판", "ONE_CARD", 3));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("<title>원카드 · 금요일 한 판 — 미르보드카페</title>")
                .contains("<meta property=\"og:description\" content=\"alice님이 초대했어요 · 4인 방 · 누르면 바로 입장\" />")
                .contains("<meta property=\"og:url\" content=\"http://localhost/rooms/" + ROOM_ID + "\" />")
                .contains("<div id=\"root\"></div>")
                .doesNotContain("미르보드카페 — 공통");
    }

    /**
     * 카톡 같은 스크래퍼의 Accept 는 알 수 없다. produces 로 협상하면 HTML 이 아닌 Accept 는 406(빈 본문)을 받고 SPA 대체 리소스로도
     * 넘어가지 않았다(사전 리뷰 IP-1 — 전체 컨텍스트 탐침에서 json·plain·image·xhtml 모두 406). 응답 타입은 명시하고 협상하지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/plain", "application/xhtml+xml", "image/*"})
    @DisplayName("Accept 가 HTML 이 아니어도(스크래퍼마다 다르다) 406 이 아니라 미리보기 HTML")
    void any_accept_gets_preview(String accept) throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID).header("Accept", accept))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("<meta property=\"og:title\" content=\"원카드 · 방\" />");
    }

    @Test
    @DisplayName("현재 인원(3/4)은 미리보기에 없다 — 정원만")
    void excludes_current_player_count() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 3));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("4인 방").doesNotContain("3/4").doesNotContain("3명");
    }

    @Test
    @DisplayName("og:url 은 프록시 뒤 실제 주소 — forwarded 헤더(운영 framework 전략의 필터)를 반영하고 쿼리는 뺀다")
    void og_url_follows_forwarded_headers() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));
        MockMvc forwarded = MockMvcBuilders
                .standaloneSetup(new RoomPagePreviewController(templateOf(TEMPLATE), rooms, registry, users))
                .addFilters(new ForwardedHeaderFilter())
                .build();

        String body = forwarded.perform(get("/rooms/" + ROOM_ID + "?utm=kakao")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "mirboard.fly.dev")
                        .header("X-Forwarded-Port", "443"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("<meta property=\"og:url\" content=\"https://mirboard.fly.dev/rooms/" + ROOM_ID + "\" />");
    }

    @Test
    @DisplayName("게임 표시 이름은 GameRegistry 에서 — 모르는 게임이면 게임 ID 그대로")
    void game_name_from_registry() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "NEW_GAME", 1));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("<meta property=\"og:title\" content=\"NEW_GAME · 방\" />");
    }

    @Test
    @DisplayName("방장 계정을 못 찾으면 초대한 사람 없이 정원만")
    void unknown_host() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));
        when(users.findById(HOST_ID)).thenReturn(Optional.empty());

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("content=\"4인 방 · 누르면 바로 입장\"");
    }

    @Test
    @DisplayName("없는 방이면 틀 그대로(공통 미리보기의 SPA) — 클라가 \"방이 없어졌어요\"를 보인다")
    void missing_room_returns_template() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenThrow(new RoomNotFoundException(ROOM_ID));

        mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string(TEMPLATE));
    }

    @Test
    @DisplayName("방 조회가 다른 이유로 실패해도(저장소 장애) 예외가 새지 않고 틀 그대로")
    void lookup_failure_returns_template() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenThrow(new IllegalStateException("redis down"));

        mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));
    }

    @Test
    @DisplayName("방 ID 모양(영숫자·하이픈 64자 이하)이 아니면 저장소를 보지 않고 틀 그대로")
    void odd_room_id_is_not_looked_up() throws Exception {
        mvc(templateOf(TEMPLATE)).perform(get("/rooms/abc:players"))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));
        mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + "a".repeat(65)))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));

        verify(rooms, never()).getRoom(anyString());
    }

    @Test
    @DisplayName("화면 틀이 없으면(개발·테스트 빌드) 기존 리소스 처리와 같게 404 — 방은 보지 않는다")
    void no_template_is_404() throws Exception {
        mvc(noTemplate()).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isNotFound());

        verify(rooms, never()).getRoom(anyString());
    }

    @Test
    @DisplayName("틀에 표식이 없으면 틀 그대로")
    void template_without_markers() throws Exception {
        String plain = "<html><head><title>Mirboard</title></head><body></body></html>";
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));

        mvc(templateOf(plain)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(plain));
    }
}
```

`server/src/test/java/com/mirboard/infra/web/RoomPagePreviewIntegrationTest.java`:

```java
package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-133 — 방 미리보기를 실제 필터 체인·저장소 위에서. 카톡 스크래퍼는 토큰 없이 긁으므로 {@code /rooms/{roomId}} 는 인증 없이
 * 열려야 한다 — {@code SecurityConfig} 는 {@code /api} 밖을 기본 허용하므로 고치지 않는다(이 테스트가 그 사실을 고정한다).
 * 같은 이름의 REST({@code /api/rooms/{id}})·WebSocket({@code /ws})·다른 경로의 리소스 처리는 그대로여야 한다.
 *
 * <p>테스트 클래스패스에는 빌드된 {@code static/index.html} 이 없어 화면 틀은 목으로 준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-preview-test-secret-must-be-32-bytes-or-more"
})
class RoomPagePreviewIntegrationTest {

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

    static final String TEMPLATE = """
            <!doctype html>
            <html lang="ko">
              <head>
                <!--og:start-->
                <title>Mirboard</title>
                <meta property="og:title" content="미르보드카페 — 공통" />
                <!--og:end-->
              </head>
              <body><div id="root"></div></body>
            </html>
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    IndexHtmlTemplate template;

    @BeforeEach
    void serveTemplate() {
        when(template.html()).thenReturn(Optional.of(TEMPLATE));
    }

    @Test
    @DisplayName("토큰 없이 방 주소를 열면 그 방의 미리보기 HTML — 방 이름은 이스케이프, 방장·정원 포함")
    void anonymous_preview_of_real_room() throws Exception {
        String token = registerAndLogin("preview_host", "validpass1");
        JsonNode room = createRoom(token, "<b>금요일</b> 한 판", "ONE_CARD");
        String roomId = room.get("roomId").asText();

        String body = mockMvc.perform(get("/rooms/" + roomId).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("<meta property=\"og:title\" content=\"원카드 · &lt;b&gt;금요일&lt;/b&gt; 한 판\" />")
                .contains("preview_host님이 초대했어요 · " + room.get("capacity").asInt() + "인 방 · 누르면 바로 입장")
                .contains("<meta property=\"og:url\" content=\"http://localhost/rooms/" + roomId + "\" />")
                .doesNotContain("<b>금요일");
    }

    @Test
    @DisplayName("없는 방이면 틀 그대로 200 — 공통 미리보기의 SPA")
    void missing_room_serves_template() throws Exception {
        mockMvc.perform(get("/rooms/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));
    }

    @Test
    @DisplayName("화면 틀이 없으면 404")
    void no_template_is_404() throws Exception {
        when(template.html()).thenReturn(Optional.empty());

        mockMvc.perform(get("/rooms/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("같은 방의 REST(/api/rooms/{id}/…)는 그대로 인증이 필요하다 — 미리보기 매핑이 가로채지 않는다")
    void api_rooms_still_requires_auth() throws Exception {
        String token = registerAndLogin("preview_api", "validpass1");
        String roomId = createRoom(token, "방", "ONE_CARD").get("roomId").asText();

        mockMvc.perform(post("/api/rooms/" + roomId + "/join-or-reconnect"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("미리보기는 /rooms/{id} 한 단계만 — /rooms·/rooms/{id}/더 는 기존 리소스 처리(여기선 틀이 없어 404)")
    void other_room_paths_fall_through() throws Exception {
        mockMvc.perform(get("/rooms")).andExpect(status().isNotFound());
        mockMvc.perform(get("/rooms/abc/extra")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("WebSocket(SockJS) 정보 엔드포인트는 그대로 열린다")
    void websocket_endpoint_untouched() throws Exception {
        String body = mockMvc.perform(get("/ws/info"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"websocket\"");
    }

    private String registerAndLogin(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).get("accessToken").asText();
    }

    private JsonNode createRoom(String token, String name, String gameType) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", name, "gameType", gameType));
        String created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(created);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- src/indexHtml`
Expected: FAIL — `Test Files  1 failed (1)` · `Tests  3 failed | 1 passed (4)`(`AssertionError: expected +0 to be 1` — 표식 수, `… to contain '<meta property="og:title" content="미르…'`,
`expected 2 to be +0` — 구간 밖 `<title>`). 처음부터 통과하는 1건(구간 밖에 미리보기 메타가 없다)은 고정 테스트다.

Run: `./gradlew :server:test --rerun --tests "com.mirboard.infra.web.RoomPreviewRendererTest" --tests "com.mirboard.infra.web.IndexHtmlTemplateTest" --tests "com.mirboard.infra.web.RoomPagePreviewControllerTest"`
Expected: FAIL — `> Task :server:compileTestJava FAILED`(`RoomPreviewRendererTest.java:6: error: package com.mirboard.infra.web.RoomPreviewRenderer does not exist` · `error: cannot find symbol`
— 새 클래스라 컴파일 실패가 정상 RED).

- [ ] **Step 3: 구현**

`client/index.html`:

```diff
   <head>
     <meta charset="UTF-8" />
     <meta name="viewport" content="width=device-width, initial-scale=1.0" />
+    <!-- D-133 — 링크 미리보기. 서버가 GET /rooms/{roomId} 에서 아래 두 표식 사이를 그 방의 미리보기로 바꾼다. 표식을 지우거나 옮기지 말 것. -->
+    <!--og:start-->
     <title>Mirboard</title>
+    <meta name="description" content="티츄·스컬킹·원카드를 친구와 바로 — 가입 없이 게스트로 들어가는 웹 보드게임" />
+    <meta property="og:type" content="website" />
+    <meta property="og:site_name" content="미르보드카페" />
+    <meta property="og:title" content="미르보드카페 — 티츄·스컬킹·원카드" />
+    <meta property="og:description" content="친구와 바로 하는 웹 보드게임 · 가입 없이 게스트로 입장" />
+    <meta property="og:locale" content="ko_KR" />
+    <meta name="twitter:card" content="summary" />
+    <!--og:end-->
   </head>
   <body>
     <div id="root"></div>
```

`server/src/main/java/com/mirboard/infra/web/RoomPreviewRenderer.java`:

```java
package com.mirboard.infra.web;

import org.springframework.web.util.HtmlUtils;

/**
 * D-133 — 방 미리보기(Open Graph) 렌더러. 순수 함수 — 화면 틀(빌드된 {@code index.html}) 문자열과 방 정보로 HTML 을 만든다.
 *
 * <p>틀의 {@code <!--og:start-->}…{@code <!--og:end-->} 구간(사이트 공통 미리보기, 클라 {@code index.html})을 그 방의 미리보기로
 * 바꾸고 구간 밖은 한 글자도 건드리지 않는다. 표식이 없거나 순서가 어긋나면 틀을 그대로 돌려준다.
 *
 * <ul>
 *   <li>제목 "{게임 표시 이름} · {방 이름}"(방 이름이 비면 "{방장}님의 방"), {@code <title>} 은 제목 + " — 미르보드카페".</li>
 *   <li>설명 "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장". <b>현재 인원은 넣지 않는다</b> — 카톡이 미리보기를 한동안
 *       저장해 낡은 숫자가 보인다(입력 {@link RoomPreview} 에 인원이 아예 없다).</li>
 *   <li>사용자 입력(방 이름·방장 이름)은 공백을 접고 길이를 자른 뒤(방 이름 {@value #MAX_ROOM_NAME}자·방장 {@value #MAX_HOST_NAME}자,
 *       코드 포인트 기준 — 이모지를 반으로 가르지 않는다) 모든 값을 {@link HtmlUtils#htmlEscape(String, String)} 으로 이스케이프한다
 *       (속성 안 따옴표 포함, UTF-8 이라 한글·이모지는 그대로).</li>
 * </ul>
 */
public final class RoomPreviewRenderer {

    static final String START = "<!--og:start-->";
    static final String END = "<!--og:end-->";
    static final String SITE_NAME = "미르보드카페";
    /** 방 이름 길이 상한(코드 포인트). 카톡 말풍선 제목은 두 줄 남짓에서 어차피 잘린다 — 메타가 끝없이 길어지지 않게만. */
    static final int MAX_ROOM_NAME = 40;
    /** 방장 이름 상한. 가입 규칙(3~20자)·게스트 이름(`guest-` + 8자)이 이미 그 안이라 방어용. */
    static final int MAX_HOST_NAME = 20;

    private static final String INDENT = "    ";

    /**
     * 미리보기에 쓰는 방 정보. {@code hostName} 은 방장 계정을 못 찾으면 {@code null}. {@code url} 은 요청의 절대 주소
     * (프록시 forwarded 헤더 반영, 쿼리 없음).
     */
    public record RoomPreview(String gameName, String roomName, String hostName, int capacity, String url) {
    }

    private RoomPreviewRenderer() {
    }

    /** 틀의 표식 구간을 방 미리보기로 바꾼다. 표식이 없거나 어긋나면 틀 그대로. */
    public static String render(String template, RoomPreview room) {
        int start = template.indexOf(START);
        int end = template.indexOf(END);
        if (start < 0 || end < start + START.length()) {
            return template;
        }
        return template.substring(0, start + START.length()) + "\n"
                + block(room)
                + INDENT + template.substring(end);
    }

    private static String block(RoomPreview room) {
        String host = room.hostName() == null ? null : clip(room.hostName(), MAX_HOST_NAME);
        String title = room.gameName() + " · " + roomLabel(room.roomName(), host);
        String description = (host == null ? "" : host + "님이 초대했어요 · ")
                + room.capacity() + "인 방 · 누르면 바로 입장";
        return line("<title>" + esc(title + " — " + SITE_NAME) + "</title>")
                + meta("name", "description", description)
                + meta("property", "og:type", "website")
                + meta("property", "og:site_name", SITE_NAME)
                + meta("property", "og:title", title)
                + meta("property", "og:description", description)
                + meta("property", "og:url", room.url())
                + meta("property", "og:locale", "ko_KR")
                + meta("name", "twitter:card", "summary");
    }

    private static String roomLabel(String roomName, String host) {
        String name = roomName == null ? "" : clip(roomName, MAX_ROOM_NAME);
        if (!name.isEmpty()) {
            return name;
        }
        return host == null ? "초대받은 방" : host + "님의 방";
    }

    /** 공백(줄바꿈·탭 포함)을 하나로 접고 앞뒤를 다듬은 뒤 코드 포인트 {@code max} 개에서 자른다(넘치면 말줄임표). */
    static String clip(String value, int max) {
        String folded = value.strip().replaceAll("\\s+", " ");
        if (folded.codePointCount(0, folded.length()) <= max) {
            return folded;
        }
        return folded.substring(0, folded.offsetByCodePoints(0, max)) + "…";
    }

    private static String meta(String attr, String key, String content) {
        return line("<meta " + attr + "=\"" + key + "\" content=\"" + esc(content) + "\" />");
    }

    private static String line(String tag) {
        return INDENT + tag + "\n";
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
```

`server/src/main/java/com/mirboard/infra/web/IndexHtmlTemplate.java`:

```java
package com.mirboard.infra.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * D-133 — 방 미리보기의 화면 틀. 빌드된 클라 {@code index.html}(Dockerfile 이 {@code classpath:static/} 으로 복사 — D-39)을
 * <b>시작할 때 한 번</b> 읽어 둔다. 요청마다 파일을 읽지 않는다 — 번들은 배포 단위라 실행 중에 바뀌지 않는다.
 *
 * <p>개발·테스트처럼 클라 번들을 동봉하지 않은 빌드에는 틀이 없다({@link Optional#empty()}). 그때 미리보기 컨트롤러는 기존 리소스
 * 처리와 같게 404 를 준다. 읽기 실패도 기동을 막지 않고 빈 틀로 둔다(경고 로그).
 */
@Component
public class IndexHtmlTemplate {

    private static final Logger log = LoggerFactory.getLogger(IndexHtmlTemplate.class);

    static final String LOCATION = "static/index.html";

    private final Optional<String> html;

    @Autowired
    public IndexHtmlTemplate() {
        this(new ClassPathResource(LOCATION));
    }

    IndexHtmlTemplate(Resource resource) {
        this.html = load(resource);
    }

    /** 빌드된 {@code index.html} 원문. 없으면 비어 있다. */
    public Optional<String> html() {
        return html;
    }

    private static Optional<String> load(Resource resource) {
        if (!resource.exists()) {
            log.info("방 미리보기 화면 틀 없음 — 클라 번들 미동봉 빌드: {}", resource.getDescription());
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("방 미리보기 화면 틀을 읽지 못함 — 미리보기 없이 기동: {}", resource.getDescription(), e);
            return Optional.empty();
        }
    }
}
```

`server/src/main/java/com/mirboard/infra/web/RoomPagePreviewController.java`:

```java
package com.mirboard.infra.web;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.web.RoomPreviewRenderer.RoomPreview;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-133 — 방 주소({@code /rooms/{roomId}})의 HTML 에 그 방의 미리보기(Open Graph)를 넣는다. 공유 링크가 방 주소 그대로라 카톡
 * 같은 스크래퍼(자바스크립트를 실행하지 않는다)가 이 응답의 메타로 말풍선을 그리고, 브라우저에게는 같은 SPA 다(클라 라우터가 방 화면을
 * 그린다). 컨트롤러 매핑이 SPA 대체 리소스 핸들러({@code StaticSpaConfig})보다 먼저 잡는다.
 *
 * <ul>
 *   <li>{@code /api} 밖이라 인증이 없다 — {@code SecurityConfig} 는 {@code /api} 밖을 기본 허용한다(스크래퍼는 토큰이 없다). 레이트리밋
 *       ({@code /api/**} 전용)도 걸지 않는다 — 방 하나당 저장소 읽기 한 번이고 방 ID 는 추측할 수 없는 UUID 다.</li>
 *   <li>방이 없거나 조회가 실패하면 틀 그대로(사이트 공통 미리보기의 SPA — 클라가 "방이 없어졌어요"를 보인다). 예외를 HTML 응답
 *       밖으로 내보내지 않는다. 틀이 없으면(클라 번들 미동봉 빌드) 기존 리소스 처리와 같게 404.</li>
 *   <li>{@code Cache-Control: no-cache} — 방마다 내용이 달라 공유 캐시 금지(브라우저는 재검증). Accept 와 무관하게 늘
 *       {@code text/html;charset=UTF-8} 이다(협상하지 않는다).</li>
 *   <li>게임 표시 이름은 {@link GameRegistry} 의 정의에서 얻는다 — 인프라에 게임 이름 분기를 두지 않는다. 새로 드러나는 정보는 없다
 *       (방 이름·방장·게임·정원은 로비 목록에 이미 공개).</li>
 * </ul>
 */
@RestController
public class RoomPagePreviewController {

    private static final Logger log = LoggerFactory.getLogger(RoomPagePreviewController.class);

    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    /** 방 ID 는 UUID. 그 밖의 모양은 저장소를 보지 않는다(키 이름에 섞여 다른 자료형을 건드리지 않게). */
    private static final Pattern ROOM_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final IndexHtmlTemplate template;
    private final RoomService rooms;
    private final GameRegistry games;
    private final UserRepository users;

    public RoomPagePreviewController(IndexHtmlTemplate template, RoomService rooms, GameRegistry games,
                                     UserRepository users) {
        this.template = template;
        this.rooms = rooms;
        this.games = games;
        this.users = users;
    }

    /**
     * produces 를 걸지 않는다 — 걸면 Accept 가 HTML 이 아닌 스크래퍼(카톡 등 — 값을 알 수 없다)가 406(빈 본문)을 받고 SPA 대체로도
     * 넘어가지 않는다. Content-Type 은 응답에 명시하고 협상하지 않는다.
     */
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<String> page(@PathVariable String roomId, HttpServletRequest request) {
        Optional<String> html = template.html();
        if (html.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String body = preview(roomId, request)
                .map(room -> RoomPreviewRenderer.render(html.get(), room))
                .orElse(html.get());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(HTML_UTF8)
                .body(body);
    }

    private Optional<RoomPreview> preview(String roomId, HttpServletRequest request) {
        if (!ROOM_ID.matcher(roomId).matches()) {
            return Optional.empty();
        }
        try {
            Room room = rooms.getRoom(roomId);
            String gameName = games.find(room.gameType())
                    .map(GameDefinition::displayName)
                    .orElse(room.gameType());
            String hostName = users.findById(room.hostId())
                    .map(User::getUsername)
                    .orElse(null);
            return Optional.of(new RoomPreview(gameName, room.name(), hostName, room.capacity(),
                    request.getRequestURL().toString()));
        } catch (RoomNotFoundException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("방 미리보기 실패 — 공통 미리보기로: roomId={}", roomId, e);
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- src/indexHtml`
Expected: PASS — `Test Files  1 passed (1)` · `Tests  4 passed (4)`.

Run: `./gradlew :server:test --rerun --tests "com.mirboard.infra.web.RoomPreviewRendererTest" --tests "com.mirboard.infra.web.IndexHtmlTemplateTest" --tests "com.mirboard.infra.web.RoomPagePreviewControllerTest"` 뒤 `awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ (failures|errors)=$/)f+=$(i+1)}} END{print "tests="t" failed="f}' server/build/test-results/test/TEST-*.xml`
Expected: `BUILD SUCCESSFUL`, `tests=33 failed=0`(렌더러 15 · 틀 4 · 컨트롤러 14 — 렌더러 1건은 실제 `../client/index.html` 의 표식과 맞물리는지 본다).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --rerun --tests "com.mirboard.infra.web.RoomPagePreviewIntegrationTest"` 뒤 `awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ (failures|errors)=$/)f+=$(i+1)}} END{print "tests="t" failed="f}' server/build/test-results/test/TEST-*.xml`
Expected: `BUILD SUCCESSFUL`, `tests=6 failed=0`(토큰 없이 200 · 없는 방 · 틀 없음 404 · `/api/rooms/…` 401 · `/rooms`·`/rooms/abc/extra` 404 · `/ws/info` 200 — `SecurityConfig` 무변경).

Run: `grep -rniE 'onecard|one_card|원카드|skullking|tichu' server/src/main/java/com/mirboard/infra | wc -l` 뒤 `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: `16`, 타입 오류 없음, 마지막 두 줄 `Test Files  72 passed (72)` · `Tests  865 passed (865)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/indexHtml.test.ts \
  server/src/test/java/com/mirboard/infra/web/RoomPreviewRendererTest.java \
  server/src/test/java/com/mirboard/infra/web/IndexHtmlTemplateTest.java \
  server/src/test/java/com/mirboard/infra/web/RoomPagePreviewControllerTest.java \
  server/src/test/java/com/mirboard/infra/web/RoomPagePreviewIntegrationTest.java \
  client/index.html \
  server/src/main/java/com/mirboard/infra/web/RoomPreviewRenderer.java \
  server/src/main/java/com/mirboard/infra/web/IndexHtmlTemplate.java \
  server/src/main/java/com/mirboard/infra/web/RoomPagePreviewController.java
git commit -m "feat(D-133): 방마다 다른 링크 미리보기 — index.html 공통 미리보기·표식, 서버 GET /rooms/{roomId} 가 표식 구간을 그 방의 Open Graph 로(Accept 무관)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: 문서 — 설계 상태·API·QA·현황 수치 + 실측·Phase Gate

코드는 바꾸지 않는다. 아래 diff 는 Task 1~5 를 마친 뒤의 파일 기준이다(이 문서들은 앞 태스크가 건드리지 않았다).

**Files:**
- Modify: `docs/plans/room-invite.md`, `docs/api.md`, `docs/qa-scenarios.md`, `docs/implementation-status.md`, `README.md`, `docs/case-study-multi-game.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: Task 1~5 의 동작·이름, 마지막 실측(클라 72파일·865건, 서버 아래 Step 3).

- [ ] **Step 1: 설계 상태·API·QA**

`docs/plans/room-invite.md`:

```diff
 # 방 초대 링크 — 링크 복사·공유, 방마다 다른 카톡 미리보기, 로그인 뒤 그 방으로 (D-133) — 설계
 
-> 상태: **설계 승인** · 2026-10-12 · 결정 이력 `docs/decisions.md` D-133(계획 Task 1 에서 추가)
+> 상태: **구현** · 2026-10-12 · 결정 이력 `docs/decisions.md` D-133 · 구현에서 정한 세부는 §8 · 스파이크 뒤 사용자 결정 A(자리 없음 → 바로 관전 + 안내)와
+> 사전 리뷰 반영 · 봇이 사람에게 자리를 비켜 주는 안은 기각(§4.5)
 > 근거: 사용자 요청("게임 방 만들고 링크 복사해서 카톡으로 공유") · 방 입장 멱등 `join-or-reconnect`(D-74) · 게스트 체험(D-117) · SPA 서빙 `StaticSpaConfig`(D-39)
 
 ## 1. 목표와 성공 기준
```

```diff
 
 | # | 기준 | 확인 방법 |
 | --- | --- | --- |
-| 1 | 대기실의 "초대 링크" 버튼이 휴대폰에서는 공유창(카톡 포함)을, 공유창이 없으면 링크 복사를, 복사도 막히면 선택된 링크를 보여 준다 | 컴포넌트 테스트(공유·복사·실패 폴백) |
+| 1 | 대기실의 "초대 링크" 버튼이 휴대폰·태블릿에서는 공유창(카톡 포함)을, PC 나 공유창이 없으면 링크 복사를, 복사도 막히면 선택된 링크를 보여 준다 | 컴포넌트 테스트(공유·복사·실패 폴백, 주입 없는 기본 경로의 포인터 판정) |
 | 2 | 로그인 안 된 친구가 `/rooms/<방ID>` 를 열면 로그인 화면에 "방에 초대받았어요" 안내와 앞쪽 "게스트로 바로 입장"이 보이고, 게스트 입장·로그인·가입 뒤 그 방으로 돌아간다 | 라우팅·로그인 화면 테스트 |
 | 3 | 돌아갈 주소는 사이트 안 경로만 허용한다(외부로 튕기는 오픈 리다이렉트 없음) | 단위 테스트 |
-| 4 | 방이 꽉 찼거나(`ROOM_FULL`) 이미 시작했으면(`GAME_ALREADY_STARTED`) "자리가 없어요 — 관전할까요?"와 관전·메인으로 버튼 | 방 화면 테스트 |
-| 5 | `GET /rooms/<방ID>`(HTML)의 `<head>` 에 그 방의 Open Graph 미리보기가 들어간다 — 제목 "{게임 표시 이름} · {방 이름}", 설명 "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장". 사용자 입력은 HTML 이스케이프 | 서버 단위·MVC 테스트 |
+| 4 | 꽉 찬 대기실(봇 자리 포함)·시작한 방이면 묻지 않고 관전으로 받아 방 화면 위에 "…관전으로 들어왔어요" 안내(그런 대기실의 초대 버튼은 미리 "관전으로 들어와요"), 확인과 입장 사이에 자리가 찬 경합(`ROOM_FULL`·`GAME_ALREADY_STARTED`)만 "자리가 없어요 — 관전할까요?" 패널 | 방 화면·허브 테스트, 서비스 단위(봇 방 관전·경합·동시 이중 요청) |
+| 5 | `GET /rooms/<방ID>`(HTML)의 `<head>` 에 그 방의 Open Graph 미리보기가 들어간다 — 제목 "{게임 표시 이름} · {방 이름}", 설명 "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장". 사용자 입력은 HTML 이스케이프, 요청의 Accept 와 무관 | 서버 단위·MVC 테스트 |
 | 6 | 방이 없거나 화면 틀(`static/index.html`)이 없으면 기존 동작(사이트 공통 미리보기의 SPA)을 그대로 돌려준다 — 화면이 깨지지 않는다 | 서버 테스트 |
 | 7 | 다른 모든 페이지에도 사이트 공통 미리보기("미르보드카페 — 티츄·스컬킹·원카드 …")가 있다 | `index.html` 원문 단언 |
 | 8 | 티츄·스컬킹·원카드의 기존 방 입장·관전·재접속 흐름 회귀 0 | 클라·서버 전체 테스트 |
```

```diff
 | --- | --- |
 | 공유 방식 | 링크 복사 + 휴대폰 공유창(Web Share API). 카카오 SDK 공유 버튼은 하지 않음(앱 키 등록 필요 — 후속) |
 | 처음 온 친구 | 로그인 화면 → "게스트로 바로 입장"(앞쪽) → 그 방으로. 로그인·가입해도 그 방으로 |
-| 자리 없음 | 관전으로 들어가기 제안 |
+| 자리 없음 | ~~관전으로 들어가기 제안~~ → **바로 관전 + 안내**(스파이크 뒤 다시 정함 — 서버 `join-or-reconnect` 가 이미 묻지 않고 관전으로 받는다, §4.3). "관전할까요?" 패널은 확인과 입장 사이 자리가 찬 경합용 |
+| 꽉 찬 대기실의 봇 | ~~봇이 사람에게 자리를 비켜 준다~~ → **비켜 주지 않는다**(스파이크 뒤 기각, §4.5) — 봇 자리도 찬 자리라 친구는 관전, 초대 버튼이 미리 알린다 |
 | 미리보기 | 방마다 다른 미리보기, **공유 링크 = 방 주소 그대로**(`/rooms/<방ID>` — 주소창을 복사해 보내도 미리보기가 뜬다) |
 
 기각: 초대 전용 주소 `/invite/<방ID>`(미리보기 페이지 → 방으로 넘김) — 화면 틀에 묶이지 않는 장점이 있지만 사용자는 방 주소 그대로를 골랐다.
```

```diff
 ### 4.1 초대 버튼 (클라)
 
 - 대기실(`RoomPage` 의 WAITING 화면) 머리에 "초대 링크" 버튼. 링크 = `${window.location.origin}/rooms/${roomId}`.
-- 순서: ① `navigator.share` 가 있으면 `navigator.share({ title, text, url })`(취소 `AbortError` 는 조용히 무시) ② 없거나 실패하면
+- 순서: ① 굵은 포인터(휴대폰·태블릿 — `matchMedia('(pointer: coarse)')`)이고 `navigator.share` 가 있으면 `navigator.share({ title, text, url })`
+  (취소 `AbortError` 는 조용히 무시. 데스크톱 Chrome·Edge(Windows)·Safari(macOS)에도 `navigator.share` 가 있어 포인터로 가른다 — 안 가르면
+  PC 에서 복사 대신 OS 공유창이 뜬다) ② 없거나 실패하면
   `navigator.clipboard.writeText(url)` → "링크를 복사했어요" 짧은 알림 ③ 복사도 실패하면(권한·비보안 문맥·일부 앱 내 브라우저) 링크가 선택된 입력란을
   보여 "직접 복사해 주세요". `title` = "{게임 표시 이름} 같이 해요", `text` = "{방 이름} — 미르보드카페".
 - 공유·복사는 순수 함수(`shareRoomLink(url, meta, env)` — `navigator` 를 주입받아 결과 `'shared' | 'copied' | 'manual' | 'cancelled'` 를 돌려줌)로 두고
   버튼은 결과만 그린다(테스트 쉬움).
-- 게임 중(IN_GAME) 화면에는 두지 않는다(초대는 시작 전 — 자리가 없다).
+- 게임 중(IN_GAME) 화면에는 두지 않는다(초대는 시작 전 — 자리가 없다). 대기실이어도 정원이 다 찼으면(봇 자리 포함 — 게스트의 봇 채우기 방은
+  만들자마자 꽉 찬다, 봇은 자리를 비켜 주지 않는다 §4.5) 설명을 "자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요"로 바꾼다(버튼은 둔다
+  — 관전 초대도 쓸모가 있다).
 
 ### 4.2 로그인 뒤 그 방으로 (클라)
 
```

```diff
   "회원가입" 링크도 `state.from` 을 넘기고, `RegisterPage` 는 가입 뒤 같은 규칙으로 이동한다.
 - `location.state` 는 주소에 드러나지 않고(카톡 앱 내 브라우저에서도 같은 탭 기록에 남음), 새로고침하면 사라질 수 있다 — 그때는 지금처럼 `/games`.
 
-### 4.3 자리 없음 → 관전 제안 (클라)
+### 4.3 자리 없음 → 바로 관전 + 안내 (클라, 사용자 결정 A)
 
-- `RoomPage` 의 첫 `joinOrReconnect` 가 `ROOM_FULL`·`GAME_ALREADY_STARTED` 로 실패하면 오류 문구 대신 패널: "자리가 없어요 — 관전할까요?" + [관전하기]
-  (`roomsApi.spectate` 뒤 방 다시 불러오기 — 허브 관전과 같은 경로) + [메인으로]. `ROOM_NOT_FOUND` 는 "방이 없어졌어요" + [메인으로]. 그 밖의 오류는 지금처럼.
+- 서버 `join-or-reconnect`(Phase 8A)는 꽉 찬 대기실(봇 자리 포함)·진행 중 방이면 오류 대신 **관전으로 받는다**
+  (`mode=SPECTATING`, 서버 무변경). 클라는 묻지 않고 그 화면(관전 대기실·관전자 게임판)을 그리고 맨 위에 안내를 띄운다(`SpectateNotice`) —
+  진행 중 "게임이 이미 시작돼 관전으로 들어왔어요", 꽉 찬 대기실 "자리가 없어 관전으로 들어왔어요". [닫기] 또는 8초 뒤 내린다.
+- 신호 = 응답 `mode === 'SPECTATING'` + 돌려받은 방 상태. 빈자리가 있는 대기실 관전(본인이 고른 것)·끝난 방(종료 카드가 설명)은 안내하지 않는다.
+  진행 중 방은 대기실 셸 없이 게임판을 바로 그리므로 세 렌더 분기(스컬킹·원카드 게임판, 티츄 게임판, 대기실·종료 셸) 모두의 맨 위에 둔다
+  (`z-[120]` — 게임판 채팅 100 위, 원카드 경쟁 레이어 150·패스 받은 카드 200 아래. 게임판은 `.app-shell` 밖이라 카드에 `app-shell`).
+- 서버는 이미 관전자여도 `SPECTATING` 을 주므로 탭 단위 기록(sessionStorage `mirboard.spectateNotice.<방ID>`, 막히면 조용히 넘어감)으로 한 번만
+  띄운다 — 안내를 띄우면 기록하고, 허브 "구경하기"·아래 패널의 [관전하기] 처럼 본인이 관전을 고르면 기록한다. 그래서 새로고침·허브 관전에는 안 뜬다.
+- 확인과 입장 사이 마지막 자리가 찬 경합이면 `joinOrReconnect` 가 `ROOM_FULL`·`GAME_ALREADY_STARTED` 로 실패한다 — 오류 문구 대신 패널:
+  "자리가 없어요 — 관전할까요?" + [관전하기](`roomsApi.spectate` 응답의 방으로 다시 그림 — 허브 관전과 같은 경로) + [메인으로]. `ROOM_NOT_FOUND` 는
+  "방이 없어졌어요" + [메인으로]. 그 밖의 오류는 지금처럼. 서버는 이 경합을 관전으로 삼키지 않는다(삼키면 패널이 뜰 길이 없다 — 서비스 단위 테스트가 고정).
+- 같은 사람의 동시 이중 요청(같은 링크를 두 탭에서·개발 StrictMode 의 이중 effect)은 둘 다 "플레이어 아님"을 읽고 입장으로 간다. 서버가 늦은 쪽의
+  `ALREADY_IN_ROOM` 을 받아 다시 읽고 앉아 있으면 `RECONNECTED` 로 준다(D-74 멱등 — 예전엔 방 화면이 그 원문을 보였다).
 
 ### 4.4 방마다 다른 미리보기 (서버)
 
-- `infra/web` 에 `RoomPagePreviewController` — `GET /rooms/{roomId}`(`produces = text/html`, 인증 없음 — `/api` 밖). 화면 틀 = 빌드된 `classpath:static/index.html`
+- `infra/web` 에 `RoomPagePreviewController` — `GET /rooms/{roomId}`(인증 없음 — `/api` 밖). 응답은 요청의 Accept 와 무관하게 `text/html;charset=UTF-8` 다 —
+  매핑에 `produces` 를 걸지 않는다(걸면 Accept 가 HTML 이 아닌 스크래퍼가 406 빈 본문을 받고 SPA 대체로도 넘어가지 않는다). 화면 틀 = 빌드된 `classpath:static/index.html`
   (`IndexHtmlTemplate` 이 시작할 때 한 번 읽어 두는 빈 — 없으면 `Optional.empty()`).
 - `client/index.html` 의 `<head>` 에 표식 `<!--og:start-->` … `<!--og:end-->` 로 감싼 **사이트 공통 미리보기**(기준 7)를 둔다. 컨트롤러는 방이 있으면 이 구간을
   방 미리보기로 바꿔 돌려준다: `og:type=website`, `og:site_name=미르보드카페`, `og:title`, `og:description`, `og:url`(요청 절대 주소 — forwarded 헤더 반영),
```

```diff
   방 하나당 Redis 읽기 한 번이고 방 ID 는 추측 불가(후속: 남용 관측 시 IP 버킷).
 - 개인정보: 새로 드러나는 정보 없음(방 이름·방장·게임·정원은 이미 로비 목록에 공개). 링크를 받은 사람만 본다.
 
-### 4.5 문서
+### 4.5 봇이 사람에게 자리를 비켜 주기 — 기각
+
+기각 — 사용자: 사람과 할 때는 봇을 다 없앤다, 방 만들 때 봇은 앞으로 없앨 예정(2026-10-12 — 스파이크에서 `room_join.lua` 봇 교체로 구현했다가 철회).
+
+### 4.6 문서
 
 - `docs/decisions.md` D-133(첫 커밋), `docs/api.md`(공개 HTML `GET /rooms/{roomId}` 미리보기 — REST 아님 표시), `docs/qa-scenarios.md`("방 초대 링크" — 휴대폰 공유창,
   카톡 미리보기, 게스트로 바로 입장, 자리 없음 → 관전), `docs/implementation-status.md` 한 줄, `CLAUDE.md` 사용자 플로우에 초대 링크 한 줄, 수치를 인용한 문서.
+- 결정 A: `docs/api.md` `/join-or-reconnect` 에 경합 오류(`ROOM_FULL`·`GAME_ALREADY_STARTED`)와 동시 이중 요청의 `RECONNECTED`. 입장 규칙(`room_join.lua`)은
+  그대로라 `docs/redis-keys.md`·`docs/architecture.md` 는 바꾸지 않는다.
 
 ## 5. 경계·예외
 
 | 경우 | 처리 |
 | --- | --- |
 | 이미 그 방 참가자가 링크를 다시 엶 | `joinOrReconnect` 멱등 — 그대로 재접속 |
-| 다른 방에 들어가 있는 사람이 링크를 엶 | 기존 서버 규칙(`ALREADY_IN_ROOM` 등)을 그대로 보인다 — 이번 범위 밖 |
+| 다른 방에 앉아 있는 사람이 링크를 엶 | 서버가 막지 않아 이 방에도 참가한다(기존 동작 — 이번 범위 밖. `ALREADY_IN_ROOM` 은 그 방 안 중복일 때만) |
+| 꽉 찬 대기실(봇 자리 포함 — 게스트 기본 봇 채우기 방)·진행 중 방의 링크 | 묻지 않고 관전 + 방 화면 위 안내(§4.3). 새로고침·허브 관전에는 안내 없음. 봇은 비켜 주지 않는다(§4.5) |
+| 같은 사람이 같은 링크를 두 탭에서 동시에 엶 | 둘 다 그 방 — 늦은 쪽도 재접속(§4.3) |
 | 카톡 앱 내 브라우저 | 공유창이 없으면 복사 → 복사도 막히면 선택된 입력란. 로그인 토큰은 그 브라우저 저장소에 따로 — 처음이면 게스트 입장 |
 | 새로고침으로 `state.from` 이 사라짐 | `/games` (지금 동작) |
 | 방 이름에 `"`·`<`·`&` | 이스케이프되어 메타 속성이 깨지지 않음 |
```

```diff
 - 클라 단위: `safeReturnPath`(정상 경로, `//evil.com`, `/\evil`, `http://…`, `/login`, 비문자열), `shareRoomLink`(공유 성공·취소·공유 없음 → 복사·복사 실패 → 수동).
 - 클라 컴포넌트: `ProtectedRoute` 가 `from` 을 싣는다, `LoginPage` 초대 안내·게스트 버튼 순서·로그인/게스트 뒤 목적지·이미 로그인 상태 리다이렉트, `RegisterPage`
   가입 뒤 목적지, `RoomPage` 초대 버튼(WAITING 만)·자리 없음 패널(관전하기 → spectate 호출)·방 없음 패널. `index.html` 공통 미리보기·표식 원문 단언.
+- 클라(결정 A): `SpectateNotice`(문구·탭 기록·8초), `RoomPage` 관전 안내(세 분기·빈자리·끝난 방·JOINED·새로고침·허브 관전·패널)·초대 문구(사람·봇으로
+  꽉 참 → 관전), `GameHubPage` 구경하기 기록.
+- 서버(결정 A 의 입장 갈래): `RoomServiceJoinOrReconnectTest`(Docker 불필요 — 봇까지 꽉 찬 방은 관전, 경합 `ROOM_FULL`·`GAME_ALREADY_STARTED` 는 그대로
+  던짐, 동시 이중 요청은 재접속, 그사이 나갔으면 그대로 던짐).
 - 서버: 렌더러 단위(방 미리보기 값·이스케이프·인원 미포함·방 이름 빈 경우·표식 없음 → 틀 그대로), MVC(`GET /rooms/{id}` text/html·`Cache-Control`·인증 없이 200,
   없는 방 → 틀 그대로, 틀 없음 → 404). 인프라 grep(게임 이름 분기) 불변.
 - 배포 뒤: 휴대폰 공유창 → 카톡으로 보내 미리보기 확인, 게스트로 입장, 꽉 찬 방 → 관전.
```

```diff
 - 카카오 SDK "카카오톡으로 보내기" 버튼(앱 키·도메인 등록 필요).
 - 미리보기 이미지(1200×630 PNG — 게임별 또는 사이트 공통).
 - `/rooms/` 미리보기 남용 시 IP 레이트리밋.
+
+## 8. 구현 메모 (스파이크, 2026-10-12)
+
+설계에 없거나 설계와 달리 정한 것. 근거는 테스트에 있다.
+
+1. **자리 없음은 묻지 않고 관전이다.** 스파이크에서 서버가 꽉 찬 대기실·진행 중 방을 이미 관전으로 받는다는 것을 찾았고(설계 §3 조사가 놓친 부분),
+   사용자가 그 위에서 "바로 관전 + 안내"로 다시 정했다(결정 A, §4.3 — 서버 무변경). 패널은 경합 대비다. [관전하기]는 `spectate` 응답의 방으로 다시
+   그린다(허브처럼 다시 `join-or-reconnect` 를 부르면 같은 결과를 요청 한 번 더 들여 얻는다).
+2. **초대 버튼 자리**: 머리의 버튼 줄이 아니라 머리 **바로 아래 한 줄**(`InviteLinkButton` — 설명 + 버튼 + 결과). 수동 입력란이 버튼 줄 안에
+   들어가면 좁은 화면에서 머리가 무너진다. WAITING 일 때만.
+3. **알림 문구**: 공유됨 "초대 링크를 보냈어요", 복사됨 "링크를 복사했어요"(둘 다 3초 뒤 사라짐), 수동 "직접 복사해 주세요"
+   (읽기 전용 입력란, 열리면 링크 전체 선택·포커스). 취소는 아무것도 보이지 않는다. 링크의 방 ID 는 경로 조각으로 인코딩한다. 알림 영역
+   (`role="status"`)은 늘 두고 내용만 바꾼다 — 영역과 내용을 함께 넣으면 일부 스크린 리더가 읽지 않는다(사전 리뷰 IP-4).
+4. **돌아갈 주소**: 설계 규칙에 더해 제어 문자를 거절하고(URL 파서가 탭·줄바꿈을 지워 `/\t/evil.com` 이 `//evil.com` 이 된다) 로그인·가입
+   판정은 쿼리·해시·끝 슬래시를 떼고 대소문자를 가리지 않는다(라우터가 대소문자를 안 가린다). 인코딩된 `/%2F%2Fevil.com` 은 사이트 안
+   경로라 받는다(라우터가 없는 경로로 보고 허브로).
+5. **로그인 화면**: 초대받은 사람의 게스트 버튼은 이름("게스트로 바로 입장")과 함께 기본(채운) 버튼이다. 가입 화면의 "로그인" 링크와 게스트
+   실패 안내의 가입 링크도 `state.from` 을 넘긴다(가입 ↔ 로그인을 오가도 초대 안내가 남게).
+6. **미리보기 틀**: 기존 `<title>Mirboard</title>` 을 표식 구간 안으로 옮겼다(방 미리보기가 제목을 바꿔도 `<title>` 이 하나). 공통 구간에는
+   `name="description"` 도 두고 방 미리보기가 같이 바꾼다. 공통 미리보기에는 `og:url` 을 넣지 않는다(사이트 주소를 하드코딩하지 않는다).
+   SPA 는 `document.title` 을 관리하지 않으므로 방 화면을 떠날 때 기본 제목 "Mirboard" 로 되돌린다 — 방 주소로 처음 연 탭이 허브·다른 방으로
+   옮겨도 처음 방 이름이 탭·방문 기록에 남지 않게(사전 리뷰 IP-5).
+7. **길이·공백**: 방 이름 40자·방장 20자(코드 포인트 — 이모지를 반으로 가르지 않는다)에서 자르고 "…", 줄바꿈·연속 공백은 공백 하나.
+   서버는 방 이름 길이를 제한하지 않는다(`@NotBlank` 만).
+8. **방장을 모를 때**(게스트 정리 등): 설명은 "{정원}인 방 · 누르면 바로 입장", 방 이름도 비면 "초대받은 방".
+9. **서버 방어**: 방 ID 모양(`[A-Za-z0-9-]{1,64}` — UUID)이 아니면 저장소를 보지 않는다. 방 없음 말고도 조회 실패(저장소 장애)는 경고 로그 뒤
+   틀 그대로. `og:url` 은 `request.getRequestURL()`(운영 `forward-headers-strategy: framework` 의 필터가 프록시 주소로 바꾼다 — 단위 테스트가
+   `ForwardedHeaderFilter` 로 고정). 매핑에 `produces` 를 걸지 않는다 — 걸었던 첫 구현은 Accept 가 HTML 이 아닌 요청(json·plain·image·xhtml)에
+   406 빈 본문을 줬다(사전 리뷰 IP-1, 카톡 스크래퍼의 Accept 는 알 수 없다).
+10. **틀**: `IndexHtmlTemplate` 은 `classpath:static/index.html` 을 시작할 때 한 번 읽고, 없거나 못 읽으면 비어 있다(기동은 막지 않는다).
+    테스트 클래스패스에는 틀이 없어 IT 는 `@MockitoBean` 으로 준다. 빌드된 `dist/index.html` 에서 Vite 가 넣는 `<script>`·`<link>` 는
+    `og:end` 뒤라 렌더러가 건드리지 않는다.
+11. **공유창은 굵은 포인터에서만**: 주입 없는 기본 경로(`InviteLinkButton`)는 `matchMedia('(pointer: coarse)')` 일 때만 `navigator.share` 를 쓴다 —
+    데스크톱 Chrome·Edge·Safari 에도 있어 PC 에서 "링크 복사" 대신 OS 공유창이 떴다(사전 리뷰 IP-2). jsdom 에는 둘 다 없어 테스트가 넣어 준다.
+12. **초대 문구는 정원 기준**: 꽉 찬 대기실(봇 자리 포함)은 "자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요"(사전 리뷰 IP-3) — 게스트의
+    봇 채우기 방은 만들자마자 이 문구다. 봇 자리를 빈자리로 세던 판정(`seatForNewcomer`)은 봇 자리 비켜주기 철회(§4.5)와 함께 지웠다.
+13. **`join-or-reconnect` 경합·멱등**(봇 교체 리뷰 중 봇과 무관해 남은 지적): 경합 `ROOM_FULL`·`GAME_ALREADY_STARTED` 를 관전으로 삼키는 변이를
+    잡는 테스트가 없었다(BY-8) — 서비스 단위 테스트로 고정. 동시 이중 요청의 늦은 쪽 `ALREADY_IN_ROOM` 은 재접속으로 바꿨다(BY-11).
```

`docs/api.md`:

````diff
 - `RECONNECTED` — 이미 본인이 `playerIds` 에 있음 (Redis 변경 없음, 좌석 보존).
 - `SPECTATING` — IN_GAME 방에 들어왔거나 capacity 가 찬 경우 자동 관전.
 
+D-133 — 방을 읽은 뒤(빈자리 있음) 실제로 앉기 전에 마지막 자리가 차거나 게임이 시작되면 `ROOM_FULL`·`GAME_ALREADY_STARTED`(409)가 관전으로
+바뀌지 않고 그대로 나간다 — 클라 방 화면은 "자리가 없어요 — 관전할까요?" 패널. 같은 사람의 동시 이중 요청(두 탭)은 늦은 쪽도 `ALREADY_IN_ROOM`
+대신 `RECONNECTED` 다.
+
 응답 `200`
 ```json
 { "mode": "JOINED" | "RECONNECTED" | "SPECTATING", "room": { ... } }
 ```
-에러: `ROOM_NOT_FOUND`. 손패 노출 방지 차원에서 비-참여자는 절대 player 로 흡수되지 않음.
+에러: `ROOM_NOT_FOUND`, `ROOM_FULL`·`GAME_ALREADY_STARTED`(위 경합). 손패 노출 방지 차원에서 비-참여자는 절대 player 로 흡수되지 않음.
 
 ### GET `/api/users/{userId}/stats` *(Phase 8D)*
 ELO rating + 누적 전적 + 파생 tier 반환. 식별 정보 (email/phone 등) 노출 0건 — D-02
````

```diff
 
 ---
 
+## 방 초대 미리보기 *(D-133 — 공개 HTML, REST 아님)*
+
+### GET `/rooms/{roomId}` *(공개, 비-`/api`)*
+초대 링크는 방 주소 그대로다. 브라우저에게는 SPA(`index.html`)이고, 카톡 같은 스크래퍼(자바스크립트를 실행하지 않는다)에게는 그 방의
+링크 미리보기다 — 서버가 빌드된 `classpath:static/index.html` 의 `<!--og:start-->`…`<!--og:end-->` 구간(사이트 공통 미리보기)을 그 방의
+Open Graph 로 바꿔 준다(`infra.web.RoomPagePreviewController`, 렌더러 `RoomPreviewRenderer`).
+
+- 응답 `200` `text/html;charset=UTF-8`, `Cache-Control: no-cache`(방마다 내용이 달라 공유 캐시 금지). 요청의 `Accept` 와 무관하다 — 협상하지 않는다
+  (`produces` 를 걸면 `Accept` 가 HTML 이 아닌 스크래퍼가 406 빈 본문을 받는다). 인증 없음(`/api` 밖 default-permit — 스크래퍼는 토큰이 없다),
+  레이트리밋 없음(`/api/**` 전용).
+- 넣는 값: `<title>{제목} — 미르보드카페</title>`, `description`·`og:description` = "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장",
+  `og:title` = "{게임 표시 이름} · {방 이름}"(방 이름이 비면 "{방장}님의 방"), `og:url`(요청 절대 주소 — forwarded 헤더 반영, 쿼리 없음),
+  `og:type=website`, `og:site_name=미르보드카페`, `og:locale=ko_KR`, `twitter:card=summary`. **현재 인원은 넣지 않는다**(카톡이 미리보기를
+  저장해 낡은 숫자가 보인다 — 정원만). 방 이름 40자·방장 20자(코드 포인트)에서 자르고 공백을 접은 뒤 전부 HTML 이스케이프.
+- 방이 없거나(`ROOM_NOT_FOUND`) 조회가 실패하면 틀 그대로 `200`(공통 미리보기의 SPA — 클라가 "방이 없어졌어요"). 방 ID 모양
+  (`[A-Za-z0-9-]{1,64}`)이 아니면 저장소를 보지 않는다. 틀이 없으면(클라 번들을 동봉하지 않은 개발·테스트 빌드) `404`.
+- 새로 드러나는 정보 없음 — 방 이름·방장·게임·정원은 로비 목록(`GET /api/rooms`)에 이미 있다. 방 ID 는 UUID(추측 불가).
+
+---
+
 ## 채팅 신고 *(D-93)*
 
 ### POST `/api/chat/reports`
```

`docs/qa-scenarios.md`:

````diff
    두 줄) 손패 첫 줄이 화면 아래 버튼 줄에 가려지지 않는다. 게스트(`guest-…`)·긴 봇 이름의 "차례" 배지는 가운데 칸 안에서 줄을 바꾸고
    옆줄 좌석에 걸치지 않는다. 원카드 경쟁 버튼이 위쪽 좌석을 최대 3초 가릴 수 있다(설계상 그대로).
 
+## 방 초대 링크 확인 (D-133)
+
+배포 뒤 운영 사이트에서 본다(미리보기는 서버가 빌드된 `index.html` 을 고쳐 주므로 Vite 개발 서버로는 볼 수 없다). 휴대폰 한 대 + 다른
+휴대폰(또는 시크릿 창) 하나로 친구 역할을 한다.
+
+```bash
+# 미리보기 HTML — 토큰 없이 200, 그 방의 og 메타 (방 ID 는 대기실 주소창에서)
+curl -s https://mirboard.fly.dev/rooms/<방ID> | grep -E '<title>|og:|twitter:'
+# → <title>원카드 · 금요일 한 판 — 미르보드카페</title>
+#   <meta property="og:description" content="alice님이 초대했어요 · 4인 방 · 누르면 바로 입장" /> …
+#   og:url 은 https://mirboard.fly.dev/rooms/<방ID> 여야 한다 — http·내부 주소(:8080)면 forwarded 헤더 반영 실패
+curl -sI https://mirboard.fly.dev/rooms/<방ID> | grep -i cache-control   # → no-cache
+# 없는 방 → 사이트 공통 미리보기("미르보드카페 — 티츄·스컬킹·원카드") 그대로
+curl -s https://mirboard.fly.dev/rooms/00000000-0000-0000-0000-000000000000 | grep og:title
+# Accept 가 HTML 이 아니어도 406 이 아니라 같은 미리보기(스크래퍼마다 Accept 가 다르다)
+curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -H 'Accept: application/json' https://mirboard.fly.dev/rooms/<방ID>
+# → 200 text/html;charset=UTF-8
+```
+
+1. **휴대폰 공유창**: 대기실 "초대 링크" → 휴대폰 공유창이 뜨고 카카오톡이 목록에 있다. 보내고 돌아오면 "초대 링크를 보냈어요". 공유창을
+   닫으면(취소) 아무 알림이 없다. 데스크톱 브라우저(포인터가 마우스)는 Chrome·Edge·Safari 처럼 공유창이 있어도 공유창 없이 "링크를 복사했어요"
+   — 붙여 넣으면 `https://…/rooms/<방ID>`. 게임이 시작되면(게임판) 초대 버튼이 없다. 꽉 찬 대기실이면 버튼 옆 설명이 "자리가 다 찼어요 —
+   링크를 받은 친구는 관전으로 들어와요"다 — 게스트로 만든 봇 채우기 방(만들자마자 봇으로 꽉 찬다)도 같다(봇은 자리를 비켜 주지 않는다).
+2. **카톡 미리보기**: 카톡 말풍선에 "{게임} · {방 이름}"과 "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장"이 뜬다(이미지 없는 글자
+   미리보기). 방 이름에 `"`·`<`·이모지를 넣어도 깨지지 않는다. 카톡은 같은 주소의 미리보기를 한동안 저장한다 — 바뀐 내용을 다시 보려면
+   카카오 개발자 "공유 디버거"에서 캐시를 지운다.
+3. **게스트로 바로 입장**: 친구 휴대폰(로그인 안 됨)에서 링크 → 로그인 화면 맨 위에 "방에 초대받았어요 — 가입하지 않아도 게스트로 바로
+   들어갈 수 있어요", 그 아래 "게스트로 바로 입장"이 아이디 입력란보다 **앞**에 있다 → 누르면 그 방 대기실(빈자리면 참가자로). 같은 화면에서
+   로그인해도, "회원가입"으로 넘어가 가입해도 그 방으로 간다. 그냥 `/login` 으로 온 사람은 지금 배치 그대로(폼 → 또는 → "게스트로 바로
+   체험하기"). 이미 로그인한 브라우저에서 링크를 열면 로그인 화면 없이 바로 그 방이다(참가자면 재접속). 같은 링크를 두 탭에서 거의 동시에
+   열어도 둘 다 그 방이다("User already in room" 같은 문구가 보이면 실패).
+4. **카톡 앱 내 브라우저**: 카톡에서 링크를 누르면 카톡 안 브라우저로 열린다 — 로그인 토큰이 기본 브라우저와 따로라 처음이면 3번의 로그인
+   화면이다. 그 안에서 "초대 링크"를 눌러 공유창·복사가 막히면 "직접 복사해 주세요" 입력란에 링크가 **선택된 채** 보인다(길게 눌러 복사).
+5. **자리 없음 → 바로 관전 + 안내**: 꽉 찬 대기실(봇 자리 포함)·이미 시작한 방의 링크를 열면 묻지 않고 관전으로 들어가 "관전 중" 대기실이나 관전자
+   게임판이 보이고, 화면 맨 위에 "자리가 없어 관전으로 들어왔어요"(대기실) / "게임이 이미 시작돼 관전으로 들어왔어요"(게임판)가 뜬다 — [닫기] 또는
+   8초 뒤 사라진다. 게임판(채팅·버튼)을 가리지 않는지, 390px 폭에서 좌우 여백이 있는지 본다. 같은 탭에서 새로고침하면, 그리고 허브에서 "구경하기"로
+   들어가면 안내가 뜨지 않는다. 열려는 순간 마지막 자리가 차는 경합이면 "자리가 없어요 — 관전할까요?" 패널 → [관전하기] 로 같은 화면(안내 없음),
+   [메인으로] 로 허브.
+6. **방 없음**: 모두 나가 사라진 방의 링크 → "방이 없어졌어요" + [메인으로] ("Room not found" 같은 서버 문구가 보이면 실패). 그 링크의 카톡
+   미리보기는 사이트 공통 미리보기다(새로 공유한 경우 — 이미 저장된 말풍선은 예전 방 미리보기가 남을 수 있다).
+
+돌아갈 주소 위조(`//evil.com`·`/\evil.com`·`https://…`·`javascript:`)는 화면에서 만들기 어려워 단위 테스트(`returnPath.test.ts`·
+`LoginPage.test.tsx`·`RegisterPage.test.tsx`)가 맡는다.
+
 ## 분산 시연 (멀티 인스턴스, Phase 6D)
 
 기본은 단일 인스턴스 + `mirboard.messaging.gateway=in-memory`. 멀티 인스턴스에서
````

- [ ] **Step 2: 현황·README·케이스 스터디·CLAUDE.md**

`docs/implementation-status.md`:

```diff
 # Mirboard 구현 현황
 
 > 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
-> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-132),
+> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-133),
 > 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
 > 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
 > `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`·
```

```diff
 | 13 | 관측성 (Prometheus 게임별 차원, Grafana, Sentry) | ✅ | `infra.metrics`, `infra.config.SentryConfig`, `ops/grafana` (D-107) |
 | 14 | **`GameEngine` 포트 (멀티게임 기반)** | ✅ | `domain.game.core.GameEngine`, `infra.ws.GameEngineProvider`, `TichuGameEngine` |
 | 15 | 테이블 좌석 배치 (원카드·스컬킹 시계 방향 테이블형 좌석 + "다음" 표시·방향 화살표, 티츄 +1 을 왼쪽에 — 클라만) | ✅ | `client/src/components/seatOrder.ts`(`tableLayout`), `features/tichu/seatView.ts`, `docs/plans/table-seating.md` (D-132) |
+| 16 | 방 초대 링크 (공유창·복사·수동, 방마다 다른 카톡 미리보기, 로그인·게스트·가입 뒤 그 방으로, 자리 없음 → 바로 관전 + 안내) | ✅ | `features/lobby/InviteLinkButton.tsx`·`shareRoomLink.ts`·`SpectateNotice.tsx`, `features/auth/returnPath.ts`, `infra.web.RoomPagePreviewController`, `docs/plans/room-invite.md` (D-133) |
 
 설계 단계(Phase 1)부터 클라이언트 통합·UI 리디자인(Phase 20)까지 로드맵 항목이 완료
 표시되어 있다(`docs/plans/mvp-roadmap.md`).
```

```diff
 - **퇴장** `POST /api/rooms/{id}/leave` — WAITING은 즉시 퇴장, IN_GAME은 탈주 처리(§8).
   빈 방은 `room_leave.lua`/`room_delete.lua` 가 관련 키까지 정리.
 - **재입장(멱등)** `POST /api/rooms/{id}/join-or-reconnect` — JOINED/RECONNECTED/SPECTATING
-  모드 반환. 나갔다 재입장 시 ALREADY_IN_ROOM 버그 제거(Phase 18, D-74).
+  모드 반환. 나갔다 재입장 시 ALREADY_IN_ROOM 버그 제거(Phase 18, D-74). 읽은 뒤 자리가 찬 경합은 `ROOM_FULL`·`GAME_ALREADY_STARTED`
+  그대로(관전으로 바꾸지 않는다 — 방 화면 패널), 같은 사람의 동시 이중 요청은 늦은 쪽도 RECONNECTED(D-133).
 - **좌석 정책** `PUT /api/rooms/{id}/team-policy` (SEQUENTIAL/RANDOM/MANUAL, 호스트만).
   이름과 달리 실체는 좌석 순서 정책이라 **게임을 가리지 않는다** — `RANDOM` 은 시작 직전
   좌석을 섞고, 개인전에서도 좌석 순서 = 턴 순서다(D-106 정정).
```

```diff
 - **중단** `POST /api/rooms/{id}/abort` (호스트, in-game → FINISHED).
 - **관전** `POST`/`DELETE /api/rooms/{id}/spectate` — 관전자는 손패·액션 불가(§상태 은닉).
 - **재동기화** `GET /api/rooms/{id}/resync` — tableView + privateHand + seq(§7).
+- **초대 링크(D-133)** — 방 주소 그대로(`/rooms/{id}`)를 대기실 "초대 링크"가 공유창(Web Share API) → 복사 → 링크가 선택된 입력란 순으로
+  건넨다. 서버 `GET /rooms/{id}`(공개 HTML, `/api` 밖)가 빌드된 `index.html` 의 표식 구간을 그 방의 Open Graph(게임·방 이름·방장·정원 —
+  현재 인원 제외)로 바꿔 카톡 미리보기가 방마다 다르다(요청의 Accept 와 무관). 방이 없거나 틀이 없으면 기존 SPA 그대로(`docs/api.md`).
+  공유창은 휴대폰·태블릿(굵은 포인터)만 — PC 는 복사. 꽉 찬 대기실(봇 자리 포함 — 봇은 자리를 비켜 주지 않는다)의 초대 설명은 "링크를 받은
+  친구는 관전으로 들어와요".
 
 동시성 보장: capacity 위반 0건(`RoomServiceConcurrencyIT`, 멀티스레드 join).
+`join-or-reconnect` 의 입장 갈래(봇까지 꽉 찬 방 관전·경합 그대로·동시 이중 요청 재접속)는 `RoomServiceJoinOrReconnectTest`(Docker 불필요, D-133).
 관련 테스트: `RoomControllerIntegrationTest`, `RoomResyncIntegrationTest`,
 `RoomCapacityIntegrationTest`(D-99 인원 가변 — 가변 게임은 테스트용 fake `GameDefinition`).
 
```

```diff
 - 상태: Zustand 스토어(`authStore`, `tichuStore`, `roomChatStore`, `themeStore`, `effectStore`).
 - **게스트 체험(D-117)**: 로그인 화면 「게스트로 바로 체험하기」 → `POST /api/auth/guest`. 헤더 '게스트'
   배지, 아바타·비번 변경 비활성, 로그아웃 시 "다시 들어올 수 없어요" 확인.
+- **로그인 뒤 그 방으로(D-133)**: 보호 경로가 `/login` 으로 보낼 때 가려던 경로를 `state.from` 에 싣고, 로그인·게스트 입장·가입 뒤(그리고
+  로그인 상태로 `/login` 에 오면) `safeReturnPath(from) ?? '/games'` 로 `replace` — 사이트 안 경로만(오픈 리다이렉트 금지). 방 경로에서 왔으면
+  "방에 초대받았어요" 안내와 "게스트로 바로 입장"을 로그인 폼 앞에. 방 화면은 서버가 관전으로 받았으면(`mode=SPECTATING` + 진행 중·꽉 찬 대기실)
+  맨 위에 "…관전으로 들어왔어요" 안내(`SpectateNotice` — 세 렌더 분기 모두, 탭에서 한 번: 새로고침·허브 "구경하기"·패널 관전에는 안 뜸), 첫 입장이
+  `ROOM_FULL`·`GAME_ALREADY_STARTED`(확인과 입장 사이 경합)면 "자리가 없어요 — 관전할까요?", `ROOM_NOT_FOUND` 면 "방이 없어졌어요" 패널. 방 화면을
+  떠나면 탭 제목을 "Mirboard" 로 되돌린다.
 - **튜토리얼(D-121)**: 게임별 레지스트리(`features/tutorial/gameTutorials.ts`) + 게임 중립
   `TutorialDialog`/`useTutorialGate`. 진입점은 허브 게임 카드 '게임 방법', 대기실 헤더 버튼 + 그 게임
   대기실 첫 입장 1회 자동 노출, 스컬킹 게임판 '규칙'(수동). 스컬킹은 13단계(단계마다
```

```diff
 
 ## 13. 테스트 현황
 
-- **서버**: **1327건** (D-131 공개 전환 시점 실측 — 최종 리뷰 반영 뒤, 실패 0, 그중 Docker 불필요 1121건(84%), 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 게임별 내역(같은 실측): 티츄 도메인 312건(그중 Docker 불필요 304건), 스컬킹 도메인 375건(371건), 원카드 도메인 191건(187건) + 원카드 서버 경로 IT 10건(`OneCardRaceIT`·`OneCardBotMatchSimulationIT`)·엔진 타이머 단위 17건. 매치 기록 실패 격리는 세 게임 모두 `infra.ws.*RecorderFailureTest`(8건, D-130·D-131). 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
+- **서버**: **1371건** (D-133 방 초대 링크 시점 — 사전 리뷰 반영·봇 자리 비켜주기 철회 뒤. **잠정(D-133)**: 그중 Docker 불필요 1159건(84%)은 실측(실패 0), Docker IT 212건은 직전 전체 실측(1362건, 실패 0) 그대로 — 이번 반영에 IT 변경 없음, OrbStack 이 멈춰 전체 `--rerun` 은 Docker 복구 뒤 확정. 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip. D-131 공개 전환 시점 1327건 + 방 미리보기 `infra.web.*` 39건(그중 Docker 불필요 33건) + 입장 갈래 `RoomServiceJoinOrReconnectTest` 5건(Docker 불필요)). 게임별 내역(같은 실측 — D-133 은 게임 도메인 무변경): 티츄 도메인 312건(그중 Docker 불필요 304건), 스컬킹 도메인 375건(371건), 원카드 도메인 191건(187건) + 원카드 서버 경로 IT 10건(`OneCardRaceIT`·`OneCardBotMatchSimulationIT`)·엔진 타이머 단위 17건. 매치 기록 실패 격리는 세 게임 모두 `infra.ws.*RecorderFailureTest`(8건, D-130·D-131). 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
   단위(룰 엔진·족보·ELO·JWT·카탈로그·포트 어댑터) + 통합(Testcontainers PostgreSQL 16/
   Redis — auth/rooms/STOMP/봇/동시성/매치 영속/2-인스턴스 인계).
   집계 방식: `./gradlew :server:test --rerun` 뒤 `server/build/test-results/test/TEST-*.xml` 을 합한다. 클래스는 **파일 이름의 바깥 클래스**로
```

```diff
   재현 스크립트는 `docs/case-study-multi-game.md` 부록 (6).
 - 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 세 봇
   평가, ~20s). 스컬킹·원카드 매치 기록 IT(D-115·D-128)는 Docker 가 필요해 `rules` 에서 뺐다.
-- **클라이언트**: **748건 / 65파일** (D-132 테이블 좌석 배치 시점 실측 — 사전·최종 리뷰 반영 뒤, 배치 함수·티츄 매핑·말풍선·원카드·스컬킹 게임판 88건 포함, 실패 0). Vitest + RTL — 스토어
+- **클라이언트**: **865건 / 72파일** (D-133 방 초대 링크 시점 실측 — 사전 리뷰·결정 A 반영, 봇 자리 비켜주기 철회 뒤, 돌아갈 주소·공유 함수·로그인/가입·라우팅·방 화면·초대 버튼·관전 안내·허브 관전 기록·`index.html` 117건 포함, 실패 0. D-132 시점 748건 / 65파일). Vitest + RTL — 스토어
   리듀서, 족보 타입, 카드 에셋 매핑 등.
 - 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.
 - **밀폐성(D-113)**: IT 는 Testcontainers 로 자기 Postgres/Redis 를 띄우고 compose 에 기대지
```

`README.md`:

```diff
 [![Deploy](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml/badge.svg)](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml)
 
 Spring Boot 4 / Java 25 · PostgreSQL · Redis · React + TypeScript ·
-서버 테스트 1327건 / 클라 748건
+서버 테스트 1371건 / 클라 865건
+<!-- 잠정(D-133): 서버 1371 = Docker 불필요 1159 실측 + Docker IT 212(직전 전체 실측 1362 의 IT 수 — 이번에 IT 변경 없음). Docker 복구 뒤 ./gradlew :server:test --rerun 으로 확정 -->
 
 **라이브**: https://mirboard.fly.dev — 로그인 화면 「게스트로 바로 체험하기」로 가입 없이 들어갈 수 있습니다.
 유휴 시 머신이 멈춰 첫 접속에 약 30초 걸립니다(콜드 스타트).
```

```diff
 | 특징 | 족보 조합, 폭탄 인터럽트, 카드 패스 | 승수 예측 후 동시 공개, 비추이적 트릭 판정 | 공격 누적·반격, 1장 남으면 **"원카드!/잡기!" 실시간 경쟁**(무작위 위치 버튼, 서버 엔진 타이머), 턴 카운트다운 |
 
 - **로비/방** — 방 생성(인원 선택)·입장·관전·랭킹·채팅. 게임 시작은 정원 충족 + **전원 준비**
+- **초대 링크** — 대기실에서 방 주소를 카톡 등으로 공유(방마다 다른 미리보기), 받은 친구는 가입 없이 게스트로 바로 그 방에 앉는다(자리가 다
+  찼으면 관전, D-133)
 - **봇** — 빈 좌석을 자동 충족. 세 게임 모두 공개 정보만 보는 결정적 휴리스틱이다(티츄 D-118 —
   랜덤 봇 상대 100전 100승, 스컬킹 D-119 — 1:무작위 2~8인 승률 0.92~1.00, 원카드 D-128 — 무작위 봇 대비 4인 승률 0.92).
   원카드 봇은 경쟁 창마다 추첨한 반응 시간(기본 1.0~2.5초)에 누른다. 재현:
```

`docs/case-study-multi-game.md`:

```diff
 `SkullKingInvariantChecker` 를 통과 케이스뿐 아니라 **고의로 위반시킨 상태 8건**
 (+ 오탐 방지 통과 2건)으로 검출 능력 자체를 테스트했습니다.
 
-**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1327건 중 1121건(84%)이 Docker 불필요**합니다(D-131 시점, 부록 (6)).
+**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1371건 중 1159건(84%)이 Docker 불필요**합니다(D-133 시점, 부록 (6)).
+<!-- 잠정(D-133): 1159 는 Docker 불필요 실측, 1371 은 + Docker IT 212(직전 전체 실측 1362 기준). Docker 복구 뒤 부록 (6) 으로 확정 -->
 자랑이 아니라 §2 의 2계층 분리가 값을 냈다는 인과 증거입니다.
 
 `코드:` `skullking/trick/TrickResolver.java` · `skullking/invariant/SkullKingInvariantChecker.java` ·
```

```diff
     if not re.search(r'(IT|IntegrationTest)$', outer): d += n
 print(f"tests={t} skipped={s} failed={f} dockerfree={d} ({d/t:.0%})")
 EOF
-# → tests=1327 skipped=5 failed=0 dockerfree=1121 (84%)   (D-131 시점)
+# → tests=1371 skipped=5 failed=0 dockerfree=1159 (84%)   (D-133 시점 — D-131 시점 1327 / 1121) 잠정(D-133): Docker 복구 뒤 확정
 
 # (7) 세 번째 게임(§8) — 인프라가 이름을 아는가 / 서버 통합에서 바뀐 공용 파일 / 클라 통합 규모
 grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra   # → 0건
```

`CLAUDE.md`:

```diff
 
 **Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + **게임 3종**: 티츄(4인 2:2 팀전), 스컬킹(2~8인 개인전), 원카드(2~6인 개인전 + "원카드!/잡기!" 실시간 경쟁, D-123~D-131).
 
-현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M7 전부 완료, 결정 이력 D-132까지). 로비/방 → 세 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소. 세 번째 게임 원카드는 `GameEngine` 포트 확장 1건(엔진 타이머 `timer`/`onTimer`, D-128)과 D-126 의 `sequenced()` 재정의만으로 붙었고(카탈로그 정의 `GameDefinition` 의 방 만들기 처음 선택 기본값 2개 `defaultPlayers()`·`defaultTurnSeconds()` 는 별도 — D-130, 기본 구현이 기존 동작. 인프라 grep `onecard` 0건) D-131 에서 공개됐다(`MIRBOARD_ONECARD_STATUS` 로 되돌림 — `docs/deploy.md`).
+현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M7 전부 완료, 결정 이력 D-133까지). 로비/방 → 세 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소. 세 번째 게임 원카드는 `GameEngine` 포트 확장 1건(엔진 타이머 `timer`/`onTimer`, D-128)과 D-126 의 `sequenced()` 재정의만으로 붙었고(카탈로그 정의 `GameDefinition` 의 방 만들기 처음 선택 기본값 2개 `defaultPlayers()`·`defaultTurnSeconds()` 는 별도 — D-130, 기본 구현이 기존 동작. 인프라 grep `onecard` 0건) D-131 에서 공개됐다(`MIRBOARD_ONECARD_STATUS` 로 되돌림 — `docs/deploy.md`).
 
 - **서버** `server/` (Spring Boot 4 / Java 25, Gradle): 도메인 `domain.lobby`·`domain.game.{core,tichu,skullking,onecard,scoring}`, 인프라 `infra.{rest,ws,bot,messaging,metrics,config,web}`.
 - **클라이언트** `client/` (Vite + React 18 + TS, Zustand, @stomp/stompjs, Tailwind+shadcn).
```

```diff
   (Redis HASH) + `CHIPS_SETTLED` 공개 이벤트(테이블 공개 정보), 정산은 신규 `RoomChipService`.
   매치 정상 종료는 FINISHED 대신 WAITING 복귀(리매치), 탈주는 FINISHED 유지. 판돈은 Redis
   room 해시 고정 → `TichuMatchCompleted.stake` 로 정산 전달.
+- D-133: **초대 링크 = 방 주소 그대로**(`/rooms/{id}`). 대기실 "초대 링크"가 공유창 → 복사 → 선택된 입력란, 서버 `GET /rooms/{id}`(공개 HTML,
+  `infra.web.RoomPagePreviewController`)가 빌드된 `index.html` 의 `<!--og:start-->`…`<!--og:end-->` 를 그 방의 미리보기로 바꾼다(표식을 지우지 말 것).
+  로그인 안 된 친구는 보호 경로가 실은 `state.from` 으로 로그인·게스트·가입 뒤 그 방으로(`safeReturnPath` — 사이트 안 경로만).
+  꽉 찬 대기실(봇 자리 포함)·시작된 방은 `join-or-reconnect` 가 지금처럼 관전으로 받고 방 화면 위에 "…관전으로 들어왔어요"(`SpectateNotice`, 탭에서
+  한 번). **봇은 사람에게 자리를 비켜 주지 않는다**(기각 — 사람과 할 때는 봇을 없앤다).
 
 ## 자주 쓰는 명령
 
```

```diff
 ./gradlew :server:test --tests "com.mirboard.infra.bot.TurnRemainingTest"   # resync 남은 턴 시간·재무장을 락 안에서 (D-131)
 ./gradlew :server:test --tests "com.mirboard.infra.ws.*RecorderFailureTest"   # 매치 기록 실패 격리 3게임 (D-130·D-131)
 ./gradlew :server:test --tests "com.mirboard.infra.bot.OneCardRaceIT"   # 원카드 경쟁 창 서버 경로 (D-128, Docker)
+./gradlew :server:test --tests "com.mirboard.infra.web.*"   # 방 초대 미리보기 렌더러·틀·컨트롤러 + IT 1개 (D-133, IT 만 Docker)
 ./gradlew :server:test --tests "com.mirboard.infra.ws.TichuEventStreamIT"   # 티츄 공개 순번 무구멍 + 플레이당 resync 측정 → server/build/d126-resync-stats.txt (D-126, Docker)
 MIRBOARD_BOT_EVAL=1 ./gradlew :server:test --rerun --tests "com.mirboard.domain.game.tichu.bot.HeuristicBotEvaluationTest"   # 티츄 봇 대형 평가 (~1m30s)
 ./gradlew :server:test --tests "com.mirboard.domain.game.tichu.DealingLifecycleTest"
```

- [ ] **Step 3: 실측**

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test` 뒤 `npm --prefix client run build` 뒤 `grep -c "og:start\|og:end" client/dist/index.html`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  72 passed (72)` · `Tests  865 passed (865)`, 빌드 성공(`dist/index.html` ≈0.97 kB · CSS ≈66.9 kB · JS ≈576.9 kB — 500 kB 청크 경고는 기존), 마지막 `2`(표식이 빌드 뒤에도
남고 Vite 가 넣는 `<script>`·`<link>` 는 `og:end` 뒤). `client/dist/` 는 빌드 산출물이라 커밋하지 않는다(gitignore).

Run: `export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 뒤 `./gradlew :server:test --rerun` 뒤 `awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ (failures|errors)=$/)f+=$(i+1)}} END{print "tests="t" failed="f}' server/build/test-results/test/TEST-*.xml`
Expected: `BUILD SUCCESSFUL`, `tests=1371 failed=0`(대형 봇 평가 5건 skip 포함 — 케이스 스터디 부록 (6) 결과 `tests=1371 skipped=5 failed=0 dockerfree=1159 (84%)` 와 같다).

- [ ] **Step 4: 커밋**

```bash
git add docs/plans/room-invite.md \
  docs/api.md \
  docs/qa-scenarios.md \
  docs/implementation-status.md \
  README.md \
  docs/case-study-multi-game.md \
  CLAUDE.md
git commit -m "docs(D-133): 방 초대 링크 — API(공개 HTML 미리보기), QA 절, 현황·README·케이스 스터디 수치, CLAUDE.md, 설계 상태 구현

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Phase Gate (컨트롤러)**

구현 에이전트가 아니라 컨트롤러가 한다. `git log --oneline 919097f..HEAD` 로 태스크 커밋 6개(리뷰 수정 커밋이 있으면 그만큼 더)를 확인하고 최종 리뷰를 거친 뒤 사용자에게
보고한다 — **사용자 승인 전에는 main 에 병합하지 않는다**(푸시하면 자동 배포). 배포 뒤 운영 사이트에서 `docs/qa-scenarios.md` "방 초대 링크 확인 (D-133)"을 본다(휴대폰 공유창,
카톡 미리보기, 게스트로 바로 입장, 꽉 찬 방 → 관전 안내, 방 없음, 운영 `curl -H 'Accept: application/json' https://mirboard.fly.dev/rooms/<방ID>` 가 200 HTML).
