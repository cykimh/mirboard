# 원카드 S0·S1 구현 계획 (D-124 · D-125)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원카드(D-123)의 선행 두 단계를 끝낸다 — S0: 공개 이벤트 순번 판정을 `useStompRoom` 한 곳으로
옮겨 게임 스토어를 순수 리듀서로 만든다(동작 보존). S1: 원카드 룰 정본 `docs/rules-onecard.md` 를 쓴다.

**Architecture:** S0 은 클라 전용 리팩터다. 순수 함수 `judgeSeq` 가 중복·구멍을 판정하고, 훅이 기준점
(`lastSeqRef`, resync 의 `eventSeq`)을 가진 채 바로 다음 순번이거나 순번 없는 이벤트만 sink 로 넘긴다.
티츄·스컬킹 스토어에서 `lastSeq` 와 판정 코드를 걷어내고, sink 반환값을 `applied`·`unhandled`·`ignored`
로 좁힌다. 각 태스크 끝에서 클라 타입 검사와 전체 테스트가 통과하도록 순서를 잡았다(훅 먼저 → 스토어 →
타입 좁히기). S1 은 문서만 만든다.

**Tech Stack:** Vite + React 18 + TypeScript, Zustand, @stomp/stompjs, Vitest + React Testing Library.

## Global Constraints

- 응답·주석·문서는 한국어.
- 작업 위치는 브랜치 `docs/onecard-plan`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan`
  (설계서·D-123·이 계획이 이미 있다). 메인 체크아웃(`/Users/yupchang/Developer/mirboard`)은 다른 세션(D-116)이
  쓰고 있으니 건드리지 않는다.
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 compile)를 돈다. 클라
  `node_modules` 가 없으면 클라 검사를 건너뛰므로 Task 1 에서 먼저 설치한다.
- 서버 코드는 바꾸지 않는다(S0·S1 모두 클라·문서).
- 티츄·스컬킹 동작은 바꾸지 않는다. 예외는 D-124 에 적는 둘뿐이다(스컬킹 `BIDDING_STARTED` 구멍 선반영
  제거, 매치 종료 뒤 구멍 난 잔여 이벤트의 resync 1회).
- 결정 번호: S0 = D-124, S1 = D-125. 착수 시점에 다른 세션이 이 번호를 썼으면 다음 빈 번호를 쓰고 본문의
  번호를 모두 바꾼다. 새 결정은 `docs/decisions.md` 의 `## D-123` 바로 위에 넣는다(최근 항목이 위).
- 클라 테스트 수: 시작 413건 → S0 끝 420건.
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를
  잡는 문맥이다. 세 경우 모두 첫 글자 하나를 떼고 읽는다(나머지 들여쓰기는 파일 그대로다).

## 파일 지도

| 파일 | 작업 | 책임 |
| --- | --- | --- |
| `client/src/ws/seqGate.ts` | 신규 | 순번 판정 순수 함수 `judgeSeq` |
| `client/src/ws/seqGate.test.ts` | 신규 | 판정 4값 |
| `client/src/ws/useStompRoom.ts` | 수정 | 기준점 `lastSeqRef` 소유, sink 앞에서 판정 |
| `client/src/ws/useStompRoom.sink.test.tsx` | 수정 | 훅 판정 계약 테스트 |
| `client/src/ws/roomEventSink.ts` | 수정 | sink 계약 문서(판정 끝난 이벤트만 온다) |
| `client/src/types/stomp.ts` | 수정 | `ApplyEventResult` 를 3값으로 |
| `client/src/features/tichu/tichuStore.ts` | 수정 | `lastSeq`·판정·`applyTableView` 제거 |
| `client/src/features/tichu/tichuRoomSink.ts` | 수정 | 스냅샷에서 `eventSeq` 전달 제거 |
| `client/src/features/tichu/*.test.ts` (3개) | 수정 | 순번 관련 단언 제거·특성화 테스트 |
| `client/src/features/skullking/skullkingStore.ts` | 수정 | `lastSeq`·판정 제거, `BIDDING_STARTED` 단순화 |
| `client/src/features/skullking/skullkingStore.test.ts` | 수정 | 순번 관련 테스트 정리·특성화 테스트 |
| `docs/decisions.md` | 수정 | D-124, D-125, D-103 보정 표시 |
| `docs/stomp-protocol.md` · `CLAUDE.md` | 수정 | 순번 판정 위치(D-124) |
| `docs/rules-onecard.md` | 신규 | 원카드 룰 정본 |
| `docs/plans/onecard.md` | 수정 | §3.2 확정 표시 |

---

## S0 — 이벤트 순번 판정을 훅으로 (D-124)

### Task 1: 순번 판정 함수 `judgeSeq` + D-124 기록

**Files:**
- Create: `client/src/ws/seqGate.ts`
- Create: `client/src/ws/seqGate.test.ts`
- Modify: `docs/decisions.md` (`## D-123` 바로 위)

**Interfaces:**
- Produces: `export type SeqVerdict = 'next' | 'duplicate' | 'gap' | 'unsequenced'`,
  `export function judgeSeq(lastSeq: number, seq: number | null | undefined): SeqVerdict` — Task 2 가 쓴다.

- [ ] **Step 1: 작업 환경 준비**

```bash
cd /Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan
git status --short
npm --prefix client ci
```

Expected: `git status` 출력 없음(깨끗한 트리), `npm ci` 성공.

- [ ] **Step 2: D-124 결정 기록**

`docs/decisions.md` 에서 `## D-123 (2026-10-04)` 줄 바로 위에 다음을 넣는다(뒤에 빈 줄 하나).

```markdown
## D-124 (2026-10-04) — 이벤트 순번 판정을 훅으로: 게임 스토어는 순수 리듀서 (원카드 S0)

D-103 이 남긴 부채("3번째 게임 전에 판정은 훅으로, sink 는 순수 리듀서로")를 원카드 착수 전에 갚는다.
티츄·스컬킹 스토어가 각자 `lastSeq` 를 들고 중복·구멍을 판정해, 세 번째 게임이 같은 코드를 또 복사해야
했다. 이제 `useStompRoom` 이 기준점(resync 의 `eventSeq`)을 갖고 `judgeSeq` 로 중복은 버리고 구멍은 resync
로 돌리며, sink 에는 바로 다음 순번이거나 순번 없는 메타 이벤트만 넘긴다(반환 `applied`·`unhandled`·
`ignored`). 동작은 그대로이고 예외는 둘이다 — 스컬킹 `BIDDING_STARTED` 가 구멍으로 올 때 미리 비우던 처리
(D-103)는 없앴다(서버가 `BIDDING_STARTED` 를 `HAND_DEALT` 보다 먼저 보내 라운드 경계에서 구멍이 아니고,
구멍이면 resync 가 권위 상태를 준다). 매치 종료 뒤 구멍 난 잔여 이벤트는 이제 resync 를 한 번 부른다(D-122
에서는 무시) — 스냅샷이 종료 상태를 그대로 주므로 무해하다.
```

- [ ] **Step 3: 실패하는 테스트 작성** — `client/src/ws/seqGate.test.ts`

```ts
import { describe, expect, it } from 'vitest';
import { judgeSeq } from './seqGate';

describe('judgeSeq — 공개 이벤트 순번 판정 (D-124)', () => {
  it('바로 다음 순번은 next', () => {
    expect(judgeSeq(10, 11)).toBe('next');
  });

  it('같거나 지난 순번은 duplicate', () => {
    expect(judgeSeq(10, 10)).toBe('duplicate');
    expect(judgeSeq(10, 3)).toBe('duplicate');
  });

  it('건너뛴 순번은 gap', () => {
    expect(judgeSeq(10, 12)).toBe('gap');
    expect(judgeSeq(10, 99)).toBe('gap');
  });

  it('순번 없는 메타 이벤트는 unsequenced', () => {
    expect(judgeSeq(10, undefined)).toBe('unsequenced');
    expect(judgeSeq(10, null)).toBe('unsequenced');
  });

  it('방 진입 직후(기준점 0)에는 1 만 next', () => {
    expect(judgeSeq(0, 1)).toBe('next');
    expect(judgeSeq(0, 2)).toBe('gap');
  });
});
```

- [ ] **Step 4: 실패 확인**

Run: `npm --prefix client run test -- seqGate`
Expected: FAIL — `Failed to resolve import "./seqGate"`.

- [ ] **Step 5: 구현** — `client/src/ws/seqGate.ts`

```ts
/**
 * 공개 토픽 이벤트의 순번 판정 (D-124). 판정은 {@link import('./useStompRoom').useStompRoom}
 * 한 곳에서만 한다 — 게임 스토어(sink)는 판정이 끝난 이벤트만 받는 순수 리듀서다. 예전에는
 * 티츄·스컬킹 스토어가 같은 판정을 각자 들고 있었다(D-103 부채).
 *
 * - `next`        바로 다음 순번(lastSeq + 1) — sink 로 넘긴다
 * - `duplicate`   이미 지난 순번 — 버린다
 * - `gap`         순번이 건너뛰었다 — 놓친 이벤트가 있으니 resync 로 권위 스냅샷을 받는다
 * - `unsequenced` 순번 없는 메타 이벤트(접속 배지·칩 정산 등) — 판정 없이 sink 로 넘긴다
 */
export type SeqVerdict = 'next' | 'duplicate' | 'gap' | 'unsequenced';

/**
 * @param lastSeq 마지막으로 반영한 순번. resync 스냅샷의 `eventSeq` 가 권위 기준점이다.
 * @param seq     envelope 의 `seq`. 서버는 메타 이벤트에서 이 필드를 생략한다(`NON_NULL`).
 */
export function judgeSeq(lastSeq: number, seq: number | null | undefined): SeqVerdict {
  if (seq === undefined || seq === null) return 'unsequenced';
  if (seq <= lastSeq) return 'duplicate';
  if (seq > lastSeq + 1) return 'gap';
  return 'next';
}
```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix client run test -- seqGate`
Expected: PASS — `Tests  5 passed (5)`.

- [ ] **Step 7: 커밋**

```bash
git add client/src/ws/seqGate.ts client/src/ws/seqGate.test.ts docs/decisions.md
git commit -m "feat(D-124): 공개 이벤트 순번 판정 함수 judgeSeq

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 훅이 순번 기준점을 소유

**Files:**
- Modify: `client/src/ws/useStompRoom.ts`
- Modify: `client/src/ws/useStompRoom.sink.test.tsx`

**Interfaces:**
- Consumes: `judgeSeq` (Task 1).
- Produces: sink 의 `applyEvent` 는 **바로 다음 순번이거나 순번 없는 이벤트만** 받는다. 반환이 `applied`·
  `ignored` 면 훅이 기준점을 전진, `unhandled` 면 resync(기준점 유지). 이 태스크에서는 스토어가 아직 자체
  판정을 들고 있으므로, 스토어가 `gap`·`duplicate` 를 돌려주는 경우를 호환으로 처리한다(Task 5 에서 제거).

- [ ] **Step 1: 테스트를 새 계약으로 바꾼다** — `client/src/ws/useStompRoom.sink.test.tsx`

(a) `const frame = ...` 줄 바로 아래에 헬퍼를 추가한다.

```ts
const frame = (body: unknown) => ({ body: JSON.stringify(body) });

/** 공개 토픽에 이벤트 한 건. seq 를 생략하면 JSON 에서 키가 빠진다(서버 `NON_NULL` 과 같다). */
const publish = (seq: number | undefined, type = 'X') =>
  handlers.get(`/topic/room/${ROOM}`)!(frame({ type, seq, payload: {} }));
```

(b) 기존 `it.each([['gap', true], ['unhandled', true], ['applied', false], ['duplicate', false]] ...)` 블록
전체(`'applyEvent 가 %s 를 반환하면 resync 재호출=%s'`)를 아래로 바꾼다.

```ts
  it.each([
    ['unhandled', true],
    ['applied', false],
    ['ignored', false],
  ] as const)(
    '다음 순번 이벤트에 sink 가 %s 를 반환하면 resync 재호출=%s',
    async (result, shouldResync) => {
      const sink = makeSink(result);
      renderHook(() => useStompRoom(ROOM, TOKEN, sink));
      await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
      const before = resyncMock.mock.calls.length;

      act(() => publish(SNAP.eventSeq + 1));

      expect(sink.applyEvent).toHaveBeenCalledTimes(1);
      if (shouldResync) {
        expect(resyncMock.mock.calls.length).toBeGreaterThan(before);
      } else {
        expect(resyncMock.mock.calls.length).toBe(before);
      }
    },
  );

  describe('순번 판정은 훅이 한다 (D-124)', () => {
    async function mounted(sink: ReturnType<typeof makeSink>) {
      renderHook(() => useStompRoom(ROOM, TOKEN, sink));
      await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
      return resyncMock.mock.calls.length;
    }

    it('지난 순번(중복)은 sink 로 넘기지 않고 resync 도 하지 않는다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => publish(SNAP.eventSeq));

      expect(sink.applyEvent).not.toHaveBeenCalled();
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('구멍 난 순번은 sink 로 넘기지 않고 resync 한다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => publish(SNAP.eventSeq + 2));

      expect(sink.applyEvent).not.toHaveBeenCalled();
      expect(resyncMock.mock.calls.length).toBeGreaterThan(before);
    });

    it('반영한 이벤트마다 기준점이 전진한다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => {
        publish(SNAP.eventSeq + 1);
        publish(SNAP.eventSeq + 2);
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(2);
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('ignored 도 기준점을 전진시킨다 — 다음 순번이 구멍으로 보이지 않는다', async () => {
      const sink = makeSink('ignored');
      const before = await mounted(sink);

      act(() => {
        publish(SNAP.eventSeq + 1);
        publish(SNAP.eventSeq + 2);
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(2);
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('unhandled 는 기준점을 전진시키지 않는다 — resync 가 다시 세운다', async () => {
      const sink = makeSink('unhandled');
      const before = await mounted(sink);

      act(() => {
        publish(SNAP.eventSeq + 1); // unhandled → resync
        publish(SNAP.eventSeq + 2); // 기준점이 그대로라 구멍 → resync, sink 미호출
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(1);
      expect(resyncMock.mock.calls.length).toBe(before + 2);
    });

    it('순번 없는 메타 이벤트는 판정 없이 넘기고 기준점을 건드리지 않는다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => {
        publish(undefined, 'PLAYER_DISCONNECTED');
        publish(SNAP.eventSeq + 1);
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(2);
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('방이 바뀌면 기준점이 0 으로 돌아간다', async () => {
      const sink = makeSink();
      const { rerender } = renderHook(({ room }) => useStompRoom(room, TOKEN, sink), {
        initialProps: { room: ROOM },
      });
      await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
      // 새 방의 resync 는 응답이 오지 않게 붙잡아, 기준점이 리셋값(0)인 구간을 본다.
      resyncMock.mockImplementation(() => new Promise(() => {}));

      rerender({ room: 'r-2' });
      act(() => {
        handlers.get('/topic/room/r-2')!(frame({ type: 'X', seq: 1, payload: {} }));
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(1);
    });
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- useStompRoom`
Expected: FAIL 3건 — `지난 순번(중복)은 sink 로 넘기지 않고 …`, `구멍 난 순번은 sink 로 넘기지 않고 …`,
`unhandled 는 기준점을 전진시키지 않는다 …` (옛 훅은 판정 없이 전부 sink 로 넘긴다). 나머지 14건 PASS.

- [ ] **Step 3: 훅 구현** — `client/src/ws/useStompRoom.ts` 에 다섯 군데를 고친다.

(a) import 에 한 줄 추가:

```ts
import type { RoomEventSink } from './roomEventSink';
import { judgeSeq } from './seqGate';
import type { ResyncEnvelope, StompEnvelope } from '@/types/stomp';
```

(b) `sinkRef.current = sink;` 바로 아래에 기준점 ref:

```ts
  const sinkRef = useRef(sink);
  sinkRef.current = sink;
  /**
   * D-124 — 공개 이벤트 순번의 기준점. 스토어가 아니라 훅이 가진다(판정을 게임마다 복사하지
   * 않도록). resync 스냅샷의 `eventSeq` 가 권위값이고, sink 가 반영했거나 의도적으로 버린
   * 순번 있는 이벤트마다 전진한다.
   */
  const lastSeqRef = useRef(0);
```

(c) `resync` 안에서 스냅샷을 넘긴 직후 기준점을 세운다:

```ts
      // 껍데기를 가공하지 않고 그대로 넘긴다 — 게임별 필드 해석은 sink 책임.
      sinkRef.current.applySnapshot(snap);
      // 순번 기준점은 스냅샷이 다시 세운다 (D-124).
      lastSeqRef.current = snap.eventSeq;
```

(d) 방 진입 effect 에서 기준점을 0 으로:

```ts
    sinkRef.current.reset(roomId);
    lastSeqRef.current = 0;
    resetChat(roomId);
```

(e) 공개 토픽 구독 콜백 전체를 바꾼다. 옛 코드:

```ts
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          const result = sinkRef.current.applyEvent(env);
          if (result === 'unhandled' || result === 'gap') {
            // 라이프사이클 이벤트 또는 갭 — 권위 있는 스냅샷 재취득.
            resync();
          }
          // 'applied' / 'duplicate' / 'ignored' 인 경우엔 추가 동작 없음.
```

새 코드:

```ts
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          // D-124 — 순번 판정은 여기서만 한다. sink 는 판정이 끝난 이벤트만 받는다.
          const verdict = judgeSeq(lastSeqRef.current, env.seq);
          if (verdict === 'duplicate') return;
          if (verdict === 'gap') {
            // 놓친 이벤트가 있다 — 권위 스냅샷 재취득(기준점도 스냅샷이 다시 세운다).
            resync();
            return;
          }
          const result = sinkRef.current.applyEvent(env);
          if (result === 'unhandled' || result === 'gap') {
            // 리듀서 없는 라이프사이클 이벤트 — 권위 스냅샷 재취득. 기준점은 그대로 둔다.
            // ('gap' 은 스토어가 아직 자체 판정을 들고 있는 동안의 호환 — D-124 Task 5 에서 제거.)
            resync();
            return;
          }
          if (result === 'duplicate') return; // 위와 같은 호환.
          // 'applied' | 'ignored' — 순번 있는 이벤트면 기준점을 전진시킨다.
          if (verdict === 'next' && typeof env.seq === 'number') lastSeqRef.current = env.seq;
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- useStompRoom seqGate`
Expected: PASS — `Tests  22 passed (22)`.

Run: `npm --prefix client run build:check && npm --prefix client run test`
Expected: 타입 오류 없음, `Tests  424 passed (424)` (스토어는 아직 자체 판정을 들고 있지만 훅과 기준점이
같아 결과가 일치한다).

- [ ] **Step 5: 커밋**

```bash
git add client/src/ws/useStompRoom.ts client/src/ws/useStompRoom.sink.test.tsx
git commit -m "feat(D-124): useStompRoom 이 순번 기준점을 갖고 sink 앞에서 판정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 티츄 스토어를 순수 리듀서로

**Files:**
- Modify: `client/src/features/tichu/tichuStore.ts`
- Modify: `client/src/features/tichu/tichuRoomSink.ts`
- Modify: `client/src/features/tichu/tichuStore.applyEvent.test.ts`
- Modify: `client/src/features/tichu/tichuRoomSink.test.ts`
- Modify: `client/src/features/tichu/tichuStore.roundHistory.test.ts`

**Interfaces:**
- Consumes: Task 2 의 훅 계약(판정 끝난 이벤트만 온다).
- Produces: `useTichuStore` 에 `lastSeq`·`applyTableView` 가 없다. `applySnapshot` 인자는
  `{ tableView, privateHand, disconnectedSeats?, chips? }`(`eventSeq` 없음). `applyEvent` 반환형은
  `@/types/stomp` 의 `ApplyEventResult`.

- [ ] **Step 1: 테스트를 새 계약으로 바꾼다**

(a) `tichuStore.applyEvent.test.ts` — `loadTable` 두 번째 인자(순번)를 일괄 제거한다. 한 줄 호출과
여러 줄 호출(`}),` 다음 줄 `1,`)을 모두 처리한다:

```bash
perl -0pi -e 's/(loadTable\(.*\)), \d+\);/$1);/g; s/(\}\),)\n\s*1,\n(\s*\);)/$1\n$2/g' \
  client/src/features/tichu/tichuStore.applyEvent.test.ts
grep -n "loadTable(" client/src/features/tichu/tichuStore.applyEvent.test.ts
```

Expected: 25행의 정의(`function loadTable(table: TableView, lastSeq = 0)`)를 빼면 모든 호출이 인자 하나다.

(b) 같은 파일에서 다음을 바꾼다.

헬퍼 — 옛 코드:

```ts
function loadTable(table: TableView, lastSeq = 0) {
  useTichuStore.setState({
    tableView: table,
    lastSeq,
  });
}
```

새 코드:

```ts
function loadTable(table: TableView) {
  useTichuStore.setState({ tableView: table });
}
```

`beforeEach` 바로 아래에 특성화 테스트를 추가한다:

```ts
  beforeEach(() => {
    useTichuStore.getState().reset('room-patch');
  });

  it('순번을 판정하지 않는다 — 중복·구멍 판정은 훅 몫 (D-124)', () => {
    loadTable(baseTable());
    const first = useTichuStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 9,
      payload: { currentTurnSeat: 2 },
    });
    const replay = useTichuStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 3,
      payload: { currentTurnSeat: 1 },
    });
    expect([first, replay]).toEqual(['applied', 'applied']);
    expect(useTichuStore.getState().tableView!.currentTurnSeat).toBe(1);
  });
```

테스트 두 개를 통째로 지운다 — 훅 테스트(Task 2)가 대신한다:

```ts
  it('dedups events with seq <= lastSeq', () => {
    loadTable(baseTable());
    const r = useTichuStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 10,
      payload: { currentTurnSeat: 2 },
    });
    expect(r).toBe('duplicate');
    expect(useTichuStore.getState().tableView!.currentTurnSeat).toBe(0);
  });
```

```ts
  it('detects gaps for resync fallback', () => {
    loadTable(baseTable());
    const r = useTichuStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 7,
      payload: { currentTurnSeat: 2 },
    });
    expect(r).toBe('gap');
  });
```

`lastSeq` 단언과 `eventSeq` 인자를 지운다:

```diff
     expect(useTichuStore.getState().disconnectedSeats.has(2)).toBe(true);
-    // seq 없는 메타 이벤트는 lastSeq 를 건드리지 않음.
-    expect(useTichuStore.getState().lastSeq).toBe(3);
```

```diff
       privateHand: { seat: 0, cards: [] },
-      eventSeq: 5,
       disconnectedSeats: [1, 3],
```

```diff
     expect(table.currentTop?.type).toBe('PAIR');
-    expect(useTichuStore.getState().lastSeq).toBe(2);
```

```diff
-  it('DRAGON_GIVEN 은 seq 만 진행 — 점수 패치는 동반 TRICK_TAKEN 이 처리', () => {
+  it('DRAGON_GIVEN 은 상태를 바꾸지 않는다 — 점수 패치는 동반 TRICK_TAKEN 이 처리', () => {
```

```diff
     expect(r).toBe('applied');
-    expect(useTichuStore.getState().lastSeq).toBe(2);
     // tableView 자체는 동일.
```

```diff
-  it('ROUND_ENDED sets banner state and advances seq', () => {
+  it('ROUND_ENDED sets banner state', () => {
```

```diff
     expect(useTichuStore.getState().roundEnded?.teamAScore).toBe(120);
-    expect(useTichuStore.getState().lastSeq).toBe(2);
```

라이프사이클 테스트 — 옛 코드:

```ts
  it('lifecycle events (DEALING_PHASE_STARTED, etc.) return unhandled for resync fallback', () => {
    loadTable(baseTable());
    const r = useTichuStore.getState().applyEvent({
      type: 'DEALING_PHASE_STARTED',
      seq: 2,
      payload: { phaseCardCount: 14 },
    });
    expect(r).toBe('unhandled');
    // store 는 변경되지 않음.
    expect(useTichuStore.getState().lastSeq).toBe(1);
  });
```

새 코드:

```ts
  it('lifecycle events (DEALING_PHASE_STARTED, etc.) return unhandled for resync fallback', () => {
    const table = baseTable();
    loadTable(table);
    const r = useTichuStore.getState().applyEvent({
      type: 'DEALING_PHASE_STARTED',
      seq: 2,
      payload: { phaseCardCount: 14 },
    });
    expect(r).toBe('unhandled');
    // store 는 변경되지 않음.
    expect(useTichuStore.getState().tableView).toBe(table);
  });
```

(c) `tichuRoomSink.test.ts`:

```diff
- * 여기서 고정하는 것은 "옮기기 전과 동작이 같다" 이며, 특히 `applySnapshot` 의 5필드 매핑이
- * 중요하다: `eventSeq→lastSeq` 오타는 모든 이벤트를 gap 으로 만들어 resync 폭주가 되고,
- * `chips`/`disconnectedSeats` 오타는 예외 없이 배지만 사라진다.
+ * 여기서 고정하는 것은 "옮기기 전과 동작이 같다" 이며, 특히 `applySnapshot` 의 필드 매핑이
+ * 중요하다: `chips`/`disconnectedSeats` 오타는 예외 없이 배지만 사라진다. (순번 기준점
+ * `eventSeq` 는 D-124 부터 스토어가 아니라 훅이 가진다 — `ws/useStompRoom.sink.test.tsx`.)
```

```diff
-  it('applySnapshot 이 5필드를 값 단위로 매핑한다', () => {
+  it('applySnapshot 이 4필드를 값 단위로 매핑한다', () => {
```

```diff
-    expect(s.lastSeq).toBe(42); // ← eventSeq→lastSeq. 틀리면 resync 폭주.
```

```diff
-    expect(useTichuStore.getState().lastSeq).toBe(1);
```

위임 테스트 — 옛 코드:

```ts
  it('applyEvent 는 스토어 판정을 그대로 위임한다', () => {
    tichuRoomSink.applySnapshot({
      roomId: 'r-1',
      phase: 'PLAYING',
      eventSeq: 5,
      tableView: TABLE,
      privateHand: HAND,
    });

    expect(tichuRoomSink.applyEvent({ type: 'PASSED', seq: 3, payload: { seat: 0 } }))
      .toBe('duplicate');
    expect(tichuRoomSink.applyEvent({ type: 'PASSED', seq: 99, payload: { seat: 0 } }))
      .toBe('gap');
    expect(tichuRoomSink.applyEvent({ type: 'NOPE', seq: 6, payload: {} }))
      .toBe('unhandled');
  });
```

새 코드:

```ts
  it('applyEvent 는 스토어 반영 결과를 그대로 위임한다 (순번 판정은 훅, D-124)', () => {
    tichuRoomSink.applySnapshot({
      roomId: 'r-1',
      phase: 'PLAYING',
      eventSeq: 5,
      tableView: TABLE,
      privateHand: HAND,
    });

    expect(tichuRoomSink.applyEvent({ type: 'PASSED', seq: 6, payload: { seat: 0 } }))
      .toBe('applied');
    expect(tichuRoomSink.applyEvent({ type: 'NOPE', seq: 7, payload: {} }))
      .toBe('unhandled');
  });
```

(d) `tichuStore.roundHistory.test.ts`:

```diff
-function resyncWith(table: TableView, eventSeq = 1) {
+function resyncWith(table: TableView) {
   useTichuStore.getState().applySnapshot({
     tableView: table,
     privateHand: emptyHand,
-    eventSeq,
   });
 }
```

```diff
-    resyncWith(table, 1);
-    resyncWith(table, 2);
-    resyncWith(table, 3);
+    resyncWith(table);
+    resyncWith(table);
+    resyncWith(table);
```

```diff
       }),
-      2,
     );
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- tichu`
Expected: FAIL — `순번을 판정하지 않는다 — 중복·구멍 판정은 훅 몫 (D-124)` 를 포함해, seq 를 실은 리듀서
테스트(`PLAYED reduces handCount …`, `TURN_CHANGED updates currentTurnSeat` 등)가 실패한다. 옛 스토어는
`lastSeq` 0 에서 seq 2 를 구멍으로 판정하기 때문이다.

- [ ] **Step 3: 스토어 구현** — `client/src/features/tichu/tichuStore.ts`

```diff
+import type { ApplyEventResult } from '@/types/stomp';
 import { effectForHandType, useEffectStore } from './effectStore';
```

```diff
   privateHand: PrivateHand | null;
-  lastSeq: number;
```

```diff
       privateHand: PrivateHand;
-      eventSeq: number;
```

```diff
-  applyTableView: (table: TableView, seq?: number) => void;
```

```diff
-export type ApplyEventResult = 'applied' | 'duplicate' | 'gap' | 'unhandled';
-
```

초기 상태(들여쓰기 2칸)와 `reset`(6칸) 두 곳:

```diff
   privateHand: null,
-  lastSeq: 0,
```

```diff
       privateHand: null,
-      lastSeq: 0,
```

```diff
-  applySnapshot({ tableView, privateHand, eventSeq, disconnectedSeats, chips }) {
+  applySnapshot({ tableView, privateHand, disconnectedSeats, chips }) {
     set({
       tableView,
       privateHand,
-      lastSeq: eventSeq,
```

`TURN_CHANGED`:

```diff
         set({
           tableView: { ...table, currentTurnSeat: p.currentTurnSeat },
-          lastSeq: seq ?? lastSeq,
           turnStartedAt: Date.now(),
         });
```

`ROUND_ENDED`:

```diff
               doubleVictory: p.score.doubleVictory ?? false,
             },
           ],
-          lastSeq: seq ?? lastSeq,
         }));
```

`PLAYER_DISCONNECTED`·`PLAYER_RECONNECTED` 두 곳:

```diff
-        set({ disconnectedSeats: next, lastSeq: seq ?? lastSeq });
+        set({ disconnectedSeats: next });
```

`CHIPS_SETTLED`:

```diff
-        // D-82 — 방 칩 정산(공개 메타 이벤트, seq 무관). stacks/deltas 키는 userId 문자열.
+        // D-82 — 방 칩 정산(공개 메타 이벤트, 순번 없음). stacks/deltas 키는 userId 문자열.
         const p = envelope.payload as {
           stacks: Record<number, number>;
           deltas: Record<number, number>;
         };
-        set({ chips: p.stacks ?? {}, chipDeltas: p.deltas ?? {}, lastSeq: seq ?? lastSeq });
+        set({ chips: p.stacks ?? {}, chipDeltas: p.deltas ?? {} });
```

`MATCH_ENDED`:

```diff
             mvpStat: p.mvpStat ?? null,
           },
-          lastSeq: seq ?? lastSeq,
         });
```

`applyEvent` 의 doc 주석 — 옛 코드:

```ts
  /**
   * Phase 5d: 공개/비공개 이벤트를 받아 가능한 경우 부분 패치로 store 에 반영한다.
   * 반환값:
   *   'applied'    — 패치 성공, lastSeq 가 envelope.seq 로 갱신됨.
   *   'duplicate'  — envelope.seq <= lastSeq, 이미 처리한 이벤트.
   *   'gap'        — envelope.seq > lastSeq + 1, /resync 권유.
   *   'unhandled'  — 본 이벤트 타입은 reducer 가 없음, /resync 권유.
   */
```

새 코드:

```ts
  /**
   * Phase 5d: 공개 이벤트를 부분 패치로 store 에 반영한다. 순번 판정(중복·구멍)은 훅이
   * 이미 끝냈다(D-124) — 여기 오는 것은 바로 다음 순번이거나 순번 없는 메타 이벤트뿐이다.
   * 반환값:
   *   'applied'    — 패치 성공.
   *   'unhandled'  — 본 이벤트 타입은 reducer 가 없음, 훅이 /resync 한다.
   */
```

`applyTableView` 구현을 통째로 지운다:

```ts
  applyTableView(table, seq) {
    if (seq !== undefined && seq <= get().lastSeq) return;
    set({ tableView: table, lastSeq: seq ?? get().lastSeq });
  },

```

`applyEvent` 머리 — 옛 코드:

```ts
  applyEvent(envelope) {
    const seq = envelope.seq;
    const lastSeq = get().lastSeq;
    if (seq !== undefined) {
      if (seq <= lastSeq) return 'duplicate';
      if (seq > lastSeq + 1) return 'gap';
    }

    const table = get().tableView;
    const advance = (next: TableView) => set({ tableView: next, lastSeq: seq ?? lastSeq });
```

새 코드:

```ts
  applyEvent(envelope) {
    const table = get().tableView;
    const advance = (next: TableView) => set({ tableView: next });
```

순번만 전진시키던 `advance(table)` 호출을 없앤다:

`PASSED` — 옛 코드:

```ts
        // TableView 에 passedSeats 는 노출되지 않으므로 seq 만 진행 (다음 TURN_CHANGED 가 차례 갱신).
        if (!table) return 'unhandled';
        // payload 는 검증 위해 캐스트만.
        envelope.payload as PassedPayload;
        advance(table);
        return 'applied';
```

새 코드:

```ts
        // TableView 에 passedSeats 는 노출되지 않으므로 반영할 것이 없다 (다음 TURN_CHANGED 가 차례 갱신).
        if (!table) return 'unhandled';
        // payload 는 검증 위해 캐스트만.
        envelope.payload as PassedPayload;
        return 'applied';
```

`DRAGON_GIVEN`:

```diff
         envelope.payload as DragonGivenPayload;
-        advance(table);
         return 'applied';
```

`PLAYER_READY` — 옛 코드:

```ts
        if (table.readySeats.includes(p.seat)) {
          advance(table);
          return 'applied';
        }
```

새 코드:

```ts
        if (table.readySeats.includes(p.seat)) return 'applied';
```

`PASSING_SUBMITTED` — 옛 코드:

```ts
        if (table.passingSubmittedSeats.includes(p.seat)) {
          advance(table);
          return 'applied';
        }
```

새 코드:

```ts
        if (table.passingSubmittedSeats.includes(p.seat)) return 'applied';
```

`client/src/features/tichu/tichuRoomSink.ts`:

```diff
       privateHand: snap.privateHand ?? ({ seat: -1, cards: [] } as PrivateHand),
-      eventSeq: snap.eventSeq,
       disconnectedSeats: snap.disconnectedSeats,
```

- [ ] **Step 4: 통과 확인**

Run: `grep -n "lastSeq\|seq ??" client/src/features/tichu/tichuStore.ts`
Expected: 출력 없음.

Run: `npm --prefix client run build:check && npm --prefix client run test`
Expected: 타입 오류 없음, `Tests  423 passed (423)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/tichu
git commit -m "refactor(D-124): 티츄 스토어를 순번 판정 없는 순수 리듀서로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 스컬킹 스토어를 순수 리듀서로

**Files:**
- Modify: `client/src/features/skullking/skullkingStore.ts`
- Modify: `client/src/features/skullking/skullkingStore.test.ts`

**Interfaces:**
- Consumes: Task 2 의 훅 계약.
- Produces: `useSkullKingStore` 에 `lastSeq` 가 없다. `applyEvent` 는 `applied`·`unhandled`·`ignored` 만
  돌려준다. `BIDDING_STARTED` 는 매치 종료 뒤가 아니면 언제나 라운드 스크럽을 하고 `applied`.

- [ ] **Step 1: 테스트를 새 계약으로 바꾼다** — `skullkingStore.test.ts`

순번 계약 블록 — 옛 코드:

```ts
describe('applyEvent — seq 4값 계약', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('중복 seq 는 duplicate', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 10))).toBe('duplicate');
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 5))).toBe('duplicate');
  });

  it('연속 seq 는 applied 이고 lastSeq 를 전진시킨다', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 11))).toBe('applied');
    expect(store().lastSeq).toBe(11);
  });

  it('구멍 난 seq 는 gap (상태 무변경)', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 13))).toBe('gap');
    expect(store().seats[0].hasBid).toBe(false);
    expect(store().lastSeq).toBe(10);
  });
```

새 코드(뒤따르는 `리듀서 없는 타입은 unhandled …` 테스트는 그대로 둔다):

```ts
describe('applyEvent — 반환값 계약 (순번 판정은 훅, D-124)', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('리듀서가 있는 타입은 applied', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 11))).toBe('applied');
  });

  it('순번을 판정하지 않는다 — 중복·구멍 판정은 훅 몫', () => {
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 0 }, 99))).toBe('applied');
    expect(store().applyEvent(ev('BID_SUBMITTED', { seat: 1 }, 3))).toBe('applied');
    expect(store().seats.filter((s) => s.hasBid).map((s) => s.seat)).toEqual([0, 1]);
  });
```

`BIDDING_STARTED` 블록 — 제목과 단언 정리:

```diff
-describe('BIDDING_STARTED — 판정과 무관한 라운드 스크럽 (D-103)', () => {
+describe('BIDDING_STARTED — 라운드 스크럽 (D-103)', () => {
```

```diff
     expect(store().settledTrick).toBeNull();
-    expect(store().lastSeq).toBe(10); // gap 이므로 전진하지 않는다 (resync 가 권위)
   });
```

```diff
-    store().applyEvent(ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 99));
+    store().applyEvent(ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 11));
```

첫 테스트 머리 — 옛 코드:

```ts
  it('gap 이어도 지난 라운드 예측값·승수·트릭을 즉시 비운다', () => {
    // seq 를 크게 띄워 gap 을 만든다 — 실제로 라운드 경계에서 거의 항상 이렇게 온다
    // (비공개 HAND_DEALT 가 좌석 수만큼 seq 를 태우므로).
    const verdict = store().applyEvent(
      ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 99),
    );

    expect(verdict).toBe('gap'); // 훅이 resync 를 부른다
```

새 코드:

```ts
  it('지난 라운드 예측값·승수·트릭을 즉시 비운다', () => {
    const verdict = store().applyEvent(
      ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 11),
    );

    expect(verdict).toBe('applied');
```

테스트 두 개를 통째로 지운다(중복은 훅이 sink 앞에서 버린다 — Task 2):

```ts
  it('연속 seq 면 applied 이고 lastSeq 도 전진한다', () => {
    const verdict = store().applyEvent(
      ev('BIDDING_STARTED', { roundNumber: 4, handSize: 4 }, 11),
    );

    expect(verdict).toBe('applied');
    expect(store().lastSeq).toBe(11);
    expect(store().roundNumber).toBe(4);
  });

  /** 지난 이벤트의 재생이 진행 중인 라운드를 지우면 안 된다. */
  it('duplicate 면 스크럽하지 않는다', () => {
    const verdict = store().applyEvent(
      ev('BIDDING_STARTED', { roundNumber: 1, handSize: 1 }, 4),
    );

    expect(verdict).toBe('duplicate');
    expect(store().roundNumber).toBe(3); // 그대로
    expect(store().seats[0].bid).toBe(2); // 그대로
  });
```

나머지 순번 단언 정리:

```diff
-  it('끊김/재접속이 Set 을 토글하고 lastSeq 판정에 영향이 없다', () => {
+  it('끊김/재접속이 Set 을 토글한다', () => {
     expect(store().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 1 }))).toBe('applied');
     expect([...store().disconnectedSeats]).toEqual([1]);
-    expect(store().lastSeq).toBe(10);
 
     expect(store().applyEvent(ev('PLAYER_RECONNECTED', { seat: 1 }))).toBe('applied');
     expect([...store().disconnectedSeats]).toEqual([]);
-    expect(store().lastSeq).toBe(10);
   });
```

```diff
-  it('공개+비공개 뷰를 함께 반영하고 lastSeq 를 권위값으로 재설정한다', () => {
+  it('공개+비공개 뷰를 함께 반영한다', () => {
     store().applySnapshot(snapshot({ eventSeq: 77 }));
 
     const s = store();
-    expect(s.lastSeq).toBe(77);
```

```diff
     expect(store().roomId).toBe('r-2');
-    expect(store().lastSeq).toBe(0);
```

```diff
-    // resync 로 lastSeq 가 되감긴 뒤 같은 라운드가 다시 와도 한 건만 남는다.
+    // resync 로 순번 기준점이 되감긴 뒤 같은 라운드가 다시 와도 한 건만 남는다.
```

```diff
-    expect(store().lastSeq).toBe(30);
     expect(store().matchEnded).toEqual(RESULT);
```

매치 종료 뒤 잔여 이벤트 — 옛 코드:

```ts
  it('잔여 이벤트는 resync 를 부르지 않는다 — gap 이어도 ignored', () => {
    expect(store().applyEvent(ev('CARD_PLAYED', {}, 99))).toBe('ignored');
    expect(store().applyEvent(ev('WHO_KNOWS', {}, 12))).toBe('ignored');
    expect(store().lastSeq).toBe(11);
  });
```

새 코드:

```ts
  it('잔여 이벤트는 ignored — resync 를 부르지 않는다', () => {
    expect(store().applyEvent(ev('CARD_PLAYED', {}, 12))).toBe('ignored');
    expect(store().applyEvent(ev('WHO_KNOWS', {}, 13))).toBe('ignored');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- skullkingStore`
Expected: FAIL 1건 — `순번을 판정하지 않는다 — 중복·구멍 판정은 훅 몫` (옛 스토어는 seq 99 를 구멍으로 판정).

- [ ] **Step 3: 스토어 구현** — `client/src/features/skullking/skullkingStore.ts`

```diff
   roomId: string | null;
-  lastSeq: number;
 
   // ── 공개 상태 (tableView 미러) ──
```

```diff
   roomId: null,
-  lastSeq: 0,
```

```diff
         completedRounds: t.completedRounds ?? [],
-        lastSeq: snap.eventSeq,
```

```diff
-      const { type, seq, payload } = envelope;
+      const { type, payload } = envelope;
```

```diff
       // ('ignored') — 권위값은 applySnapshot 으로만 들어오고, 그쪽은 이 가드 밖이다.
-      // 연결 상태 배지는 게임 진행이 아니라 그대로 반영한다.
+      // 구멍 난 잔여 이벤트는 sink 앞에서 훅이 resync 로 돌린다(D-124) — 스냅샷이 종료 상태를
+      // 그대로 주므로 무해하다. 연결 상태 배지는 게임 진행이 아니라 그대로 반영한다.
```

```diff
-      // seq 없는 메타 이벤트는 lastSeq 판정 밖에서 처리한다 (연결 상태 배지).
+      // 순번 없는 메타 이벤트 (연결 상태 배지).
```

```diff
-          set({ seats: patchSeat(state.seats, seat, { hasBid: true }), ...advance });
+          set({ seats: patchSeat(state.seats, seat, { hasBid: true }) });
```

```diff
-          set({ currentTurnSeat, turnStartedAt: Date.now(), ...advance });
+          set({ currentTurnSeat, turnStartedAt: Date.now() });
```

아래 줄을 일곱 곳(`BIDS_REVEALED`·`PLAYING_STARTED`·`CARD_PLAYED`·`TRICK_TAKEN`·`ROUND_ENDED`·
`SEAT_DESERTED`·`MATCH_ENDED`)에서 모두 지운다:

```diff
-            ...advance,
```

```diff
-            // 받아도(resync 로 lastSeq 가 되감긴 경우) 한 건만 남도록 upsert 한다.
+            // 받아도(resync 로 순번 기준점이 되감긴 경우) 한 건만 남도록 upsert 한다.
```

판정·`BIDDING_STARTED` 머리 — 옛 코드:

```ts
      let verdict: ApplyEventResult = 'applied';
      if (seq !== undefined) {
        if (seq <= state.lastSeq) verdict = 'duplicate';
        else if (seq > state.lastSeq + 1) verdict = 'gap';
      }

      // 라운드 시작은 **seq 판정과 무관하게** 라운드 로컬 상태를 즉시 비운다 (D-103).
      // 스컬킹은 라운드마다 재분배하므로 비공개 HAND_DEALT 가 좌석 수만큼 seq 를 태우고
      // (본인 큐 핸들러는 lastSeq 를 전진시키지 않는다) 뒤따르는 BIDDING_STARTED 는 거의
      // 항상 gap 이다. resync 도착 전까지 지난 라운드 예측값·트릭이 남으면 혼동이므로
      // 화면만 먼저 비우고 판정값은 그대로 돌려준다(훅이 resync 를 부른다).
      //
      // duplicate 는 예외 — 이미 지난 이벤트의 재생이 진행 중인 라운드를 지워선 안 된다.
      if (type === 'BIDDING_STARTED' && verdict !== 'duplicate') {
```

새 코드:

```ts
      // 새 라운드 — 지난 라운드의 예측값·트릭·손패를 비운다 (D-103). 순번 판정은 훅이 이미
      // 끝냈다(D-124): 중복은 여기까지 오지 않고(진행 중인 라운드를 지우지 않는다), 구멍이면
      // resync 가 권위 상태를 준다. 서버는 BIDDING_STARTED 를 HAND_DEALT 보다 먼저 보내므로
      // 라운드 경계에서 이 이벤트 자체는 구멍이 아니다 — 비공개 HAND_DEALT 가 태운 순번은 다음
      // 공개 이벤트(BID_SUBMITTED)에서 구멍이 되어 라운드마다 resync 한 번을 부른다.
      if (type === 'BIDDING_STARTED') {
```

`BIDDING_STARTED` 꼬리 — 옛 코드:

```ts
            handCount: p.handSize,
          })),
          ...(verdict === 'applied' && seq !== undefined ? { lastSeq: seq } : {}),
        });
        return verdict;
      }
```

새 코드:

```ts
            handCount: p.handSize,
          })),
        });
        return 'applied';
      }
```

switch 앞의 두 줄과 빈 줄을 지운다:

```ts
      if (verdict !== 'applied') return verdict;
      const advance = seq !== undefined ? { lastSeq: seq } : {};

```

- [ ] **Step 4: 통과 확인**

Run: `grep -n "lastSeq\|verdict\|advance" client/src/features/skullking/skullkingStore.ts`
Expected: 출력 없음.

Run: `npm --prefix client run build:check && npm --prefix client run test`
Expected: 타입 오류 없음, `Tests  420 passed (420)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/features/skullking/skullkingStore.ts client/src/features/skullking/skullkingStore.test.ts
git commit -m "refactor(D-124): 스컬킹 스토어를 순번 판정 없는 순수 리듀서로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: sink 계약 좁히기 + 문서

**Files:**
- Modify: `client/src/types/stomp.ts`
- Modify: `client/src/ws/roomEventSink.ts`
- Modify: `client/src/ws/useStompRoom.ts`
- Modify: `docs/stomp-protocol.md`, `CLAUDE.md`, `docs/decisions.md`

**Interfaces:**
- Produces: `export type ApplyEventResult = 'applied' | 'unhandled' | 'ignored'` — 원카드 sink(S4)가 이
  계약을 따른다.

- [ ] **Step 1: 타입을 좁힌다** — `client/src/types/stomp.ts` 의 `ApplyEventResult` 주석과 정의를 바꾼다.

옛 코드:

```ts
/**
 * 부분 패치 결과 (D-103). `tichuStore` 의 동명 타입은 이 유니온의 **부분집합**이라 구조적으로
 * 호환된다 — 티츄 스토어를 0줄도 고치지 않고 sink 계약을 만족시키기 위해 여기 새로 선언한다.
 *
 * - `applied`   — 패치 성공, lastSeq 갱신
 * - `duplicate` — 이미 처리한 이벤트
 * - `gap`       — seq 구멍, /resync 권유
 * - `unhandled` — 리듀서 없는 타입, /resync 권유
 * - `ignored`   — 반영하지 않기로 한 이벤트(예: 스컬킹 매치 종료 뒤의 잔여 이벤트, D-122).
 *                 resync 도 부르지 않는다 — 다시 받아 봐야 또 버릴 것이라서다.
 */
export type ApplyEventResult = 'applied' | 'duplicate' | 'gap' | 'unhandled' | 'ignored';
```

새 코드:

```ts
/**
 * 게임 sink(스토어)의 반영 결과 (D-103 → D-124). 순번 판정(중복·구멍)은 훅이 sink 앞에서
 * 끝낸다 — `ws/seqGate.ts` 의 `judgeSeq`. 그래서 여기에는 순번 관련 값이 없다.
 *
 * - `applied`   — 상태에 반영함. 순번 있는 이벤트면 훅이 기준점을 전진시킨다
 * - `unhandled` — 리듀서 없는 타입(라이프사이클 등) — 훅이 /resync 로 권위 스냅샷을 받는다
 * - `ignored`   — 반영하지 않기로 한 이벤트(예: 스컬킹 매치 종료 뒤의 잔여 이벤트, D-122).
 *                 resync 도 부르지 않는다 — 다시 받아 봐야 또 버릴 것이라서다. 기준점은 전진한다
 */
export type ApplyEventResult = 'applied' | 'unhandled' | 'ignored';
```

- [ ] **Step 2: 타입 검사로 호환 코드가 죽은 코드임을 확인**

Run: `npm --prefix client run build:check`
Expected: FAIL — `useStompRoom.ts` 의 `result === 'gap'`·`result === 'duplicate'` 비교에서
`This comparison appears to be unintentional because the types … have no overlap` (TS2367) 오류 2건.

- [ ] **Step 3: 호환 코드 제거와 주석 정리** — `client/src/ws/useStompRoom.ts`

옛 코드:

```ts
          const result = sinkRef.current.applyEvent(env);
          if (result === 'unhandled' || result === 'gap') {
            // 리듀서 없는 라이프사이클 이벤트 — 권위 스냅샷 재취득. 기준점은 그대로 둔다.
            // ('gap' 은 스토어가 아직 자체 판정을 들고 있는 동안의 호환 — D-124 Task 5 에서 제거.)
            resync();
            return;
          }
          if (result === 'duplicate') return; // 위와 같은 호환.
```

새 코드:

```ts
          const result = sinkRef.current.applyEvent(env);
          if (result === 'unhandled') {
            // 리듀서 없는 라이프사이클 이벤트 — 권위 스냅샷 재취득. 기준점은 그대로 둔다.
            resync();
            return;
          }
```

훅 머리 주석:

```diff
  * 스냅샷 재취득. 초기 mount 와 STOMP onConnect 는 항상 /resync (재접속 안전망).
+ *
+ * <p><b>D-124: 순번 판정(중복·구멍)은 이 훅만 한다.</b> 기준점은 resync 의 `eventSeq` 이고
+ * 판정은 `judgeSeq`(./seqGate) — 게임 스토어는 판정이 끝난 이벤트만 받는 순수 리듀서다.
```

onConnect 주석:

```diff
-        // 연결/재연결 직후 권위 있는 스냅샷으로 lastSeq 동기화.
+        // 연결/재연결 직후 권위 있는 스냅샷으로 순번 기준점 동기화.
```

- [ ] **Step 4: sink 계약 문서** — `client/src/ws/roomEventSink.ts`

```diff
-  /** REST `/resync` 응답을 권위 스냅샷으로 반영 (lastSeq 재설정 포함). */
+  /** REST `/resync` 응답을 권위 스냅샷으로 반영. 순번 기준점(`eventSeq`)은 훅이 가진다(D-124). */
```

`applyEvent` 주석 — 옛 코드:

```ts
  /**
   * 공개 토픽 이벤트의 부분 패치. 반환값이 `'gap'`/`'unhandled'` 면 훅이 `/resync` 를 부른다.
   *
   * <p>시그니처는 티츄 스토어의 `applyEvent` 와 글자 그대로 같다 — 어댑터 없이 만족하도록.
   */
```

새 코드:

```ts
  /**
   * 공개 토픽 이벤트의 부분 패치 — **순번 판정이 끝난 이벤트만 온다(D-124).** 훅이 중복은 버리고
   * 구멍은 `/resync` 로 돌린 뒤, 바로 다음 순번이거나 순번 없는 메타 이벤트만 넘긴다. 그래서
   * 구현은 `lastSeq` 를 갖지 않는 순수 리듀서다. 반환값이 `'unhandled'` 면 훅이 `/resync` 를 부른다.
   *
   * <p>시그니처는 티츄 스토어의 `applyEvent` 와 글자 그대로 같다 — 어댑터 없이 만족하도록.
   */
```

- [ ] **Step 5: 문서**

`docs/stomp-protocol.md` — 옛 코드:

```markdown
  는 `seq: null`. 클라는 `seq <= localSeq` 인 이벤트를 무시(idempotent).
```

새 코드:

```markdown
  는 `seq: null`. 클라(`useStompRoom`, D-124)는 `seq <= lastSeq` 인 공개 이벤트를 버린다
  (idempotent). 본인 큐 이벤트의 seq 는 판정에 쓰지 않으므로, 남의 비공개 이벤트가 소비한
  번호는 다음 공개 이벤트에서 구멍(gap)으로 보여 resync 를 부른다.
```

같은 파일의 Phase 5d 문단:

```diff
   초기 mount 및 STOMP onConnect 직후 `/resync` 는 유지.
+  **순번 판정은 훅만 한다(D-124)** — 기준점은 resync 의 `eventSeq`, 판정은
+  `client/src/ws/seqGate.ts`. 게임 스토어는 판정이 끝난 이벤트만 받아 `applied`/`unhandled`/
+  `ignored` 만 돌려준다.
```

`CLAUDE.md` — "클라 인게임도 게임 중립 (D-103)" 항목의 마지막 줄:

```diff
-  게임판이 자기 소켓·sink 를 소유해 다른 게임의 코드 경로는 실행되지 않는다.
+  게임판이 자기 소켓·sink 를 소유해 다른 게임의 코드 경로는 실행되지 않는다. 공개 이벤트의 순번
+  판정(중복·구멍)은 훅만 한다(D-124) — 게임 스토어는 `lastSeq` 없는 순수 리듀서다.
```

`docs/decisions.md` — D-103 항목의 마지막 문단 뒤:

```diff
 추출이 실제 게임에서 동작함을 확인했다. 클라 239건(기존 144 무변경) 그린.
+
+*보정 → D-124*: 위 **부채 명기**(seq 판정 중복)를 갚았다 — 판정은 훅으로, 두 스토어는 순수 리듀서로.
```

- [ ] **Step 6: 전체 확인**

Run: `npm --prefix client run build:check && npm --prefix client run test`
Expected: 타입 오류 없음, `Tests  420 passed (420)`.

Run: `grep -rn "lastSeq" client/src | grep -v "lastSeqRef\|seqGate"`
Expected: `client/src/ws/roomEventSink.ts` 의 문서 한 줄(`` 구현은 `lastSeq` 를 갖지 않는 … ``)만 나온다.

- [ ] **Step 7: 커밋**

```bash
git add client/src/types/stomp.ts client/src/ws/roomEventSink.ts client/src/ws/useStompRoom.ts \
  docs/stomp-protocol.md CLAUDE.md docs/decisions.md
git commit -m "refactor(D-124): sink 반환값을 applied·unhandled·ignored 로 좁히고 문서 정리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## S1 — 원카드 룰 명세 (D-125)

### Task 6: `docs/rules-onecard.md` + D-125

**Files:**
- Create: `docs/rules-onecard.md`
- Modify: `docs/decisions.md` (`## D-124` 바로 위)
- Modify: `docs/plans/onecard.md` (§3.2 제목)

**Interfaces:**
- Produces: 룰 정본. S2(순수 엔진)의 테스트는 이 문서의 절 번호(§5.2, §6.2, §8.2, §9, §11)를 기준으로
  쓴다.

- [ ] **Step 1: D-125 결정 기록** — `docs/decisions.md` 의 `## D-124` 줄 바로 위에:

```markdown
## D-125 (2026-10-04) — 원카드 룰 명세 (원카드 S1, 코드 변경 0)

`docs/rules-onecard.md` 를 원카드 룰의 정본으로 둔다. D-123 의 기준선(표준 기본형)에 설계서 §3.2 기본안을
확정하고, 그때 답하지 않은 세부를 정했다 — 첫 차례는 서버 무작위, 시작 카드가 일반 카드가 아니면 뽑을 더미
맨 아래로 넣고 다시 뒤집기, 7 위의 7 허용(같은 숫자), 탈주자 차례에 걸린 공격은 사라짐, 순위는 동순위 다음을
건너뛰는 경쟁 순위(1·1·3). 원카드는 하우스 룰 편차가 커서 우리가 정한 항목을 본문에 **[결정]** 으로 표시하고
§12 에 모았다.
```

- [ ] **Step 2: 룰 명세 작성** — `docs/rules-onecard.md`

````markdown
# 원카드 룰 명세 (One Card Rules)

본 문서는 Mirboard 원카드 룰의 **단일 진실 공급원**이다. 코드(S2 순수 엔진)는 이 문서를 따르고, 어긋나면
코드를 고치거나 이 문서와 결정(`docs/decisions.md`)을 함께 고친다. 코드 위치·테스트 매핑은 S2 에서 각 절에
`rules-skullking.md` 와 같은 표기(**코드:** / **테스트:** / **갭:**)로 붙인다.

> **출처와 하우스 룰.** 원카드는 모임마다 규칙이 크게 다르다. 이 문서는 D-123 에서 고른 **표준 기본형**만
> 다룬다. 원문이 답하지 않거나 갈리는 항목을 우리가 정한 것은 본문에 **[결정]** 으로 표시하고 §12 에 모았다.
> 설계 배경은 `docs/plans/onecard.md`.

---

## 1. 카드 (54장)

| 구분 | 장수 | 코드 표기 |
| --- | --- | --- |
| 무늬 카드 | 52 | 4무늬(♠ `SPADE` · ♥ `HEART` · ♦ `DIAMOND` · ♣ `CLUB`) × 13숫자 |
| 흑백 조커 | 1 | `BLACK_JOKER` |
| 컬러 조커 | 1 | `COLOR_JOKER` |

숫자는 A(1) · 2~10 · J(11) · Q(12) · K(13). 조커에는 무늬·숫자가 없다.

| 카드 | 분류 | 효과 |
| --- | --- | --- |
| 2 | 공격 | +2 |
| A | 공격 | +3 |
| 흑백 조커 | 공격 | +5 |
| 컬러 조커 | 공격 | +7 |
| J | 특수 | 다음 사람 건너뛰기 |
| Q | 특수 | 진행 방향 반전 |
| K | 특수 | 한 번 더 |
| 7 | 특수 | 무늬 지정 |
| 3·4·5·6·8·9·10 | 일반 | 없음 |

## 2. 인원과 좌석

- 2~6명. 좌석 번호 0..n−1 은 방 참가자 순서다.
- 진행 방향의 초기값은 좌석 번호가 커지는 쪽(+1)이다.
- **[결정] 첫 차례는 서버가 무작위로 고른다.** 방장(좌석 0)에 고정하면 선 이점이 한 사람에게 쏠린다.
- **살아 있는 플레이어** = 탈락(§10)하지 않은 플레이어. 차례 계산과 "다음 사람"은 살아 있는 플레이어만 센다.

## 3. 분배와 시작

1. 54장을 섞어 각자 7장씩 나눈다.
2. 남은 카드가 뽑을 더미가 된다.
3. 뽑을 더미 맨 위 카드를 뒤집어 버린 더미의 첫 카드로 둔다.
4. **[결정] 첫 카드가 일반 카드(3·4·5·6·8·9·10)가 아니면** 그 카드를 뽑을 더미 맨 아래에 넣고 3 을
   되풀이한다. 일반 카드가 28장이라 반드시 끝난다. 첫 차례에 공격·특수 효과를 떠안는 불공정을 없앤다.

6인이면 42장을 나누고 시작 카드 1장을 뒤집어 뽑을 더미에 11장이 남는다.

## 4. 차례에 할 수 있는 것

자기 차례에는 **카드 1장 내기**(§5) 또는 **먹기**(§7) 중 하나를 한다.

- **[결정] 낼 수 있어도 먹을 수 있다**(전략적 먹기).
- 턴 제한이 있는 방에서 시간을 넘기면 먹기를 한 것으로 본다.

## 5. 내기

### 5.1 기준

- **맨 위 카드** = 버린 더미의 맨 위.
- **기준 무늬** = 7 로 지정된 무늬가 있으면 그 무늬, 없으면 맨 위 카드의 무늬.

### 5.2 공격받는 중이 아닐 때

다음 중 하나면 낼 수 있다.

1. 기준 무늬와 같은 무늬.
2. 맨 위 카드와 같은 숫자. **[결정] 7 위의 7 도 포함한다** — 무늬가 지정된 뒤에도 숫자 일치 규칙은 그대로다.
3. 조커(언제나).
4. **[결정] 맨 위가 조커이고 그 공격이 먹기로 끝난 상태면 아무 카드나.** 조커에는 무늬가 없다.

### 5.3 공격받는 중일 때

반격 조건(§6.2)을 지키는 공격 카드만 낼 수 있다. 일반 카드와 7·J·Q·K 는 낼 수 없다.

### 5.4 내고 나면 (순서대로)

1. 카드 효과를 적용한다 — 공격은 §6, 특수는 §8.
2. 손패가 0장이면 즉시 매치를 끝낸다(§11). 마지막 카드가 특수·공격 카드여도 인정한다 **[결정]**.
3. 손패가 정확히 1장이면 외치기 경쟁(§9)이 먼저 열린다.
4. 다음 차례를 정한다(§8.2).

## 6. 공격과 반격

### 6.1 공격

공격 카드를 내면 **공격 누적**에 그 값을 더하고, 다음 사람이 "공격받는 중"으로 차례를 받는다.

### 6.2 반격

공격받는 사람은 공격 카드로 반격해 누적을 다음 사람에게 넘길 수 있다.

- **세기**: 2 < A < 흑백 조커 < 컬러 조커. 맨 위 공격 카드와 **같거나 센** 카드만 낼 수 있다.
- **일치**: 같은 숫자는 무늬와 무관하다. 다른 숫자(2 위의 A)는 기준 무늬가 같아야 한다. 조커는 무늬와 무관하다.

| 맨 위 공격 | 반격할 수 있는 카드 |
| --- | --- |
| 2 | 모든 2 · 기준 무늬의 A · 조커 2장 |
| A | 모든 A · 조커 2장 |
| 흑백 조커 | 컬러 조커 |
| 컬러 조커 | 없음 |

### 6.3 받기

반격하지 않으면(못 하거나 안 하거나) 누적 장수만큼 먹는다(§7). 누적은 0 이 되고 공격이 끝나며, 먹은 사람의
차례도 끝난다.

## 7. 먹기

- 공격받는 중이 아니면 1장, 공격받는 중이면 누적 장수만큼 먹는다.
- **[결정] 먹으면 차례가 끝난다** — 먹은 카드는 이번 차례에 낼 수 없다.
- 뽑을 더미가 모자라면 §7.1 로 채운다. 그래도 모자라면 있는 만큼만 먹는다(0장일 수 있다) **[결정]**.
- 먹은 뒤 손패가 20장 이상이면 파산이다(§10).

### 7.1 뽑을 더미 다시 채우기

뽑을 더미가 비었는데 더 먹어야 하면 버린 더미의 맨 위 1장만 남기고 나머지를 섞어 뽑을 더미로 만든다. 맨 위
카드와 그 상태(지정 무늬·공격 누적)는 그대로다.

## 8. 특수 카드와 차례 순서

### 8.1 효과 (공격받는 중이 아닐 때만 낼 수 있다)

- **J** — 다음 사람을 건너뛴다.
- **Q** — 진행 방향을 뒤집는다.
- **K** — 같은 사람이 한 번 더 한다(내기 또는 먹기). K 를 이어 내면 계속 이어진다.
- **7** — 낼 때 무늬 하나를 지정한다(7 자신의 무늬도 가능). 다음 카드가 놓일 때까지 기준 무늬가 된다.
- **[결정] 살아 있는 플레이어가 2명이면 J·Q 도 "한 번 더"로 동작한다.** 둘 다 결과적으로 같은 사람에게
  차례가 돌아온다.

### 8.2 다음 차례

| 방금 낸 카드 | 다음 차례 |
| --- | --- |
| 일반·7 | 현재 방향의 다음 살아 있는 플레이어 |
| 공격 카드 | 다음 살아 있는 플레이어 — "공격받는 중"으로 받는다 |
| J | 다음 살아 있는 플레이어를 건너뛴 그다음 |
| Q | 방향을 뒤집은 뒤 그 방향의 다음 살아 있는 플레이어 |
| K (2인이면 J·Q 도) | 같은 사람 |
| (먹기) | 현재 방향의 다음 살아 있는 플레이어 |

## 9. 외치기 경쟁 ("원카드!" / "잡기!")

1. 카드를 낸 결과 손패가 **정확히 1장**이면 경쟁 창이 열린다. 어떤 카드를 냈든(공격·특수·K 포함) 같다.
2. 창은 **3초**다. 창이 열린 동안에는 아무도 내거나 먹을 수 없다. 턴 제한 시계는 창이 닫힌 뒤 다시 시작한다.
3. 1장 남은 사람(**주인**)은 "원카드!", 살아 있는 다른 플레이어는 "잡기!"를 누를 수 있다. 관전자와
   탈락자는 누를 수 없다.
4. **첫 누름이 창을 닫는다.** "먼저"는 서버에 먼저 도착해 처리된 요청이다.

   | 결과 | 조건 | 효과 |
   | --- | --- | --- |
   | `CALLED` | 주인이 먼저 | 없음 |
   | `CAUGHT` | 다른 사람이 먼저 | 주인이 1장 먹는다(§7 적용) |
   | `EXPIRED` | 3초 동안 아무도 안 누름 | 없음 |

5. 창마다 번호가 있고, 지금 창과 번호가 다른 누름은 거절한다(늦게 도착한 누름이 다음 창에 붙지 않도록).
6. 봇도 사람처럼 반응 시간을 두고 누른다(창마다 추첨, 기본 1.0~2.5초).
7. 창이 열린 동안 탈주가 생기면 창은 벌칙 없이 닫힌다.
8. 창이 닫히면 §5.4 의 다음 차례 결정을 이어 간다(K 면 주인이 다시, 공격이면 다음 사람이 공격받는 중으로).

## 10. 파산과 탈락

- **파산**: 먹은 뒤 손패가 20장 이상이면 탈락한다. 손패는 섞지 않고 뽑을 더미 맨 아래에 넣는다. 벌칙(§9)으로는
  파산할 수 없다 — 1장에서 2장이 될 뿐이다.
- **탈주**: 게임 중 나가기와 끊김 유예 초과는 파산과 같이 탈락으로 처리한다. 손패는 뽑을 더미 맨 아래로 간다.
- 탈락자는 차례에서 빠진다. 탈락자의 차례였다면 다음 살아 있는 플레이어에게 넘어간다.
- **[결정] 탈주자의 차례에 걸려 있던 공격은 사라진다**(누적 0). 다른 사람의 탈주로 다음 사람이 공격을 떠안지
  않게 한다. 공격 누적을 먹고 파산했다면 그 공격은 이미 끝났다.

## 11. 종료와 순위

### 11.1 종료 조건 (즉시, 위에서부터 판정)

| 사유 | 조건 |
| --- | --- |
| `FINISHED` | 누군가 마지막 카드를 냈다 |
| `LAST_STANDING` | 탈락으로 살아 있는 플레이어가 1명 |
| `NO_HUMANS` | 살아 있는 플레이어 중 사람(봇이 아닌)이 없다 |
| `STALEMATE` | 교착 또는 차례 상한(§11.3) |

### 11.2 순위

1. `FINISHED` 면 다 낸 사람이 1등이고, 나머지 살아 있는 플레이어는 남은 장수가 적은 순이다.
2. 그 밖의 사유면 살아 있는 플레이어 전원을 남은 장수가 적은 순으로 매긴다.
3. 그 아래 파산자 — 늦게 파산한 쪽이 위다.
4. 맨 아래 탈주자 — 탈주자끼리는 동순위다.

- 장수가 같으면 동순위다. **[결정] 동순위 다음 순위는 건너뛴다**(1, 1, 3).
- **승리는 1등이다**(동순위면 모두).

### 11.3 교착과 차례 상한

- **패스** = 먹기를 했는데 더미가 비어 실제로 먹은 장수가 0 인 차례.
- 살아 있는 전원이 연달아 패스하면 교착이다 → `STALEMATE`.
- 누군가 카드를 내거나 1장 이상 먹으면 연속 패스 수는 0 으로 돌아간다.
- **[결정] 총 차례 상한 600**: 내기·먹기 1회를 1차례로 센다(경쟁 창의 누름은 차례가 아니다). 600 에 닿으면
  `STALEMATE`. 실제 판은 수십~백여 차례라 안전장치다.

## 12. 우리가 정한 것 (미규정·하우스 룰 선택)

| # | 항목 | 결정 | 이유 |
| --- | --- | --- | --- |
| 1 | 첫 차례 | 서버 무작위 | 방장 선 고정 방지 |
| 2 | 시작 카드 | 일반 카드가 나올 때까지 다시 뒤집기(더미 맨 아래로) | 첫 차례 불공정 제거 |
| 3 | 전략적 먹기 | 허용 | 손패 관리 선택지 |
| 4 | 먹은 직후 내기 | 불가(차례 종료) | 단순하고 흔한 규칙 |
| 5 | 7 위의 7 | 허용(같은 숫자) | 숫자 일치 규칙 일관성 |
| 6 | 조커 뒤 | 공격이 끝나면 아무 카드나 | 조커에는 무늬가 없다 |
| 7 | 공격 세기·반격 | 2<A<흑백<컬러, 같거나 센 카드, 다른 숫자는 무늬 일치 | §6.2 표 |
| 8 | 공격 중 특수 카드 | 불가 | 반격 수단이 아니다 |
| 9 | 2인 J·Q | "한 번 더" | 결과적으로 같은 사람 차례 |
| 10 | K 로 1장 | 경쟁 먼저, 해소 뒤 같은 사람 계속 | §9-8 |
| 11 | 특수·공격 카드로 끝내기 | 허용 | 단순 |
| 12 | 누적이 더미보다 많음 | 있는 만큼만 | §7 |
| 13 | 탈주자 차례의 공격 | 사라짐 | 남의 탈주 피해 방지 |
| 14 | 동순위 | 경쟁 순위(1, 1, 3) | 흔한 표기 |
| 15 | 차례 상한 | 600 → `STALEMATE` | 무한 진행 방지 |
| 16 | 경쟁 창 | 3초, 첫 누름, 벌칙 1장 | D-123 |

## 13. 범위 밖 (v1)

♠A 특례, 3 방어, 폭탄, 계단(연속 내기), 같은 숫자 동시 내기, 마지막 카드 제한, 리매치, 내기 칩, 방 옵션으로
하는 하우스 룰 선택.

## 14. 검증 기준 (S2)

- **카드 보존**: 매 전이 뒤 뽑을 더미 + 버린 더미 + 살아 있는 플레이어 손패 = 54장(탈락자 손패는 더미로
  옮겨졌으므로 0).
- 공격 누적 ≥ 0 이고, 공격받는 중이 아니면 누적 = 0.
- 2~6인 무작위 봇 시뮬레이션 인원별 2,000판이 전부 끝난다(차례 상한 도달 빈도를 기록한다).
- §6.2 표의 모든 칸, §8.2 표의 모든 행(2인 포함), §9 의 세 결과, §11.1 의 네 사유마다 단위 테스트가 있다.
````

- [ ] **Step 3: 설계서 §3.2 를 확정 표시로**

`docs/plans/onecard.md`:

```diff
-### 3.2 S1 에서 확정할 세부 (기본안)
+### 3.2 세부 (S1 에서 확정 — 정본 `docs/rules-onecard.md`, D-125)
```

- [ ] **Step 4: 문서 점검**

Run: `grep -c "\[결정\]" docs/rules-onecard.md`
Expected: 15 이상(본문 표시 수). §12 표의 16개 항목이 본문의 [결정] 표시 또는 D-123 기준선과 1:1 로 맞는지
눈으로 대조한다(16번은 D-123 에서 정한 것이라 본문에 [결정] 이 없다).

Run: `grep -n "TBD\|TODO" docs/rules-onecard.md`
Expected: 출력 없음.

- [ ] **Step 5: 커밋**

```bash
git add docs/rules-onecard.md docs/decisions.md docs/plans/onecard.md
git commit -m "docs(D-125): 원카드 룰 명세 rules-onecard.md

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 단계 마무리 (Phase Gate)

- [ ] 전체 검증: `./scripts/check.sh fast` 통과(클라 tsc + vitest 420건 + 서버 compile).
- [ ] 브라우저 스모크(선택이지만 권장): 로컬에서 티츄·스컬킹 방을 하나씩 열어 몇 차례 진행 — 차례 표시·손패가
  평소처럼 갱신되는지 본다(순번 판정이 옮겨졌을 뿐 화면 동작은 같아야 한다).
- [ ] 사용자에게 S0·S1 변경 요약을 보고하고 승인을 받는다. 승인되면 `docs/onecard-plan` 브랜치를 main 에
  병합한다(병합·푸시는 사용자 승인 뒤).
- [ ] 다음: S2(순수 엔진) 계획 `docs/plans/onecard-s2-tasks.md` 를 `docs/rules-onecard.md` 기준으로 작성한다.
