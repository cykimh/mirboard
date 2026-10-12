# 방 초대 링크 — 링크 복사·공유, 방마다 다른 카톡 미리보기, 로그인 뒤 그 방으로 (D-133) — 설계

> 상태: **설계 승인** · 2026-10-12 · 결정 이력 `docs/decisions.md` D-133(계획 Task 1 에서 추가)
> 근거: 사용자 요청("게임 방 만들고 링크 복사해서 카톡으로 공유") · 방 입장 멱등 `join-or-reconnect`(D-74) · 게스트 체험(D-117) · SPA 서빙 `StaticSpaConfig`(D-39)

## 1. 목표와 성공 기준

방을 만든 사람이 버튼 하나로 방 링크를 카카오톡 등으로 보내고, 받은 친구가 링크를 누르면 — 가입하지 않았어도 — 게스트로 바로 그 방에 앉는다.
카톡 말풍선에는 그 방의 미리보기(게임·방 이름·초대한 사람·정원)가 뜬다.

| # | 기준 | 확인 방법 |
| --- | --- | --- |
| 1 | 대기실의 "초대 링크" 버튼이 휴대폰에서는 공유창(카톡 포함)을, 공유창이 없으면 링크 복사를, 복사도 막히면 선택된 링크를 보여 준다 | 컴포넌트 테스트(공유·복사·실패 폴백) |
| 2 | 로그인 안 된 친구가 `/rooms/<방ID>` 를 열면 로그인 화면에 "방에 초대받았어요" 안내와 앞쪽 "게스트로 바로 입장"이 보이고, 게스트 입장·로그인·가입 뒤 그 방으로 돌아간다 | 라우팅·로그인 화면 테스트 |
| 3 | 돌아갈 주소는 사이트 안 경로만 허용한다(외부로 튕기는 오픈 리다이렉트 없음) | 단위 테스트 |
| 4 | 방이 꽉 찼거나(`ROOM_FULL`) 이미 시작했으면(`GAME_ALREADY_STARTED`) "자리가 없어요 — 관전할까요?"와 관전·메인으로 버튼 | 방 화면 테스트 |
| 5 | `GET /rooms/<방ID>`(HTML)의 `<head>` 에 그 방의 Open Graph 미리보기가 들어간다 — 제목 "{게임 표시 이름} · {방 이름}", 설명 "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장". 사용자 입력은 HTML 이스케이프 | 서버 단위·MVC 테스트 |
| 6 | 방이 없거나 화면 틀(`static/index.html`)이 없으면 기존 동작(사이트 공통 미리보기의 SPA)을 그대로 돌려준다 — 화면이 깨지지 않는다 | 서버 테스트 |
| 7 | 다른 모든 페이지에도 사이트 공통 미리보기("미르보드카페 — 티츄·스컬킹·원카드 …")가 있다 | `index.html` 원문 단언 |
| 8 | 티츄·스컬킹·원카드의 기존 방 입장·관전·재접속 흐름 회귀 0 | 클라·서버 전체 테스트 |

## 2. 확정 결정 (사용자, 2026-10-12)

| 항목 | 결정 |
| --- | --- |
| 공유 방식 | 링크 복사 + 휴대폰 공유창(Web Share API). 카카오 SDK 공유 버튼은 하지 않음(앱 키 등록 필요 — 후속) |
| 처음 온 친구 | 로그인 화면 → "게스트로 바로 입장"(앞쪽) → 그 방으로. 로그인·가입해도 그 방으로 |
| 자리 없음 | 관전으로 들어가기 제안 |
| 미리보기 | 방마다 다른 미리보기, **공유 링크 = 방 주소 그대로**(`/rooms/<방ID>` — 주소창을 복사해 보내도 미리보기가 뜬다) |

기각: 초대 전용 주소 `/invite/<방ID>`(미리보기 페이지 → 방으로 넘김) — 화면 틀에 묶이지 않는 장점이 있지만 사용자는 방 주소 그대로를 골랐다.

## 3. 지금 구조 (조사, 2026-10-12)

- 라우트 `client/src/App.tsx`: `/rooms/:roomId` 는 `ProtectedRoute` 안 — 토큰이 없으면 `<Navigate to="/login" replace />` 로 **가려던 주소를 잃는다**.
  `LoginPage` 는 로그인·게스트 입장 뒤, 그리고 이미 로그인 상태로 `/login` 에 오면 늘 `/games` 로 보낸다(D-122 의 replace·로그인 상태 가드).
- `RoomPage` 는 들어오면 한 번 `roomsApi.joinOrReconnect` 를 부르고, 실패하면 서버 메시지를 오류로 보인다. 관전은 `roomsApi.spectate`(허브의 관전 버튼이 쓴다).
  join 오류 코드: `ROOM_FULL`·`GAME_ALREADY_STARTED`·`ROOM_NOT_FOUND`·`ALREADY_IN_ROOM`(`GlobalExceptionHandler`).
- 서버 `infra/config/StaticSpaConfig` 가 `/**` 정적 파일을 찾고 없으면 `static/index.html` 로 대체(SPA history 라우팅). 컨트롤러 매핑이 리소스 핸들러보다
  먼저 잡는다. `SecurityConfig` 는 `/api` 밖을 기본 허용. 운영 `server.forward-headers-strategy: framework`(Fly 프록시 헤더로 절대 주소 계산 가능).
- `client/index.html` 에는 `<title>Mirboard</title>` 뿐 — 미리보기 메타가 없다. 카톡 스크래퍼는 자바스크립트를 실행하지 않으므로 메타는 서버가 준
  HTML 에 있어야 한다. 미리보기 이미지로 쓸 PNG·JPG 가 없다(카드 그림은 SVG — 카톡이 받지 않는다) → 이번엔 글자 미리보기.
- 방 ID 는 UUID(추측 불가). 방 이름·방장·게임은 로그인한 누구에게나 로비 목록에 이미 보인다.

## 4. 설계

### 4.1 초대 버튼 (클라)

- 대기실(`RoomPage` 의 WAITING 화면) 머리에 "초대 링크" 버튼. 링크 = `${window.location.origin}/rooms/${roomId}`.
- 순서: ① `navigator.share` 가 있으면 `navigator.share({ title, text, url })`(취소 `AbortError` 는 조용히 무시) ② 없거나 실패하면
  `navigator.clipboard.writeText(url)` → "링크를 복사했어요" 짧은 알림 ③ 복사도 실패하면(권한·비보안 문맥·일부 앱 내 브라우저) 링크가 선택된 입력란을
  보여 "직접 복사해 주세요". `title` = "{게임 표시 이름} 같이 해요", `text` = "{방 이름} — 미르보드카페".
- 공유·복사는 순수 함수(`shareRoomLink(url, meta, env)` — `navigator` 를 주입받아 결과 `'shared' | 'copied' | 'manual' | 'cancelled'` 를 돌려줌)로 두고
  버튼은 결과만 그린다(테스트 쉬움).
- 게임 중(IN_GAME) 화면에는 두지 않는다(초대는 시작 전 — 자리가 없다).

### 4.2 로그인 뒤 그 방으로 (클라)

- `ProtectedRoute` 가 `/login` 으로 보낼 때 `state: { from: 지금 경로(pathname + search) }` 를 싣는다.
- 순수 함수 `safeReturnPath(from: unknown): string | null` — 문자열이고 `/` 로 시작하며 `//`·`/\` 로 시작하지 않고 `/login`·`/register` 가 아닐 때만 그대로,
  아니면 null(오픈 리다이렉트·되돌이 방지). 이동 목적지 = `safeReturnPath(from) ?? '/games'`.
- `LoginPage`: 로그인·게스트 입장 뒤, 그리고 이미 로그인 상태로 왔을 때(D-122 가드) 그 목적지로 `replace`. `from` 이 `/rooms/` 로 시작하면 "방에 초대받았어요 —
  가입하지 않아도 게스트로 바로 들어갈 수 있어요" 안내를 위에 두고 "게스트로 바로 입장" 버튼을 로그인 폼보다 **앞**에 둔다(일반 진입은 지금 배치 그대로).
  "회원가입" 링크도 `state.from` 을 넘기고, `RegisterPage` 는 가입 뒤 같은 규칙으로 이동한다.
- `location.state` 는 주소에 드러나지 않고(카톡 앱 내 브라우저에서도 같은 탭 기록에 남음), 새로고침하면 사라질 수 있다 — 그때는 지금처럼 `/games`.

### 4.3 자리 없음 → 관전 제안 (클라)

- `RoomPage` 의 첫 `joinOrReconnect` 가 `ROOM_FULL`·`GAME_ALREADY_STARTED` 로 실패하면 오류 문구 대신 패널: "자리가 없어요 — 관전할까요?" + [관전하기]
  (`roomsApi.spectate` 뒤 방 다시 불러오기 — 허브 관전과 같은 경로) + [메인으로]. `ROOM_NOT_FOUND` 는 "방이 없어졌어요" + [메인으로]. 그 밖의 오류는 지금처럼.

### 4.4 방마다 다른 미리보기 (서버)

- `infra/web` 에 `RoomPagePreviewController` — `GET /rooms/{roomId}`(`produces = text/html`, 인증 없음 — `/api` 밖). 화면 틀 = 빌드된 `classpath:static/index.html`
  (`IndexHtmlTemplate` 이 시작할 때 한 번 읽어 두는 빈 — 없으면 `Optional.empty()`).
- `client/index.html` 의 `<head>` 에 표식 `<!--og:start-->` … `<!--og:end-->` 로 감싼 **사이트 공통 미리보기**(기준 7)를 둔다. 컨트롤러는 방이 있으면 이 구간을
  방 미리보기로 바꿔 돌려준다: `og:type=website`, `og:site_name=미르보드카페`, `og:title`, `og:description`, `og:url`(요청 절대 주소 — forwarded 헤더 반영),
  `og:locale=ko_KR`, `twitter:card=summary`, `<title>`(같은 제목 + " — 미르보드카페"). 모든 값은 `HtmlUtils.htmlEscape`(속성 안 따옴표 포함).
- 값: 게임 표시 이름은 `GameRegistry` 의 정의(인프라에 게임 이름 분기 없음). 방 이름이 비면 "{방장}님의 방". 방장 = 방장 사용자 이름(게스트는 `guest-…` 그대로).
  **현재 인원은 넣지 않는다** — 카톡이 미리보기를 한동안 저장해 낡은 숫자가 보인다(정원만).
- 방이 없거나(`RoomNotFoundException`) 틀이 없거나 표식이 없으면: 틀이 있으면 틀 그대로(공통 미리보기의 SPA — 클라가 오류를 보인다), 틀이 없으면 기존
  리소스 처리와 같게 404. 예외를 HTML 응답 밖으로 새지 않게 한다.
- `Cache-Control: no-cache`(방마다 내용이 달라 공유 캐시 금지 — 브라우저는 재검증). 응답 크기는 틀 그대로라 작다. 레이트리밋(`/api/**` 전용)은 걸지 않는다 —
  방 하나당 Redis 읽기 한 번이고 방 ID 는 추측 불가(후속: 남용 관측 시 IP 버킷).
- 개인정보: 새로 드러나는 정보 없음(방 이름·방장·게임·정원은 이미 로비 목록에 공개). 링크를 받은 사람만 본다.

### 4.5 문서

- `docs/decisions.md` D-133(첫 커밋), `docs/api.md`(공개 HTML `GET /rooms/{roomId}` 미리보기 — REST 아님 표시), `docs/qa-scenarios.md`("방 초대 링크" — 휴대폰 공유창,
  카톡 미리보기, 게스트로 바로 입장, 자리 없음 → 관전), `docs/implementation-status.md` 한 줄, `CLAUDE.md` 사용자 플로우에 초대 링크 한 줄, 수치를 인용한 문서.

## 5. 경계·예외

| 경우 | 처리 |
| --- | --- |
| 이미 그 방 참가자가 링크를 다시 엶 | `joinOrReconnect` 멱등 — 그대로 재접속 |
| 다른 방에 들어가 있는 사람이 링크를 엶 | 기존 서버 규칙(`ALREADY_IN_ROOM` 등)을 그대로 보인다 — 이번 범위 밖 |
| 카톡 앱 내 브라우저 | 공유창이 없으면 복사 → 복사도 막히면 선택된 입력란. 로그인 토큰은 그 브라우저 저장소에 따로 — 처음이면 게스트 입장 |
| 새로고침으로 `state.from` 이 사라짐 | `/games` (지금 동작) |
| 방 이름에 `"`·`<`·`&` | 이스케이프되어 메타 속성이 깨지지 않음 |
| 방이 끝나 사라진 뒤 미리보기 | 공통 미리보기 + 방 화면이 "방이 없어졌어요" |

## 6. 테스트

- 클라 단위: `safeReturnPath`(정상 경로, `//evil.com`, `/\evil`, `http://…`, `/login`, 비문자열), `shareRoomLink`(공유 성공·취소·공유 없음 → 복사·복사 실패 → 수동).
- 클라 컴포넌트: `ProtectedRoute` 가 `from` 을 싣는다, `LoginPage` 초대 안내·게스트 버튼 순서·로그인/게스트 뒤 목적지·이미 로그인 상태 리다이렉트, `RegisterPage`
  가입 뒤 목적지, `RoomPage` 초대 버튼(WAITING 만)·자리 없음 패널(관전하기 → spectate 호출)·방 없음 패널. `index.html` 공통 미리보기·표식 원문 단언.
- 서버: 렌더러 단위(방 미리보기 값·이스케이프·인원 미포함·방 이름 빈 경우·표식 없음 → 틀 그대로), MVC(`GET /rooms/{id}` text/html·`Cache-Control`·인증 없이 200,
  없는 방 → 틀 그대로, 틀 없음 → 404). 인프라 grep(게임 이름 분기) 불변.
- 배포 뒤: 휴대폰 공유창 → 카톡으로 보내 미리보기 확인, 게스트로 입장, 꽉 찬 방 → 관전.

## 7. 후속

- 카카오 SDK "카카오톡으로 보내기" 버튼(앱 키·도메인 등록 필요).
- 미리보기 이미지(1200×630 PNG — 게임별 또는 사이트 공통).
- `/rooms/` 미리보기 남용 시 IP 레이트리밋.
