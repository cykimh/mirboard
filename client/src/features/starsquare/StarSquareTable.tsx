import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { Tier } from '@/api/users';
import { GameIcon } from '@/components/GameIcon';
import { GameMenu } from '@/components/GameMenu';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { ErrorNotice } from '@/components/notice/FloatingNotice';
import { useAuthStore } from '@/features/auth/authStore';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { useSfx } from '@/features/sound/useSfx';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useStompRoom } from '@/ws/useStompRoom';
import { NumberPad } from './NumberPad';
import { PuzzleResult } from './PuzzleResult';
import { RivalStrip } from './RivalStrip';
import { StarGrid } from './StarGrid';
import { StarSquareMatchEnd } from './StarSquareMatchEnd';
import { ObservatoryArt } from './starSquareArt';
import { clockText, magicSumOf } from './starSquareMath';
import { starSquareRoomSink } from './starSquareRoomSink';
import { canEditBoard, useStarSquareStore } from './starSquareStore';
import { STAR_SQUARE_TOKENS_CLASS } from './starSquareTheme';
import { STAR_SQUARE_TUTORIAL } from './tutorial/starSquareTutorial';
import { useStarSquareFx } from './useStarSquareFx';
import { useSyncSender } from './useSyncSender';

/** 키보드 두 자리 수 — 이 시간 안에 이어 치면 한 수(1, 2 → 12, §13). */
export const TWO_DIGIT_MS = 600;

/** 판 키보드를 쉬게 하는 겹친 층 — 규칙 다이얼로그(Radix)·게임 메뉴·확인 창. 열려 있으면 키는 그 층의 것이다. */
const KEY_OVERLAYS = '[role="dialog"], [role="alertdialog"], [role="menu"], [aria-modal="true"]';

/** 키가 겹친 층의 것인가 — 대상이 층 안이거나(포털이라 게임판 밖) 층이 하나라도 열려 있다(포커스가 층 밖에 남은 경우). */
function overlayOwnsKeys(target: EventTarget | null): boolean {
  if (target instanceof Element && target.closest(KEY_OVERLAYS)) return true;
  return document.querySelector(KEY_OVERLAYS) !== null;
}

interface Props {
  roomId: string;
  playerIds: number[];
  myUserId: number;
  spectator?: boolean;
  botSeats?: number[];
  usernames?: Record<number, string>;
  turnSeconds?: number;
  spectatorCount?: number;
  onExit?: () => void;
  practice?: boolean;
  onAbort?: () => void;
  /** D-120 — 방은 끝났는데 이 세션이 종료 전이를 봐서 게임판을 유지하는 중. 입력을 숨긴다. */
  roomFinished?: boolean;
  /** D-153 — 판이 끝나 방이 대기실로 돌아왔다. [대기실로]가 부른다. */
  onBackToRoom?: () => void;
  /** D-162 — 방장만: 끊긴 사람을 내보낸다. */
  onKick?: (userId: number) => void;
  tiers?: Record<number, Tier>;
}

/** 지금 단계의 남은 시간 — 서버가 준 남은 ms 를 받은 때부터 줄인다. */
function useRemaining(clock: { since: number; remainingMs: number } | null): number | null {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!clock) return;
    const t = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(t);
  }, [clock]);
  if (!clock) return null;
  return Math.max(0, clock.remainingMs - (now - clock.since));
}

/**
 * 별자리 수판 게임판 조립 루트(D-286, `docs/rules-starsquare.md` §13). 다른 게임판처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
 *
 * <p>모두가 같은 퍼즐을 <b>동시에</b> 푼다 — 차례가 없다. 내 판은 로컬이 먼저고(입력은 바로 화면), 판 전체를 0.4초에 한 번까지 모아 보낸다
 * ({@link useSyncSender} — 보낸 판을 기다리며 잠그는 상태가 없다). 남의 판은 숫자 없이 진행도(채운 칸·맞은 줄)만 보이고, 퍼즐이 끝나면 모두의
 * 판과 정답 하나를 나란히 본다.
 *
 * <p><b>D-294</b> — 판이 주인공이다: 시계는 머리 줄에, 칸 크기는 화면 폭·높이에서 계산한다(CSS). 내가 이 퍼즐을 완성하면 아직 푸는 사람의 판을
 * 골라 볼 수 있다(보기 전용 — 판은 서버가 완성한 사람의 본인 큐로만 보낸다. 푸는 동안·관전 중에는 스토어 `peers` 가 비어 있다).
 */
export function StarSquareTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  usernames = {},
  spectatorCount = 0,
  onExit,
  onAbort,
  practice = false,
  roomFinished = false,
  onBackToRoom,
  onKick,
  tiers = {},
}: Props) {
  const token = useAuthStore((st) => st.token);
  const mySeat = spectator ? -1 : playerIds.indexOf(myUserId);
  // 스토어가 내 좌석을 알아야 SOLVED·비공개 뷰를 내 것으로 읽는다 — 첫 스냅샷 전에(레이아웃 단계에서) 알린다
  useLayoutEffect(() => {
    useStarSquareStore.getState().setMySeat(mySeat);
  }, [mySeat]);
  const { connected, sendAction, sendChat, chatPanelOpenRef, requestResync } = useStompRoom(
    roomId,
    token,
    starSquareRoomSink,
  );
  useSyncSender(sendAction, connected);
  const [chatOpen, setChatOpen] = useState(false);
  const { muted, toggleMute } = useSfx();
  const chatLines = useSeatChatLines(playerIds);
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(STAR_SQUARE_TUTORIAL.seenKey, false);
  // 연출(별똥별·반짝임)은 앱의 연출 설정을 따른다 — 꺼져 있으면 `sq-anim` 이 없어 CSS 애니메이션이 하나도 돌지 않는다. 소리는 음소거만 따른다.
  const animate = useCardAnimStore((st) => st.enabled);
  const fx = useStarSquareFx(animate);

  const s = useStarSquareStore();
  const remaining = useRemaining(roomFinished ? null : s.clock);
  const editable = canEditBoard(s) && !roomFinished;
  const n = s.size;
  const solo = playerIds.length === 1;

  // ── 남의 판 보기(D-294) — 내가 완성한 뒤, 아직 푸는 살아 있는 좌석만. 고른 좌석이 완성하거나 나가면 내 판으로 돌아온다 ──
  const [watchPick, setWatchPick] = useState<number | null>(null);
  const watchable =
    s.phase === 'PLAYING' && s.mySolved && mySeat >= 0 && !roomFinished && s.result === null
      ? s.seats.filter((v) => v.seat !== mySeat && !v.solved && !v.deserted).map((v) => v.seat)
      : [];
  const watching = watchPick !== null && watchable.includes(watchPick) ? watchPick : null;
  const puzzleKey = s.puzzleIndex;
  useEffect(() => {
    setWatchPick(null); // 퍼즐이 바뀌면 내 판부터
  }, [puzzleKey]);
  // 포커스가 있던 탭 단추가 사라지면(그 사람이 완성했거나 나갔다) 포커스가 body 로 떨어진다 — '내 판' 탭으로 옮긴다.
  // 지워진 요소는 blur 를 믿을 수 없어, 마지막으로 포커스를 받은 탭 좌석을 focusin 으로 적어 둔다(다른 곳으로 옮기면 지워진다).
  const focusedTabSeat = useRef<number | null>(null);
  const myTabRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const onFocusIn = (e: FocusEvent) => {
      const tab = e.target instanceof Element ? e.target.closest<HTMLElement>('[data-watch-seat]') : null;
      focusedTabSeat.current = tab ? Number(tab.dataset.watchSeat) : null;
    };
    document.addEventListener('focusin', onFocusIn);
    return () => document.removeEventListener('focusin', onFocusIn);
  }, []);
  const watchableKey = watchable.join(',');
  useEffect(() => {
    const seat = focusedTabSeat.current;
    if (seat === null || watchableKey.split(',').includes(String(seat))) return;
    focusedTabSeat.current = null;
    const active = document.activeElement;
    if (active && active !== document.body) return; // 포커스가 이미 다른 곳에 있다
    myTabRef.current?.focus();
  }, [watchableKey]);

  const resyncNonce = s.resyncNonce;
  useEffect(() => {
    if (resyncNonce > 0) requestResync();
  }, [resyncNonce, requestResync]);

  const nameOf = useCallback(
    (seat: number) => {
      const uid = playerIds[seat];
      if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
      return usernames[uid] ?? `#${uid ?? seat}`;
    },
    [playerIds, botSeats, usernames],
  );

  // ── 키보드: 숫자(두 자리는 0.6초 안에 이어서), 방향키, Backspace, Ctrl/⌘+Z ──
  const pendingDigit = useRef<{ d: number; t: number; cell: number; changed: boolean } | null>(null);
  const playRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
      // 규칙 다이얼로그·게임 메뉴 안의 키(숫자·Backspace·방향키)가 뒤의 판을 고치고 SYNC 까지 내보내지 않게
      if (overlayOwnsKeys(e.target)) return;
      const st = useStarSquareStore.getState();
      if (!canEditBoard(st) || roomFinished) return;
      const size = st.size;
      const nn = size * size;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        pendingDigit.current = null;
        st.undo();
        return;
      }
      if (/^[0-9]$/.test(e.key) && !e.ctrlKey && !e.metaKey && !e.altKey) {
        e.preventDefault();
        const d = Number(e.key);
        const now = performance.now();
        const p = pendingDigit.current;
        pendingDigit.current = null;
        if (p && p.cell === st.selected && now - p.t < TWO_DIGIT_MS && p.d * 10 + d <= nn) {
          st.place(p.d * 10 + d, p.changed);
          return;
        }
        if (d === 0 || d > nn) return;
        const changed = st.place(d);
        if (d * 10 <= nn) pendingDigit.current = { d, t: now, cell: st.selected, changed };
        return;
      }
      const moves: Record<string, [number, number]> = {
        ArrowUp: [-1, 0],
        ArrowDown: [1, 0],
        ArrowLeft: [0, -1],
        ArrowRight: [0, 1],
      };
      const mv = moves[e.key];
      if (mv && st.clues) {
        e.preventDefault();
        pendingDigit.current = null;
        let r = Math.floor(st.selected / size);
        let c = st.selected % size;
        for (;;) {
          r += mv[0];
          c += mv[1];
          if (r < 0 || r >= size || c < 0 || c >= size) return;
          if (st.clues[r * size + c] === 0) break;
        }
        const next = r * size + c;
        st.select(next);
        // roving tabindex — 고른 칸으로 DOM 포커스도 옮긴다(고른 칸·포커스 칸이 갈라지지 않게, 스크린리더가 새 칸을 읽게)
        playRef.current?.querySelector<HTMLElement>(`[role="grid"] [data-cell="${next}"]`)?.focus();
        return;
      }
      if (e.key === 'Backspace' || e.key === 'Delete') {
        e.preventDefault();
        pendingDigit.current = null;
        st.place(0);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [roomFinished]);

  const exit = () => {
    if (!onExit) return;
    if (s.result === null && !roomFinished && !spectator && mySeat >= 0 && !solo) {
      if (!window.confirm('판 도중에 나가면 남은 퍼즐은 0점이 됩니다. 나갈까요?')) return;
    }
    onExit();
  };

  const mySeatView = mySeat >= 0 ? s.seats[mySeat] : undefined;
  const awaiting = s.sync.awaitingSolveSince > 0 && !s.mySolved;
  let status: string;
  if (roomFinished && !s.result) status = '게임이 종료되었습니다.';
  else if (s.result) status = '판이 끝났어요.';
  else if (s.phase === 'INTRO' || s.phase === null) status = '곧 첫 퍼즐이 열려요';
  else if (s.phase === 'RESULT') status = '퍼즐이 끝났어요';
  else if (spectator || mySeat < 0) status = '관전 중 — 모두의 진행만 보여요';
  else if (watching !== null) status = `${nameOf(watching)}의 판을 보는 중 — 보기만 할 수 있어요`;
  else if (s.mySolved && mySeatView)
    status =
      watchable.length > 0
        ? `완성! ${mySeatView.solvePlace}번째 — 아직 푸는 사람의 판을 볼 수 있어요`
        : `완성! ${mySeatView.solvePlace}번째 — 다른 사람을 기다려요`;
  else if (awaiting) status = '마방진인지 확인하는 중…';
  else if (s.closing) status = '누군가 완성했어요 — 마감이 다가와요!';
  else status = `1~${n * n} 을 한 번씩 — 모든 줄의 합을 ${s.magicSum || magicSumOf(n)}로`;

  const showBoard = s.phase === 'PLAYING' && s.clues !== null && n > 0;
  const nextInMs = s.phase === 'RESULT' && s.result === null && remaining !== null && remaining > 0 ? remaining : null;

  return (
    <div
      className={`sq-table ${STAR_SQUARE_TOKENS_CLASS}${s.closing ? ' sq-table-closing' : ''}${animate ? ' sq-anim' : ''}`}
      data-phase={s.phase ?? 'LOADING'}
    >
      <header className="sq-header gm-header">
        <div className="sq-header-info gm-header-info">
          <GameIcon gameId="STAR_SQUARE" size={22} />
          <span className="sq-badge sq-badge-title">별자리 수판</span>
          {s.puzzleCount > 0 && (
            <span className="sq-badge" aria-label={`퍼즐 ${Math.min(s.puzzleIndex + 1, s.puzzleCount)} / ${s.puzzleCount}`}>
              퍼즐 {Math.min(s.puzzleIndex + 1, s.puzzleCount)}/{s.puzzleCount}
            </span>
          )}
          {spectator && <span className="sq-badge">👀 관전 중</span>}
        </div>
        {/* D-294 — 시계는 머리 줄에(판 위 한 줄을 판에 준다) */}
        {remaining !== null && s.phase !== 'ENDED' && s.phase !== 'RESULT' && !s.result && (
          <div
            className={`sq-clock${s.closing ? ' sq-clock-closing' : ''}${s.phase === 'INTRO' ? ' sq-clock-intro' : ''}`}
            role="timer"
            aria-live="off"
            aria-label={s.phase === 'INTRO' ? '시작까지' : s.closing ? '마감까지' : '남은 시간'}
          >
            {s.phase === 'INTRO' ? (
              <span className="sq-clock-intro-text">곧 시작 · {Math.max(1, Math.ceil(remaining / 1000))}</span>
            ) : (
              <>
                <span className="sq-clock-label">{s.closing ? '마감' : '남은 시간'}</span>
                <span className="sq-clock-value">{clockText(remaining)}</span>
              </>
            )}
          </div>
        )}
        <div className="sq-header-actions gm-header-actions">
          <button type="button" className="sq-badge" onClick={() => setChatOpen((v) => !v)} aria-pressed={chatOpen}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <GameMenu
            triggerClassName="sq-badge"
            info={[
              n > 0 && `${n}×${n} · 합 ${s.magicSum || magicSumOf(n)}`,
              practice && '봇과 연습',
              spectatorCount > 0 && `관전 ${spectatorCount}명`,
            ]}
            sound={{ muted, onToggle: toggleMute }}
            onRules={rules.show}
            onAbort={onAbort}
            onExit={onExit ? exit : undefined}
          />
        </div>
      </header>

      <RivalStrip
        seats={s.seats}
        n={n}
        mySeat={mySeat}
        playerIds={playerIds}
        nameOf={nameOf}
        tiers={tiers}
        disconnected={s.disconnectedSeats}
        showProgress={s.phase === 'PLAYING'}
        chatLines={chatLines}
        onKick={onKick && !roomFinished ? onKick : undefined}
      />

      <div className="sq-stage">
        {/* D-269 — 끊김·거절 안내는 화면 위에 뜬다(흐름에 끼우지 않는다). */}
        <ReconnectBanner connected={connected} />
        <ErrorNotice message={s.errorMessage} seq={s.errorSeq} sticky={s.errorSticky} onClose={() => s.setError(null)} />

        {(s.phase === 'INTRO' || s.phase === null) && !s.result && (
          <div className="sq-intro" aria-live="polite">
            <ObservatoryArt className="sq-intro-art" />
            <p className="sq-intro-title">별을 밝힐 준비</p>
            <p className="sq-intro-text">모두에게 같은 퍼즐이 동시에 열려요.</p>
          </div>
        )}

        {showBoard && s.clues && (
          <div
            ref={playRef}
            className={`sq-play${s.mySolved ? ' sq-play-solved' : ''}${awaiting ? ' sq-play-checking' : ''}${watching !== null ? ' sq-play-watching' : ''}${editable ? ' sq-play-editing' : ''}`}
          >
            {/* 내가 완성한 뒤 — 누구 판을 볼지 고른다(보기 전용). 넓은 화면은 내 판 옆에, 좁은 화면은 판 위에 */}
            {watchable.length > 0 && (
              <div className="sq-watch-tabs" role="group" aria-label="누구 판 보기">
                <button
                  ref={myTabRef}
                  type="button"
                  className="sq-watch-tab"
                  aria-pressed={watching === null}
                  onClick={() => setWatchPick(null)}
                >
                  내 판
                </button>
                {watchable.map((seat) => (
                  <button
                    key={seat}
                    type="button"
                    className="sq-watch-tab"
                    data-watch-seat={seat}
                    aria-pressed={watching === seat}
                    onClick={() => setWatchPick(seat)}
                  >
                    {nameOf(seat)}
                  </button>
                ))}
              </div>
            )}
            <div className="sq-board sq-board-mine">
              <StarGrid
                n={n}
                board={s.board.length === n * n ? s.board : s.clues}
                clues={s.clues}
                selected={s.selected}
                editable={editable}
                onSelect={(cell) => {
                  pendingDigit.current = null;
                  s.select(cell);
                }}
                label={spectator || mySeat < 0 ? '퍼즐(단서)' : '내 판'}
              />
              {/* 완성 도장 — 글자는 CSS(`attr(data-place)` + "등!")가 그린다. 장식이라 눌림을 받지 않는다 */}
              {s.mySolved && mySeatView && mySeatView.solvePlace > 0 && (
                <span className="sq-fx-stamp" data-place={mySeatView.solvePlace} aria-hidden="true" />
              )}
            </div>
            {watching !== null && (
              <div className="sq-board sq-board-peer" aria-live="off">
                <p className="sq-peer-title">
                  <span className="sq-peer-name">{nameOf(watching)}</span>의 판 <span className="sq-peer-note">· 보기 전용</span>
                </p>
                {/* 줄 합·별자리 선은 그 사람 판 기준. 아직 판을 받지 못했으면(막 골랐을 때) 단서 판부터 */}
                <StarGrid
                  n={n}
                  board={(s.peers[watching]?.length ?? 0) === n * n ? s.peers[watching] : s.clues}
                  clues={s.clues}
                  selected={-1}
                  editable={false}
                  label={`${nameOf(watching)}의 판`}
                />
              </div>
            )}
            {editable && (
              <NumberPad
                n={n}
                board={s.board}
                enabled={editable && s.selected >= 0}
                canUndo={s.history.length > 0}
                canReset={editable && s.board.some((v, i) => v !== 0 && s.clues![i] === 0)}
                onNumber={(v) => {
                  pendingDigit.current = null;
                  s.place(v);
                }}
                onErase={() => {
                  pendingDigit.current = null;
                  s.place(0);
                }}
                onUndo={() => {
                  pendingDigit.current = null;
                  s.undo();
                }}
                onReset={() => {
                  pendingDigit.current = null;
                  s.clearAll();
                }}
              />
            )}
          </div>
        )}

        {/* 별똥별 — 누군가 완성한 순간 무대 위를 지나간다(화면 전용, 눌림 통과). 끝나면 스스로 지워진다 */}
        {fx.meteors.length > 0 && (
          <div className="sq-fx-layer" aria-hidden="true">
            {fx.meteors.map((m) => (
              <span
                key={m.id}
                className={`sq-fx-meteor${m.mine ? ' sq-fx-meteor-mine' : ''}`}
                data-lane={m.lane}
                onAnimationEnd={() => fx.dismiss(m.id)}
              />
            ))}
          </div>
        )}

        {s.lastPuzzle && s.clues && (s.phase === 'RESULT' || s.phase === 'ENDED') && (
          <PuzzleResult
            outcome={s.lastPuzzle}
            n={n}
            clues={s.clues}
            mySeat={mySeat}
            nameOf={nameOf}
            nextInMs={nextInMs}
          />
        )}
      </div>

      <p className={`sq-status${s.closing ? ' sq-status-warn' : ''}`} aria-live="polite">
        {status}
      </p>

      {s.result && (
        <StarSquareMatchEnd
          result={s.result}
          mySeat={mySeat}
          nameOf={nameOf}
          solo={solo}
          onExit={onExit}
          onBackToRoom={onBackToRoom}
        />
      )}

      {roomFinished && !s.result && (
        <section className="sq-end" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="sq-primary" onClick={onExit}>
              메인으로
            </button>
          )}
        </section>
      )}

      {chatOpen && (
        <RoomChat
          myUserId={myUserId}
          sendChat={sendChat}
          panelOpenRef={chatPanelOpenRef}
          onClose={() => setChatOpen(false)}
          roomId={roomId}
        />
      )}

      <TutorialDialog tutorial={STAR_SQUARE_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
