import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useSfx } from '@/features/sound/useSfx';
import { BOARD_SIZE, type OmokPoint, type OmokStone } from '@/types/omok';
import { omokRoomSink } from './omokRoomSink';
import { useOmokStore } from './omokStore';
import { FORBIDDEN_LABEL, coordName, forbiddenAt, key, stonesOf } from './omokRules';
import { OmokBoard } from './OmokBoard';
import { OmokMatchEnd } from './OmokMatchEnd';
import { OmokTurnCountdown } from './OmokTurnCountdown';
import { OMOK_TUTORIAL } from './tutorial/omokTutorial';
import { OMOK_TOKENS_CLASS } from './omokTheme';

/** D-140 — 원카드와 같은 멈춘 차례 복구 유예·지터. */
export const STALE_TURN_GRACE_MS = 2000;
export const STALE_TURN_JITTER_MS = 1000;
/** 데스크톱 "한 번 클릭으로 두기" — 이 기기 설정(localStorage, 실패해도 기본값으로 동작). */
export const SINGLE_CLICK_KEY = 'mirboard.omok.singleClick.v1';

function readSingleClick(): boolean {
  try {
    return window.localStorage.getItem(SINGLE_CLICK_KEY) === '1';
  } catch {
    return false;
  }
}

function writeSingleClick(on: boolean) {
  try {
    window.localStorage.setItem(SINGLE_CLICK_KEY, on ? '1' : '0');
  } catch {
    // 저장소가 막혀도(사생활 보호 창 등) 이번 세션 동안은 켜진 채 동작한다.
  }
}

/** 판 틀의 좌표 눈금(D-192) — 넓은 판에서만 보인다(CSS 컨테이너 쿼리). 가로 A~O, 세로 15~1. */
const RULER_X = Array.from({ length: BOARD_SIZE }, (_, i) => String.fromCharCode(65 + i));
const RULER_Y = Array.from({ length: BOARD_SIZE }, (_, i) => BOARD_SIZE - i);

interface MarkerBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * D-192 — 차례 띠의 놋쇠 고리. 지금 차례인 명패 자리를 재서 고리 하나를 그 위로 옮긴다(CSS 전환이 미끄러뜨린다). 명패 폭은
 * 카운트다운·끊김 배지로 바뀌므로 크기 변화를 지켜본다. 재지 못하는 환경(테스트)에서는 고리가 없을 뿐이다.
 */
function useTurnMarker(turnSeat: number, seatCount: number) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const [box, setBox] = useState<MarkerBox | null>(null);
  useLayoutEffect(() => {
    const wrap = wrapRef.current;
    if (!wrap) return;
    const measure = () => {
      const el = wrap.querySelector<HTMLElement>('.om-seat-turn');
      const next =
        el && el.offsetWidth > 0
          ? {
              x: el.offsetLeft,
              y: el.offsetTop,
              w: el.offsetWidth,
              h: el.offsetHeight,
            }
          : null;
      setBox((prev) =>
        prev && next && prev.x === next.x && prev.y === next.y && prev.w === next.w && prev.h === next.h ? prev : next,
      );
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(wrap);
    wrap.querySelectorAll('.om-seat').forEach((seat) => observer.observe(seat));
    return () => observer.disconnect();
  }, [turnSeat, seatCount]);
  return { wrapRef, box };
}

const RULE_LABEL = {
  RENJU: '렌주',
  DOUBLE_THREE_ONLY: '삼삼만 금지',
  FREESTYLE: '자유 오목',
} as const;
const COLOR_LABEL: Record<OmokStone, string> = { BLACK: '흑', WHITE: '백' };
/** D-193 — 강하게 둔 돌이 판에 닿는 때(CSS `om-slam-drop` 의 눌림 지점). 효과음·진동이 여기에 맞춘다. */
export const SLAM_IMPACT_SEC = 0.32;

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

/**
 * 오목 게임판 조립 루트 (D-175·D-180). 다른 게임판처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
 *
 * <p>비공개 뷰가 없어 내 좌석은 방의 `playerIds` 에서 찾는다. 판이 화면 폭을 다 쓰므로 좌석을 판 옆에 두지 않고 위에 **차례 띠**
 * (좌석 순서 — 2:2 는 같은 팀이 같은 색)를, 아래에 내 줄과 [여기에 두기]를 둔다(설계서 §6.2).
 */
export function OmokTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  usernames = {},
  turnSeconds = 0,
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
  const { connected, sendAction, sendChat, chatPanelOpenRef, requestResync } = useStompRoom(
    roomId,
    token,
    omokRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const [showNumbers, setShowNumbers] = useState(false);
  const [singleClick, setSingleClick] = useState(readSingleClick);
  /** D-193 — 다음 착수를 강하게 둘지(한 번 두면 풀린다). */
  const [slamArmed, setSlamArmed] = useState(false);
  const { muted, toggleMute, playChime, playSlam } = useSfx();
  const chatLines = useSeatChatLines(playerIds);
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(OMOK_TUTORIAL.seenKey, false);

  const s = useOmokStore();
  const seatCount = s.seatColors.length || playerIds.length;
  const mySeat = spectator ? -1 : playerIds.indexOf(myUserId);
  const myColor: OmokStone | null = mySeat >= 0 ? (s.seatColors[mySeat] ?? null) : null;
  const turnSeat = roomFinished ? -1 : s.turnSeat;
  const turnColor: OmokStone | null = turnSeat >= 0 ? (s.seatColors[turnSeat] ?? null) : null;
  const myTurn = !spectator && mySeat >= 0 && s.result === null && !roomFinished && turnSeat === mySeat;
  const canPlace = myTurn && connected && s.pending === null;
  const mySlamsLeft = mySeat >= 0 ? (s.slamsLeft[mySeat] ?? 0) : 0;
  const slamOn = slamArmed && mySlamsLeft > 0;

  // D-193 — 강하게 둔 수가 판에 닿을 때 "쿵" + 진동. 내 수는 보낸 즉시(흐린 돌이 바로 내리친다), 남의 수는 STONE_PLACED.slam 을
  // 받은 때. 내 수가 확정되면 pendingSlam 이 slamMoves 로 옮겨 가 합이 그대로라 두 번 울리지 않는다.
  const slamCount = s.slamMoves.size + (s.pendingSlam ? 1 : 0);
  const lastSlamCount = useRef(slamCount);
  useEffect(() => {
    if (slamCount > lastSlamCount.current) {
      playSlam(SLAM_IMPACT_SEC);
      if (!muted) window.setTimeout(() => navigator.vibrate?.([70, 40, 110]), SLAM_IMPACT_SEC * 1000);
    }
    lastSlamCount.current = slamCount;
  }, [slamCount, playSlam, muted]);

  // 내 차례가 되면 알림음 + 휴대폰 진동 한 번(원카드 D-160 과 같다).
  const wasMyTurn = useRef(myTurn);
  useEffect(() => {
    if (myTurn && !wasMyTurn.current) {
      playChime();
      if (!muted) navigator.vibrate?.(120);
    }
    wasMyTurn.current = myTurn;
  }, [myTurn, playChime, muted]);

  // 다시 받기 신호.
  const resyncNonce = s.resyncNonce;
  useEffect(() => {
    if (resyncNonce > 0) requestResync();
  }, [resyncNonce, requestResync]);

  // D-140 — 멈춘 차례 복구: 카운트다운이 0 + 유예 + 지터 뒤에도 같은 차례면 그 차례에 한 번 권위 스냅샷.
  const turnClock = s.turnClock;
  const turnEpoch = s.turnEpoch;
  const counting = !roomFinished && turnSeat >= 0 && turnSeconds > 0 && turnClock !== null;
  useEffect(() => {
    if (!counting || turnClock === null) return;
    const full = turnSeconds * 1000;
    const deadline = turnClock.since + Math.min(turnClock.remainingMs ?? full, full);
    const timer = window.setTimeout(
      () => {
        const st = useOmokStore.getState();
        if (st.turnEpoch === turnEpoch && st.result === null) st.requestTurnResync(turnEpoch);
      },
      Math.max(0, deadline + STALE_TURN_GRACE_MS + Math.random() * STALE_TURN_JITTER_MS - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [counting, turnClock, turnEpoch, turnSeconds]);

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };

  const stones = stonesOf(s.moves);
  const preview = canPlace ? s.preview : null;
  const previewForbidden = myColor === 'BLACK' ? forbiddenAt(s.forbidden, preview) : null;
  const previewOk = preview !== null && !stones.has(key(preview)) && previewForbidden === null;

  const place = (p: OmokPoint) => {
    if (!canPlace) return;
    if (stones.has(key(p))) return;
    if (myColor === 'BLACK' && forbiddenAt(s.forbidden, p)) {
      s.setPreview(p); // 확정하지 않고 이유를 보여 준다
      return;
    }
    s.markPending(p, slamOn);
    sendAction(slamOn ? { '@action': 'PLACE_STONE', x: p.x, y: p.y, slam: true } : { '@action': 'PLACE_STONE', x: p.x, y: p.y });
    setSlamArmed(false);
  };

  const resign = () => {
    const team = s.mode === 'TEAMS_2V2' ? ' 팀 전체가' : '';
    if (window.confirm(`기권하면${team} 집니다. 기권할까요?`)) sendAction({ '@action': 'RESIGN' });
  };

  const exit = () => {
    if (!onExit) return;
    if (s.result === null && !roomFinished && !spectator && mySeat >= 0) {
      const team = s.mode === 'TEAMS_2V2' ? ' 팀이' : '';
      if (!window.confirm(`판 도중에 나가면${team} 집니다. 나갈까요?`)) return;
    }
    onExit();
  };

  const toggleSingleClick = () => {
    const next = !singleClick;
    setSingleClick(next);
    writeSingleClick(next);
  };

  const last = s.moves.length > 0 ? s.moves[s.moves.length - 1] : null;
  const winLine = s.result?.winLine ?? [];

  let status: string;
  if (s.result) status = '판이 끝났어요.';
  else if (roomFinished) status = '게임이 종료되었습니다.';
  else if (turnSeat < 0) status = '다음 차례를 기다리는 중…';
  else if (myTurn && s.pending) status = '두는 중…';
  else if (myTurn && previewForbidden) status = FORBIDDEN_LABEL[previewForbidden.kind];
  else if (myTurn && preview) status = `${coordName(preview)} — 한 번 더 누르거나 [${slamOn ? '강하게 두기' : '여기에 두기'}]`;
  else if (myTurn && slamOn) status = '💥 강하게 둘 자리를 누르세요';
  else if (myTurn) status = '내 차례예요 — 둘 자리를 누르세요';
  else status = `${nameOf(turnSeat)}${turnColor ? `(${COLOR_LABEL[turnColor]})` : ''} 차례`;

  const seats = Array.from({ length: seatCount }, (_, i) => i);
  const marker = useTurnMarker(turnSeat, seatCount);

  return (
    <div className={`om-table ${OMOK_TOKENS_CLASS}`}>
      <header className="om-header">
        <div className="om-header-badges">
          <span className="om-badge om-badge-title">오목</span>
          {s.mode && <span className="om-badge">{s.mode === 'TEAMS_2V2' ? '2:2 팀전' : '1:1'}</span>}
          {s.rule && <span className="om-badge">{RULE_LABEL[s.rule]}</span>}
          {practice && <span className="om-badge">🤖 혼자 연습</span>}
          {spectator && <span className="om-badge">👀 관전 중</span>}
          {spectatorCount > 0 && <span className="om-badge">관전 {spectatorCount}</span>}
        </div>
        <div className="om-header-actions">
          <button type="button" className="om-badge" onClick={rules.show}>
            규칙
          </button>
          <button type="button" className="om-badge" onClick={() => setChatOpen((v) => !v)} aria-pressed={chatOpen}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <button type="button" className="om-badge" onClick={toggleMute} aria-pressed={!muted}>
            {muted ? '🔇' : '🔔'}
          </button>
          {!spectator && mySeat >= 0 && s.result === null && !roomFinished && (
            <button type="button" className="om-badge" onClick={resign}>
              기권
            </button>
          )}
          {onAbort && (
            <button type="button" className="om-badge" onClick={onAbort}>
              게임 종료
            </button>
          )}
          {onExit && (
            <button type="button" className="om-badge" onClick={exit}>
              나가기
            </button>
          )}
        </div>
      </header>

      {/* 차례 띠 — 좌석 순서(진행 +1). 2:2 는 같은 팀이 같은 색이다. 놋쇠 고리가 지금 차례인 명패로 미끄러진다(D-192). */}
      <div className="om-strip-wrap" ref={marker.wrapRef}>
        <span
          className="om-turn-marker"
          aria-hidden="true"
          style={
            marker.box
              ? {
                  opacity: 1,
                  transform: `translate(${marker.box.x}px, ${marker.box.y}px)`,
                  width: marker.box.w,
                  height: marker.box.h,
                }
              : undefined
          }
        >
          {/* 차례가 넘어갈 때마다 고리 위로 반사광이 한 번 지나간다. */}
          <span key={turnSeat} className="om-turn-sheen" />
        </span>
        <ol className="om-strip" aria-label="차례 순서">
          {seats.map((seat) => {
            const color = s.seatColors[seat];
            const uid = playerIds[seat];
            const isTurn = turnSeat === seat;
            return (
              <li
                key={seat}
                className={`om-seat${isTurn ? ' om-seat-turn' : ''}${seat === mySeat ? ' om-seat-me' : ''}`}
                data-color={color}
                aria-current={isTurn ? 'step' : undefined}
              >
                <span
                  className={`om-dot om-dot-${color === 'BLACK' ? 'black' : 'white'}`}
                  aria-label={color ? COLOR_LABEL[color] : ''}
                />
                {tiers[uid] && <TierBadge tier={tiers[uid]} size={16} />}
                <span className="om-seat-name">
                  {nameOf(seat)}
                  {seat === mySeat ? ' (나)' : ''}
                </span>
                {isTurn && <OmokTurnCountdown turnSeconds={turnSeconds} />}
                {s.disconnectedSeats.has(seat) && <span className="om-badge om-badge-warn">끊김</span>}
                {onKick && !roomFinished && seat !== mySeat && s.disconnectedSeats.has(seat) && (
                  <button type="button" className="om-badge" onClick={() => onKick(uid)}>
                    내보내기
                  </button>
                )}
                {chatLines[seat] && (
                  <span key={chatLines[seat].id} className="om-seat-chat" title={chatLines[seat].text}>
                    {chatLines[seat].text}
                  </span>
                )}
              </li>
            );
          })}
        </ol>
      </div>

      <div className="om-stage">
        {/* D-193 — 끊김·거절 안내는 판 위에 띄운다(흐름에 끼우면 판이 밀려 내려간다). */}
        <div className="om-notices">
          <ReconnectBanner connected={connected} />
          {s.errorMessage && (
            <p className="om-error" role="alert">
              <span>{s.errorMessage}</span>
              <button type="button" className="om-error-close" aria-label="닫기" onClick={() => s.setError(null)}>
                ×
              </button>
            </p>
          )}
        </div>
        <div className={`om-board-wrap${myTurn ? ' om-board-myturn' : ''}`}>
          <div className="om-ruler om-ruler-y" aria-hidden="true">
            {RULER_Y.map((n) => (
              <span key={n}>{n}</span>
            ))}
          </div>
          <div className="om-ruler om-ruler-x" aria-hidden="true">
            {RULER_X.map((c) => (
              <span key={c}>{c}</span>
            ))}
          </div>
          {/* 등불 — 판 위에 얹는 빛 한 겹(불투명도만 흔들린다). */}
          <span className="om-lamp" aria-hidden="true" />
          <OmokBoard
            moves={s.moves}
            forbidden={turnColor === 'BLACK' && !s.result ? s.forbidden : []}
            forbiddenIsMine={myTurn && myColor === 'BLACK'}
            preview={preview}
            pending={s.pending}
            pendingSlam={s.pendingSlam}
            slamMoves={s.slamMoves}
            slamArmed={slamOn && canPlace}
            myColor={myColor}
            winLine={winLine}
            showNumbers={showNumbers}
            enabled={canPlace}
            singleClick={singleClick}
            setPreview={s.setPreview}
            onConfirm={place}
          />
        </div>
      </div>

      <p className={`om-status${myTurn && previewForbidden ? ' om-status-warn' : ''}`} aria-live="polite">
        {status}
        {last && s.mode === 'TEAMS_2V2' && !s.result && (
          <span className="om-last-by">
            {' '}
            · 방금 {nameOf(last.seat)}
            {last.timedOut ? '(시간 초과)' : ''}
          </span>
        )}
      </p>

      {!spectator && mySeat >= 0 && s.result === null && !roomFinished && (
        <div className="om-controls">
          <div className="om-place-row">
            {/* D-193 — 한 판에 한 번 강하게 두기. 켜 두면 다음 착수(어느 방법으로 두든)가 강하게 나간다. */}
            <button
              type="button"
              className={`om-slam-toggle${slamOn ? ' om-slam-on' : ''}`}
              aria-pressed={slamOn}
              disabled={mySlamsLeft <= 0 || s.pending !== null}
              onClick={() => setSlamArmed((v) => !v)}
              title="한 판에 한 번 — 돌을 쾅 내리쳐 둡니다(룰은 그대로)"
            >
              <span aria-hidden="true">💥</span> 강하게
              <span className="om-slam-left">{mySlamsLeft > 0 ? `${mySlamsLeft}회` : '다 씀'}</span>
            </button>
            <button
              type="button"
              className={`om-primary${slamOn ? ' om-primary-slam' : ''}`}
              disabled={!canPlace || !previewOk}
              onClick={() => preview && place(preview)}
            >
              {slamOn ? '강하게 두기!' : '여기에 두기'}
            </button>
          </div>
          <label className="om-single-click">
            <input type="checkbox" checked={singleClick} onChange={toggleSingleClick} />
            마우스 한 번 클릭으로 두기
          </label>
        </div>
      )}

      {s.result && (
        <OmokMatchEnd
          result={s.result}
          afterWinLine={winLine.length >= 2}
          mode={s.mode}
          mySeat={mySeat}
          nameOf={nameOf}
          showNumbers={showNumbers}
          onToggleNumbers={() => setShowNumbers((v) => !v)}
          onExit={onExit}
          onBackToRoom={onBackToRoom}
        />
      )}

      {roomFinished && !s.result && (
        <section className="om-end" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="om-primary" onClick={onExit}>
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

      <TutorialDialog tutorial={OMOK_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
