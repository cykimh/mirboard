# 테이블 좌석 배치 구현 계획 — 원카드·스컬킹 테이블형 좌석 + 티츄 시계 방향 (D-132)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원카드(2~6인)·스컬킹(2~8인) 게임판의 상대를 격자 대신 실제 테이블처럼 내 기준 왼쪽 옆줄·윗줄·오른쪽 옆줄에 시계 방향으로 앉히고, 차례 테두리 +
"다음" 표시 + 가운데 진행 방향 화살표로 순서를 보이며, 티츄도 시계 방향(내 다음 +1 이 왼쪽)으로 바꾸고, 원카드 "내기"·"먹기" 버튼 사이를 32px 벌린다.

**Architecture:** 배치는 공용 순수 함수 `tableLayout`(`client/src/components/seatOrder.ts`)이 정하고(옆줄별 인원 `sideCounts` — left = right = min(2, ⌊k/2⌋)), 게임판은
그 결과를 `grid-template-areas` 격자(`.oc-felt`·`.sk-felt`)에 꽂는다 — 좌석 DOM 순서는 시계 방향 그대로, 자리는 CSS 가 잡는다(왼쪽 옆줄은 `column-reverse`
로 아래→위). "다음"은 게임별 순수 함수(원카드 `onecardRules.nextTurnSeat`, 스컬킹 `seatLayout.nextTurnSeat`)로 계산하고 스토어는 바꾸지 않는다(D-103).
티츄는 흩어진 매핑 네 곳을 `viewPosOf` 하나로 모아 배열만 뒤집는다(CSS 무변경).

**Tech Stack:** React 18 + TypeScript + Zustand, Vitest + RTL(jsdom), CSS parts(`client/src/styles/parts/*`).

**Spec:** `docs/plans/table-seating.md`(설계 정본 — 계획과 함께 읽는다).

**참고:** 이 계획의 코드는 검증용 스파이크(사전 리뷰 Critical 0 · Important 4 · Minor 6 전부 반영)에서 옮겼고, 스파이크에서 태스크 N 까지만 적용한 트리가
N=1..6 모두 RED→GREEN·tsc·클라 전체를 통과하는 것과 아래 각 단계의 실패·통과·테스트 수를 확인했다(클라 65파일 746건). 서버는 바뀌지 않는다.

## Global Constraints

- 응답·주석·문서는 한국어.
- 작업 위치: 브랜치 `feat/table-seating`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan`(설계 커밋 `e88bf68` 기반 — main `d4731af` + 설계 문서).
  메인 체크아웃(`/Users/yupchang/Developer/mirboard`)과 다른 워크트리는 건드리지 않는다. Bash 는 매 호출 작업 폴더가 바뀔 수 있으니 **명령마다 워크트리 절대경로로
  `cd` 하거나 `git -C` 를 쓴다.** `git add` 는 파일을 콕 집어서 한다.
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` — 모델이 무엇이든, 하니스가 다른 줄을 권해도 이 줄 그대로.
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 컴파일)를 돈다. `--no-verify` 금지. 그래서 **매 태스크가 끝난 트리는 타입 검사와 클라 전체
  테스트를 통과해야 한다.**
- 개발 서버(`bootRun`·`npm run dev`)·브라우저·운영 접속(`flyctl` 포함)·푸시를 하지 않는다. `.env` 를 읽지 않는다.
- **한 파일은 정확히 한 태스크가 고친다**(아래 파일 지도). 다른 태스크의 파일이 필요해 보이면 멈추고 보고한다.
- **사용자 결정(2026-10-12)**: 진행은 **시계 방향** — 나 다음 차례(+1)가 내 **왼쪽**(티츄도). 순서 표시 = 차례 테두리 + "다음" 표시 + 가운데 화살표(방향이 바뀌면 함께
  뒤집힘). 5인 이상 = 위쪽 반원(왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄). 범위 = 원카드·스컬킹 테이블 배치 + 티츄 방향만. 스컬킹 좁은 화면(≤480px) = 옆줄 좌석 손패 장수
  숨김 + 가운데 트릭 레일을 옆줄 아래 전체 폭으로. 원카드 경쟁 버튼 위치는 그대로. 원카드 "내기"·"먹기" 사이 **32px**. 결정 번호 **D-132**.
- 클라 게임 중립(D-103·D-124): 게임 스토어는 순수 리듀서, sink 는 모듈 상수 — 이 계획은 스토어·sink 를 바꾸지 않는다. 게임판 CSS 는 접두 네임스페이스(`.oc-`·`.sk-`),
  18·19 파일 `@media` 0개, 폭 조건은 `17-responsive.css` 맨 끝에만(17 이 마지막) — 네임스페이스 테스트가 강제한다.
- 테스트 명령: 클라 특정 `npm --prefix client run test -- <이름 일부...>`, 클라 전체 `npm --prefix client run test`, 타입 검사 `npm --prefix client run build:check`.
- 테스트 수(누적, 클라 `npm --prefix client run test` 마지막 두 줄): 기준 61파일·660건 → Task 2 62·679 → Task 3 65·692 → Task 4 65·720 → Task 5 65·746(Task 1·6 은 문서 — 불변).
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를 잡는 문맥이다(지우지 않는다). 세 경우 모두 첫 글자 하나를
  떼고 읽는다(나머지 들여쓰기는 파일 그대로). `+` 뒤가 비어 있으면 빈 줄을 넣는다. 한 블록은 파일에서 정확히 한 곳과 맞고, 블록은 적힌 순서대로 적용한다. 안에 ```
  가 든 블록은 네 개짜리 펜스(````)로 감쌌다 — 그 ``` 줄은 파일 내용이다. 새 파일 블록은 그대로 쓰고 끝에 줄바꿈 하나.

## Review Focus

설계가 요구하지만 직접 말하지 않은, 사람이 쓰다가 가장 먼저 부딪칠 다섯 가지 — 각 줄의 테스트는 소유 태스크에 들어 있다.

1. **관전자 화면** — 아래 칸에 좌석 0 이 일반 좌석으로 앉고 "나" 줄이 없으며, "다음"이 "나" 줄로 가지 않는다(원카드 Task 4·스컬킹 Task 5, 배치 Task 2).
2. **탈락이 섞인 원카드 "다음"** — 다음 사람이 탈락이면 건너뛰고, 나 혼자 남으면 없고, 역방향에서도 같다(Task 4 `onecardRules.test.ts`).
3. **방향 반전 직후(원카드 Q)** — 화살표 글자("↺ 반시계 방향")와 "다음" 좌석이 함께 반대로 간다(Task 4 `OneCardTable.test.tsx`).
4. **긴 이름(좁은 옆줄)** — 좁은 좌석에서는 이름이 아바타 아래 줄로 내려가 말줄임되고 `title` 로 전체 이름을 가진다(Task 4·5 CSS 원문 단언 + 속성 단언).
5. **티츄 관전자와 트릭 카드 비행** — 관전자 매핑이 기존 식 그대로이고, 카드가 +1 좌석(왼쪽)에서 날아오며, 말풍선·이모지도 같은 자리에서 뜬다(Task 3).

## 파일 지도

| 파일 | 태스크 | 책임 |
| --- | --- | --- |
| `docs/decisions.md` | 1 | D-132 결정 기록(D-131 위), D-45·D-103 에 *변경 → D-132* |
| `client/src/components/seatOrder.ts` | 2 | `TableSide`·`TableSlot`·`sideCounts`·`tableLayout` — 내 기준 테이블 배치(관전자는 좌석 0 이 아래) |
| `client/src/features/tichu/seatView.ts`, `TableArena.tsx`, `useGameTableEffects.ts`, `ReactionFloats.tsx`, `SeatCardStack.tsx`, `client/src/features/chat/ArenaChatBubbles.tsx` | 3 | 티츄 `viewPosOf` 로 매핑 통합 + 시계 방향 |
| `client/src/features/onecard/onecardRules.ts`, `OneCardSeat.tsx`, `OneCardCenter.tsx`, `OneCardTable.tsx`, `client/src/styles/parts/19-onecard-table.css` | 4 | 원카드 테이블 격자·다음 표시·방향 화살표·버튼 간격 32px |
| `client/src/features/skullking/seatLayout.ts`, `SkullSeatCard.tsx`, `SkullKingTable.tsx`, `client/src/styles/parts/18-skullking-table.css`, `17-responsive.css`, `client/src/styles/cssSources.ts` | 5 | 스컬킹 테이블 격자·다음 표시·좁은 화면 규칙 |
| 문서 6개(아래 Task 6) | 6 | 설계 상태·QA·모바일 점검표·현황·README·CLAUDE.md |

---

### Task 1: 문서 — 결정 기록 D-132 (문서 선행)

코드보다 결정 기록이 먼저다(저장소 규칙). 테스트는 없다.

**Files:**
- Modify: `docs/decisions.md`

**Interfaces:**
- Produces: `## D-132 (2026-10-12) — 테이블 좌석 배치: 원카드·스컬킹 테이블형 좌석 + 다음 차례 표시, 티츄 시계 방향`(D-131 바로 위), D-45·D-103 끝에 `*변경 → D-132*`.
  뒤 태스크의 코드 주석·문서는 `D-132` 로 이 항목을 가리킨다.

- [ ] **Step 1: 결정 항목 추가**

`docs/decisions.md`:

```diff
 
 *보정 → D-124*: 위 **부채 명기**(seq 판정 중복)를 갚았다 — 판정은 훅으로, 두 스토어는 순수 리듀서로. 위 **라운드 경계 resync** 문단의 전제("BIDDING_STARTED 는 거의 항상 gap")도 바로잡았다 — 서버는 BIDDING_STARTED 를 HAND_DEALT 보다 먼저 보내므로 구멍은 그 뒤 첫 공개 이벤트에서 나고, BIDDING_STARTED 의 판정 무관 스크럽은 없앴다.
 
+*변경 → D-132* (상대 좌석 auto-fit 격자 → 테이블형 배치(왼쪽 옆줄·윗줄·오른쪽 옆줄, 시계 방향). 재생순 트릭 레일은 가운데 칸에 그대로 둔다 — 좁은 화면(≤480px)만 옆줄 아래 전체 폭.)
+
 ## D-102 (2026-07-31) — 스컬킹 통합: 포트 배선 + desert 3치화 + 봇 풀 확장 (M5/T7 S5)
 
 S4 순수 엔진을 `SkullKingGameDefinition`(id `SKULL_KING`, 2~8인, AVAILABLE)으로 등록하고
```

```diff
 보류 3건: ① 스컬킹 매치 결과 영속·ELO·desert_count 는 `users.rating` 단일 컬럼(D-02)의
 게임별 분리 결정이 선행이라 별건. ② 봇은 포트 기본(합법 균등 분포)으로 시작 — 휴리스틱은
 후속. ③ 카탈로그에 노출되지만 클라 게임판은 S6(D-103) — 직후 과제.
+
+## D-132 (2026-10-12) — 테이블 좌석 배치: 원카드·스컬킹 테이블형 좌석 + 다음 차례 표시, 티츄 시계 방향
+
+원카드·스컬킹의 상대 좌석을 auto-fit 격자 대신 실제 테이블처럼 내 기준 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄에 시계 방향으로 앉힌다(공용
+`tableLayout` — 옆줄은 최대 2명씩이라 5인 이상은 위쪽 반원, 관전자는 좌석 0 을 아래에). 지금 차례 테두리에 더해 일반 진행 기준 "다음" 차례와
+가운데 진행 방향 화살표를 보이고(원카드 Q 로 방향이 바뀌면 함께 뒤집힌다), 원카드 내기·먹기 버튼 사이를 32px 띄운다. 티츄는 본인 시점 매핑
+배열만 `['s','e','n','w']` 로 바꿔 다음 차례(+1, 패스 문구의 "좌")가 왼쪽에 보이게 한다 — 지금까지는 오른쪽에 그렸다. 격자 테이블이라 폭
+미디어 없이 맞추고(각도 기반 원형·티츄 `.table-arena` 재사용은 기각), 스컬킹만 ≤480px 에서 17 끝 규칙으로 옆줄 손패 장수를 숨기고 가운데
+트릭 레일을 옆줄 아래 전체 폭으로 내린다(390px 가운데 칸 ≈138px 에서는 레일이 한 줄에 1장씩 쌓인다 — 사전 리뷰 뒤 사용자 결정). 서버·STOMP·
+경쟁 버튼 슬롯은 그대로다(설계 `docs/plans/table-seating.md`).
 
 ## D-131 (2026-10-06) — 원카드 공개(AVAILABLE) + resync 남은 턴 시간·원카드 카운트다운 + 매치 기록 실패 격리 3게임
 
```

```diff
 실제 트럼프 카드 이미지 + 부채꼴 fan-out 은 8F 와 함께 도입 예정 (현 SortableHand
 는 가로 일렬 유지). 클라 빌드 +1.5KB CSS, 37 단위 테스트 회귀 그린.
 
+*변경 → D-132* (매핑 배열을 `['s','e','n','w']` 로 — 다음 차례(+1)가 왼쪽(E), 직전 차례(+3)가 오른쪽(W). 인덱스 식·좌석 CSS 는 그대로.)
+
 ## D-44 (2026-05-15) — Phase 8D 마감: ELO + 6단계 티어
 
 V2 마이그레이션 (`users.rating INT NOT NULL DEFAULT 1000`) — D-02 schema constraint
```

- [ ] **Step 2: 확인**

Run: `grep -n "^## D-132 " docs/decisions.md` 뒤 `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 한 줄, 타입 오류 없음, 마지막 두 줄 `Test Files  61 passed (61)` · `Tests  660 passed (660)`.

- [ ] **Step 3: 커밋**

```bash
git add docs/decisions.md
git commit -m "docs(D-132): 결정 기록 — 원카드·스컬킹 테이블형 좌석, 다음 차례 표시, 티츄 시계 방향

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 클라 — 공용 테이블 배치 함수

**Files:**
- Create: `client/src/components/seatOrder.test.ts`
- Modify: `client/src/components/seatOrder.ts`

**Interfaces:**
- Consumes: 기존 `viewOrder(seatCount, mySeat): number[]`(나 다음 좌석부터 n−1개 = 시계 방향, 관전자는 0..n−1).
- Produces: `export type TableSide = 'left' | 'top' | 'right'`, `export interface TableSlot { seat: number; side: TableSide; index: number }`(index 는 옆줄 안 순서 — left 는
  아래→위, top 은 왼쪽→오른쪽, right 는 위→아래), `sideCounts(opponents: number): { left: number; top: number; right: number }`(left = right = min(2, ⌊k/2⌋), top = 나머지),
  `tableLayout(seatCount: number, mySeat: number): { bottomSeat: number | null; slots: TableSlot[] }`(관전자 `mySeat < 0` 이면 `bottomSeat = 0`). `viewOrder`·`seatMinWidth`
  테스트는 `features/skullking/seatLayout.test.ts` 에 그대로 둔다(그 파일은 Task 5 소유).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/components/seatOrder.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { sideCounts, tableLayout, viewOrder, type TableSide, type TableSlot } from './seatOrder';

/**
 * 테이블 좌석 배치 (D-132, 설계 `docs/plans/table-seating.md` §4.1). 진행은 시계 방향 — 위에서 내려다볼 때 나(아래) 다음
 * 차례가 내 **왼쪽**이다. 그래서 상대는 왼쪽 옆줄(아래→위) → 윗줄(왼쪽→오른쪽) → 오른쪽 옆줄(위→아래) 순으로 앉는다.
 * (`viewOrder`·`seatMinWidth` 의 전수 테스트는 스컬킹 재내보내기와 함께 `features/skullking/seatLayout.test.ts` 에 있다.)
 */

const SEAT_COUNTS = [2, 3, 4, 5, 6, 7, 8];

/** 설계 §4.1 표 — 상대 수 → [left, top, right]. */
const SIDES: Record<number, [number, number, number]> = {
  1: [0, 1, 0],
  2: [1, 0, 1],
  3: [1, 1, 1],
  4: [2, 0, 2],
  5: [2, 1, 2],
  6: [2, 2, 2],
  7: [2, 3, 2],
};

const SIDE_ORDER: TableSide[] = ['left', 'top', 'right'];

/** 슬롯이 옆줄 순서(left → top → right)대로 놓이고 각 옆줄의 index 가 0 부터 1씩 느는지. */
function expectSideRuns(slots: TableSlot[], label: string) {
  let sideRank = 0;
  const next = new Map<TableSide, number>();
  for (const slot of slots) {
    const rank = SIDE_ORDER.indexOf(slot.side);
    expect(rank, `${label} 옆줄 순서`).toBeGreaterThanOrEqual(sideRank);
    sideRank = rank;
    expect(slot.index, `${label} ${slot.side} index`).toBe(next.get(slot.side) ?? 0);
    next.set(slot.side, slot.index + 1);
  }
}

function countBySide(slots: TableSlot[]): [number, number, number] {
  return SIDE_ORDER.map((side) => slots.filter((s) => s.side === side).length) as [number, number, number];
}

describe('sideCounts — 상대 수별 옆줄 인원 (설계 §4.1 표)', () => {
  it.each(Object.entries(SIDES))('상대 %s명', (k, [left, top, right]) => {
    expect(sideCounts(Number(k))).toEqual({ left, top, right });
  });

  it('좌우 옆줄은 같은 수이고 2명을 넘지 않으며, 합이 상대 수다', () => {
    for (let k = 0; k <= 9; k++) {
      const { left, top, right } = sideCounts(k);
      expect(left, `k=${k}`).toBe(right);
      expect(left, `k=${k}`).toBeLessThanOrEqual(2);
      expect(left + top + right, `k=${k}`).toBe(k);
    }
  });

  it('상대가 없거나 음수면 모두 0', () => {
    expect(sideCounts(0)).toEqual({ left: 0, top: 0, right: 0 });
    expect(sideCounts(-1)).toEqual({ left: 0, top: 0, right: 0 });
  });
});

describe('tableLayout — 2~8인 × 내 좌석 전수', () => {
  it('상대 좌석 = 나를 뺀 전 좌석(중복 없음), 내 자리는 아래 — bottomSeat 은 null', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        const { bottomSeat, slots } = tableLayout(n, mySeat);
        const seats = slots.map((s) => s.seat);

        expect(bottomSeat, `n=${n} mySeat=${mySeat}`).toBeNull();
        expect(seats, `n=${n} mySeat=${mySeat}`).toHaveLength(n - 1);
        expect(new Set(seats).size, `n=${n} mySeat=${mySeat} 중복`).toBe(n - 1);
        expect(seats).not.toContain(mySeat);
      }
    }
  });

  it('슬롯 순서가 시계 방향 — 내 다음 좌석부터 viewOrder 그대로', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        const seats = tableLayout(n, mySeat).slots.map((s) => s.seat);
        expect(seats, `n=${n} mySeat=${mySeat}`).toEqual(viewOrder(n, mySeat));
        expect(seats[0], `n=${n} mySeat=${mySeat} 첫 좌석`).toBe((mySeat + 1) % n);
      }
    }
  });

  it('옆줄별 인원이 §4.1 표와 같고, 왼쪽 → 위 → 오른쪽 순서에 옆줄 안 index 가 0 부터 이어진다', () => {
    for (const n of SEAT_COUNTS) {
      for (let mySeat = 0; mySeat < n; mySeat++) {
        const { slots } = tableLayout(n, mySeat);
        expect(countBySide(slots), `n=${n} mySeat=${mySeat}`).toEqual(SIDES[n - 1]);
        expectSideRuns(slots, `n=${n} mySeat=${mySeat}`);
      }
    }
  });
});

describe('tableLayout — 예시', () => {
  it('2인: 상대가 위에 혼자 앉는다', () => {
    expect(tableLayout(2, 1)).toEqual({ bottomSeat: null, slots: [{ seat: 0, side: 'top', index: 0 }] });
  });

  it('4인 내 좌석 0: 다음(1)이 왼쪽, 맞은편(2)이 위, 직전(3)이 오른쪽', () => {
    expect(tableLayout(4, 0)).toEqual({
      bottomSeat: null,
      slots: [
        { seat: 1, side: 'left', index: 0 },
        { seat: 2, side: 'top', index: 0 },
        { seat: 3, side: 'right', index: 0 },
      ],
    });
  });

  it('8인 내 좌석 5: 내 좌석 기준으로 돌아 왼쪽 2·위 3·오른쪽 2', () => {
    expect(tableLayout(8, 5).slots).toEqual([
      { seat: 6, side: 'left', index: 0 },
      { seat: 7, side: 'left', index: 1 },
      { seat: 0, side: 'top', index: 0 },
      { seat: 1, side: 'top', index: 1 },
      { seat: 2, side: 'top', index: 2 },
      { seat: 3, side: 'right', index: 0 },
      { seat: 4, side: 'right', index: 1 },
    ]);
  });
});

describe('tableLayout — 관전자 (mySeat < 0)', () => {
  it('좌석 0 을 아래에 두고 나머지를 0 기준 시계 방향으로 앉힌다', () => {
    for (const n of SEAT_COUNTS) {
      const { bottomSeat, slots } = tableLayout(n, -1);

      expect(bottomSeat, `n=${n}`).toBe(0);
      expect(slots.map((s) => s.seat), `n=${n}`).toEqual(viewOrder(n, 0));
      expect(countBySide(slots), `n=${n}`).toEqual(SIDES[n - 1]);
      expectSideRuns(slots, `관전 n=${n}`);
    }
  });

  it('6인 관전: 1·2 왼쪽, 3 위, 4·5 오른쪽', () => {
    expect(tableLayout(6, -1)).toEqual({
      bottomSeat: 0,
      slots: [
        { seat: 1, side: 'left', index: 0 },
        { seat: 2, side: 'left', index: 1 },
        { seat: 3, side: 'top', index: 0 },
        { seat: 4, side: 'right', index: 0 },
        { seat: 5, side: 'right', index: 1 },
      ],
    });
  });
});

describe('tableLayout — 범위 밖 입력 (viewOrder 의 기존 폴백)', () => {
  it('내 좌석이 좌석 수 이상이면 전 좌석을 상대로 앉히고 아래는 비운다', () => {
    const { bottomSeat, slots } = tableLayout(4, 9);

    expect(bottomSeat).toBeNull();
    expect(slots.map((s) => s.seat)).toEqual([0, 1, 2, 3]);
    expect(countBySide(slots)).toEqual([2, 0, 2]);
  });

  it('좌석 수가 2 미만이면 앉힐 상대가 없다', () => {
    expect(tableLayout(1, 0)).toEqual({ bottomSeat: null, slots: [] });
    expect(tableLayout(0, 0)).toEqual({ bottomSeat: null, slots: [] });
    expect(tableLayout(0, -1)).toEqual({ bottomSeat: null, slots: [] });
    expect(tableLayout(1, -1)).toEqual({ bottomSeat: 0, slots: [] });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- components/seatOrder`
Expected: FAIL — `Test Files  1 failed (1)` · `Tests  19 failed (19)`(`TypeError: sideCounts is not a function` · `TypeError: tableLayout is not a function` — 모듈은 있고
내보내기만 없어 `Failed to resolve import` 가 아니다).

- [ ] **Step 3: 구현**

`client/src/components/seatOrder.ts`:

```diff
 /**
- * 가변 좌석 게임판의 순수 배치 계산 — 스컬킹(2~8인, D-103 Row-Flow)과 원카드(2~6인, D-129)가 같이 쓴다. DOM 을
- * 모른다 — 전수 테스트가 jsdom 없이 돈다(`features/skullking/seatLayout.test.ts`).
+ * 가변 좌석 게임판의 순수 배치 계산 — 스컬킹(2~8인, D-103)과 원카드(2~6인, D-129)가 같이 쓴다. DOM 을 모른다 —
+ * 전수 테스트가 jsdom 없이 돈다(`viewOrder`·`seatMinWidth` 는 `features/skullking/seatLayout.test.ts`, 테이블 배치는
+ * `seatOrder.test.ts`).
  */
 
 /**
- * 상대 좌석을 화면에 늘어놓을 순서. **내 다음 차례부터 좌→우**라 진행 방향이 읽힌다.
+ * 상대 좌석의 진행 순서 — **내 다음 차례부터**(시계 방향). `tableLayout` 이 이 순서대로 테이블에 앉힌다(D-132).
  *
  * @param mySeat 관전자는 -1 — 그때는 내 좌석을 뺄 것이 없으므로 전 좌석을 돌려준다.
  */
```

```diff
 }
 
 /**
- * 좌석 카드의 최소 폭. `repeat(auto-fit, minmax(이 값, 168px))` 에 꽂으면 인원이 늘수록
- * 한 행에 더 많이 들어가고, 넘치면 **폭 미디어 쿼리 없이** 자동 줄바꿈된다.
+ * 좌석 카드의 최소 폭 — 인원이 늘수록 좁아져 한 줄에 더 많이 들어간다(**폭 미디어 쿼리 없이**). 테이블 배치(D-132)
+ * 에서는 윗줄 좌석의 기준 폭이다(옆줄 좌석 폭은 각 게임 CSS 의 `clamp()`).
  */
 export function seatMinWidth(seatCount: number): string {
   if (seatCount <= 4) return '132px';
   if (seatCount <= 6) return '116px';
   return '100px';
 }
+
+/** 테이블에서 상대가 앉는 옆줄 (D-132). 내 자리는 늘 아래라 여기 없다. */
+export type TableSide = 'left' | 'top' | 'right';
+
+export interface TableSlot {
+  /** 서버 좌석 번호. */
+  seat: number;
+  side: TableSide;
+  /** 옆줄 안 순서(0 부터, 시계 방향) — left 는 아래→위, top 은 왼쪽→오른쪽, right 는 위→아래. */
+  index: number;
+}
+
+/**
+ * 상대 수 → 옆줄별 인원. 좌우 옆줄은 같은 수로 최대 2명씩이고 나머지가 윗줄이다 — 5인 이상은 위쪽 반원을 따라
+ * 앉는다(왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄). 설계 `docs/plans/table-seating.md` §4.1 표.
+ */
+export function sideCounts(opponents: number): { left: number; top: number; right: number } {
+  const k = Math.max(0, opponents);
+  const side = Math.min(2, Math.floor(k / 2));
+  return { left: side, top: k - 2 * side, right: side };
+}
+
+/**
+ * 내 좌석 기준 테이블 배치 (D-132). 진행은 **시계 방향**이라 위에서 내려다볼 때 나(아래) 다음 차례가 내 왼쪽이다 —
+ * 상대를 `viewOrder` 순서(내 다음 좌석부터) 그대로 왼쪽 옆줄(아래→위) → 윗줄(왼쪽→오른쪽) → 오른쪽 옆줄(위→아래)에
+ * 채운다. 그래서 `slots` 순서가 곧 진행 순서다(DOM 순서로 쓰면 스크린리더가 다음 사람부터 읽는다).
+ *
+ * <p>내 자리는 늘 아래다(`bottomSeat = null` — 게임판의 "나" 줄이 그 자리). 관전자(`mySeat < 0`)는 좌석 0 을 아래
+ * (`bottomSeat = 0`)에 두고 나머지를 0 기준으로 앉힌다. 범위 밖 `mySeat`(≥ 좌석 수)은 `viewOrder` 의 폴백(전 좌석)을 따른다.
+ */
+export function tableLayout(
+  seatCount: number,
+  mySeat: number,
+): { bottomSeat: number | null; slots: TableSlot[] } {
+  if (mySeat < 0) {
+    if (seatCount <= 0) return { bottomSeat: null, slots: [] };
+    return { bottomSeat: 0, slots: toSlots(viewOrder(seatCount, 0)) };
+  }
+  return { bottomSeat: null, slots: toSlots(viewOrder(seatCount, mySeat)) };
+}
+
+function toSlots(order: number[]): TableSlot[] {
+  const { left, top } = sideCounts(order.length);
+  return order.map((seat, i) => {
+    if (i < left) return { seat, side: 'left', index: i };
+    if (i < left + top) return { seat, side: 'top', index: i - left };
+    return { seat, side: 'right', index: i - left - top };
+  });
+}
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- components/seatOrder`
Expected: PASS — `Test Files  1 passed (1)` · `Tests  19 passed (19)`.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  62 passed (62)` · `Tests  679 passed (679)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/components/seatOrder.test.ts \
  client/src/components/seatOrder.ts
git commit -m "feat(D-132): 공용 테이블 배치 함수 — tableLayout·sideCounts (시계 방향, 관전자 좌석 0 아래)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 클라 — 티츄 시계 방향(매핑 통합)

**Files:**
- Create: `client/src/features/tichu/seatView.test.ts`, `client/src/features/chat/ArenaChatBubbles.test.tsx`, `client/src/features/tichu/ReactionFloats.test.tsx`, `client/src/features/tichu/seatView.ts`
- Modify: `client/src/features/tichu/GameTable.test.tsx`, `client/src/features/tichu/useGameTableEffects.test.tsx`, `client/src/features/tichu/SeatCardStack.tsx`, `client/src/features/tichu/TableArena.tsx`, `client/src/features/tichu/useGameTableEffects.ts`, `client/src/features/chat/ArenaChatBubbles.tsx`, `client/src/features/tichu/ReactionFloats.tsx`

**Interfaces:**
- Consumes: 티츄 CSS 의 자리 클래스(`.seat-e` = 왼쪽, `.seat-w` = 오른쪽, `.seat-n` = 위, `.seat-s` = 아래 — `03-table-arena.css`, 바꾸지 않는다), `SeatCardStack.tsx` 의
  `SeatViewPos`, 서버 규칙 +1 = 시계 방향 다음 = 패스 "왼쪽"(`docs/rules-tichu.md:81`).
- Produces: `viewPosOf(seat: number, mySeat: number): SeatViewPos` = `['s','e','n','w'][(seat - mySeat + 4) % 4]`(`features/tichu/seatView.ts`) — `TableArena`(좌석 클래스·팀
  판정·카드 더미), `useGameTableEffects`(트릭 카드 비행 출발 좌석), `ArenaChatBubbles`(말풍선), `ReactionFloats`(이모지)가 모두 이것을 쓴다. 관전자(mySeat −1)는 기존 식 그대로.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/tichu/seatView.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { viewPosOf } from './seatView';

/**
 * 티츄 본인 시점 매핑 (D-132 — 시계 방향). 서버는 +1 이 다음 차례이고 룰의 패스 "좌" 도 +1 이다. 위에서 내려다볼 때 다음
 * 차례가 내 **왼쪽**에 앉아야 하므로 +1 → 'e'(`.seat-e` 가 왼쪽), +3 → 'w'(오른쪽)다. 지금까지는 거꾸로 그렸다.
 */
describe('viewPosOf — 시계 방향 (D-132)', () => {
  it('나는 아래, 다음 차례(+1)는 왼쪽 e, 파트너(+2)는 위 n, 직전 차례(+3)는 오른쪽 w', () => {
    expect(viewPosOf(0, 0)).toBe('s');
    expect(viewPosOf(1, 0)).toBe('e');
    expect(viewPosOf(2, 0)).toBe('n');
    expect(viewPosOf(3, 0)).toBe('w');
  });

  it('내 좌석이 바뀌어도 상대 위치는 좌석 차이로만 정해진다', () => {
    for (let mySeat = 0; mySeat < 4; mySeat++) {
      expect(viewPosOf(mySeat, mySeat), `mySeat=${mySeat}`).toBe('s');
      expect(viewPosOf((mySeat + 1) % 4, mySeat), `mySeat=${mySeat}`).toBe('e');
      expect(viewPosOf((mySeat + 2) % 4, mySeat), `mySeat=${mySeat}`).toBe('n');
      expect(viewPosOf((mySeat + 3) % 4, mySeat), `mySeat=${mySeat}`).toBe('w');
    }
  });

  it('관전자(mySeat −1)는 기존 식 그대로 좌석 3 이 아래에 오고, 그 다음 좌석 0 이 왼쪽이다', () => {
    expect(viewPosOf(3, -1)).toBe('s');
    expect(viewPosOf(0, -1)).toBe('e');
    expect(viewPosOf(1, -1)).toBe('n');
    expect(viewPosOf(2, -1)).toBe('w');
  });
});
```

`client/src/features/tichu/GameTable.test.tsx`:

```diff
     expect(screen.queryByText(/관전/)).toBeNull();
   });
 });
+
+/** 좌석 배지 글자 → 그 좌석의 위치 클래스(seat-s/e/n/w). CSS 는 `.seat-e` 가 왼쪽, `.seat-w` 가 오른쪽이다. */
+function seatPositions(container: HTMLElement): Record<string, string> {
+  const out: Record<string, string> = {};
+  container.querySelectorAll('.seat').forEach((el) => {
+    const label = el.querySelector('.seat-team')?.textContent?.trim() ?? '';
+    const pos = [...el.classList].find((c) => /^seat-[snew]$/.test(c)) ?? '';
+    out[label] = pos;
+  });
+  return out;
+}
+
+describe('GameTable — 좌석 방향 (D-132 시계 방향)', () => {
+  it('내 다음 차례(+1)가 왼쪽(seat-e), 파트너가 위, 직전 차례(+3)가 오른쪽(seat-w)', () => {
+    seed({ tableView: tableView(), privateHand: privateHand([]) });
+
+    const { container } = renderTable();
+
+    expect(seatPositions(container)).toEqual({
+      나: 'seat-s',
+      '#20': 'seat-e',
+      '#30': 'seat-n',
+      '#40': 'seat-w',
+    });
+  });
+
+  it('내 좌석이 2 여도 다음 좌석(3)이 왼쪽이다', () => {
+    seed({ tableView: tableView(), privateHand: { seat: 2, cards: [] } });
+
+    const { container } = renderTable({ myUserId: 30 });
+
+    expect(seatPositions(container)).toEqual({
+      나: 'seat-s',
+      '#40': 'seat-e',
+      '#10': 'seat-n',
+      '#20': 'seat-w',
+    });
+  });
+
+  it('관전자는 기존 식 그대로 좌석 3 을 아래에 두고, 그 다음 좌석 0 이 왼쪽이다', () => {
+    seed({ tableView: tableView(), privateHand: null });
+
+    const { container } = renderTable({ spectator: true, myUserId: 99 });
+
+    const pos = seatPositions(container);
+    expect(pos['#10']).toBe('seat-e');
+    expect(pos['#20']).toBe('seat-n');
+    expect(pos['#30']).toBe('seat-w');
+    expect(container.querySelectorAll('.seat.seat-s')).toHaveLength(1);
+  });
+});
```

`client/src/features/tichu/useGameTableEffects.test.tsx`:

```diff
     expect(hook.result.current.fly).toBeNull();
   });
 });
+
+/** 좌표를 고정한 DOMRect — jsdom 은 레이아웃을 하지 않아 getBoundingClientRect 가 늘 0 이다. */
+function rect(left: number, top: number, width: number, height: number): DOMRect {
+  return {
+    left,
+    top,
+    width,
+    height,
+    right: left + width,
+    bottom: top + height,
+    x: left,
+    y: top,
+    toJSON: () => ({}),
+  } as DOMRect;
+}
+
+/**
+ * D-132 — 트릭 카드 비행은 낸 사람의 좌석에서 가운데로 날아온다. 경기장 CSS 대로 좌석을 놓는다: `.seat-e` 왼쪽,
+ * `.seat-w` 오른쪽, `.seat-n` 위, `.seat-s` 아래(가운데 트릭은 (500, 300)). `dx`·`dy` 는 가운데 → 좌석 벡터다.
+ */
+function renderPositioned(mySeat: number) {
+  const hook = renderHook((p: Props) =>
+    useGameTableEffects({
+      mySeat,
+      myTeam: 'A',
+      myTurn: false,
+      matchEnded: null,
+      triggerEffect: vi.fn(),
+      playChime: vi.fn(),
+      cardAnimEnabled: true,
+      spectator: mySeat < 0,
+      isInPassing: false,
+      iAmPassSubmitted: false,
+      passCardsBySlot: NO_PASS,
+      sendAction: vi.fn(),
+      ...p,
+    }),
+    { initialProps: BASE },
+  );
+  const arena = document.createElement('div');
+  arena.getBoundingClientRect = () => rect(0, 0, 1000, 600);
+  const at: Record<string, DOMRect> = {
+    s: rect(450, 500, 100, 80),
+    e: rect(20, 260, 100, 80),
+    n: rect(450, 20, 100, 80),
+    w: rect(880, 260, 100, 80),
+  };
+  Object.entries(at).forEach(([pos, r]) => {
+    const seat = document.createElement('div');
+    seat.className = `seat seat-${pos}`;
+    seat.getBoundingClientRect = () => r;
+    arena.appendChild(seat);
+  });
+  const center = document.createElement('div');
+  center.getBoundingClientRect = () => rect(450, 250, 100, 100);
+  hook.result.current.arenaRef.current = arena;
+  hook.result.current.centerTrickRef.current = center;
+  return hook;
+}
+
+describe('useGameTableEffects — 트릭 카드 비행 출발 좌석 (D-132 시계 방향)', () => {
+  beforeEach(() => {
+    vi.stubGlobal('requestAnimationFrame', () => 0);
+    vi.stubGlobal('cancelAnimationFrame', () => {});
+  });
+
+  afterEach(() => {
+    vi.unstubAllGlobals();
+  });
+
+  it('내 다음 차례(+1)가 내면 왼쪽 좌석에서 날아온다', () => {
+    const hook = renderPositioned(0);
+    hook.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 1 });
+
+    expect(hook.result.current.fly?.dx).toBeLessThan(0);
+    expect(hook.result.current.fly?.dy).toBe(0);
+  });
+
+  it('직전 차례(+3)가 내면 오른쪽, 파트너(+2)가 내면 위에서 날아온다', () => {
+    const right = renderPositioned(0);
+    right.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 3 });
+    expect(right.result.current.fly?.dx).toBeGreaterThan(0);
+
+    const partner = renderPositioned(0);
+    partner.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 2 });
+    expect(partner.result.current.fly?.dx).toBe(0);
+    expect(partner.result.current.fly?.dy).toBeLessThan(0);
+  });
+
+  it('관전자에게는 아래 좌석(3) 다음인 좌석 0 이 왼쪽에서 날아온다', () => {
+    const hook = renderPositioned(-1);
+    hook.rerender({ ...BASE, currentTop: FULL_HOUSE, currentTopSeat: 0 });
+
+    expect(hook.result.current.fly?.dx).toBeLessThan(0);
+  });
+});
```

`client/src/features/chat/ArenaChatBubbles.test.tsx`:

```tsx
import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { ArenaChatBubbles } from './ArenaChatBubbles';
import { useRoomChatStore } from './roomChatStore';

/**
 * D-132 — 말풍선도 좌석 클래스·카드 비행과 같은 자리(`viewPosOf`)에서 뜬다(설계 성공 기준 4). 매핑이 갈라지면 상대 좌석은
 * 왼쪽인데 말풍선은 오른쪽에 뜬다 — 말풍선 클래스로 고정한다(경기장 CSS 에서 `-e` 가 왼쪽, `-w` 가 오른쪽).
 */
const PLAYERS = [100, 101, 102, 103];

function say(userId: number, message: string) {
  useRoomChatStore.getState().appendIncoming(
    { eventId: `e-${userId}`, ts: Date.now(), userId, username: `u${userId}`, message },
    false,
  );
}

describe('ArenaChatBubbles — 말풍선 자리 (D-132 시계 방향)', () => {
  beforeEach(() => {
    useRoomChatStore.getState().reset('r-1');
  });

  it('내 다음 차례(+1)의 말풍선은 왼쪽(e)에 뜬다', () => {
    say(101, '안녕');
    const { container } = render(<ArenaChatBubbles playerIds={PLAYERS} mySeat={0} />);

    expect(container.querySelector('.arena-bubble-e')).toHaveTextContent('안녕');
    expect(container.querySelector('.arena-bubble-w')).toBeNull();
  });

  it('직전 차례(+3)의 말풍선은 오른쪽(w)에 뜬다', () => {
    say(103, '반가워');
    const { container } = render(<ArenaChatBubbles playerIds={PLAYERS} mySeat={0} />);

    expect(container.querySelector('.arena-bubble-w')).toHaveTextContent('반가워');
    expect(container.querySelector('.arena-bubble-e')).toBeNull();
  });
});
```

`client/src/features/tichu/ReactionFloats.test.tsx`:

```tsx
import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useReactionStore } from '@/features/chat/reactionStore';
import { ReactionFloats } from './ReactionFloats';

/**
 * D-132 — 이모지 반응도 좌석 클래스·카드 비행과 같은 자리(`viewPosOf`)에서 떠오른다(설계 성공 기준 4). 경기장 CSS 에서
 * `reaction-e` 가 왼쪽, `reaction-w` 가 오른쪽이다.
 */
describe('ReactionFloats — 이모지 자리 (D-132 시계 방향)', () => {
  beforeEach(() => {
    useReactionStore.getState().reset();
  });

  it('직전 차례(+3)가 보낸 이모지는 오른쪽(w)에서 떠오른다', () => {
    useReactionStore.getState().add(3, '👍');
    const { container } = render(<ReactionFloats mySeat={0} />);

    expect(container.querySelector('.reaction-w')).toHaveTextContent('👍');
    expect(container.querySelector('.reaction-e')).toBeNull();
  });

  it('관전자(−1)에게는 좌석 0 이 왼쪽(e)이다 — 아래 좌석(3) 다음', () => {
    useReactionStore.getState().add(0, '🎉');
    const { container } = render(<ReactionFloats mySeat={-1} />);

    expect(container.querySelector('.reaction-e')).toHaveTextContent('🎉');
    expect(container.querySelector('.reaction-w')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- features/tichu/seatView features/tichu/GameTable features/tichu/useGameTableEffects features/chat/ArenaChatBubbles features/tichu/ReactionFloats`
Expected: FAIL — `Test Files  5 failed (5)` · `Tests  10 failed | 19 passed (29)`(`Error: Failed to resolve import "./seatView" from "src/features/tichu/seatView.test.ts"`,
`× GameTable — 좌석 방향 (D-132 시계 방향) > 내 다음 차례(+1)가 왼쪽(seat-e), …`(`expected 'seat-w' to be 'seat-e'`), `× useGameTableEffects — 트릭 카드 비행 출발 좌석
(D-132 시계 방향) > 내 다음 차례(+1)가 내면 왼쪽 좌석에서 날아온다`(`expected 430 to be less than 0`), 말풍선·이모지 4건).

- [ ] **Step 3: 구현**

`client/src/features/tichu/SeatCardStack.tsx`:

```diff
 const BACK_SRC = '/cards/back.svg';
 const MAX_SHOWN = 6;
 
-/** 본인 시점 좌석 위치(S=하단/W=우측/N=상단/E=좌측). 부채 방향을 중앙 지향으로 회전. */
+/**
+ * 본인 시점 좌석 위치(S=하단/W=우측/N=상단/E=좌측). 시계 방향이라 E 가 다음 차례(+1), W 가 직전 차례(+3) 상대다
+ * (`seatView.viewPosOf`, D-132). 부채 방향을 중앙 지향으로 회전.
+ */
 export type SeatViewPos = 's' | 'w' | 'n' | 'e';
 
 /**
```

```diff
  * 정확한 수는 옆 숫자로.
  *
  * #3 — 부채를 좌석 위치별로 중앙을 향하게 회전(실제 보드게임처럼). s=위·n=아래·
- * w=좌(우측좌석이 중앙 향함)·e=우(좌측좌석이 중앙 향함). 실제 회전은 styles.css
- * `.fan-{viewPos}` 가 담당(s 는 기본 위 부채).
+ * w=왼쪽으로 펼침(오른쪽 좌석 — 직전 차례 상대가 중앙을 향함)·e=오른쪽으로 펼침(왼쪽 좌석 — 다음 차례 상대가
+ * 중앙을 향함). 실제 회전은 `styles/parts/06-seat-cardstack.css` 의 `.fan-{viewPos}` 가 담당(s 는 기본 위 부채).
  */
 export function SeatCardStack({
   count,
```

`client/src/features/tichu/seatView.ts`:

```ts
import type { SeatViewPos } from './SeatCardStack';

/** 본인 시점 위치 — 0 = 나(아래 S), +1 = 다음 차례(왼쪽 E), +2 = 파트너(위 N), +3 = 직전 차례(오른쪽 W). */
const VIEW_POS: readonly SeatViewPos[] = ['s', 'e', 'n', 'w'];

/**
 * 티츄 좌석의 본인 시점 위치 (D-132 — 시계 방향). 서버는 +1 이 다음 차례이고(`TurnManager`) 룰의 패스 "좌" 도 +1 이라,
 * 위에서 내려다볼 때 다음 차례가 내 **왼쪽**(`.seat-e` — 경기장 CSS 에서 왼쪽)에 앉는다. 관전자(`mySeat` −1)도 같은 식이라
 * 좌석 3 이 아래에 온다(기존 동작).
 *
 * <p>좌석 클래스·카드 더미 부채·트릭 카드 비행·채팅 말풍선·이모지가 모두 이 함수 하나를 쓴다 — 매핑이 한 곳이라 서로
 * 다른 자리를 가리키지 않는다.
 */
export function viewPosOf(seat: number, mySeat: number): SeatViewPos {
  return VIEW_POS[(seat - mySeat + 4) % 4];
}
```

`client/src/features/tichu/TableArena.tsx`:

```diff
 import { CardChip } from './CardChip';
 import { SeatAvatar } from './SeatAvatar';
 import { SeatCardStack } from './SeatCardStack';
+import { viewPosOf } from './seatView';
 import { ReactionFloats } from './ReactionFloats';
 import { TurnCountdown } from './TurnCountdown';
 import type { FlyState } from './useGameTableEffects';
```

```diff
         const decl = tableView.declarations[seat];
         const declared: 'tichu' | 'grand' | null =
           decl && decl !== 'NONE' ? (decl === 'GRAND_TICHU' ? 'grand' : 'tichu') : null;
-        // Phase 8E — 본인 시점 좌석 매핑. mySeat 기준 회전 후 (S/W/N/E) 배치.
-        // viewIdx 0=South(본인), 1=West(우적), 2=North(파트너), 3=East(좌적).
-        const viewIdx = ((seat - mySeat) + 4) % 4;
-        const viewPos = ['s', 'w', 'n', 'e'][viewIdx];
+        // 본인 시점 좌석 매핑(D-132 시계 방향): 나=South(아래), +1=East(왼쪽·다음 차례 상대),
+        // +2=North(파트너), +3=West(오른쪽·직전 차례 상대).
+        const viewPos = viewPosOf(seat, mySeat);
         return (
           <div
             key={uid}
```

```diff
             {viewPos !== 's' && (
               <SeatCardStack
                 count={tableView.handCounts[seat] ?? 0}
-                viewPos={viewPos as 's' | 'w' | 'n' | 'e'}
+                viewPos={viewPos}
               />
             )}
             {tableView.declarations[seat] && tableView.declarations[seat] !== 'NONE' && (
```

`client/src/features/tichu/useGameTableEffects.ts`:

```diff
 import { cardKey } from '@/types/tichu';
 import type { EffectKind, EffectTone } from './effectStore';
 import type { TichuRoomState } from './tichuStore';
+import { viewPosOf } from './seatView';
 
 /** Phase B(D-78) — 비행 카드 오버레이 상태. left/top 은 arena 기준 도착 중심,
  *  dx/dy 는 출발 좌석 중심까지의 오프셋(여기서 0 으로 transition). */
```

```diff
     const arena = arenaRef.current;
     const center = centerTrickRef.current;
     if (!arena || !center) return;
-    const viewIdx = ((currentTopSeat - mySeat) + 4) % 4;
-    const viewPos = ['s', 'w', 'n', 'e'][viewIdx];
-    const seatEl = arena.querySelector(`.seat-${viewPos}`) as HTMLElement | null;
+    // 낸 사람의 좌석에서 출발한다 — 좌석 클래스와 같은 매핑(D-132 시계 방향, +1 은 왼쪽).
+    const seatEl = arena.querySelector(`.seat-${viewPosOf(currentTopSeat, mySeat)}`) as HTMLElement | null;
     if (!seatEl) return;
 
     const arenaRect = arena.getBoundingClientRect();
```

`client/src/features/chat/ArenaChatBubbles.tsx`:

```diff
 import { useEffect, useState } from 'react';
+import { viewPosOf } from '@/features/tichu/seatView';
 import { useRoomChatStore } from './roomChatStore';
 
 interface ArenaChatBubblesProps {
```

```diff
 /** 메시지 노출 수명(ms). 마지막 ~1s 는 fade. */
 const TTL_MS = 5000;
 const FADE_MS = 1200;
-const VIEW_POS = ['s', 'w', 'n', 'e'] as const;
 
 /**
  * Phase 15(#5) — 경기장 내 채팅 말풍선. `.table-arena` 안에 마운트되어 발신자
```

```diff
   return (
     <>
       {[...bySeat.entries()].map(([seat, m]) => {
-        const viewIdx = ((seat - mySeat) + 4) % 4;
-        const viewPos = VIEW_POS[viewIdx];
+        const viewPos = viewPosOf(seat, mySeat);
         const fading = now - m.ts > TTL_MS - FADE_MS;
         return (
           <div
```

`client/src/features/tichu/ReactionFloats.tsx`:

```diff
 import { useEffect, useState } from 'react';
 import { useReactionStore, REACTION_TTL_MS } from '@/features/chat/reactionStore';
-
-const VIEW_POS = ['s', 'w', 'n', 'e'] as const;
+import { viewPosOf } from './seatView';
 
 /**
  * P2(7) — 좌석에 떠오르는 이모지 반응. `.table-arena` 안에 마운트되어 발신 좌석
```

```diff
   return (
     <>
       {live.map((r) => {
-        const viewIdx = ((r.fromSeat - mySeat) + 4) % 4;
-        const viewPos = VIEW_POS[viewIdx];
+        const viewPos = viewPosOf(r.fromSeat, mySeat);
         // 같은 좌석에서 짧게 연속 도착한 반응이 정확히 겹치지 않도록 약간의 가로 분산.
         const dx = ((r.id % 5) - 2) * 16;
         return (
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- features/tichu/seatView features/tichu/GameTable features/tichu/useGameTableEffects features/chat/ArenaChatBubbles features/tichu/ReactionFloats`
Expected: PASS — `Test Files  5 passed (5)` · `Tests  32 passed (32)`.

Run: `grep -rn "\['s', *'w'" client/src | grep -v "\.test\."` 뒤 `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 첫 명령 출력 없음(옛 매핑이 본 코드에 남지 않음 — 테스트 파일의 좌석 목록 `['s', 'w', 'n', 'e'].forEach` 는 매핑이 아니라 뺀다), 타입 오류 없음, 마지막 두 줄 `Test Files  65 passed (65)` · `Tests  692 passed (692)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/tichu/seatView.test.ts \
  client/src/features/tichu/GameTable.test.tsx \
  client/src/features/tichu/useGameTableEffects.test.tsx \
  client/src/features/chat/ArenaChatBubbles.test.tsx \
  client/src/features/tichu/ReactionFloats.test.tsx \
  client/src/features/tichu/SeatCardStack.tsx \
  client/src/features/tichu/seatView.ts \
  client/src/features/tichu/TableArena.tsx \
  client/src/features/tichu/useGameTableEffects.ts \
  client/src/features/chat/ArenaChatBubbles.tsx \
  client/src/features/tichu/ReactionFloats.tsx
git commit -m "feat(D-132): 티츄 시계 방향 — viewPosOf 로 좌석 매핑 4곳 통합, 내 다음(+1)이 왼쪽

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 클라 — 원카드 테이블 좌석·다음 표시·방향 화살표·버튼 간격

**Files:**
- Modify: `client/src/features/onecard/onecardRules.test.ts`, `client/src/features/onecard/OneCardTable.test.tsx`, `client/src/styles/onecardCssNamespace.test.ts`, `client/src/features/onecard/onecardRules.ts`, `client/src/features/onecard/OneCardSeat.tsx`, `client/src/features/onecard/OneCardCenter.tsx`, `client/src/features/onecard/OneCardTable.tsx`, `client/src/styles/parts/19-onecard-table.css`

**Interfaces:**
- Consumes: Task 2 `tableLayout`·`TableSide`, 원카드 스토어 `turnSeat`(카드를 낸 직후·경쟁 창 동안 −1)·`direction`(+1 정방향 = 시계 방향, Q 로 뒤집힘)·`seats[].eliminated`,
  `OneCardCenter`(`<section aria-label="테이블">`).
- Produces: `nextTurnSeat(turnSeat: number, direction: number, eliminated: boolean[]): number`(`onecardRules.ts` — −1 이면 −1, 진행 방향으로 탈락하지 않은 다음 좌석, 혼자면
  −1. J·K·2인 특례는 반영하지 않음), `OneCardSeat` props `isNext?`·`side?: TableSide | 'bottom'`·`slot?`(`data-side`·`data-slot`, "다음" 꼬리표 `.oc-seat-next`),
  `.oc-felt`(`role="group"` `aria-label="좌석 배치"`, 가운데 칸 = `OneCardCenter`), 방향 배지 `↻ 시계 방향`/`↺ 반시계 방향`(글리프 `aria-hidden`), 다음이 나면 "나" 줄에 "다음",
  `.oc-play + .oc-draw { margin-inline-start: 32px; }`.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/onecard/onecardRules.test.ts`:

```diff
   drawCount,
   isAttack,
   isSuitChange,
+  nextTurnSeat,
   sortForDisplay,
 } from './onecardRules';
 import type { OneCardCard, OneCardSuit } from '@/types/onecard';
```

```diff
     expect(hand[0]).toBe(COLOR);
   });
 });
+
+/**
+ * D-132 — 게임판의 "다음" 표시. 일반 진행 기준이다: J(건너뛰기)·K(한 번 더)·2인 특례는 카드를 내기 전엔 알 수 없으므로
+ * 반영하지 않는다. 탈락 좌석은 서버 진행(`OneCardEngine`)처럼 건너뛴다.
+ */
+describe('다음 차례 — nextTurnSeat (D-132)', () => {
+  const alive = (n: number) => Array.from({ length: n }, () => false);
+
+  it('정방향이면 +1, 마지막 좌석 다음은 0', () => {
+    expect(nextTurnSeat(0, 1, alive(4))).toBe(1);
+    expect(nextTurnSeat(3, 1, alive(4))).toBe(0);
+  });
+
+  it('역방향이면 −1, 0 다음은 마지막 좌석', () => {
+    expect(nextTurnSeat(2, -1, alive(4))).toBe(1);
+    expect(nextTurnSeat(0, -1, alive(4))).toBe(3);
+  });
+
+  it('탈락한 좌석은 건너뛴다 — 역방향에서도', () => {
+    const out = [false, true, true, false, false];
+    expect(nextTurnSeat(0, 1, out)).toBe(3);
+    expect(nextTurnSeat(4, 1, out)).toBe(0);
+    expect(nextTurnSeat(3, -1, out)).toBe(0);
+    expect(nextTurnSeat(0, -1, out)).toBe(4);
+  });
+
+  it('차례가 비어 있으면(−1 — 카드를 낸 직후·경쟁 창) 다음도 없다', () => {
+    expect(nextTurnSeat(-1, 1, alive(4))).toBe(-1);
+    expect(nextTurnSeat(-1, -1, alive(4))).toBe(-1);
+  });
+
+  it('나머지가 모두 탈락해 혼자 남으면 다음이 없다', () => {
+    const lone = [true, true, false, true];
+    expect(nextTurnSeat(2, 1, lone)).toBe(-1);
+    expect(nextTurnSeat(2, -1, lone)).toBe(-1);
+  });
+
+  it('2인은 방향과 무관하게 상대가 다음이다', () => {
+    expect(nextTurnSeat(0, 1, alive(2))).toBe(1);
+    expect(nextTurnSeat(0, -1, alive(2))).toBe(1);
+  });
+
+  it('좌석 수 밖의 차례는 다음이 없다 (방어)', () => {
+    expect(nextTurnSeat(4, 1, alive(4))).toBe(-1);
+  });
+});
```

`client/src/features/onecard/OneCardTable.test.tsx`:

```diff
     expect(countdown()).toBeNull();
   });
 });
+
+/** 좌석 배치 안 좌석들의 [좌석, 옆줄, 옆줄 안 순서] — DOM 순서 그대로. */
+function layoutOf(): [number, string | null, string | null][] {
+  const felt = screen.getByRole('group', { name: '좌석 배치' });
+  return [...felt.querySelectorAll('.oc-seat')].map((el) => [
+    Number(el.getAttribute('data-seat')),
+    el.getAttribute('data-side'),
+    el.getAttribute('data-slot'),
+  ]);
+}
+
+describe('OneCardTable — 테이블 좌석 배치 (D-132)', () => {
+  it.each([
+    [2, [[1, 'top', '0']]],
+    [
+      4,
+      [
+        [1, 'left', '0'],
+        [2, 'top', '0'],
+        [3, 'right', '0'],
+      ],
+    ],
+    [
+      6,
+      [
+        [1, 'left', '0'],
+        [2, 'left', '1'],
+        [3, 'top', '0'],
+        [4, 'right', '0'],
+        [5, 'right', '1'],
+      ],
+    ],
+  ])('%i인: 내 다음 좌석부터 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄에 시계 방향으로 앉는다', (n, expected) => {
+    seed({ seatCount: n, mySeat: 0 });
+    renderTable({ playerIds: playerIds(n) });
+
+    expect(layoutOf()).toEqual(expected);
+  });
+
+  it('내 좌석 기준으로 돈다 — 4인 내 좌석 2 면 3 이 왼쪽, 0 이 위, 1 이 오른쪽', () => {
+    seed({ seatCount: 4, mySeat: 2 });
+    renderTable();
+
+    expect(layoutOf()).toEqual([
+      [3, 'left', '0'],
+      [0, 'top', '0'],
+      [1, 'right', '0'],
+    ]);
+  });
+
+  it('가운데(region "테이블")가 좌석 배치 안에 있고, 내 정보줄은 그 밖 아래다', () => {
+    seed({ seatCount: 4, mySeat: 0 });
+    const { container } = renderTable();
+
+    const felt = screen.getByRole('group', { name: '좌석 배치' });
+    expect(within(felt).getByRole('region', { name: '테이블' })).toBeInTheDocument();
+    expect(felt.querySelector('.oc-me')).toBeNull();
+    expect(container.querySelector('.oc-me')).not.toBeNull();
+  });
+
+  it('관전자는 좌석 0 을 아래에 일반 좌석으로 두고 나머지를 0 기준으로 앉힌다 — "나" 줄은 없다', () => {
+    seed({ seatCount: 4, mySeat: -1 });
+    const { container } = renderTable({ spectator: true });
+
+    expect(layoutOf()).toEqual([
+      [1, 'left', '0'],
+      [2, 'top', '0'],
+      [3, 'right', '0'],
+      [0, 'bottom', '0'],
+    ]);
+    expect(container.querySelector('.oc-me')).toBeNull();
+    expect(screen.queryByText(/\(나\)/)).toBeNull();
+  });
+
+  it('좁은 옆줄 좌석의 긴 이름은 말줄임 대상이고 title 로 전체 이름을 보인다', () => {
+    const long = '아주아주긴이름을가진플레이어';
+    seed({ seatCount: 4, mySeat: 0 });
+    const { container } = renderTable({ usernames: { 101: long } });
+
+    const seat = container.querySelector('[data-seat="1"]') as HTMLElement;
+    expect(seat).toHaveAttribute('data-side', 'left');
+    expect(seat.querySelector('.oc-seat-name')).toHaveAttribute('title', long);
+  });
+});
+
+describe('OneCardTable — 다음 차례 표시 (D-132)', () => {
+  const seatEl = (seat: number) => document.querySelector(`[data-seat="${seat}"]`) as HTMLElement;
+
+  it('지금 차례 좌석은 테두리, 진행 방향의 다음 좌석에는 "다음"', () => {
+    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
+    renderTable();
+
+    expect(seatEl(1)).toHaveClass('oc-seat-turn');
+    expect(within(seatEl(2)).getByText('다음')).toBeInTheDocument();
+    expect(screen.getAllByText('다음')).toHaveLength(1);
+  });
+
+  it('다음이 나면 "나" 줄에 표시한다', () => {
+    seed({ seatCount: 4, mySeat: 0, turnSeat: 3 });
+    const { container } = renderTable();
+
+    expect(within(container.querySelector('.oc-me') as HTMLElement).getByText('다음')).toBeInTheDocument();
+    expect(screen.getAllByText('다음')).toHaveLength(1);
+  });
+
+  it('다음 사람이 탈락했으면 건너뛴다', () => {
+    seed({
+      seatCount: 4,
+      mySeat: 0,
+      turnSeat: 1,
+      seats: [seatOf(0), seatOf(1), seatOf(2, { eliminated: 'BANKRUPT', handCount: 0 }), seatOf(3)],
+    });
+    renderTable();
+
+    expect(within(seatEl(3)).getByText('다음')).toBeInTheDocument();
+    expect(within(seatEl(2)).queryByText('다음')).toBeNull();
+  });
+
+  it('가운데에 진행 방향을 화살표와 글자로 보인다 — 화살표 글리프는 읽지 않는다', () => {
+    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
+    renderTable();
+
+    const dir = within(screen.getByRole('region', { name: '테이블' })).getByText('시계 방향', { exact: false });
+    expect(dir).toHaveTextContent('↻ 시계 방향');
+    expect(dir.querySelector('[aria-hidden="true"]')).toHaveTextContent('↻');
+  });
+
+  it('Q 로 방향이 뒤집히면 화살표와 "다음"이 함께 반대로 — 차례가 비는 동안은 "다음"이 없다', () => {
+    seed({ seatCount: 4, mySeat: 0, turnSeat: 1 });
+    renderTable();
+    const center = () => screen.getByRole('region', { name: '테이블' });
+    expect(within(seatEl(2)).getByText('다음')).toBeInTheDocument();
+
+    act(() => {
+      onecardRoomSink.applyEvent({
+        type: 'CARD_PLAYED',
+        seq: 2,
+        payload: { seat: 1, card: c('HEART', 12), declaredSuit: null, handCount: 4, attackStack: 0, direction: -1 },
+      } as never);
+    });
+    expect(center()).toHaveTextContent('↺ 반시계 방향');
+    expect(screen.queryByText('다음')).toBeNull();
+
+    act(() => {
+      onecardRoomSink.applyEvent({ type: 'TURN_CHANGED', seq: 3, payload: { seat: 0, direction: -1, attackStack: 0 } } as never);
+    });
+    expect(within(seatEl(3)).getByText('다음')).toBeInTheDocument();
+    expect(screen.getAllByText('다음')).toHaveLength(1);
+  });
+
+  it('관전자에게는 "다음"이 아래 좌석(0)에 붙고 "나" 줄로 가지 않는다', () => {
+    seed({ seatCount: 4, mySeat: -1, turnSeat: 3 });
+    const { container } = renderTable({ spectator: true });
+
+    expect(seatEl(0)).toHaveAttribute('data-side', 'bottom');
+    expect(within(seatEl(0)).getByText('다음')).toBeInTheDocument();
+    expect(container.querySelector('.oc-me')).toBeNull();
+  });
+});
```

`client/src/styles/onecardCssNamespace.test.ts`:

```diff
 }
 
 /**
+ * 같은 특이도에선 뒤 규칙이 이긴다 — `selector` 를 포함한 규칙들 중 `prop` 의 **마지막** 값(D-132). `declared` 는 첫 값이라
+ * `.oc-felt-left, .oc-felt-right { flex-direction: column }` 그룹 규칙을 읽어 `.oc-felt-left` 의 `column-reverse` 를 놓친다.
+ */
+function lastDeclared(selector: string, prop: string): string | undefined {
+  const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
+  let last: string | undefined;
+  for (const body of bodiesOf(selector)) {
+    const m = decl.exec(body);
+    if (m) last = m[2].replace(/\s+/g, ' ').trim();
+  }
+  return last;
+}
+
+/**
  * jsdom(css:false)은 캐스케이드를 계산하지 않는다 — 전역 규칙이 OC 규칙을 이기는지는 원문으로 고정한다.
  * `01-base.css` 의 `button:hover:not(:disabled)`(0,2,1)·`button:disabled { opacity: .5 }` 와 `08-hand.css` 의
  * `.card-chip:hover:not(:disabled)`(0,3,0)는 단일 클래스(0,1,0) 규칙을 이긴다. 그래서 hover·disabled 상태를 19 가 직접
```

```diff
   });
 });
 
+/** D-132 — 테이블 좌석 배치와 버튼 간격. jsdom(css:false)은 레이아웃을 재지 않으므로 원문으로 고정한다. */
+describe('19-onecard-table.css — 테이블 배치 (D-132)', () => {
+  it('내기와 먹기 사이를 손가락 너비(32px) 띄운다 — 붙어 있으면 먹기를 잘못 누른다', () => {
+    expect(declared('.oc-play + .oc-draw', 'margin-inline-start')).toBe('32px');
+  });
+
+  it('테이블 색은 토큰이고 라이트 테마 값을 따로 둔다', () => {
+    const dark = tokensOf('.oc-table').get('--oc-felt-bg');
+    const light = tokensOf('html:not(.dark) .oc-table').get('--oc-felt-bg');
+
+    expect(dark, '.oc-table --oc-felt-bg').toBeTruthy();
+    expect(light, 'html:not(.dark) .oc-table --oc-felt-bg').toBeTruthy();
+    expect(light).not.toBe(dark);
+    expect(declared('.oc-felt', 'background')).toBe('var(--oc-felt-bg)');
+  });
+
+  it('옆줄 좌석 폭은 화면 폭을 따른다 — 폭 미디어 없이 clamp 로', () => {
+    expect(declared('.oc-felt-left .oc-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
+    expect(declared('.oc-felt-right .oc-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
+  });
+
+  it('옆줄 사이 가운데 칸이 좁아지면 더미와 맨 위 카드가 줄을 바꾼다 — 넘쳐 옆 좌석을 덮지 않는다', () => {
+    expect(declared('.oc-felt .oc-piles', 'flex-wrap')).toBe('wrap');
+  });
+
+  // 좌석 DOM 의 data-side 는 표식일 뿐 — 자리를 잡는 것은 이 CSS 다. 여기를 반시계로 뒤집으면 컴포넌트 테스트는 모두 통과한다.
+  it('시계 방향 자리 — 왼쪽 옆줄은 왼쪽 칸에서 아래→위, 오른쪽 옆줄은 오른쪽 칸에서 위→아래', () => {
+    expect(lastDeclared('.oc-felt', 'grid-template-areas')).toBe(
+      "'top top top' 'left center right' 'bottom bottom bottom'",
+    );
+    expect(lastDeclared('.oc-felt-left', 'grid-area')).toBe('left');
+    expect(lastDeclared('.oc-felt-left', 'flex-direction')).toBe('column-reverse');
+    expect(lastDeclared('.oc-felt-right', 'grid-area')).toBe('right');
+    expect(lastDeclared('.oc-felt-right', 'flex-direction')).toBe('column');
+  });
+
+  // 390px 옆줄 좌석 내용 ≈65px 에 아바타 28px 면 한 줄 이름 칸은 ≈31px(두 글자)다. 모바일은 title 툴팁이 뜨지 않는다.
+  it('좁은 좌석에서는 이름이 아바타 아래 줄로 내려간다 — 한 줄에 아바타 + 이름 44px 가 안 들어가면', () => {
+    expect(lastDeclared('.oc-seat-top', 'flex-wrap')).toBe('wrap');
+    expect(lastDeclared('.oc-seat-name', 'flex')).toBe('1 1 44px');
+  });
+
+  // 390px 가운데 칸 ≈138px — 더미 뒷면 62px + 문구 + 맨 위 카드 48px 가 한 줄에 안 들어가 천이 ≈80px 높아졌다.
+  it('가운데 칸이 좁으면 더미 뒷면·문구·간격이 폭을 따라 줄어 더미와 맨 위 카드가 한 줄에 든다', () => {
+    expect(lastDeclared('.oc-felt .oc-pile-back', 'width')).toBe('clamp(40px, 11vw, 62px)');
+    expect(lastDeclared('.oc-felt .oc-pile-count', 'max-width')).toBe('clamp(50px, 14vw, 120px)');
+    expect(lastDeclared('.oc-felt .oc-piles', 'gap')).toBe('8px clamp(6px, 2vw, 24px)');
+  });
+
+  // .oc-badge 는 nowrap 이라 "guest-ab12cd34 차례" ≈137px 가 가운데 칸(≈126px)을 넘어 옆줄 좌석에 걸쳤다. 헤더 배지는 그대로.
+  it('가운데 칸 배지는 칸 안에서 줄을 바꾼다 — 긴 이름의 "차례" 배지가 옆줄 좌석으로 넘치지 않는다', () => {
+    expect(lastDeclared('.oc-felt .oc-center-info .oc-badge', 'white-space')).toBe('normal');
+    expect(lastDeclared('.oc-felt .oc-center-info .oc-badge', 'max-width')).toBe('100%');
+  });
+});
+
 describe('index.css — 캐스케이드 순서', () => {
   const order = [...indexCssSource.matchAll(/@import '\.\/parts\/(\d+)-[^']+'/g)].map((m) =>
     Number(m[1]),
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- onecard`
Expected: FAIL — `Test Files  3 failed | 6 passed (9)` · `Tests  28 failed | 192 passed (220)`(`TypeError: nextTurnSeat is not a function`, `Unable to find an accessible element with the
role "group" and name "좌석 배치"`, `Unable to find an element with the text: 다음`, `… text: 시계 방향`, CSS 원문 단언 — `expected undefined to be '32px'` 등).

- [ ] **Step 3: 구현**

`client/src/features/onecard/onecardRules.ts`:

```diff
     c.joker ? 100 + (c.joker === 'BLACK' ? 0 : 1) : SUIT_ORDER[c.suit!] * 20 + c.rank;
   return [...hand].sort((a, b) => order(a) - order(b));
 }
+
+/**
+ * 일반 진행 기준 다음 차례 (D-132 — 게임판의 "다음" 표시). 진행 방향(`direction` ≥ 0 이면 +1, 아니면 −1)으로 탈락하지 않은 다음
+ * 좌석이고, 차례가 비어 있으면(`turnSeat` −1 — 카드를 낸 직후·경쟁 창) 또는 나머지가 모두 탈락했으면 −1 이다. J(건너뛰기)·
+ * K(한 번 더)·2인 특례는 카드를 내기 전엔 알 수 없으므로 반영하지 않는다. 표시 전용이고 차례의 권위는 서버다.
+ *
+ * @param eliminated 좌석 번호별 탈락 여부 — 길이가 곧 좌석 수다.
+ */
+export function nextTurnSeat(turnSeat: number, direction: number, eliminated: boolean[]): number {
+  const n = eliminated.length;
+  if (turnSeat < 0 || turnSeat >= n) return -1;
+  const step = direction < 0 ? -1 : 1;
+  for (let i = 1; i < n; i++) {
+    const seat = (((turnSeat + step * i) % n) + n) % n;
+    if (!eliminated[seat]) return seat;
+  }
+  return -1;
+}
```

`client/src/features/onecard/OneCardSeat.tsx`:

```diff
 import { avatarSrc } from '@/api/avatar';
 import { animalFor } from '@/components/avatarGlyph';
+import type { TableSide } from '@/components/seatOrder';
 import type { EliminationReason, OneCardSeatView } from '@/types/onecard';
 
 const ACCENTS = [
```

```diff
   username?: string;
   isBot?: boolean;
   isTurn?: boolean;
+  /** D-132 — 일반 진행 기준 다음 차례. */
+  isNext?: boolean;
   isDisconnected?: boolean;
+  /** D-132 — 테이블 자리(관전자의 아래 좌석은 `bottom`). 자리는 게임판의 옆줄 래퍼가 잡고, 이 값은 표식이다. */
+  side?: TableSide | 'bottom';
+  /** 옆줄 안 순서(시계 방향, 0 부터). */
+  slot?: number;
 }
 
 /**
  * 상대 좌석 하나 — 이름과 남은 손패 **장수**만 보인다(State Hiding). 1장 남으면 표시를 세우고, 탈락하면 사유를 단다.
- * 스컬킹처럼 정상 흐름의 `auto-fit` 그리드에 놓여 2~6인이 폭 미디어 없이 줄바꿈된다.
+ * 테이블(D-132)의 옆줄·윗줄에 놓인다 — 옆줄 칸은 좁아질 수 있어 이름은 말줄임하고 `title` 로 전체 이름을 보인다.
  */
 export function OneCardSeat({
   seat,
```

```diff
   username,
   isBot = false,
   isTurn = false,
+  isNext = false,
   isDisconnected = false,
+  side,
+  slot,
 }: Props) {
   const glyph = isBot ? '🤖' : animalFor(userId, seat.seat);
   const name = username ?? (isBot ? '봇' : `#${userId ?? seat.seat}`);
```

```diff
       className={className}
       style={{ ['--oc-accent' as string]: seatAccent(seat.seat) }}
       data-seat={seat.seat}
+      data-side={side}
+      data-slot={slot}
     >
+      {isNext && <span className="oc-badge oc-seat-next">다음</span>}
       <div className="oc-seat-top">
         <span className="oc-seat-avatar" aria-hidden>
           {!isBot && userId != null ? (
```

`client/src/features/onecard/OneCardCenter.tsx`:

```diff
 
       <div className="oc-center-info">
         {attackStack > 0 && <span className="oc-badge oc-attack">공격 +{attackStack}</span>}
-        <span className="oc-badge">{direction >= 0 ? '진행 → 정방향' : '진행 ← 역방향'}</span>
+        {/* D-132 — 진행 방향. 글리프는 읽지 않고 글자로 읽는다. 좌석이 시계 방향으로 앉으므로 +1 이 시계 방향이다. */}
+        <span className="oc-badge oc-direction">
+          <span className="oc-direction-arrow" aria-hidden="true">
+            {direction >= 0 ? '↻' : '↺'}
+          </span>{' '}
+          {direction >= 0 ? '시계 방향' : '반시계 방향'}
+        </span>
         {raceOpen ? (
           <span className="oc-badge oc-race-status">원카드 경쟁 중</span>
         ) : (
```

`client/src/features/onecard/OneCardTable.tsx`:

```diff
 import { useAuthStore } from '@/features/auth/authStore';
 import { useStompRoom } from '@/ws/useStompRoom';
 import { ReconnectBanner } from '@/components/ReconnectBanner';
-import { seatMinWidth, viewOrder } from '@/components/seatOrder';
+import { seatMinWidth, tableLayout, type TableSide } from '@/components/seatOrder';
 import { RoomChat } from '@/features/chat/RoomChat';
 import { useRoomChatStore } from '@/features/chat/roomChatStore';
 import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
```

```diff
 import { cardKey } from '@/types/onecard';
 import { onecardRoomSink } from './onecardRoomSink';
 import { useOneCardStore, type PressAction } from './onecardStore';
-import { isSuitChange } from './onecardRules';
+import { isSuitChange, nextTurnSeat } from './onecardRules';
 import { ELIMINATED_LABEL, OneCardSeat, seatAccent } from './OneCardSeat';
 import { OneCardCenter, raceResultText } from './OneCardCenter';
 import { OneCardHand } from './OneCardHand';
```

```diff
 /**
  * 원카드 게임판 조립 루트 (D-129). 스컬킹처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103) — 다른 게임의 코드
  * 경로는 실행되지 않는다. 좌석 수의 권위값은 `tableView.seats.length` 이고 `playerIds` 는 이름·아바타 조회용이다.
+ *
+ * <p>상대는 테이블처럼 내 기준 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄에 시계 방향으로 앉고(`tableLayout`, D-132), 가운데 칸에 더미·방향
+ * 화살표가 놓인다. 지금 차례는 테두리, 일반 진행 기준 다음 차례는 "다음" 표시다.
  *
  * <p>경쟁 창은 화면 위 오버레이 버튼(`RaceButton`)과 aria-live 알림으로 보여 준다. 락 경합 `BUSY` 를 받은 누름은
  * 창이 열린 동안 최대 2번 다시 보낸다(스토어가 신호를 올리고 여기서 보낸다).
```

```diff
   const s = useOneCardStore();
   const seatCount = s.seats.length || playerIds.length;
   const mySeat = spectator ? -1 : s.mySeat;
-  const opponents = viewOrder(seatCount, mySeat);
+  const { bottomSeat, slots } = tableLayout(seatCount, mySeat);
   const me = s.seats.find((x) => x.seat === mySeat) ?? null;
+  // D-132 — 일반 진행 기준 다음 차례. 차례가 비면(카드 직후·경쟁 창·종료) 테두리와 함께 사라진다.
+  const eliminated = Array.from(
+    { length: seatCount },
+    (_, i) => s.seats.find((x) => x.seat === i)?.eliminated != null,
+  );
+  const nextSeat = nextTurnSeat(s.turnSeat, s.direction, eliminated);
   const iAmOut = me?.eliminated != null;
   const raceOpen = s.race !== null;
   const myTurn =
```

```diff
     const uid = playerIds[seat];
     if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
     return usernames[uid] ?? `#${uid ?? seat}`;
+  };
+
+  const seatAt = (seat: number, side: TableSide | 'bottom', slot: number) => {
+    const view = s.seats.find((x) => x.seat === seat);
+    if (!view) return null;
+    return (
+      <OneCardSeat
+        key={seat}
+        seat={view}
+        userId={playerIds[seat]}
+        username={usernames[playerIds[seat]]}
+        isBot={botSeats.includes(seat)}
+        isTurn={s.turnSeat === seat}
+        isNext={nextSeat === seat}
+        isDisconnected={s.disconnectedSeats.has(seat)}
+        side={side}
+        slot={slot}
+      />
+    );
+  };
+  // 옆줄마다 묶는다 — 좌석 DOM 순서가 slots 순서(= 진행 순서)라 스크린리더가 다음 사람부터 읽는다.
+  const sideRow = (side: TableSide) => {
+    const row = slots.filter((x) => x.side === side);
+    if (row.length === 0) return null;
+    return <div className={`oc-felt-${side}`}>{row.map((x) => seatAt(x.seat, side, x.index))}</div>;
   };
 
   // BUSY 재시도 — 스토어가 신호(retryNonce)를 올리면 기다리던 누름을 다시 보낸다. 지금도 이 창을 기다리는 누름만
```

```diff
         </p>
       )}
 
-      <div className="oc-seats">
-        {opponents.map((seat) => {
-          const view = s.seats.find((x) => x.seat === seat);
-          if (!view) return null;
-          return (
-            <OneCardSeat
-              key={seat}
-              seat={view}
-              userId={playerIds[seat]}
-              username={usernames[playerIds[seat]]}
-              isBot={botSeats.includes(seat)}
-              isTurn={s.turnSeat === seat}
-              isDisconnected={s.disconnectedSeats.has(seat)}
-            />
-          );
-        })}
+      {/* D-132 — 테이블. 내 자리는 아래(아래의 "나" 줄)이고, 관전자는 좌석 0 을 아래 칸에 일반 좌석으로 둔다. */}
+      <div className="oc-felt" role="group" aria-label="좌석 배치">
+        {sideRow('left')}
+        {sideRow('top')}
+        {sideRow('right')}
+
+        <OneCardCenter
+          topCard={s.topCard}
+          declaredSuit={s.declaredSuit}
+          attackStack={s.attackStack}
+          direction={s.direction}
+          drawPileCount={s.drawPileCount}
+          turnName={s.turnSeat >= 0 ? nameOf(s.turnSeat) : null}
+          raceOpen={raceOpen}
+          lastRace={s.lastRace}
+          late={s.raceNotice === 'LATE'}
+          nameOf={nameOf}
+          // D-131 — 서버 남은 시간 기준 카운트다운. 방이 끝났으면(강제 종료 — 결과 없음) 세지 않는다.
+          countdown={roomFinished ? null : <OneCardTurnCountdown turnSeconds={turnSeconds} />}
+        />
+
+        {bottomSeat !== null && <div className="oc-felt-bottom">{seatAt(bottomSeat, 'bottom', 0)}</div>}
       </div>
-
-      <OneCardCenter
-        topCard={s.topCard}
-        declaredSuit={s.declaredSuit}
-        attackStack={s.attackStack}
-        direction={s.direction}
-        drawPileCount={s.drawPileCount}
-        turnName={s.turnSeat >= 0 ? nameOf(s.turnSeat) : null}
-        raceOpen={raceOpen}
-        lastRace={s.lastRace}
-        late={s.raceNotice === 'LATE'}
-        nameOf={nameOf}
-        // D-131 — 서버 남은 시간 기준 카운트다운. 방이 끝났으면(강제 종료 — 결과 없음) 세지 않는다.
-        countdown={roomFinished ? null : <OneCardTurnCountdown turnSeconds={turnSeconds} />}
-      />
 
       {s.result && (
         <OneCardMatchEnd result={s.result} mySeat={mySeat} nameOf={nameOf} onExit={onExit} />
```

```diff
             <strong>{nameOf(mySeat)} (나)</strong>
             <span className="oc-me-count">손패 {s.hand.length}장</span>
             {myTurn && <span className="oc-badge oc-badge-turn">내 차례</span>}
+            {nextSeat === mySeat && <span className="oc-badge oc-seat-next">다음</span>}
             {myTurn && s.attackStack > 0 && (
               <span className="oc-badge oc-attack">공격받는 중 +{s.attackStack}</span>
             )}
```

`client/src/styles/parts/19-onecard-table.css`:

```diff
 /* 원카드 게임판 (D-129) — 스컬킹 게임판(18)과 같은 규칙을 따른다.
  *
  * ⚠ 캐스케이드: index.css 에서 18 다음, **17-responsive 앞**에 import 된다. 그래서 이 파일에는 폭 미디어가
- *   **0개**다 — 좌석은 auto-fit 그리드라 브레이크포인트 없이 줄바꿈되고 손패는 여러 줄로 감긴다. 카드 축소는
+ *   **0개**다 — 좌석은 테이블 격자(D-132)라 옆줄 폭을 clamp() 로 줄여 브레이크포인트 없이 맞추고 손패는 여러 줄로 감긴다. 카드 축소는
  *   17 의 공용 규칙(.card-chip, .action-bar)을 상속한다. 폭·터치 규칙이 불가피하면 17-responsive.css **맨 끝**에
  *   .oc-* 블록만 추가한다(D-88).
  *
```

```diff
   --oc-accent-3: #e0b32a;
   --oc-accent-4: #7b5cd6;
   --oc-accent-5: #2fb5c0;
+  /* D-132 — 테이블 천. 라이트 테마 값은 아래 블록. */
+  --oc-felt-bg: #173a2a;
+  --oc-felt-edge: #2c6046;
 
   position: relative;
   display: flex;
```

```diff
   gap: 10px;
   padding: 10px;
   min-height: 100vh;
+}
+
+/* 좌석·가운데는 자기 배경(--color-bg-*)이 있어 천 위 글자색은 문제되지 않는다 — 천만 테마를 따라 밝힌다. */
+html:not(.dark) .oc-table {
+  --oc-felt-bg: #dbeee1;
+  --oc-felt-edge: #9fcbb0;
 }
 
 /* ── 헤더 ─────────────────────────────────────────────────────────── */
```

```diff
   cursor: pointer;
 }
 
-/* ── 상대 좌석 그리드 — 폭 미디어 없이 2~6인 대응 ─────────────────── */
-/* max 는 1fr 이어야 auto-fit 이 min 기준으로 열을 센다(스컬킹 18 의 C5 실측과 같은 이유). */
-.oc-seats {
+/* ── 테이블 (D-132) — 내 기준 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄, 시계 방향. 폭 미디어 없이 2~6인 ─────── */
+/* 자리 계산은 `tableLayout`(components/seatOrder.ts), 배치는 여기. 옆줄 열은 좌석이 없으면 auto 로 접힌다(좌우는 늘 같은 수라
+   대칭). 내 자리(아래)는 천 밖의 "나" 줄이고, 관전자만 좌석 0 을 아래 칸에 둔다. */
+.oc-felt {
   display: grid;
-  grid-template-columns: repeat(auto-fit, minmax(var(--oc-seat-min), 1fr));
-  justify-items: center;
+  grid-template-columns: auto minmax(0, 1fr) auto;
+  grid-template-rows: auto 1fr auto;
+  grid-template-areas:
+    'top top top'
+    'left center right'
+    'bottom bottom bottom';
   gap: 8px;
+  padding: 12px 8px;
+  border-radius: 24px;
+  border: 2px solid var(--oc-felt-edge);
+  background: var(--oc-felt-bg);
+}
+
+.oc-felt-top,
+.oc-felt-bottom {
+  display: flex;
+  justify-content: center;
+  gap: 8px;
+  min-width: 0;
+}
+
+.oc-felt-top {
+  grid-area: top;
+}
+
+.oc-felt-bottom {
+  grid-area: bottom;
+}
+
+/* 옆줄은 세로로 고르게 — 1명이면 가운데, 2명이면 위·아래. 간격은 "다음" 꼬리표(좌석 위로 9px)가 위 좌석에 닿지 않게. */
+.oc-felt-left,
+.oc-felt-right {
+  display: flex;
+  flex-direction: column;
+  justify-content: space-around;
+  gap: 14px;
+  min-width: 0;
+}
+
+/* 왼쪽 옆줄은 아래→위다(index 0 = 내 바로 왼쪽 = 다음 차례). DOM 순서는 진행 순서 그대로 두고 보이는 순서만 뒤집는다. */
+.oc-felt-left {
+  grid-area: left;
+  flex-direction: column-reverse;
+}
+
+.oc-felt-right {
+  grid-area: right;
+}
+
+.oc-felt > .oc-center {
+  grid-area: center;
+  min-width: 0;
+  padding: 8px 6px;
+}
+
+/* 390px 3인 이상이면 가운데 칸이 ≈138px(내용 ≈126px)다. 더미 뒷면(62px)·"뽑을 더미 N장"(≈80px)·맨 위 카드(≤480px 48px)를
+   그대로 두면 한 줄에 안 들어가 두 줄(천 +80px)이 되므로, 뒷면·문구 폭·간격을 화면 폭에 맞춰 줄인다(390px ≈110px — 문구는
+   "뽑을 더미 / N장" 두 줄). 그래도 넘치면(7 의 지정 무늬 배지가 붙은 직후) 줄을 바꾼다 — 안 바꾸면 넘쳐 오른쪽 옆줄 좌석을
+   덮는다. 넓은 화면은 62px·120px·24px 라 지금처럼 한 줄이다. */
+.oc-felt .oc-piles {
+  flex-wrap: wrap;
+  gap: 8px clamp(6px, 2vw, 24px);
+}
+
+.oc-felt .oc-pile-back {
+  width: clamp(40px, 11vw, 62px);
+  height: auto;
+  aspect-ratio: 62 / 88;
+}
+
+.oc-felt .oc-pile-count {
+  max-width: clamp(50px, 14vw, 120px);
+  text-align: center;
+}
+
+/* 가운데 배지는 칸 안에서 줄을 바꾼다 — .oc-badge 는 nowrap 이라 "guest-ab12cd34 차례"(≈137px)가 가운데 칸을 넘어 옆줄 좌석에
+   걸쳤다. 헤더 배지는 그대로 한 줄. */
+.oc-felt .oc-center-info .oc-badge {
+  max-width: 100%;
+  white-space: normal;
+  overflow-wrap: anywhere;
+}
+
+/* 옆줄 좌석은 화면 폭을 따라 64~150px, 윗줄·아래 좌석은 기준 폭(--oc-seat-min)에서 최대 168px. 이름은 말줄임(.oc-seat-name). */
+.oc-felt-left .oc-seat,
+.oc-felt-right .oc-seat {
+  width: clamp(64px, 22vw, 150px);
+}
+
+.oc-felt-top .oc-seat,
+.oc-felt-bottom .oc-seat {
+  flex: 1 1 var(--oc-seat-min);
 }
 
 .oc-seat {
```

```diff
     0 0 0 4px var(--oc-race-catch);
 }
 
+/* 좁은 좌석(내용 < 아바타 28 + 간격 6 + 이름 44 = 78px — 390px 옆줄 ≈65px)에서는 이름이 아바타 아래 줄로 내려가 좌석 폭을 다
+   쓴다(≈8자). 한 줄이면 이름 칸이 ≈31px(두 글자 + 말줄임)라 봇이 모두 "bo…"로 보이고, 모바일은 title 툴팁이 뜨지 않는다.
+   윗줄 좌석·넓은 화면 옆줄은 한 줄 그대로다(폭 미디어 없음). */
 .oc-seat-top {
   display: flex;
+  flex-wrap: wrap;
   align-items: center;
   gap: 6px;
+  row-gap: 2px;
   min-width: 0;
 }
 
```

```diff
 }
 
 .oc-seat-name {
+  flex: 1 1 44px;
+  min-width: 0;
   overflow: hidden;
   text-overflow: ellipsis;
   white-space: nowrap;
```

```diff
 .oc-seat-tag {
   align-self: flex-start;
   font-size: 11px;
+}
+
+/* D-132 — 일반 진행 기준 다음 차례. 좌석 안에서는 위 테두리에 걸친 꼬리표라 좁은 옆줄 좌석의 이름 칸을 줄이지 않는다.
+   "나" 줄에서는 다른 배지처럼 줄 안에 놓인다. */
+.oc-seat-next {
+  border-color: var(--oc-accent);
+  background: var(--color-bg-surface);
+  font-size: 11px;
+  font-weight: 700;
+}
+
+.oc-seat > .oc-seat-next {
+  position: absolute;
+  top: -9px;
+  right: 6px;
+  padding: 0 6px;
 }
 
 /* ── 가운데 — 더미·맨 위 카드·상태 ─────────────────────────────────── */
```

```diff
   gap: 6px;
 }
 
+/* D-132 — 진행 방향 화살표(↻ 시계 / ↺ 반시계). 글리프는 aria-hidden, 방향은 뒤의 글자로 읽힌다. */
+.oc-direction {
+  font-weight: 600;
+}
+
+.oc-direction-arrow {
+  font-size: 15px;
+  line-height: 1;
+}
+
 /* D-131 — 차례 옆 남은 시간. 숫자 폭이 흔들리지 않게 고정폭 숫자, 5초 이하는 경고 테두리·배경(아래 .oc-attack 과 같은
    규칙 — 이 블록보다 뒤에 두어 굵기 700 이 이긴다. 글자색은 그대로 — 대비). */
 .oc-countdown {
```

```diff
 
 .oc-draw {
   background: #7a6bd0;
+}
+
+/* D-132 — "먹기"는 공격 누적이면 여러 장을 먹는 되돌릴 수 없는 동작이다. 엄지로 "내기"를 누르려다 "먹기"를 누르지 않게
+   손가락 너비만큼 띄운다(줄이 감겨 아래로 내려가면 버튼 높이 44px 가 이미 그만큼 떨어뜨린다). */
+.oc-play + .oc-draw {
+  margin-inline-start: 32px;
 }
 
 /* 반투명 흰색은 라이트 테마에서 흰 글자가 된다 — 불투명 회색으로 고정한다(스컬킹 C5 실측과 같은 이유). 전역
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- onecard`
Expected: PASS — `Test Files  9 passed (9)` · `Tests  220 passed (220)`(기존 단언 — 좌석 수·`data-seat`·`region "테이블"`·차례 테두리·경쟁·카운트다운 — 무변경으로 통과).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  65 passed (65)` · `Tests  720 passed (720)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/onecard/onecardRules.test.ts \
  client/src/features/onecard/OneCardTable.test.tsx \
  client/src/styles/onecardCssNamespace.test.ts \
  client/src/features/onecard/onecardRules.ts \
  client/src/features/onecard/OneCardSeat.tsx \
  client/src/features/onecard/OneCardCenter.tsx \
  client/src/features/onecard/OneCardTable.tsx \
  client/src/styles/parts/19-onecard-table.css
git commit -m "feat(D-132): 원카드 테이블 좌석 — 시계 방향 격자, 다음 차례 표시, 방향 화살표, 내기·먹기 간격 32px

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 클라 — 스컬킹 테이블 좌석·다음 표시·좁은 화면 규칙

**Files:**
- Modify: `client/src/features/skullking/seatLayout.test.ts`, `client/src/features/skullking/SkullKingTable.test.tsx`, `client/src/styles/skullkingCssNamespace.test.ts`, `client/src/features/skullking/seatLayout.ts`, `client/src/features/skullking/SkullSeatCard.tsx`, `client/src/features/skullking/SkullKingTable.tsx`, `client/src/styles/parts/18-skullking-table.css`, `client/src/styles/parts/17-responsive.css`, `client/src/styles/cssSources.ts`

**Interfaces:**
- Consumes: Task 2 `tableLayout`·`TableSide`, 스컬킹 스토어 `currentTurnSeat`(입찰·라운드 정리 중 −1)·`trick`·`settledTrick`(트릭이 막 끝난 순간), 트릭 레일 `SkullTrickRow`(D-103 — 유지).
- Produces: `nextTurnSeat(currentTurnSeat: number, seatCount: number, trickLength: number): number`(`seatLayout.ts` — −1 이거나 지금 사람이 트릭의 마지막이면 −1, 아니면
  `(currentTurnSeat + 1) % seatCount`, 탈주 좌석도 건너뛰지 않음), 게임판은 트릭이 막 끝나 승자의 리드를 기다리는 동안(`trick` 빔 + `settledTrick.winnerSeat !== currentTurnSeat`)
  "다음"을 숨긴다. `SkullSeatCard` props `isNext?`·`side?`·`slot?`(옆줄이면 `sk-seat-side`, 손패 통계 `sk-stat-hand`), `.sk-felt`(`role="group"` `aria-label="좌석 배치"`,
  가운데 칸 = `↻ 시계 방향` + 트릭 레일), `17-responsive.css` 맨 끝 ≤480px 블록(옆줄 손패 숨김 + 가운데 칸을 옆줄 아래 전체 폭), `cssSources.responsiveCssSource`.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/skullking/seatLayout.test.ts`:

```diff
 import {
   isLegalPlayHint,
   leadSuitOf,
+  nextTurnSeat,
   seatAccent,
   seatMinWidth,
   viewOrder,
```

```diff
     expect(isLegalPlayHint(suit('BLACK', 5), voidInGreen, 'GREEN')).toBe(true);
   });
 });
+
+/**
+ * D-132 — 게임판의 "다음" 표시. 서버 진행은 트릭 안에서 +1(`TrickState`)이고, 트릭의 마지막 사람 다음은 트릭 승자가 리드하므로
+ * 아직 모른다. 탈주 좌석은 유령이 대신 두므로 건너뛰지 않는다(룰 ⑱ — 함수는 탈주를 모른다).
+ */
+describe('nextTurnSeat — 일반 진행 기준 다음 차례 (D-132)', () => {
+  it('트릭 중이면 +1, 마지막 좌석 다음은 0', () => {
+    expect(nextTurnSeat(1, 4, 0)).toBe(2);
+    expect(nextTurnSeat(3, 4, 1)).toBe(0);
+  });
+
+  it('입찰·라운드 정리 중(차례 −1)이면 없다 — 트릭이 막 끝난 순간은 게임판이 따로 다룬다', () => {
+    expect(nextTurnSeat(-1, 4, 0)).toBe(-1);
+    expect(nextTurnSeat(-1, 8, 3)).toBe(-1);
+  });
+
+  it('지금 사람이 이번 트릭의 마지막이면 없다 — 다음 리드는 트릭 승자라 아직 모른다', () => {
+    expect(nextTurnSeat(3, 4, 3)).toBe(-1);
+    expect(nextTurnSeat(0, 2, 1)).toBe(-1);
+  });
+
+  it('2~8인 × 리드 좌석 전수 — 트릭의 마지막 사람 전까지는 늘 다음 좌석(+1)', () => {
+    for (const n of SEAT_COUNTS) {
+      for (let lead = 0; lead < n; lead++) {
+        for (let played = 0; played < n; played++) {
+          const current = (lead + played) % n;
+          const expected = played + 1 < n ? (current + 1) % n : -1;
+          expect(nextTurnSeat(current, n, played), `n=${n} lead=${lead} played=${played}`).toBe(expected);
+        }
+      }
+    }
+  });
+});
```

`client/src/features/skullking/SkullKingTable.test.tsx`:

```diff
-import { fireEvent, render, screen, within } from '@testing-library/react';
+import { act, fireEvent, render, screen, within } from '@testing-library/react';
 import { beforeEach, describe, expect, it, vi } from 'vitest';
 import { SkullKingTable } from './SkullKingTable';
 import { useSkullKingStore } from './skullkingStore';
 import { useAuthStore } from '@/features/auth/authStore';
 import type {
   CompletedRoundView,
   MatchEndedPayload,
+  PlayedCardView,
   SeatView,
   SkullCard,
   SkullKingPhase,
   SkullSuit,
```

```diff
   roundNumber?: number;
   completedRounds?: CompletedRoundView[];
   cumulativeScores?: Record<number, number>;
   matchResult?: MatchEndedPayload | null;
+  trick?: PlayedCardView[];
+  desertedSeats?: number[];
 }) {
   const seats =
     opts.seats ??
     Array.from({ length: opts.seatCount }, (_, i) =>
```

```diff
       handSize: 3,
       startSeat: 0,
       currentTurnSeat: opts.currentTurnSeat ?? -1,
       seats,
-      trick: [],
+      trick: opts.trick ?? [],
       cumulativeScores: opts.cumulativeScores ?? {},
-      desertedSeats: [],
+      desertedSeats: opts.desertedSeats ?? [],
       roundScores: {},
       completedRounds: opts.completedRounds,
       matchResult: opts.matchResult,
     },
```

```diff
       bids: { 0: 1, 1: 2, 2: 0, 3: 3 },
     });
     const { container } = renderTable();
 
-    const text = container.querySelector('.sk-seats')!.textContent ?? '';
+    // 좌석 카드들의 글자만 본다 — 테이블(D-132) 가운데 칸의 트릭 메타("트릭 1 / 3")가 섞이면 판별력이 없다.
+    const text = [...container.querySelectorAll('.sk-seat')].map((el) => el.textContent).join(' ');
     expect(text).toContain('2');
     expect(text).toContain('3');
   });
 });
```

```diff
     fireEvent.click(screen.getByRole('button', { name: '규칙' }));
     expect(screen.getByRole('dialog')).toBeInTheDocument();
   });
 });
+
+/** 좌석 배치 안 좌석들의 [좌석, 옆줄, 옆줄 안 순서] — DOM 순서 그대로. */
+function layoutOf(): [number, string | null, string | null][] {
+  const felt = screen.getByRole('group', { name: '좌석 배치' });
+  return [...felt.querySelectorAll('.sk-seat')].map((el) => [
+    Number(el.getAttribute('data-seat')),
+    el.getAttribute('data-side'),
+    el.getAttribute('data-slot'),
+  ]);
+}
+
+const seatEl = (seat: number) => document.querySelector(`.sk-seat[data-seat="${seat}"]`) as HTMLElement;
+
+describe('테이블 좌석 배치 (D-132)', () => {
+  it.each([
+    [2, [[1, 'top', '0']]],
+    [
+      5,
+      [
+        [1, 'left', '0'],
+        [2, 'left', '1'],
+        [3, 'right', '0'],
+        [4, 'right', '1'],
+      ],
+    ],
+    [
+      8,
+      [
+        [1, 'left', '0'],
+        [2, 'left', '1'],
+        [3, 'top', '0'],
+        [4, 'top', '1'],
+        [5, 'top', '2'],
+        [6, 'right', '0'],
+        [7, 'right', '1'],
+      ],
+    ],
+  ])('%i인: 내 다음 좌석부터 왼쪽 옆줄 → 윗줄(위쪽 반원) → 오른쪽 옆줄에 시계 방향으로 앉는다', (n, expected) => {
+    seed({ seatCount: n, mySeat: 0, phase: 'PLAYING' });
+    renderTable({ playerIds: playerIds(n) });
+
+    expect(layoutOf()).toEqual(expected);
+  });
+
+  it('가운데 칸에 트릭 레일과 시계 방향 화살표 — 화살표 글리프는 읽지 않는다', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
+    renderTable();
+
+    const felt = screen.getByRole('group', { name: '좌석 배치' });
+    expect(within(felt).getByRole('region', { name: '이번 트릭' })).toBeInTheDocument();
+    const dir = within(felt).getByText('시계 방향');
+    expect(dir).toHaveTextContent('↻ 시계 방향');
+    expect(dir.querySelector('[aria-hidden="true"]')).toHaveTextContent('↻');
+  });
+
+  it('관전자는 좌석 0 을 아래에 일반 좌석으로 두고 나머지를 0 기준으로 앉힌다', () => {
+    seed({ seatCount: 4, mySeat: -1, phase: 'BIDDING' });
+    renderTable({ spectator: true });
+
+    expect(layoutOf()).toEqual([
+      [1, 'left', '0'],
+      [2, 'top', '0'],
+      [3, 'right', '0'],
+      [0, 'bottom', '0'],
+    ]);
+  });
+
+  it('옆줄 좌석만 좁은 화면에서 손패 장수를 숨길 수 있게 표시한다 — 윗줄은 그대로', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
+    renderTable();
+
+    expect(seatEl(1)).toHaveClass('sk-seat-side');
+    expect(seatEl(3)).toHaveClass('sk-seat-side');
+    expect(seatEl(2)).not.toHaveClass('sk-seat-side');
+    expect(seatEl(1).querySelector('.sk-stat-hand')).toHaveAttribute('title', '남은 손패');
+  });
+
+  it('좁은 옆줄 좌석의 긴 이름은 말줄임 대상이고 title 로 전체 이름을 보인다', () => {
+    const long = '아주아주긴이름을가진해적선장';
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING' });
+    renderTable({ usernames: { 101: long } });
+
+    expect(seatEl(1)).toHaveAttribute('data-side', 'left');
+    expect(seatEl(1).querySelector('.sk-seat-name')).toHaveAttribute('title', long);
+  });
+});
+
+describe('다음 차례 표시 (D-132)', () => {
+  it('지금 차례 좌석은 테두리, 그 다음 좌석(+1)에는 "다음"', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING', currentTurnSeat: 1 });
+    renderTable();
+
+    expect(seatEl(1)).toHaveClass('sk-seat-turn');
+    expect(within(seatEl(2)).getByText('다음')).toBeInTheDocument();
+    expect(screen.getAllByText('다음')).toHaveLength(1);
+  });
+
+  it('다음이 나면 "나" 줄에 표시한다', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING', currentTurnSeat: 3 });
+    const { container } = renderTable();
+
+    expect(within(container.querySelector('.sk-me') as HTMLElement).getByText('다음')).toBeInTheDocument();
+    expect(screen.getAllByText('다음')).toHaveLength(1);
+  });
+
+  it('지금 사람이 트릭의 마지막이면 "다음"이 없다 — 다음 리드는 승자다', () => {
+    seed({
+      seatCount: 4,
+      mySeat: 0,
+      phase: 'PLAYING',
+      currentTurnSeat: 0,
+      trick: [
+        { seat: 1, card: suit('GREEN', 3), declaredAs: null },
+        { seat: 2, card: suit('GREEN', 5), declaredAs: null },
+        { seat: 3, card: suit('GREEN', 7), declaredAs: null },
+      ],
+    });
+    renderTable();
+
+    expect(screen.queryByText('다음')).toBeNull();
+  });
+
+  it('입찰 중(차례 −1)에는 "다음"이 없다', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'BIDDING' });
+    renderTable();
+
+    expect(screen.queryByText('다음')).toBeNull();
+  });
+
+  it('탈주 좌석도 건너뛰지 않는다 — 유령이 대신 둔다', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING', currentTurnSeat: 1, desertedSeats: [2] });
+    renderTable();
+
+    expect(within(seatEl(2)).getByText('다음')).toBeInTheDocument();
+  });
+
+  it('관전자에게는 "다음"이 아래 좌석(0)에 붙고 "나" 줄로 가지 않는다', () => {
+    seed({ seatCount: 4, mySeat: -1, phase: 'PLAYING', currentTurnSeat: 3 });
+    const { container } = renderTable({ spectator: true });
+
+    expect(seatEl(0)).toHaveAttribute('data-side', 'bottom');
+    expect(within(seatEl(0)).getByText('다음')).toBeInTheDocument();
+    expect(container.querySelector('.sk-me')).toBeNull();
+  });
+});
+
+/**
+ * 서버는 트릭이 완성되면 `CARD_PLAYED` → `TRICK_TAKEN` → `TURN_CHANGED(승자)` 순서로 낸다. `TRICK_TAKEN` 은 트릭만 비우고
+ * 차례(`currentTurnSeat` = 방금 낸 마지막 사람)는 그대로라, 그 사이 "다음"을 (마지막 사람 + 1)에 붙이면 틀린다 — 다음 리드는
+ * 승자다. 각 이벤트가 별도 프레임이라 실제로 보이는 순간이 있다.
+ */
+describe('다음 차례 표시 — 트릭이 막 끝난 순간 (D-132)', () => {
+  const ev = (type: string, payload: unknown) => ({ type, payload });
+  const trickOf3: PlayedCardView[] = [
+    { seat: 0, card: suit('GREEN', 3), declaredAs: null },
+    { seat: 1, card: suit('GREEN', 9), declaredAs: null },
+    { seat: 2, card: suit('GREEN', 5), declaredAs: null },
+  ];
+
+  function finishTrick(winnerSeat: number) {
+    act(() => {
+      const store = useSkullKingStore.getState();
+      store.applyEvent(ev('CARD_PLAYED', { seat: 3, card: suit('GREEN', 7), declaredAs: null }));
+      store.applyEvent(ev('TRICK_TAKEN', { winnerSeat, winningCard: suit('GREEN', 9), trickNumber: 1 }));
+    });
+  }
+
+  it('승자의 리드(TURN_CHANGED)를 기다리는 동안은 "다음"이 없고, 리드가 정해지면 승자 다음 좌석에 붙는다', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING', currentTurnSeat: 3, trick: trickOf3 });
+    renderTable();
+
+    finishTrick(1);
+    expect(screen.queryByText('다음')).toBeNull();
+
+    act(() => {
+      useSkullKingStore.getState().applyEvent(ev('TURN_CHANGED', { currentTurnSeat: 1 }));
+    });
+    expect(within(seatEl(2)).getByText('다음')).toBeInTheDocument();
+    expect(screen.getAllByText('다음')).toHaveLength(1);
+  });
+
+  it('마지막 사람이 트릭을 가져가면 그 사람이 리드하므로 곧바로 그 다음(나)에 "다음"', () => {
+    seed({ seatCount: 4, mySeat: 0, phase: 'PLAYING', currentTurnSeat: 3, trick: trickOf3 });
+    const { container } = renderTable();
+
+    finishTrick(3);
+    expect(within(container.querySelector('.sk-me') as HTMLElement).getByText('다음')).toBeInTheDocument();
+    expect(screen.getAllByText('다음')).toHaveLength(1);
+  });
+});
```

`client/src/styles/skullkingCssNamespace.test.ts`:

```diff
 import { describe, expect, it } from 'vitest';
-import { indexCssSource, skullkingCssSource } from './cssSources';
+import { indexCssSource, responsiveCssSource, skullkingCssSource } from './cssSources';
 
 /**
  * `.sk-` 네임스페이스 규칙을 관례가 아니라 **기계로** 강제한다 (D-103).
```

```diff
   });
 });
 
+/** D-132 — 테이블 좌석 배치. jsdom(css:false)은 레이아웃·미디어 쿼리를 재지 않으므로 원문으로 고정한다. */
+describe('18-skullking-table.css — 테이블 배치 (D-132)', () => {
+  /** 쉼표 그룹 안에 `selector` 가 (정확히) 들어 있는 규칙들 중 `prop` 을 정한 첫 값 — 공백은 접어서 비교한다. */
+  function declaredIn(selector: string, prop: string): string | undefined {
+    const re = /([^{}@]+?)\s*\{([^}]*)\}/g;
+    const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
+    let m: RegExpExecArray | null;
+    while ((m = re.exec(cssCode)) !== null) {
+      const group = m[1].split(',').map((one) => one.replace(/\s+/g, ' ').trim());
+      const d = group.includes(selector) ? decl.exec(m[2]) : null;
+      if (d) return d[2].trim();
+    }
+    return undefined;
+  }
+
+  /**
+   * 같은 특이도에선 뒤 규칙이 이긴다 — `selector` 를 포함한 규칙들 중 `prop` 의 **마지막** 값. `declaredIn` 은 첫 값이라
+   * `.sk-felt-left, .sk-felt-right { flex-direction: column }` 그룹 규칙을 읽어 `.sk-felt-left` 의 `column-reverse` 를 놓친다.
+   */
+  function lastDeclaredIn(selector: string, prop: string): string | undefined {
+    const re = /([^{}@]+?)\s*\{([^}]*)\}/g;
+    const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
+    let last: string | undefined;
+    let m: RegExpExecArray | null;
+    while ((m = re.exec(cssCode)) !== null) {
+      const group = m[1].split(',').map((one) => one.replace(/\s+/g, ' ').trim());
+      const d = group.includes(selector) ? decl.exec(m[2]) : null;
+      if (d) last = d[2].replace(/\s+/g, ' ').trim();
+    }
+    return last;
+  }
+
+  it('테이블 색은 토큰이고 라이트 테마 값을 따로 둔다', () => {
+    const dark = tokensOf('.sk-table').get('--sk-felt-bg');
+    const light = tokensOf('html:not(.dark) .sk-table').get('--sk-felt-bg');
+
+    expect(dark, '.sk-table --sk-felt-bg').toBeTruthy();
+    expect(light, 'html:not(.dark) .sk-table --sk-felt-bg').toBeTruthy();
+    expect(light).not.toBe(dark);
+    expect(declaredIn('.sk-felt', 'background')).toBe('var(--sk-felt-bg)');
+  });
+
+  it('옆줄 좌석 폭은 화면 폭을 따른다 — 폭 미디어 없이 clamp 로', () => {
+    expect(declaredIn('.sk-felt-left .sk-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
+    expect(declaredIn('.sk-felt-right .sk-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
+  });
+
+  // 390px 가운데 칸 ≈138px 에서는 트릭 레일이 한 줄에 1장씩 쌓여 8인 천이 ≈800~1000px 가 됐다 — 좁은 화면만 가운데를 옆줄
+  // 아래 전체 폭으로 내린다. 좌·우·위 자리(시계 방향)는 그대로다.
+  it('17-responsive 맨 끝 블록이 ≤480px 에서 옆줄 손패 장수를 숨기고 가운데 칸을 옆줄 아래 전체 폭으로 내린다 — .sk- 선택자만', () => {
+    const code = responsiveCssSource.replace(/\/\*[\s\S]*?\*\//g, '');
+    const last = code.slice(code.lastIndexOf('@media')).replace(/\s+/g, ' ').trim();
+
+    expect(last).toBe(
+      '@media (max-width: 480px) { .sk-seat-side .sk-stat-hand { display: none; } ' +
+        ".sk-felt { grid-template-areas: 'top top top' 'left . right' 'center center center' 'bottom bottom bottom'; " +
+        'grid-template-rows: auto auto auto auto; } }',
+    );
+  });
+
+  // 좌석 DOM 의 data-side 는 표식일 뿐 — 자리를 잡는 것은 이 CSS 다. 여기를 반시계로 뒤집으면 컴포넌트 테스트는 모두 통과한다.
+  it('시계 방향 자리 — 왼쪽 옆줄은 왼쪽 칸에서 아래→위, 오른쪽 옆줄은 오른쪽 칸에서 위→아래', () => {
+    expect(lastDeclaredIn('.sk-felt', 'grid-template-areas')).toBe(
+      "'top top top' 'left center right' 'bottom bottom bottom'",
+    );
+    expect(lastDeclaredIn('.sk-felt-left', 'grid-area')).toBe('left');
+    expect(lastDeclaredIn('.sk-felt-left', 'flex-direction')).toBe('column-reverse');
+    expect(lastDeclaredIn('.sk-felt-right', 'grid-area')).toBe('right');
+    expect(lastDeclaredIn('.sk-felt-right', 'flex-direction')).toBe('column');
+  });
+
+  // 390px 옆줄 좌석 내용 ≈65px 에 아바타 28px 면 한 줄 이름 칸은 ≈31px(두 글자)다. 모바일은 title 툴팁이 뜨지 않는다.
+  it('좁은 좌석에서는 이름이 아바타 아래 줄로 내려간다 — 한 줄에 아바타 + 이름 44px 가 안 들어가면', () => {
+    expect(lastDeclaredIn('.sk-seat-top', 'flex-wrap')).toBe('wrap');
+    expect(lastDeclaredIn('.sk-seat-name', 'flex')).toBe('1 1 44px');
+  });
+
+  // 8인 윗줄 3명 × 100px + 간격이 375·360px 천 내용을 넘으면 2 + 1 로 감겨 위쪽 반원 순서가 흐트러진다 — 한 줄에서 줄인다.
+  it('윗줄·아래 줄은 감기지 않는다 — 좌석이 한 줄에서 줄어든다', () => {
+    expect(lastDeclaredIn('.sk-felt-top', 'flex-wrap') ?? 'nowrap').toBe('nowrap');
+    expect(lastDeclaredIn('.sk-felt-bottom', 'flex-wrap') ?? 'nowrap').toBe('nowrap');
+    expect(lastDeclaredIn('.sk-seat', 'min-width')).toBe('0');
+  });
+
+  // 좌석 배경이 반투명이라 키 글자 뒤가 밝은 천(#dde7f1)이다 — opacity .6 이면 4.36:1 로 AA(4.5) 미달.
+  it('라이트 테마 천 위 좌석 통계 키는 AA 대비가 나오게 덜 흐리게 한다', () => {
+    expect(lastDeclaredIn('html:not(.dark) .sk-felt .sk-stat-key', 'opacity')).toBe('0.7');
+  });
+});
+
 describe('index.css — 캐스케이드 순서', () => {
   const order = [...indexCss.matchAll(/@import '\.\/parts\/(\d+)-[^']+'/g)].map(
     (m) => Number(m[1]),
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- skullking`
Expected: FAIL — `Test Files  3 failed | 5 passed (8)` · `Tests  23 failed | 149 passed (172)`(`TypeError: nextTurnSeat is not a function`, `Unable to find an accessible element with the
role "group" and name "좌석 배치"`, `Unable to find an element with the text: 다음`, `toHaveClass("sk-seat-side")`, `TypeError: Cannot read properties of undefined (reading 'replace')`
(`responsiveCssSource` 없음), CSS 원문 단언. "다음"이 없어야 하는 고정 테스트 3건은 처음부터 통과).

- [ ] **Step 3: 구현**

`client/src/features/skullking/seatLayout.ts`:

```diff
 } from '@/types/skullking';
 
 /**
- * 2~8인 가변 좌석의 순수 배치 계산 (D-103, Row-Flow). DOM 을 모른다 — 전수 테스트가
+ * 2~8인 가변 좌석의 순수 배치 계산 (D-103, 테이블 배치 D-132). DOM 을 모른다 — 전수 테스트가
  * jsdom 없이 돈다. 좌석 순서·최소 폭은 원카드도 쓰는 공용 모듈에서 다시 내보낸다(D-129).
  */
 
```

```diff
 
 export function seatAccent(seat: number): string {
   return ACCENTS[((seat % ACCENTS.length) + ACCENTS.length) % ACCENTS.length];
+}
+
+/**
+ * 일반 진행 기준 다음 차례 (D-132 — 게임판의 "다음" 표시). 서버 진행은 트릭 안에서 +1(`TrickState`)이다. 차례가 없거나
+ * (`currentTurnSeat` −1 — 입찰·라운드 정리) 지금 사람이 이번 트릭의 마지막이면(`trickLength + 1 >= seatCount` — 다음 리드는 트릭
+ * 승자라 아직 모른다) −1. 탈주 좌석은 유령이 대신 두므로 건너뛰지 않는다(룰 ⑱). 표시 전용이다.
+ *
+ * <p>트릭이 막 끝난 순간(`TRICK_TAKEN` 뒤 승자의 `TURN_CHANGED` 전)은 스토어가 차례를 −1 로 만들지 않는다(지난 사람이 남는다) —
+ * 그 구간은 게임판(`SkullKingTable`)이 −1 로 다룬다.
+ *
+ * @param trickLength 이번 트릭에 이미 나온 카드 수(지금 사람 것 제외).
+ */
+export function nextTurnSeat(currentTurnSeat: number, seatCount: number, trickLength: number): number {
+  if (currentTurnSeat < 0 || seatCount <= 0) return -1;
+  if (trickLength + 1 >= seatCount) return -1;
+  return (currentTurnSeat + 1) % seatCount;
 }
 
 /** 캐릭터 카드인가 — 인어·해적·스컬킹·해적 선언 티그리스 (§6.1). */
```

`client/src/features/skullking/SkullSeatCard.tsx`:

```diff
 import { avatarSrc } from '@/api/avatar';
 import { animalFor } from '@/components/avatarGlyph';
+import type { TableSide } from '@/components/seatOrder';
 import { seatAccent } from './seatLayout';
 import type { SeatView } from '@/types/skullking';
 
```

```diff
   username?: string;
   isBot?: boolean;
   isTurn?: boolean;
+  /** D-132 — 일반 진행 기준 다음 차례. */
+  isNext?: boolean;
   isDeserted?: boolean;
   isDisconnected?: boolean;
   /** 전원 제출 후에만 예측값을 보여준다 (§5). */
   bidsRevealed: boolean;
+  /**
+   * D-132 — 테이블 자리(관전자의 아래 좌석은 `bottom`). 옆줄(left·right)이면 `sk-seat-side` 를 달아 좁은 화면에서 손패 장수를
+   * 숨긴다(17-responsive 끝 규칙).
+   */
+  side?: TableSide | 'bottom';
+  /** 옆줄 안 순서(시계 방향, 0 부터). */
+  slot?: number;
 }
 
 /**
- * 상대 좌석 하나. 절대배치가 아니라 **정상 흐름**에 놓이므로 좌석 수가 2~8 로 변해도
- * 그리드가 알아서 줄바꿈한다 (D-103 Row-Flow). 티츄 `SeatAvatar` 를 재사용하지 않는 이유:
+ * 상대 좌석 하나. 절대배치가 아니라 테이블 격자(D-132)의 옆줄·윗줄에 **정상 흐름**으로 놓인다 — 옆줄 칸은 좁아질 수
+ * 있어 이름은 말줄임하고 `title` 로 전체 이름을 보인다. 티츄 `SeatAvatar` 를 재사용하지 않는 이유:
  * `seat % 2` 팀색 링이 개인전에서 무의미하고 `size` 가 인라인이라 CSS 로 줄일 수 없다.
  */
 export function SkullSeatCard({
```

```diff
   username,
   isBot = false,
   isTurn = false,
+  isNext = false,
   isDeserted = false,
   isDisconnected = false,
   bidsRevealed,
+  side,
+  slot,
 }: Props) {
   const glyph = isBot ? '🤖' : animalFor(userId, seat.seat);
   const name = username ?? (isBot ? '봇' : `#${userId ?? seat.seat}`);
```

```diff
   const className = [
     'sk-seat',
     isTurn ? 'sk-seat-turn' : '',
+    side === 'left' || side === 'right' ? 'sk-seat-side' : '',
     isDeserted ? 'sk-seat-deserted' : '',
     isDisconnected ? 'sk-seat-offline' : '',
   ]
```

```diff
       className={className}
       style={{ ['--sk-accent' as string]: seatAccent(seat.seat) }}
       data-seat={seat.seat}
+      data-side={side}
+      data-slot={slot}
     >
+      {isNext && <span className="sk-badge sk-seat-next">다음</span>}
       <div className="sk-seat-top">
         <span className="sk-seat-avatar" aria-hidden>
           {!isBot && userId != null ? (
```

```diff
           <span className="sk-stat-key" aria-hidden>획</span>
           <span className="sk-stat-val">{seat.tricksWon}</span>
         </span>
-        <span className="sk-stat" title="남은 손패">
+        <span className="sk-stat sk-stat-hand" title="남은 손패">
           <span className="sk-stat-key" aria-hidden>손</span>
           <span className="sk-stat-val">{seat.handCount}</span>
         </span>
```

`client/src/features/skullking/SkullKingTable.tsx`:

```diff
 import { useRoomChatStore } from '@/features/chat/roomChatStore';
 import { skullkingRoomSink } from './skullkingRoomSink';
 import { bidsRevealed, lastRoundResult, useSkullKingStore } from './skullkingStore';
-import { leadSuitOf, seatAccent, seatMinWidth, viewOrder } from './seatLayout';
+import { tableLayout, type TableSide } from '@/components/seatOrder';
+import { leadSuitOf, nextTurnSeat, seatAccent, seatMinWidth } from './seatLayout';
 import { SkullSeatCard } from './SkullSeatCard';
 import { SkullTrickRow } from './SkullTrickRow';
 import { SkullCardChip } from './SkullCardChip';
```

```diff
  * 스컬킹 게임판 조립 루트 (D-103, S6). 티츄 `GameTable` 과 같은 구조로 자기 소켓을 소유하고
  * 자기 sink 를 주입한다 — 그래서 두 게임의 코드 경로가 겹치지 않는다.
  *
- * <p>기하는 **Row-Flow**다: 상대 좌석은 `auto-fit` 그리드에 정상 흐름으로 놓여 2~8인이
- * 폭 미디어 없이 줄바꿈되고, 트릭은 재생순 레일로 늘어놓는다. 좌석 수의 권위값은
- * `tableView.seats.length`(스토어의 `seats`)이며 `playerIds` 는 이름·아바타 조회용이다.
+ * <p>기하는 **테이블 격자**다(D-132): 상대 좌석은 내 기준 왼쪽 옆줄 → 윗줄(5인 이상은 위쪽 반원) → 오른쪽 옆줄에 시계
+ * 방향으로 앉고(`tableLayout`), 가운데 칸에 재생순 트릭 레일(D-103 — 카드는 좌석 앞이 아니라 레일에)과 진행 방향 화살표를
+ * 둔다. 폭 미디어 없이 옆줄 폭만 줄인다. 좌석 수의 권위값은 `tableView.seats.length`(스토어의 `seats`)이며 `playerIds` 는
+ * 이름·아바타 조회용이다.
  */
 export function SkullKingTable({
   roomId,
```

```diff
   const revealed = bidsRevealed(s);
   const seatCount = s.seats.length || playerIds.length;
   const mySeat = spectator ? -1 : s.mySeat;
-  const opponents = viewOrder(seatCount, mySeat);
+  const { bottomSeat, slots } = tableLayout(seatCount, mySeat);
   const leadSuit = leadSuitOf(s.trick);
   const myTurn = !spectator && mySeat >= 0 && s.currentTurnSeat === mySeat;
+  // D-132 — 일반 진행 기준 다음 차례(입찰·라운드 정리·트릭의 마지막 사람이면 없음). 트릭이 막 끝나(TRICK_TAKEN) 승자의
+  // 리드(TURN_CHANGED)를 기다리는 동안은 currentTurnSeat 가 방금 낸 마지막 사람이다 — 다음 리드는 승자라 아직 다음을 모른다.
+  // (마지막 사람이 승자면 그 사람이 곧 리드하므로 그대로 계산한다.)
+  const awaitingLead =
+    s.trick.length === 0 && s.settledTrick !== null && s.settledTrick.winnerSeat !== s.currentTurnSeat;
+  const nextSeat = awaitingLead ? -1 : nextTurnSeat(s.currentTurnSeat, seatCount, s.trick.length);
 
   const nameOf = (seat: number) => {
     const uid = playerIds[seat];
     if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
     return usernames[uid] ?? `#${uid ?? seat}`;
+  };
+
+  const seatAt = (seat: number, side: TableSide | 'bottom', slot: number) => {
+    const view = s.seats.find((x) => x.seat === seat);
+    if (!view) return null;
+    return (
+      <SkullSeatCard
+        key={seat}
+        seat={view}
+        userId={playerIds[seat]}
+        username={usernames[playerIds[seat]]}
+        isBot={botSeats.includes(seat)}
+        isTurn={s.currentTurnSeat === seat}
+        isNext={nextSeat === seat}
+        isDeserted={s.desertedSeats.includes(seat)}
+        isDisconnected={s.disconnectedSeats.has(seat)}
+        bidsRevealed={revealed}
+        side={side}
+        slot={slot}
+      />
+    );
+  };
+  // 옆줄마다 묶는다 — 좌석 DOM 순서가 slots 순서(= 진행 순서)다.
+  const sideRow = (side: TableSide) => {
+    const row = slots.filter((x) => x.side === side);
+    if (row.length === 0) return null;
+    return <div className={`sk-felt-${side}`}>{row.map((x) => seatAt(x.seat, side, x.index))}</div>;
   };
 
   const mySeatView = s.seats.find((x) => x.seat === mySeat) ?? null;
```

```diff
       <ReconnectBanner connected={connected} />
       {s.errorMessage && <p className="sk-error">{s.errorMessage}</p>}
 
-      <div className="sk-seats">
-        {opponents.map((seat) => {
-          const view = s.seats.find((x) => x.seat === seat);
-          if (!view) return null;
-          return (
-            <SkullSeatCard
-              key={seat}
-              seat={view}
-              userId={playerIds[seat]}
-              username={usernames[playerIds[seat]]}
-              isBot={botSeats.includes(seat)}
-              isTurn={s.currentTurnSeat === seat}
-              isDeserted={s.desertedSeats.includes(seat)}
-              isDisconnected={s.disconnectedSeats.has(seat)}
-              bidsRevealed={revealed}
+      {/* D-132 — 테이블. 내 자리는 아래(아래의 "나" 줄)이고, 관전자는 좌석 0 을 아래 칸에 일반 좌석으로 둔다. */}
+      <div className="sk-felt" role="group" aria-label="좌석 배치">
+        {sideRow('left')}
+        {sideRow('top')}
+        {sideRow('right')}
+
+        <div className="sk-felt-center">
+          {/* 스컬킹은 늘 시계 방향(+1)이다. 글리프는 읽지 않고 글자로 읽는다. */}
+          <span className="sk-badge sk-felt-dir">
+            <span className="sk-felt-dir-arrow" aria-hidden="true">
+              ↻
+            </span>{' '}
+            시계 방향
+          </span>
+          {s.phase === 'PLAYING' && (
+            <SkullTrickRow
+              trick={s.trick}
+              settled={s.settledTrick}
+              currentTurnSeat={s.currentTurnSeat}
+              handSize={s.handSize}
+              completedTricks={completedTricks}
+              nameOf={nameOf}
             />
-          );
-        })}
+          )}
+        </div>
+
+        {bottomSeat !== null && <div className="sk-felt-bottom">{seatAt(bottomSeat, 'bottom', 0)}</div>}
       </div>
-
-      {s.phase === 'PLAYING' && (
-        <SkullTrickRow
-          trick={s.trick}
-          settled={s.settledTrick}
-          currentTurnSeat={s.currentTurnSeat}
-          handSize={s.handSize}
-          completedTricks={completedTricks}
-          nameOf={nameOf}
-        />
-      )}
 
       {s.matchEnded && (
         <SkullMatchEndPanel
```

```diff
               </span>
             )}
             {myTurn && <span className="sk-badge">내 차례</span>}
+            {nextSeat === mySeat && <span className="sk-badge sk-seat-next">다음</span>}
           </div>
 
           {s.phase === 'BIDDING' && !roomFinished && (
```

`client/src/styles/parts/18-skullking-table.css`:

```diff
-/* 스컬킹 게임판 (D-103, S6) — Row-Flow 가변 좌석 기하.
+/* 스컬킹 게임판 (D-103, S6) — 가변 좌석 기하(테이블 배치 D-132).
  *
  * ⚠ 캐스케이드 제약: 이 파일은 index.css 에서 **17-responsive 앞**에 import 된다.
  *   따라서 여기에 폭 미디어(@media max-width)를 두면 17 에 덮인다. 이 파일에는 폭 미디어가
- *   **0개**이고 그게 설계 의도다 — 좌석은 auto-fit 그리드라 브레이크포인트 없이 줄바꿈되고,
- *   카드/버튼 축소는 17 의 공용 규칙(.card-chip, .action-bar)을 상속한다.
+ *   **0개**이고 그게 설계 의도다 — 좌석은 테이블 격자라 옆줄 폭을 clamp() 로 줄여 브레이크포인트
+ *   없이 맞추고, 카드/버튼 축소는 17 의 공용 규칙(.card-chip, .action-bar)을 상속한다.
+ *   (좁은 화면 ≤480px 의 옆줄 좌석 손패 장수 숨김과 가운데 칸(트릭 레일)을 옆줄 아래 전체 폭으로 내리는
+ *   배치만 17 맨 끝 .sk-* 블록 — D-132.)
  *   폭 규칙이 불가피해지면 17-responsive.css **맨 끝**에 .sk-* 블록만 추가할 것
  *   (기존 블록 사이 삽입 금지 — 480/440 순서 의존, D-88).
  *
```

```diff
   --sk-accent-5: #2fb5c0;
   --sk-accent-6: #f08a3c;
   --sk-accent-7: #a0a4b8;
+  /* D-132 — 테이블 천. 라이트 테마 값은 아래 블록. */
+  --sk-felt-bg: #13283b;
+  --sk-felt-edge: #2a4a66;
 
   display: flex;
   flex-direction: column;
   gap: 10px;
   padding: 10px;
   min-height: 100vh;
+}
+
+/* 좌석 배경이 반투명이라 글자는 페이지 글자색을 따른다 — 천도 테마를 따라 밝혀야 라이트 테마에서 읽힌다. */
+html:not(.dark) .sk-table {
+  --sk-felt-bg: #dde7f1;
+  --sk-felt-edge: #a7bfd6;
 }
 
 /* ── 헤더 / 메타 줄 ───────────────────────────────────────────────── */
```

```diff
   font-weight: 700;
 }
 
-/* ── 상대 좌석 그리드 — 폭 미디어 없이 2~8인 대응 ─────────────────── */
-/* ⚠ max 는 반드시 `1fr` 이어야 한다. `minmax(100px, 168px)` 처럼 max 가 **고정값**이면
- * CSS 스펙상 auto-fit 반복 횟수를 max 기준으로 세므로(168+8 단위) 375px 화면에서 트랙이
- * 1개만 생겨 8인이 세로로 쌓인다 — C5 실측에서 실제로 그랬다. `1fr` 이면 min(100px)
- * 기준으로 세어 3열이 되고, 각 좌석은 아래 max-width 로 데스크톱에서만 168px 로 묶인다. */
-.sk-seats {
+/* ── 테이블 (D-132) — 내 기준 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄, 시계 방향. 폭 미디어 없이 2~8인 ─────── */
+/* 자리 계산은 `tableLayout`(components/seatOrder.ts), 배치는 여기. 옆줄은 최대 2명씩이라 5인 이상은 윗줄이 늘어 위쪽 반원이
+   된다. 옆줄 열은 좌석이 없으면 auto 로 접힌다. 가운데 칸은 재생순 트릭 레일(D-103 — 원형 배치는 8인에서 카드↔낸 사람이
+   모호해 기각)과 방향 화살표. 내 자리(아래)는 천 밖의 "나" 줄이고, 관전자만 좌석 0 을 아래 칸에 둔다.
+   ≤480px 는 가운데 칸이 ≈138px(390px)라 트릭 레일이 한 줄에 1장씩 쌓이므로 17 맨 끝 블록이 가운데를 옆줄 아래 전체 폭으로
+   내린다(좌·우·위 자리는 그대로). */
+.sk-felt {
   display: grid;
-  grid-template-columns: repeat(auto-fit, minmax(var(--sk-seat-min), 1fr));
-  justify-items: center;
+  grid-template-columns: auto minmax(0, 1fr) auto;
+  grid-template-rows: auto 1fr auto;
+  grid-template-areas:
+    'top top top'
+    'left center right'
+    'bottom bottom bottom';
   gap: 8px;
+  padding: 12px 8px;
+  border-radius: 24px;
+  border: 2px solid var(--sk-felt-edge);
+  background: var(--sk-felt-bg);
+}
+
+/* 감기지 않는다 — 8인 윗줄 3명이 375·360px 에서 2 + 1 로 감기면 위쪽 반원 순서가 흐트러진다. 좌석(min-width 0)이 한 줄에서 줄어든다. */
+.sk-felt-top,
+.sk-felt-bottom {
+  display: flex;
+  justify-content: center;
+  gap: 8px;
+  min-width: 0;
+}
+
+.sk-felt-top {
+  grid-area: top;
+}
+
+.sk-felt-bottom {
+  grid-area: bottom;
+}
+
+/* 옆줄은 세로로 고르게 — 1명이면 가운데, 2명이면 위·아래. 간격은 "다음" 꼬리표(좌석 위로 9px)가 위 좌석에 닿지 않게. */
+.sk-felt-left,
+.sk-felt-right {
+  display: flex;
+  flex-direction: column;
+  justify-content: space-around;
+  gap: 14px;
+  min-width: 0;
+}
+
+/* 왼쪽 옆줄은 아래→위다(index 0 = 내 바로 왼쪽 = 다음 차례). DOM 순서는 진행 순서 그대로 두고 보이는 순서만 뒤집는다. */
+.sk-felt-left {
+  grid-area: left;
+  flex-direction: column-reverse;
+}
+
+.sk-felt-right {
+  grid-area: right;
+}
+
+.sk-felt-center {
+  grid-area: center;
+  display: flex;
+  flex-direction: column;
+  gap: 6px;
+  min-width: 0;
+}
+
+/* 진행 방향(스컬킹은 늘 ↻ 시계 방향). 글리프는 aria-hidden, 방향은 뒤의 글자로 읽힌다. */
+.sk-felt-dir {
+  align-self: center;
+  font-weight: 600;
+}
+
+.sk-felt-dir-arrow {
+  font-size: 15px;
+  line-height: 1;
+}
+
+/* 옆줄 좌석은 화면 폭을 따라 64~150px, 윗줄·아래 좌석은 기준 폭(--sk-seat-min — 인원이 늘면 좁아진다)에서 최대 168px. */
+.sk-felt-left .sk-seat,
+.sk-felt-right .sk-seat {
+  width: clamp(64px, 22vw, 150px);
+}
+
+.sk-felt-top .sk-seat,
+.sk-felt-bottom .sk-seat {
+  flex: 1 1 var(--sk-seat-min);
 }
 
 .sk-seat {
```

```diff
   border-style: dashed;
 }
 
+/* 좁은 좌석(내용 < 아바타 28 + 간격 6 + 이름 44 = 78px — 390px 옆줄 ≈65px)에서는 이름이 아바타 아래 줄로 내려가 좌석 폭을 다
+   쓴다(≈8자). 한 줄이면 이름 칸이 ≈31px(두 글자 + 말줄임)라 봇이 모두 "bo…"로 보이고, 모바일은 title 툴팁이 뜨지 않는다.
+   윗줄 좌석(8인도 ≈82px 이상)·넓은 화면 옆줄은 한 줄 그대로다(폭 미디어 없음). */
 .sk-seat-top {
   display: flex;
+  flex-wrap: wrap;
   align-items: center;
   gap: 6px;
+  row-gap: 2px;
   min-width: 0;
 }
 
```

```diff
 }
 
 .sk-seat-name {
-  flex: 1 1 auto;
+  flex: 1 1 44px;
   min-width: 0;
   overflow: hidden;
   text-overflow: ellipsis;
```

```diff
   opacity: 0.6;
 }
 
+/* 좌석 배경이 반투명이라 라이트 테마에서는 키 글자 뒤가 밝은 천(#dde7f1)이다 — .6 이면 4.36:1 로 AA(4.5) 미달, .7 이면 ≈6:1.
+   "나" 줄(.sk-me)은 천 밖이라 그대로. */
+html:not(.dark) .sk-felt .sk-stat-key {
+  opacity: 0.7;
+}
+
 .sk-stat-val {
   font-weight: 700;
   font-variant-numeric: tabular-nums;
```

```diff
 
 .sk-seat-tag {
   align-self: flex-start;
+}
+
+/* D-132 — 일반 진행 기준 다음 차례. 좌석 안에서는 위 테두리에 걸친 꼬리표라 좁은 옆줄 좌석의 이름 칸을 줄이지 않는다.
+   "나" 줄에서는 다른 배지처럼 줄 안에 놓인다. 천 위에서도 읽히게 배경을 불투명하게 둔다. */
+.sk-seat-next {
+  border-color: var(--sk-accent);
+  background: var(--color-bg-surface);
+  font-size: 11px;
+  font-weight: 700;
+}
+
+.sk-seat > .sk-seat-next {
+  position: absolute;
+  top: -9px;
+  right: 6px;
+  padding: 0 6px;
 }
 
 /* ── 트릭 레일 (재생순) ───────────────────────────────────────────── */
```

`client/src/styles/parts/17-responsive.css`:

```diff
   .oc-header button.oc-badge {
     min-width: 44px;
     min-height: 44px;
   }
 }
+
+/* D-132 — 스컬킹 테이블(18 은 폭 미디어 0개라 여기 맨 끝에 둔다, D-88). 좁은 화면에서
+   ① 옆줄 좌석(64~105px)은 손패 장수를 숨긴다 — 이름 + 예측·획득만. 윗줄·넓은 화면은 그대로.
+   ② 가운데 칸(390px ≈138px)에서는 트릭 레일이 한 줄에 1장씩 쌓여 8인 천이 ≈800~1000px 가 된다 — 가운데(트릭 레일·방향)를
+   옆줄 아래 전체 폭으로 내린다. 좌·우·위 자리와 시계 방향은 그대로이고 옆줄 사이는 빈다. 넓은 화면은 가운데 그대로. */
+@media (max-width: 480px) {
+  .sk-seat-side .sk-stat-hand {
+    display: none;
+  }
+  .sk-felt {
+    grid-template-areas:
+      'top top top'
+      'left . right'
+      'center center center'
+      'bottom bottom bottom';
+    grid-template-rows: auto auto auto auto;
+  }
+}
```

`client/src/styles/cssSources.ts`:

```diff
 export const skullkingCssSource = readCss('./parts/18-skullking-table.css');
 export const onecardCssSource = readCss('./parts/19-onecard-table.css');
 export const indexCssSource = readCss('./index.css');
+/** D-132 — 스컬킹 옆줄 손패 숨김이 맨 끝 블록인지 원문으로 본다. */
+export const responsiveCssSource = readCss('./parts/17-responsive.css');
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- skullking`
Expected: PASS — `Test Files  8 passed (8)` · `Tests  172 passed (172)`(기존 DOM 순서 단언 `[6, 7, 0, 1, 2, 3, 4]`·`--sk-seat-min`·관전자 4좌석 무변경으로 통과).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  65 passed (65)` · `Tests  746 passed (746)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/skullking/seatLayout.test.ts \
  client/src/features/skullking/SkullKingTable.test.tsx \
  client/src/styles/skullkingCssNamespace.test.ts \
  client/src/features/skullking/seatLayout.ts \
  client/src/features/skullking/SkullSeatCard.tsx \
  client/src/features/skullking/SkullKingTable.tsx \
  client/src/styles/parts/18-skullking-table.css \
  client/src/styles/parts/17-responsive.css \
  client/src/styles/cssSources.ts
git commit -m "feat(D-132): 스컬킹 테이블 좌석 — 시계 방향 격자, 다음 차례 표시, 좁은 화면 옆줄·트릭 레일 규칙

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: 문서 — 설계 상태·QA·모바일 점검표·현황 수치 + 실측·Phase Gate

코드는 바꾸지 않는다. 아래 diff 는 Task 1~5 를 마친 뒤의 파일 기준이다(이 문서들은 앞 태스크가 건드리지 않았다).

**Files:**
- Modify: `docs/plans/table-seating.md`, `docs/qa-scenarios.md`, `docs/plans/mobile-responsive-checklist.md`, `docs/implementation-status.md`, `README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: Task 1~5 의 동작·이름, 마지막 실측(클라 65파일·746건).

- [ ] **Step 1: 설계 상태와 QA·모바일 점검표**

`docs/plans/table-seating.md`:

```diff
 # 테이블 좌석 배치 — 원카드·스컬킹 테이블형 좌석 + 티츄 시계 방향 (D-132) — 설계
 
-> 상태: **설계 승인** · 2026-10-12 · 결정 이력 `docs/decisions.md` D-132(계획 Task 1 에서 추가)
+> 상태: **구현** · 2026-10-12(설계 승인 → 구현 → 사전 리뷰 SP-1~SP-10 반영, 클라 65파일 746건) · 결정 이력 `docs/decisions.md` D-132 ·
+> 운영 실측(§1 기준 5·7)은 배포 뒤
+> 구현 메모: §6 의 `viewOrder`·`seatMinWidth` 테스트 이동은 하지 않았다 — `features/skullking/seatLayout.test.ts` 를 스컬킹 태스크가 함께 고쳐
+> 한 파일이 두 태스크에 걸리기 때문이다(새 배치 함수 테스트만 `components/seatOrder.test.ts`). 옆줄·윗줄 자리는 좌석 속성이 아니라 옆줄
+> 래퍼(`.oc-felt-left` 등, 왼쪽은 `column-reverse`)가 잡는다 — `data-side`·`data-slot` 은 표식이고, 자리를 잡는 CSS 는 네임스페이스 테스트가
+> 캐스케이드 승자로 단언한다. §4.5 와 달리 테이블 색 토큰(`--oc-felt-*`·`--sk-felt-*`)은 포털 토큰(`.oc-tokens`·`.sk-tokens`)에 넣지 않았다 —
+> 튜토리얼 다이얼로그는 천을 그리지 않는다(테스트는 다크·라이트 값이 따로 있는지 본다).
+> 390px 수치(계산): 천 내용 326px, 옆줄 좌석 ≈86px(내용 ≈65px), 3인 이상 가운데 칸 ≈138px(375px ≈130, 360px ≈122). 데스크톱(1200px)은
+> 옆줄 150px·가운데 676px.
 > 근거: 사용자 요청(원카드 게임판 스크린샷 — 상대가 단순 격자) · 티츄 테이블 배치 D-45 · 스컬킹 Row-Flow D-103 · 원카드 게임판 D-129
 > 범위: 클라이언트만. 서버·STOMP·REST 계약, 경쟁 버튼 슬롯 표는 바뀌지 않는다.
 
```

```diff
   윗줄은 1행 전체 폭의 flex 줄, 왼쪽 옆줄은 1열(index 0 = 아래 3행, 1 = 위 2행, 1명이면 2~3행 가운데), 오른쪽 옆줄은 3열(index 0 = 위 2행,
   1 = 아래 3행), 가운데는 2열 2~3행. 좌석 요소에 `data-side`·`data-slot` 을 달아 CSS 가 자리를 잡는다(위치 계산은 함수, 배치는 CSS).
 - 옆줄 좌석 폭은 `clamp(64px, 22vw, 150px)`, 윗줄 좌석은 flex(최대 168px) — `@media` 없이 폭에 맞춘다. 이름은 말줄임.
+  390px 옆줄 좌석은 내용이 ≈65px 라 아바타(28px) 옆 한 줄 이름 칸이 ≈31px(두 글자)이고 모바일은 `title` 툴팁이 뜨지 않으므로, 좁은 좌석
+  (내용 < 아바타 28 + 간격 6 + 이름 44 = 78px)에서는 이름이 아바타 아래 줄로 내려가 좌석 폭을 다 쓴다(≈8자 — `flex-wrap`, 이름
+  `flex: 1 1 44px`, 스컬킹 좌석도 같음). 윗줄 좌석·넓은 화면은 한 줄 그대로.
+- 가운데 칸은 390px 3인 이상에서 ≈138px(내용 ≈126px)다. 더미 뒷면·"뽑을 더미 N장" 문구 폭·간격을 `clamp()` 로 줄여 더미와 맨 위 카드가
+  한 줄에 든다(390px ≈110px — 7 의 지정 무늬 배지가 붙은 직후만 두 줄, 줄바꿈 허용). 가운데 배지(`○○ 차례` 등)는 칸 안에서 줄을 바꾼다 —
+  `.oc-badge` 의 `nowrap` 그대로면 긴 이름이 옆줄 좌석으로 넘친다. 넓은 화면은 지금 크기 그대로.
 - **"다음" 표시**: `onecardRules.ts` 에 순수 함수 `nextTurnSeat(turnSeat, direction, eliminated: boolean[]): number` — `turnSeat < 0` 이면 −1, 아니면
   진행 방향으로 탈락하지 않은 다음 좌석(자기 자신뿐이면 −1). J(건너뛰기)·K(한 번 더)·2인 특례는 카드를 내기 전엔 알 수 없으므로 반영하지 않는다
   ("일반 진행 기준 다음"). 차례가 비는 순간(카드를 낸 직후, 경쟁 창)에는 차례 테두리와 함께 사라진다. 다음 좌석이 나면 "나" 줄에 표시한다.
```

```diff
 ### 4.3 스컬킹 게임판
 
 - `.sk-seats` 를 `.sk-felt` 로 바꾸고 같은 배치 함수·격자를 쓴다. 가운데 칸에는 기존 트릭 레일(`SkullTrickRow`, PLAYING 일 때)과 시계 방향
-  화살표(`↻`)를 둔다 — 낸 카드는 좌석 앞이 아니라 레일에 그대로 놓는다(D-103 의 결정 유지).
-- **"다음" 표시**: `seatLayout.ts` 에 `nextTurnSeat(currentTurnSeat, seatCount, trickLength): number` — `currentTurnSeat < 0`(입찰·트릭 정리 중)이거나
+  화살표(`↻`)를 둔다 — 낸 카드는 좌석 앞이 아니라 레일에 그대로 놓는다(D-103 의 결정 유지). 좁은 화면은 아래처럼 가운데를 내린다.
+- **"다음" 표시**: `seatLayout.ts` 에 `nextTurnSeat(currentTurnSeat, seatCount, trickLength): number` — `currentTurnSeat < 0`(입찰·라운드 정리 중)이거나
   지금 사람이 이번 트릭의 마지막(`trickLength + 1 >= seatCount`)이면 −1, 아니면 `(currentTurnSeat + 1) % seatCount`. 탈주 좌석은 유령이 대신 두므로
-  건너뛰지 않는다(룰 ⑱). `SkullSeatCard` 에 `isNext` → `sk-seat-next` 표시.
-- 좁은 화면(≤480px)의 옆줄 좌석은 손패 장수를 숨긴다 — 규칙은 `17-responsive.css` 끝에 `.sk-*` 로만(18 파일은 `@media` 0개 유지).
+  건너뛰지 않는다(룰 ⑱). `SkullSeatCard` 에 `isNext` → `sk-seat-next` 표시. 트릭이 막 끝난 순간(`TRICK_TAKEN` 뒤 승자의 `TURN_CHANGED` 전)은
+  스토어가 차례를 −1 로 만들지 않고 방금 낸 마지막 사람을 남기므로, 게임판이 그 구간(트릭이 비었고 정리된 트릭의 승자가 지금 차례가 아님 —
+  승자 리드 대기)을 −1 로 다룬다. 마지막 사람이 승자면 곧 리드하므로 그대로 계산한다.
+- 좁은 화면(≤480px)은 `17-responsive.css` 끝 `.sk-*` 블록 하나로(18 파일은 `@media` 0개 유지) ① 옆줄 좌석의 손패 장수를 숨기고 ② 가운데 칸
+  (트릭 레일·방향 화살표)을 옆줄 아래 전체 폭으로 내린다 — `.sk-felt` 템플릿 `'top top top' 'left . right' 'center center center'
+  'bottom bottom bottom'`(사용자 결정, 2026-10-12 사전 리뷰 SP-2). 390px 가운데 칸 ≈138px 에서는 트릭 슬롯(카드 48px + 이름칩, 슬롯 ≈63~96px)이
+  한 줄에 1장씩 쌓여 8인 천이 ≈800~1000px 가 되고 매 차례 트릭과 손패 사이를 스크롤해야 한다. 전체 폭(≈314px)이면 3~4장씩 2~3줄,
+  천 ≈570~670px. 좌·우·위 자리와 시계 방향은 그대로이고 옆줄 사이 칸은 빈다. 넓은 화면은 가운데 그대로.
+- 윗줄·아래 줄은 감기지 않는다(`flex-wrap` 없음) — 8인 윗줄 3명이 375·360px 에서 2 + 1 로 감기면 위쪽 반원 순서가 흐트러진다. 좌석
+  (`min-width: 0`)이 한 줄에서 줄어든다(375px ≈98px).
 - `--sk-seat-min` 은 윗줄 좌석 최소 폭으로 계속 쓴다(기존 테스트 유지).
 
 ### 4.4 티츄 — 시계 방향
```

```diff
   네임스페이스 테스트가 대조)으로 라이트·다크 둘 다. 티츄 `.table-arena` 는 재사용하지 않는다(`.table-arena .seat{position:absolute}` 충돌).
 - 폭 조건이 필요한 규칙은 `17-responsive.css` **끝**에만(기존 블록 사이 삽입 금지).
 - 차례 테두리 `.oc-seat-turn`·`.oc-seat-turn.oc-seat-last`(경쟁 강조와 겹침) 스타일은 그대로 — 네임스페이스 테스트가 두 변수를 확인한다.
+- 스컬킹 좌석 배경은 반투명이라 라이트 테마에서 글자 뒤가 밝은 천이다 — 좌석 통계 키(예·획·손, `opacity`)는 천 위에서만 `.7` 로 덜 흐리게
+  해 AA(4.5:1)를 지킨다(`.6` 은 4.36:1).
 
 ### 4.6 접근성
 
```

```diff
 | 관전자 | 좌석 0 을 아래에 두고 나머지를 시계 방향으로. 아래 좌석은 일반 좌석 카드("나" 아님) |
 | 원카드 탈락 좌석 | 자리에 그대로(흐리게). "다음" 계산에서 건너뜀 |
 | 스컬킹 탈주 좌석 | 자리에 그대로. 유령이 두므로 "다음" 에서 건너뛰지 않음 |
-| 차례 없음(−1) | 테두리·"다음" 표시 없음(원카드 카드 낸 직후·경쟁 창, 스컬킹 입찰·트릭 정리) |
-| 긴 이름 | 말줄임 + `title` 로 전체 이름 |
+| 차례 없음(−1) | 테두리·"다음" 표시 없음(원카드 카드 낸 직후·경쟁 창, 스컬킹 입찰·라운드 정리). 스컬킹 트릭이 막 끝나 승자 리드를 기다리는 동안은 "다음"만 없음 |
+| 긴 이름 | 좁은 좌석은 이름이 아바타 아래 줄로(≈8자), 그래도 길면 말줄임 + `title`(데스크톱 — 모바일은 툴팁이 뜨지 않는다) |
 | 경쟁 버튼 | 위치 표 그대로(뷰포트 %). 위쪽 좌석을 최대 3초 가릴 수 있음 — 기존과 같음 |
 
 ## 6. 테스트
```

```diff
 - 단위: `tableLayout`·`sideCounts`(2~8인 전수 — 좌석 집합 보존, 시계 방향 순서, 내 좌석 회전, 관전자), `viewPosOf`(+1 → `'e'`, +2 → `'n'`, +3 → `'w'`,
   관전자), 원카드 `nextTurnSeat`(정·역방향, 탈락 건너뛰기, −1, 혼자 남음), 스컬킹 `nextTurnSeat`(마지막 사람, 입찰, 일반).
   `viewOrder`·`seatMinWidth` 테스트는 `components/seatOrder.test.ts` 로 옮기고 스컬킹 재내보내기는 유지(`docs/plans/onecard.md` 의 기존 후속).
-- CSS: 원카드 내기·먹기 간격 32px 단언(§4.4b).
+- CSS: 원카드 내기·먹기 간격 32px 단언(§4.4b). 시계 방향 자리(옆줄 `grid-area`·왼쪽 `column-reverse`·`grid-template-areas`)를 캐스케이드 승자
+  (같은 특이도면 뒤 규칙)로 단언 — `data-side` 단언은 DOM 표식만 본다. 좁은 좌석 이름 줄바꿈, 원카드 가운데 칸 축소·배지 줄바꿈, 스컬킹 ≤480px
+  맨 끝 블록(손패 숨김 + 가운데 내림)·윗줄 감기지 않음·라이트 테마 통계 키 대비도 원문 단언.
 - 컴포넌트: 원카드 2·4·6인의 `data-side`/`data-slot`, "다음" 표시 좌석, 방향 화살표 뒤집힘, 기존 단언(좌석 수·`data-seat`·`region "테이블"`) 유지.
-  스컬킹 2·5·8인 배치, 기존 DOM 순서 단언 유지, "다음" 표시. 티츄 좌석 클래스(+1 이 `seat-e`), 트릭 카드 비행이 그 좌석에서 출발.
+  스컬킹 2·5·8인 배치, 기존 DOM 순서 단언 유지, "다음" 표시(트릭이 막 끝난 순간 포함 — `CARD_PLAYED`→`TRICK_TAKEN`→`TURN_CHANGED`). 티츄 좌석 클래스
+  (+1 이 `seat-e`), 트릭 카드 비행이 그 좌석에서 출발, 채팅 말풍선·이모지가 같은 자리.
 - CSS 네임스페이스 테스트 유지(18·19 `@media` 0개, 17 마지막, 토큰 대조).
 - 배포 뒤 운영 사이트에서 390px·데스크톱으로 원카드 2·4·6인, 스컬킹 4·8인, 티츄 4인을 본다(봇 채우기 방). `docs/qa-scenarios.md` 에 항목 추가.
 
```

```diff
 - **티츄 `.table-arena` 재사용** — 좌석 절대 위치 규칙과 충돌하고 티츄 회귀 위험.
 - **티츄 CSS 를 좌우로 맞바꾸기** — 03·06·14·17 파일과 css-baseline 이 모두 바뀐다. 매핑 배열만 바꾸면 같은 효과.
 - **경쟁 버튼 슬롯 재배치** — 사용자 결정으로 보류(튜토리얼 연습·테스트가 함께 바뀌는 비용).
+- **스컬킹 좁은 화면에서 트릭 레일을 가운데 칸에 두고 슬롯만 줄이기**(이름칩 상한 52px·여백 축소로 2장/줄) — 375px 에서 슬롯 합 120px 대 내용
+  122px 로 빠듯하고 360px 에서 넘쳐 다시 1장/줄이 된다. 가운데를 옆줄 아래로 내리는 안(§4.3)을 택했다.
```

`docs/qa-scenarios.md`:

```diff
 10. **경쟁 버튼·늦은 누름**: 4 의 "늦었어요"는 빨간 오류 줄 없이 잠깐만 뜨고, 버튼에 마우스를 올려도(모바일은 탭한 뒤) 글자가
    읽힌다(전역 `button:hover` 배경에 덮이지 않는지 — 라이트·다크 둘 다).
 
+## 테이블 좌석 배치 확인 (D-132)
+
+배포 뒤 운영 사이트(또는 로컬)에서 봇 채우기 방으로 본다. 모바일은 **390px** 폭(브라우저 기기 모드), 데스크톱은 넓은 창 — 라이트·다크
+둘 다. 진행은 시계 방향이다: 위에서 내려다볼 때 내(아래) 다음 차례가 내 **왼쪽**이다.
+
+1. **원카드 좌석 배치**: 4인이면 내 다음 차례가 왼쪽 옆줄, 맞은편이 위, 직전 차례가 오른쪽 옆줄. 6인이면 왼쪽 2명(아래→위)·위 1명·
+   오른쪽 2명(위→아래), 2인이면 상대가 위에 혼자. 가운데 녹색 테이블 칸에 뽑을 더미·맨 위 카드·방향·차례가 있다.
+2. **차례·다음 표시**: 지금 차례 좌석에 테두리, 그 다음 좌석 위 테두리에 "다음" 꼬리표. 다음이 나면 "나" 줄에 "다음". 카드를 낸
+   직후·경쟁 창 동안은 테두리와 "다음"이 함께 사라진다. 파산·탈주한 좌석은 흐리게 자리를 지키고 "다음"은 그 좌석을 건너뛴다.
+3. **방향 화살표**: 가운데에 "↻ 시계 방향". Q 가 나오면 "↺ 반시계 방향"으로 바뀌고, 다음 차례가 정해지면 "다음"이 반대쪽(내 오른쪽
+   방향)으로 옮겨 간다.
+4. **내기·먹기 간격**: 손패 아래 "카드 내기"와 "먹기 (N장)" 사이가 손가락 하나(32px) 이상 떨어져 있어 엄지로 내기를 누르다 먹기를
+   누르지 않는다. 화면이 좁아 줄이 감겨도 두 버튼이 붙지 않는다.
+5. **스컬킹**: 8인이면 왼쪽 2·위 3·오른쪽 2(위쪽 반원) — 360px 에서도 윗줄 3명이 한 줄이다(2 + 1 로 감기지 않음). 넓은 화면은 가운데
+   칸에, 좁은 화면(≤480px)은 옆줄 아래 전체 폭에 "↻ 시계 방향"과 트릭 레일(낸 순서대로 순번·이름칩 — 카드는 좌석 앞이 아니라 레일에
+   놓인다. 390px 8인이면 한 줄에 3~4장). "다음"은 트릭 중 지금 차례 다음 좌석에 붙고, 그 트릭의 마지막 사람 차례·트릭이 끝나 승자가
+   리드하기 전·예측 중에는 없다(다음 리드는 트릭 승자 — 트릭이 끝난 순간 엉뚱한 좌석에 "다음"이 깜빡이지 않는다). 탈주(자동) 좌석도
+   건너뛰지 않는다. 라이트 테마에서 좌석의 예·획·손 글자가 천 위에서 읽힌다.
+6. **관전자**(원카드·스컬킹): 좌석 0 이 아래 칸에 일반 좌석으로 앉고 "나" 줄이 없다. 다음 차례가 좌석 0 이면 아래 좌석에 "다음"이
+   붙는다.
+7. **티츄 4인**: 내 다음 차례(패스의 '왼쪽' 상대)가 **왼쪽** 좌석, 직전 차례가 오른쪽, 파트너가 위다. 상대가 카드를 내면 그 좌석 쪽에서
+   가운데로 날아오고, 채팅 말풍선·이모지도 같은 좌석 옆에 뜬다. 관전자 화면도 좌석 3 이 아래, 좌석 0 이 왼쪽이다.
+8. **390px 겹침·넘침**(원카드 2·4·6인, 스컬킹 4·8인, 티츄 4인): 좌석·더미·트릭 레일·손패가 겹치거나 가로로 넘치지 않는다. 옆줄 좌석은
+   이름이 아바타 아래 줄로 내려가 8자 안팎 보인다(봇이 모두 "bo…"로 보이면 실패). 그보다 긴 이름은 말줄임 — 데스크톱은 마우스를 올리면
+   전체 이름(title)이 보이고, 모바일은 title 이 뜨지 않는다. 스컬킹 옆줄 좌석은 이름 + 예측·획득만(손패 장수 숨김) — 윗줄 좌석과
+   데스크톱은 손패 장수까지 보인다. 원카드 가운데 칸(≈138px)에서도 뽑을 더미와 맨 위 카드가 한 줄이고(7 의 지정 무늬 배지가 붙은 직후만
+   두 줄) 손패 첫 줄이 화면 아래 버튼 줄에 가려지지 않는다. 게스트(`guest-…`)·긴 봇 이름의 "차례" 배지는 가운데 칸 안에서 줄을 바꾸고
+   옆줄 좌석에 걸치지 않는다. 원카드 경쟁 버튼이 위쪽 좌석을 최대 3초 가릴 수 있다(설계상 그대로).
+
 ## 분산 시연 (멀티 인스턴스, Phase 6D)
 
 기본은 단일 인스턴스 + `mirboard.messaging.gateway=in-memory`. 멀티 인스턴스에서
```

`docs/plans/mobile-responsive-checklist.md`:

```diff
 - [ ] 준비/게임 방법/나가기 버튼 터치 타겟 충분(≥44px), 헤더에서 서로 겹치지 않음 (D-121)
 
 ### 게임판(GameTable) — 핵심
-- [ ] 4인 원형 좌석(S/W/N/E)이 360px 에서 겹치거나 화면 밖으로 넘치지 않음
+- [ ] 4인 원형 좌석(S/W/N/E)이 360px 에서 겹치거나 화면 밖으로 넘치지 않음 — 내 다음 차례(+1)가 **왼쪽**(E) 좌석 (D-132 시계 방향)
+- [ ] 원카드(2·4·6인)·스컬킹(4·8인) 테이블 배치(D-132)가 390px 에서 겹치거나 넘치지 않음 — 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄 시계 방향,
+  옆줄 좌석 이름이 아바타 아래 줄에 8자 안팎(모바일은 title 툴팁 없음), 스컬킹 옆줄 좌석은 손패 장수 숨김·트릭 레일은 옆줄 아래 전체 폭
+  (≤480px)·8인 윗줄 3명 한 줄(360px), 원카드 가운데 칸(≈138px)의 더미·맨 위 카드가 한 줄(지정 무늬 직후만 두 줄)·"차례" 배지가 칸 안,
+  "다음" 꼬리표가 위 좌석에 닿지 않음
 - [ ] 중앙 트릭 영역이 좌석/손패와 겹치지 않음
 - [ ] 손패: **탭 = 선택**, **드래그 = 재배열** 이 명확히 구분(8px 임계)
 - [ ] 손패 14장이 한 줄에서 겹쳐 들어오고 가장자리 카드가 잘리지 않음
```

- [ ] **Step 2: 현황·README·CLAUDE.md**

`docs/implementation-status.md`:

```diff
 # Mirboard 구현 현황
 
 > 지금까지 **실제로 구현된 기능**을 end-to-end로 정리한 현황 문서.
-> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-131),
+> 구조/흐름은 `docs/architecture.md`, 의사결정 이력은 `docs/decisions.md`(D-01~D-132),
 > 단계별 진행은 `docs/plans/mvp-roadmap.md` 참조.
 > 기능 설명의 세부 계약은 `docs/api.md`(REST), `docs/stomp-protocol.md`(STOMP),
 > `docs/game-port.md`(`GameEngine` 포트), `docs/rules-tichu.md`·`docs/rules-skullking.md`·
```

```diff
 | 12 | 멀티 인스턴스 (opt-in) | ✅(opt-in) | `infra.messaging.RedisMessageGateway` |
 | 13 | 관측성 (Prometheus 게임별 차원, Grafana, Sentry) | ✅ | `infra.metrics`, `infra.config.SentryConfig`, `ops/grafana` (D-107) |
 | 14 | **`GameEngine` 포트 (멀티게임 기반)** | ✅ | `domain.game.core.GameEngine`, `infra.ws.GameEngineProvider`, `TichuGameEngine` |
+| 15 | 테이블 좌석 배치 (원카드·스컬킹 시계 방향 테이블형 좌석 + "다음" 표시·방향 화살표, 티츄 +1 을 왼쪽에 — 클라만) | ✅ | `client/src/components/seatOrder.ts`(`tableLayout`), `features/tichu/seatView.ts`, `docs/plans/table-seating.md` (D-132) |
 
 설계 단계(Phase 1)부터 클라이언트 통합·UI 리디자인(Phase 20)까지 로드맵 항목이 완료
 표시되어 있다(`docs/plans/mvp-roadmap.md`).
```

```diff
   재현 스크립트는 `docs/case-study-multi-game.md` 부록 (6).
 - 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 세 봇
   평가, ~20s). 스컬킹·원카드 매치 기록 IT(D-115·D-128)는 Docker 가 필요해 `rules` 에서 뺐다.
-- **클라이언트**: **660건 / 61파일** (D-131 공개 전환 시점 실측 — 최종 리뷰 반영 뒤, 원카드 턴 카운트다운 17건·본인 큐 가드 1건 포함, 실패 0). Vitest + RTL — 스토어
+- **클라이언트**: **746건 / 65파일** (D-132 테이블 좌석 배치 시점 실측 — 사전 리뷰 반영 뒤, 배치 함수·티츄 매핑·말풍선·원카드·스컬킹 게임판 86건 포함, 실패 0). Vitest + RTL — 스토어
   리듀서, 족보 타입, 카드 에셋 매핑 등.
 - 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.
 - **밀폐성(D-113)**: IT 는 Testcontainers 로 자기 Postgres/Redis 를 띄우고 compose 에 기대지
```

```diff
   완주 IT** (`SkullKingBotMatchSimulationIT`).
 - **클라(S6, D-103) 완료**: `useStompRoom` 을 `RoomEventSink` 주입으로 게임 중립화하고
   (`tichuStore` 0줄 수정) `features/skullking/` 에 게임판을 새로 만들었다. 2~8 가변 좌석은
-  **Row-Flow**(auto-fit 좌석 그리드 + 재생순 트릭 레일)로 폭 미디어 쿼리 0개. CSS 는
+  **Row-Flow**(auto-fit 좌석 그리드 + 재생순 트릭 레일 — 좌석은 D-132 에서 테이블 배치로)로 폭 미디어 쿼리 0개. CSS 는
   `styles/parts/18-skullking-table.css` 에 `.sk-` 접두로 격리하고 네임스페이스 규칙을
   테스트로 기계화했다. 375×667 8인 실측 완료.
 - **매치 영속·ELO(D-115)**: 게임별 전적 테이블로 해소 — §9.
```

`README.md`:

```diff
 [![Deploy](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml/badge.svg)](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml)
 
 Spring Boot 4 / Java 25 · PostgreSQL · Redis · React + TypeScript ·
-서버 테스트 1327건 / 클라 660건
+서버 테스트 1327건 / 클라 746건
 
 **라이브**: https://mirboard.fly.dev — 로그인 화면 「게스트로 바로 체험하기」로 가입 없이 들어갈 수 있습니다.
 유휴 시 머신이 멈춰 첫 접속에 약 30초 걸립니다(콜드 스타트).
```

`CLAUDE.md`:

```diff
 
 **Mirboard** — 웹 기반 턴제 보드게임 플랫폼. 공통 허브/로비 + **게임 3종**: 티츄(4인 2:2 팀전), 스컬킹(2~8인 개인전), 원카드(2~6인 개인전 + "원카드!/잡기!" 실시간 경쟁, D-123~D-131).
 
-현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M7 전부 완료, 결정 이력 D-131까지). 로비/방 → 세 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소. 세 번째 게임 원카드는 `GameEngine` 포트 확장 1건(엔진 타이머 `timer`/`onTimer`, D-128)과 D-126 의 `sequenced()` 재정의만으로 붙었고(카탈로그 정의 `GameDefinition` 의 방 만들기 처음 선택 기본값 2개 `defaultPlayers()`·`defaultTurnSeconds()` 는 별도 — D-130, 기본 구현이 기존 동작. 인프라 grep `onecard` 0건) D-131 에서 공개됐다(`MIRBOARD_ONECARD_STATUS` 로 되돌림 — `docs/deploy.md`).
+현재는 **동작하는 MVP** 상태이며 상용화 트랙(A/C/D/E/G) 진행 중이다(설계 Phase 1 ~ 클라 통합·UI 리디자인 Phase 20 완료, 이후 M0~M7 전부 완료, 결정 이력 D-132까지). 로비/방 → 세 게임 풀게임 → 점수·ELO 영속(게임별, D-115) → 봇 자동 채움 → 재접속/탈주 → 라이트/다크 UI 까지 end-to-end로 연결되어 있다. 멀티게임(트랙 E)은 **완료** — 포트 추출(D-98) 후 스컬킹을 룰 명세(D-100)·순수 엔진(D-101)·탈주(D-104)·인게임 배선(D-102)·클라 게임판(D-103)까지 붙였다. 스컬킹 매치 영속·ELO 는 게임별 전적 테이블(`user_game_stats`, D-115)로 해소. 세 번째 게임 원카드는 `GameEngine` 포트 확장 1건(엔진 타이머 `timer`/`onTimer`, D-128)과 D-126 의 `sequenced()` 재정의만으로 붙었고(카탈로그 정의 `GameDefinition` 의 방 만들기 처음 선택 기본값 2개 `defaultPlayers()`·`defaultTurnSeconds()` 는 별도 — D-130, 기본 구현이 기존 동작. 인프라 grep `onecard` 0건) D-131 에서 공개됐다(`MIRBOARD_ONECARD_STATUS` 로 되돌림 — `docs/deploy.md`).
 
 - **서버** `server/` (Spring Boot 4 / Java 25, Gradle): 도메인 `domain.lobby`·`domain.game.{core,tichu,skullking,onecard,scoring}`, 인프라 `infra.{rest,ws,bot,messaging,metrics,config,web}`.
 - **클라이언트** `client/` (Vite + React 18 + TS, Zustand, @stomp/stompjs, Tailwind+shadcn).
```

```diff
   세션에서 본 IN_GAME→FINISHED 직후에도 게임판을 유지하고 `roomFinished` 를 넘긴다, D-120·D-129), 각
   게임판이 자기 소켓·sink 를 소유해 다른 게임의 코드 경로는 실행되지 않는다. 공개 이벤트의 순번
   판정(중복·구멍)은 훅만 한다(D-124) — 게임 스토어는 `lastSeq` 없는 순수 리듀서다.
+- **좌석은 시계 방향 테이블 (D-132)**: 진행 +1 이 내 **왼쪽**이다. 가변 좌석 게임판(원카드·스컬킹)은
+  `components/seatOrder.tableLayout` 으로 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄에 앉히고(관전자는 좌석 0 이 아래), 티츄 본인 시점 매핑은
+  `features/tichu/seatView.viewPosOf` 한 곳뿐이다(+1 → `seat-e` 왼쪽 — 좌석·비행·말풍선·이모지가 같이 쓴다).
 - **튜토리얼도 게임이 선언한다 (D-121)**: 각 게임이 `features/{game}/tutorial` 에
   `GameTutorial{steps, description, seenKey, bodyClassName?}` 을 두고, 게임 id 매핑은
   `features/tutorial/gameTutorials.ts` 한 곳뿐이다. 허브·대기실은 `tutorialFor(gameId)` 만 본다
```

- [ ] **Step 3: 실측**

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test` 뒤 `npm --prefix client run build`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  65 passed (65)` · `Tests  746 passed (746)`, 빌드 성공(CSS ≈66.7 kB · JS ≈571.7 kB — 기준 대비 각각 +3.0 · +2.3 kB, 500 kB 청크 경고는 기존).
`client/dist/` 는 빌드 산출물이라 커밋하지 않는다(gitignore).

Run: `grep -rn "\b732\b\|\b660 건\|660건" README.md docs/implementation-status.md`
Expected: 출력 없음(현재 수치는 746 / 65 로 고쳐졌다 — 이력 문서의 옛 수치는 두었다).

- [ ] **Step 4: 커밋**

```bash
git add docs/plans/table-seating.md \
  docs/qa-scenarios.md \
  docs/plans/mobile-responsive-checklist.md \
  docs/implementation-status.md \
  README.md \
  CLAUDE.md
git commit -m "docs(D-132): 테이블 좌석 배치 — 설계 상태, QA·모바일 점검 항목, 현황·README 수치, CLAUDE.md

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Phase Gate (컨트롤러)**

구현 에이전트가 아니라 컨트롤러가 한다. `git log --oneline e88bf68..HEAD` 로 태스크 커밋 6개(리뷰 수정 커밋이 있으면 그만큼 더)를 확인하고 최종 리뷰를 거친 뒤 사용자에게
보고한다 — **사용자 승인 전에는 main 에 병합하지 않는다**(푸시하면 자동 배포). 배포 뒤 운영 사이트에서 390px·데스크톱으로 원카드 2·4·6인, 스컬킹 4·8인, 티츄 4인을 본다
(`docs/qa-scenarios.md` "테이블 좌석 배치 확인").
