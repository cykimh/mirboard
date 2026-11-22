import { useEffect, useRef } from 'react';
import { looksComplete } from './starSquareMath';
import { useStarSquareStore } from './starSquareStore';

/** 판을 모아 보내는 간격(§7-1) — 0.4초에 한 번까지. 완성처럼 보이는 판은 기다리지 않는다. */
export const SYNC_THROTTLE_MS = 400;
/** 완성처럼 보이는 판을 보낸 뒤 `SOLVED` 를 기다리는 표시의 상한 — 넘으면 표시를 풀고 한 번 더 보낸다(입력은 원래 막지 않는다). */
export const SOLVE_CONFIRM_MS = 3_000;

/**
 * 내 판 보내기(§7) — 화면을 막지 않는 뒤따르기. 바뀐 판(`sync.dirty`)이 있으면 마지막 보낸 때 + 0.4초(또는 `BUSY` 뒤 잠깐)에 판 전체를
 * `SYNC` 로 보낸다. 칸마다 보내지 않아 방 락 `BUSY` 를 줄이고, 판 전체라 하나가 빠져도 다음 보내기가 메운다(멱등·마지막 판이 이긴다).
 *
 * <p>응답을 기다리는 동안 잠그는 상태가 없다 — 입력은 늘 된다. 완성처럼 보이는 판을 보낸 뒤의 "확인 중" 표시도 {@link SOLVE_CONFIRM_MS} 뒤
 * 스스로 풀리고 판을 한 번 더 보낸다(D-270 의 시간 제한 해제와 같은 뜻 — 보낸 판이 닿지 않아도 화면이 멈추지 않는다).
 */
export function useSyncSender(
  sendAction: (action: Record<string, unknown>) => boolean | void,
  connected: boolean,
) {
  const dirty = useStarSquareStore((s) => s.sync.dirty);
  const retryAt = useStarSquareStore((s) => s.sync.retryAt);
  const board = useStarSquareStore((s) => s.board);
  const awaiting = useStarSquareStore((s) => s.sync.awaitingSolveSince);
  const lastSentAt = useRef(0);

  useEffect(() => {
    if (!dirty || !connected) return;
    const st = useStarSquareStore.getState();
    const now = Date.now();
    const complete = looksComplete(st.board, st.size);
    const at = Math.max(complete ? now : lastSentAt.current + SYNC_THROTTLE_MS, st.sync.retryAt);
    const timer = window.setTimeout(
      () => {
        const s = useStarSquareStore.getState();
        const msg = s.takeSync(Date.now(), looksComplete(s.board, s.size));
        if (msg) {
          // 소켓이 그 사이 끊겨 나가지 않았으면(false) 보내지 않은 것으로 되돌린다 — 다시 연결되면 아래 효과가 보낸다
          if (sendAction(msg) === false) s.markUnsent();
          else lastSentAt.current = Date.now();
        }
      },
      Math.max(0, at - now),
    );
    return () => window.clearTimeout(timer);
  }, [dirty, board, retryAt, connected, sendAction]);

  // 다시 연결되면 지금 판을 한 번 더 보낸다 — 끊긴 사이 보낸 판이 조용히 버려졌을 수 있다(멱등이라 이미 닿았어도 무해)
  const wasConnected = useRef(connected);
  useEffect(() => {
    if (connected && !wasConnected.current) useStarSquareStore.getState().markUnsent();
    wasConnected.current = connected;
  }, [connected]);

  useEffect(() => {
    if (!awaiting) return;
    const timer = window.setTimeout(
      () => useStarSquareStore.getState().solveConfirmTimedOut(),
      Math.max(0, awaiting + SOLVE_CONFIRM_MS - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [awaiting]);
}
