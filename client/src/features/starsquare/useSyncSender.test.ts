import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BUSY_RETRY_MS, useStarSquareStore } from './starSquareStore';
import { LO_SHU, seedStore, view } from './starSquareTestFixtures';
import { SOLVE_CONFIRM_MS, SYNC_THROTTLE_MS, useSyncSender } from './useSyncSender';

const st = () => useStarSquareStore.getState();

/** §7-1·§7-2 — 0.4초 모아 보내기, 완성처럼 보이면 바로, BUSY 뒤 다시, 나가지 않은 판·재연결(resync 가 정함), 완성 확인 시간 제한. 화면을 잠그지 않는다. */
describe('useSyncSender', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
    seedStore();
  });
  afterEach(() => vi.useRealTimers());

  it('탭마다가 아니라 0.4초에 한 번까지 — 마지막 판 하나만 나간다', () => {
    const send = vi.fn((_: Record<string, unknown>) => true);
    renderHook(() => useSyncSender(send, true));
    act(() => {
      st().place(7);
    });
    act(() => vi.advanceTimersByTime(0));
    expect(send).toHaveBeenCalledTimes(1); // 첫 판은 바로(앞선 보내기 없음)
    act(() => {
      st().select(2);
      st().place(6);
      st().select(3);
      st().place(9);
    });
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS - 1));
    expect(send).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(1));
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0]).toMatchObject({ version: 2, grid: [2, 7, 6, 9, 0, 1, 0, 0, 0] });
  });

  it('완성처럼 보이는 판은 기다리지 않고, SOLVED 가 안 오면 표시를 풀고 한 번 더 보낸다', () => {
    const send = vi.fn((_: Record<string, unknown>) => true);
    renderHook(() => useSyncSender(send, true));
    act(() => {
      st().place(7);
    });
    act(() => vi.advanceTimersByTime(0));
    const cells = LO_SHU.map((v, i) => [i, v] as const).filter(([i]) => ![0, 1, 5].includes(i));
    act(() => {
      for (const [i, v] of cells) {
        st().select(i);
        st().place(v);
      }
    });
    act(() => vi.advanceTimersByTime(0)); // 쓰로틀을 기다리지 않는다
    expect(send).toHaveBeenCalledTimes(2);
    expect(st().sync.awaitingSolveSince).toBeGreaterThan(0);
    expect(st().board).toEqual(LO_SHU);
    act(() => vi.advanceTimersByTime(SOLVE_CONFIRM_MS));
    expect(st().sync.awaitingSolveSince).toBe(0);
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    expect(send).toHaveBeenCalledTimes(3); // 확인이 안 와 한 번 더
  });

  it('BUSY 뒤에는 잠깐 뒤 지금 판을 새 버전으로 다시 보낸다', () => {
    const send = vi.fn((_: Record<string, unknown>) => true);
    renderHook(() => useSyncSender(send, true));
    act(() => {
      st().place(7);
    });
    act(() => vi.advanceTimersByTime(0));
    act(() => st().onSyncRejected('BUSY', Date.now()));
    act(() => vi.advanceTimersByTime(BUSY_RETRY_MS - 1));
    expect(send).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0]).toMatchObject({ version: 2 });
  });

  it('나가지 않은 판(소켓 끊김 — false)은 되돌려 두고, 다시 연결되면 보낸다', () => {
    const send = vi.fn((_: Record<string, unknown>) => false);
    const { rerender } = renderHook(({ c }) => useSyncSender(send, c), { initialProps: { c: true } });
    act(() => {
      st().place(7);
    });
    act(() => vi.advanceTimersByTime(0));
    expect(send).toHaveBeenCalledTimes(1);
    expect(st().sync.dirty).toBe(true);
    rerender({ c: false });
    send.mockImplementation(() => true);
    act(() => vi.advanceTimersByTime(5_000));
    expect(send).toHaveBeenCalledTimes(1); // 끊긴 동안은 보내지 않는다
    rerender({ c: true });
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    expect(send).toHaveBeenCalledTimes(2);
    expect(st().sync.dirty).toBe(false);
  });

  const snapshot = (grid: number[], version: number, eventSeq: number) =>
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid, version, solved: false },
      }),
    );

  /** 리뷰 2 — 재연결만으로는 다시 보내지 않는다(다른 탭의 판을 덮지 않게). 다시 받은 판이 정한다. */
  it('다시 연결돼도 스스로 다시 보내지 않는다 — 서버가 받은 판이면 그대로, 못 받은 판이면 resync 뒤 다시', () => {
    const send = vi.fn((_: Record<string, unknown>) => true);
    const { rerender } = renderHook(({ c }) => useSyncSender(send, c), { initialProps: { c: true } });
    act(() => {
      st().place(7);
    });
    act(() => vi.advanceTimersByTime(0));
    expect(send).toHaveBeenCalledTimes(1);
    snapshot([2, 7, 0, 0, 0, 1, 0, 0, 0], 1, 5); // 서버가 받았다
    rerender({ c: false });
    rerender({ c: true });
    act(() => vi.advanceTimersByTime(5_000));
    expect(send).toHaveBeenCalledTimes(1);

    // 다음 판은 죽은 소켓으로 나가 닿지 않았다(sendAction 은 true) — 재연결 뒤 resync 의 낡은 판이 덮지 않고 다시 보낸다
    act(() => {
      st().select(2);
      st().place(6);
    });
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    expect(send).toHaveBeenCalledTimes(2);
    rerender({ c: false });
    rerender({ c: true });
    act(() => vi.advanceTimersByTime(5_000));
    expect(send).toHaveBeenCalledTimes(2);
    snapshot([2, 7, 0, 0, 0, 1, 0, 0, 0], 1, 6);
    expect(st().board.slice(0, 3)).toEqual([2, 7, 6]);
    act(() => vi.advanceTimersByTime(SYNC_THROTTLE_MS));
    expect(send).toHaveBeenCalledTimes(3);
    expect(send.mock.calls[2][0]).toMatchObject({ version: 3, grid: [2, 7, 6, 0, 0, 1, 0, 0, 0] });
  });

  it('보내는 동안에도 입력은 막히지 않는다', () => {
    const send = vi.fn((_: Record<string, unknown>) => true);
    renderHook(() => useSyncSender(send, true));
    act(() => {
      st().place(7);
    });
    act(() => vi.advanceTimersByTime(0));
    let ok = false;
    act(() => {
      st().select(2);
      ok = st().place(6);
    });
    expect(ok).toBe(true);
  });
});
