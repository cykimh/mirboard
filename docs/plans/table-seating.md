# 테이블 좌석 배치 — 원카드·스컬킹 테이블형 좌석 + 티츄 시계 방향 (D-132) — 설계

> 상태: **설계 승인** · 2026-10-12 · 결정 이력 `docs/decisions.md` D-132(계획 Task 1 에서 추가)
> 근거: 사용자 요청(원카드 게임판 스크린샷 — 상대가 단순 격자) · 티츄 테이블 배치 D-45 · 스컬킹 Row-Flow D-103 · 원카드 게임판 D-129
> 범위: 클라이언트만. 서버·STOMP·REST 계약, 경쟁 버튼 슬롯 표는 바뀌지 않는다.

## 1. 목표와 성공 기준

상대를 격자로 늘어놓는 대신 실제 보드게임 테이블처럼 **내 기준 위치**에 앉히고(4인이면 내 왼쪽·위·오른쪽), 진행 순서가 보이게 한다.
진행은 **시계 방향**이다 — 위에서 내려다볼 때 나(아래) 다음 차례가 내 **왼쪽**에 앉는다.

| # | 기준 | 확인 방법 |
| --- | --- | --- |
| 1 | 원카드(2~6인)·스컬킹(2~8인)의 상대가 왼쪽 옆줄·윗줄·오른쪽 옆줄에 시계 방향 순서로 앉는다(§4.1 표) | 배치 함수 단위 테스트, 게임판 컴포넌트 테스트(`data-side`·`data-slot`) |
| 2 | 지금 차례 좌석은 테두리, 다음 차례 좌석은 "다음" 표시, 테이블 가운데에 진행 방향 화살표 | 컴포넌트 테스트 |
| 3 | 원카드의 방향이 뒤집히면(Q) 화살표와 "다음" 표시가 함께 뒤집힌다 | 스토어/컴포넌트 테스트 |
| 4 | 티츄에서 내 다음 좌석(+1)이 왼쪽에 보이고, 카드 비행·말풍선·이모지가 같은 자리에서 나온다 | 매핑 단위 테스트, 비행 방향 테스트 |
| 5 | 390px 폭에서 좌석·더미·트릭 레일·손패가 겹치거나 화면 밖으로 넘치지 않는다(원카드 2·4·6인, 스컬킹 4·8인, 티츄 4인) | 배포 뒤 운영 사이트 브라우저 실측(모바일·데스크톱) |
| 6 | 기존 테스트의 계약 유지 — 좌석 DOM 순서(시계 방향), 가운데 `region "테이블"`, CSS 네임스페이스 규칙(18·19 파일 `@media` 0개, 17 이 마지막) | 클라 전체 테스트 |
| 7 | 원카드 "내기"와 "먹기" 버튼 사이가 손가락 너비(32px) 이상 떨어져 잘못 누르기 어렵다 | CSS 단언 테스트, 운영 실측 |

## 2. 확정 결정 (사용자, 2026-10-12)

| 항목 | 결정 |
| --- | --- |
| 진행 방향 | 시계 방향 — 나 다음(+1)이 내 왼쪽. **티츄도 시계 방향으로** 바꾼다 |
| 순서 표시 | 차례 테두리 + "다음" 표시 + 가운데 화살표(방향이 바뀌면 함께 뒤집힘) |
| 5인 이상 | 위쪽 반원(왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄)을 따라 앉힘 |
| 범위 | 원카드 + 스컬킹(테이블형 배치), 티츄(방향만) |
| 배치 방식 | 격자 테이블(각도 기반 원형·티츄 `.table-arena` 재사용은 기각 — §8) |
| 스컬킹 옆줄 좌석(좁은 화면) | 이름 + 예측·획득(손패 장수는 숨김). 윗줄·넓은 화면은 지금처럼 |
| 원카드 내기·먹기 버튼 | 사이를 벌린다(사용자 추가 요청 — 붙어 있으면 잘못 누른다) |
| 원카드 경쟁 버튼 | 지금대로 — 위쪽 좌석에 최대 3초 겹칠 수 있다(설계 의도는 손패·내 줄·더미 회피, `docs/plans/onecard.md` §4.10) |

## 3. 지금 구조 (조사, 2026-10-12)

- **공용 순서**: `client/src/components/seatOrder.ts` — `viewOrder(seatCount, mySeat)`(나 다음 좌석부터 n−1개, 관전자는 0..n−1)·`seatMinWidth`.
  스컬킹은 `features/skullking/seatLayout.ts` 가 재내보내고, 단위 테스트도 거기(`seatLayout.test.ts`)에 있다.
- **원카드** `features/onecard/OneCardTable.tsx` — `.oc-seats`(auto-fit 격자)에 `opponents = viewOrder(...)` 를 그리고, 그 아래 `OneCardCenter`
  (`<section className="oc-center" aria-label="테이블">`, 방향 배지 `진행 → 정방향`/`진행 ← 역방향`). `s.turnSeat` 는 `CARD_PLAYED` 직후·경쟁 창
  동안 −1 이다. CSS `19-onecard-table.css` 는 `@media` 0개여야 한다(`onecardCssNamespace.test.ts`).
- **스컬킹** `features/skullking/SkullKingTable.tsx` — `.sk-seats` auto-fit 격자(`--sk-seat-min`), 가운데에 재생순 트릭 레일 `SkullTrickRow`
  (원형 배치를 하지 않은 이유가 주석에 있다 — 8인 링에서 카드↔낸 사람 대응이 모호). 서버 진행은 +1(`TrickState`). CSS `18-skullking-table.css` 도
  `@media` 0개(`skullkingCssNamespace.test.ts`).
- **티츄** — 같은 매핑 식 `['s','w','n','e'][(seat - mySeat + 4) % 4]` 이 네 곳에 따로 있다: `TableArena.tsx:94-97`(좌석 클래스·팀 판정·카드 더미),
  `useGameTableEffects.ts:112-114`(트릭 카드 비행 출발 좌석), `features/chat/ArenaChatBubbles.tsx`(말풍선), `ReactionFloats.tsx`(이모지). CSS 는
  `.seat-w` 가 **오른쪽**, `.seat-e` 가 **왼쪽**이다(`03-table-arena.css:81-91`). 서버는 +1 이 시계 방향 다음이고(`TurnManager.java`), 룰
  `docs/rules-tichu.md:81` 은 패스 대상 "좌 = (seat+1)%4". 그래서 **지금 화면은 "왼쪽" 패스 대상(+1)을 오른쪽에 그린다** — 이번 변경이 이 불일치를 푼다.

## 4. 설계

### 4.1 공용 배치 함수 (`client/src/components/seatOrder.ts`)

```ts
export type TableSide = 'left' | 'top' | 'right';
export interface TableSlot {
  seat: number;      // 서버 좌석 번호
  side: TableSide;
  index: number;     // 옆줄 안 순서(0 부터, 시계 방향): left 는 아래→위, top 은 왼쪽→오른쪽, right 는 위→아래
}
/** 상대 수 → 옆줄별 인원. left = right = min(2, floor(k/2)), top = 나머지. */
export function sideCounts(opponents: number): { left: number; top: number; right: number };
/** 내 좌석 기준 테이블 배치. 관전자(mySeat < 0)는 좌석 0 을 아래(bottomSeat)에 두고 본다. */
export function tableLayout(seatCount: number, mySeat: number): { bottomSeat: number | null; slots: TableSlot[] };
```

| 상대 수 k | left | top | right | 인원 |
| --- | --- | --- | --- | --- |
| 1 | 0 | 1 | 0 | 2인 |
| 2 | 1 | 0 | 1 | 3인 |
| 3 | 1 | 1 | 1 | 4인 |
| 4 | 2 | 0 | 2 | 5인 |
| 5 | 2 | 1 | 2 | 6인 |
| 6 | 2 | 2 | 2 | 7인(스컬킹) |
| 7 | 2 | 3 | 2 | 8인(스컬킹) |

- 상대 순서는 `viewOrder(seatCount, 기준 좌석)` 그대로(기준 좌석 다음부터 = 시계 방향). 앞에서부터 left(아래→위) → top(왼쪽→오른쪽) → right(위→아래)에 채운다.
- 내 자리는 늘 아래(`bottomSeat = null` — 게임판의 "나" 줄이 그 자리다). 관전자는 `bottomSeat = 0` 이고 나머지가 0 기준으로 앉는다.
- 범위 밖 입력(좌석 수 < 2, mySeat ≥ seatCount)은 `viewOrder` 의 기존 폴백을 따른다.

### 4.2 원카드 게임판

- `.oc-seats` 를 녹색 테이블 격자 `.oc-felt`(`role="group"`, `aria-label="좌석 배치"` — 가운데의 `region "테이블"` 과 이름이 겹치지 않게)로 바꾸고, 그 안
  가운데 칸에 기존 `OneCardCenter` 를 둔다. 좌석 DOM 순서는 `slots` 순서(= 시계 방향) 그대로 — 기존 순서 단언이 유지된다.
- 격자: 열 `auto minmax(0, 1fr) auto`(옆줄 열은 좌석이 없으면 접힌다 — left 와 right 는 늘 같은 수라 대칭), 행 `auto 1fr 1fr`(+ 관전자면 아래 행).
  윗줄은 1행 전체 폭의 flex 줄, 왼쪽 옆줄은 1열(index 0 = 아래 3행, 1 = 위 2행, 1명이면 2~3행 가운데), 오른쪽 옆줄은 3열(index 0 = 위 2행,
  1 = 아래 3행), 가운데는 2열 2~3행. 좌석 요소에 `data-side`·`data-slot` 을 달아 CSS 가 자리를 잡는다(위치 계산은 함수, 배치는 CSS).
- 옆줄 좌석 폭은 `clamp(64px, 22vw, 150px)`, 윗줄 좌석은 flex(최대 168px) — `@media` 없이 폭에 맞춘다. 이름은 말줄임.
- **"다음" 표시**: `onecardRules.ts` 에 순수 함수 `nextTurnSeat(turnSeat, direction, eliminated: boolean[]): number` — `turnSeat < 0` 이면 −1, 아니면
  진행 방향으로 탈락하지 않은 다음 좌석(자기 자신뿐이면 −1). J(건너뛰기)·K(한 번 더)·2인 특례는 카드를 내기 전엔 알 수 없으므로 반영하지 않는다
  ("일반 진행 기준 다음"). 차례가 비는 순간(카드를 낸 직후, 경쟁 창)에는 차례 테두리와 함께 사라진다. 다음 좌석이 나면 "나" 줄에 표시한다.
  `OneCardSeat` 에 `isNext` → `<span className="oc-badge oc-seat-next">다음</span>`.
- **방향 화살표**: `OneCardCenter` 의 방향 배지를 `↻ 시계 방향`(direction ≥ 0) / `↺ 반시계 방향`(< 0)으로 바꾼다(화살표 글리프는 `aria-hidden`,
  글자는 읽힌다). 지금 문구 "정방향/역방향" 을 단언하는 테스트는 없다.
- 탈락·끊김 좌석은 자리에 그대로 두고 기존 상태 클래스로 흐리게.

### 4.3 스컬킹 게임판

- `.sk-seats` 를 `.sk-felt` 로 바꾸고 같은 배치 함수·격자를 쓴다. 가운데 칸에는 기존 트릭 레일(`SkullTrickRow`, PLAYING 일 때)과 시계 방향
  화살표(`↻`)를 둔다 — 낸 카드는 좌석 앞이 아니라 레일에 그대로 놓는다(D-103 의 결정 유지).
- **"다음" 표시**: `seatLayout.ts` 에 `nextTurnSeat(currentTurnSeat, seatCount, trickLength): number` — `currentTurnSeat < 0`(입찰·트릭 정리 중)이거나
  지금 사람이 이번 트릭의 마지막(`trickLength + 1 >= seatCount`)이면 −1, 아니면 `(currentTurnSeat + 1) % seatCount`. 탈주 좌석은 유령이 대신 두므로
  건너뛰지 않는다(룰 ⑱). `SkullSeatCard` 에 `isNext` → `sk-seat-next` 표시.
- 좁은 화면(≤480px)의 옆줄 좌석은 손패 장수를 숨긴다 — 규칙은 `17-responsive.css` 끝에 `.sk-*` 로만(18 파일은 `@media` 0개 유지).
- `--sk-seat-min` 은 윗줄 좌석 최소 폭으로 계속 쓴다(기존 테스트 유지).

### 4.4 티츄 — 시계 방향

- 네 곳의 매핑을 `features/tichu/seatView.ts` 의 `viewPosOf(seat, mySeat): 's' | 'w' | 'n' | 'e'` 하나로 모으고 배열을 `['s','e','n','w']` 로 바꾼다
  (+1 → `'e'` = 왼쪽, +3 → `'w'` = 오른쪽). CSS·서버·패스 문구는 그대로 — 클래스가 이미 자리를 뜻하므로 카드 더미 부채꼴·말풍선·이모지·모바일
  보정이 클래스를 따라간다. 관전자(mySeat −1)의 기존 동작(`(seat + 5) % 4`)도 같은 식으로 유지.
- 주석 정정: `TableArena.tsx`(viewIdx 설명), `SeatCardStack.tsx`(부채 방향 설명).

### 4.4b 원카드 내기·먹기 버튼 간격

- 지금은 `.oc-actions` 의 `gap: 8px` 하나로 두 버튼이 붙어 있다(`19-onecard-table.css:433-460`). 손패 아래 버튼 줄에서 "먹기"는 공격 누적이면 여러 장을
  먹는 되돌릴 수 없는 동작이라, 엄지로 "내기"를 누르려다 "먹기"를 누르지 않게 둘 사이를 **32px**(손가락 너비) 띄운다 —
  `.oc-play + .oc-draw { margin-inline-start: 32px; }`(다른 간격·버튼 크기·순서는 그대로). 줄이 감겨 "먹기"가 다음 줄로 내려가도 위아래로 떨어지므로
  붙지 않는다(`.oc-actions` `row-gap` 은 8px 유지 — 감긴 경우 위아래 간격은 버튼 높이 44px 가 이미 손가락 너비를 넘는다).
- 네임스페이스 테스트처럼 CSS 원문을 읽는 단언 1건(`.oc-play + .oc-draw` 에 `margin-inline-start: 32px`)으로 고정한다.

### 4.5 CSS 규칙

- 원카드 `.oc-felt*`·스컬킹 `.sk-felt*` 는 각 게임 part(19·18)에 접두 네임스페이스로. 테이블 색은 게임 토큰(`.oc-tokens`·`.sk-tokens` 와 같은 값 —
  네임스페이스 테스트가 대조)으로 라이트·다크 둘 다. 티츄 `.table-arena` 는 재사용하지 않는다(`.table-arena .seat{position:absolute}` 충돌).
- 폭 조건이 필요한 규칙은 `17-responsive.css` **끝**에만(기존 블록 사이 삽입 금지).
- 차례 테두리 `.oc-seat-turn`·`.oc-seat-turn.oc-seat-last`(경쟁 강조와 겹침) 스타일은 그대로 — 네임스페이스 테스트가 두 변수를 확인한다.

### 4.6 접근성

- 좌석 순서는 DOM 에서 시계 방향 — 스크린리더는 다음 사람부터 읽는다. "다음" 은 글자로 읽힌다. 화살표 글리프는 `aria-hidden`, 방향은 글자로.
- 터치 타겟·포커스 순서는 바뀌지 않는다(좌석은 버튼이 아니다).

## 5. 경계·예외

| 경우 | 처리 |
| --- | --- |
| 관전자 | 좌석 0 을 아래에 두고 나머지를 시계 방향으로. 아래 좌석은 일반 좌석 카드("나" 아님) |
| 원카드 탈락 좌석 | 자리에 그대로(흐리게). "다음" 계산에서 건너뜀 |
| 스컬킹 탈주 좌석 | 자리에 그대로. 유령이 두므로 "다음" 에서 건너뛰지 않음 |
| 차례 없음(−1) | 테두리·"다음" 표시 없음(원카드 카드 낸 직후·경쟁 창, 스컬킹 입찰·트릭 정리) |
| 긴 이름 | 말줄임 + `title` 로 전체 이름 |
| 경쟁 버튼 | 위치 표 그대로(뷰포트 %). 위쪽 좌석을 최대 3초 가릴 수 있음 — 기존과 같음 |

## 6. 테스트

- 단위: `tableLayout`·`sideCounts`(2~8인 전수 — 좌석 집합 보존, 시계 방향 순서, 내 좌석 회전, 관전자), `viewPosOf`(+1 → `'e'`, +2 → `'n'`, +3 → `'w'`,
  관전자), 원카드 `nextTurnSeat`(정·역방향, 탈락 건너뛰기, −1, 혼자 남음), 스컬킹 `nextTurnSeat`(마지막 사람, 입찰, 일반).
  `viewOrder`·`seatMinWidth` 테스트는 `components/seatOrder.test.ts` 로 옮기고 스컬킹 재내보내기는 유지(`docs/plans/onecard.md` 의 기존 후속).
- CSS: 원카드 내기·먹기 간격 32px 단언(§4.4b).
- 컴포넌트: 원카드 2·4·6인의 `data-side`/`data-slot`, "다음" 표시 좌석, 방향 화살표 뒤집힘, 기존 단언(좌석 수·`data-seat`·`region "테이블"`) 유지.
  스컬킹 2·5·8인 배치, 기존 DOM 순서 단언 유지, "다음" 표시. 티츄 좌석 클래스(+1 이 `seat-e`), 트릭 카드 비행이 그 좌석에서 출발.
- CSS 네임스페이스 테스트 유지(18·19 `@media` 0개, 17 마지막, 토큰 대조).
- 배포 뒤 운영 사이트에서 390px·데스크톱으로 원카드 2·4·6인, 스컬킹 4·8인, 티츄 4인을 본다(봇 채우기 방). `docs/qa-scenarios.md` 에 항목 추가.

## 7. 문서

- `docs/decisions.md` D-132(첫 커밋). D-45 끝에 *변경 → D-132*(티츄 매핑), D-103 끝에 *변경 → D-132*(스컬킹 좌석 배치 — 트릭 레일은 유지).
- `docs/plans/mobile-responsive-checklist.md` 의 4인 원형 좌석 항목에 원카드·스컬킹 테이블 배치 점검 추가, `docs/qa-scenarios.md` 항목,
  `docs/implementation-status.md` 한 줄, 테스트 수가 바뀌면 수치를 인용한 문서(README·케이스 스터디 등).

## 8. 기각한 안

- **각도 기반 원형(절대 위치)** — 실제 원탁에 가깝지만 `@media` 없이 좁은 화면에서 좌석·더미·트릭 레일 겹침을 막기 어렵다.
- **티츄 `.table-arena` 재사용** — 좌석 절대 위치 규칙과 충돌하고 티츄 회귀 위험.
- **티츄 CSS 를 좌우로 맞바꾸기** — 03·06·14·17 파일과 css-baseline 이 모두 바뀐다. 매핑 배열만 바꾸면 같은 효과.
- **경쟁 버튼 슬롯 재배치** — 사용자 결정으로 보류(튜토리얼 연습·테스트가 함께 바뀌는 비용).
