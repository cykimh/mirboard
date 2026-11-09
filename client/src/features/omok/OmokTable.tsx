import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useSfx } from '@/features/sound/useSfx';
import type { OmokPoint, OmokStone } from '@/types/omok';
import { omokRoomSink } from './omokRoomSink';
import { useOmokStore } from './omokStore';
import { FORBIDDEN_LABEL, coordName, forbiddenAt, key, stonesOf } from './omokRules';
import { OmokBoard } from './OmokBoard';
import { OmokMatchEnd } from './OmokMatchEnd';
import { OmokTurnCountdown } from './OmokTurnCountdown';
import { OMOK_TUTORIAL } from './tutorial/omokTutorial';

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

const RULE_LABEL = { RENJU: '렌주', DOUBLE_THREE_ONLY: '삼삼만 금지', FREESTYLE: '자유 오목' } as const;
const COLOR_LABEL: Record<OmokStone, string> = { BLACK: '흑', WHITE: '백' };

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
  const { muted, toggleMute, playChime } = useSfx();
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
    s.markPending(p);
    sendAction({ '@action': 'PLACE_STONE', x: p.x, y: p.y });
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
  else if (myTurn && preview) status = `${coordName(preview)} — 한 번 더 누르거나 [여기에 두기]`;
  else if (myTurn) status = '내 차례예요 — 둘 자리를 누르세요';
  else status = `${nameOf(turnSeat)}${turnColor ? `(${COLOR_LABEL[turnColor]})` : ''} 차례`;

  const seats = Array.from({ length: seatCount }, (_, i) => i);

  return (
    <div className="om-table om-tokens">
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

      <ReconnectBanner connected={connected} />
      {s.errorMessage && (
        <p className="om-error" role="alert">
          <span>{s.errorMessage}</span>
          <button type="button" className="om-error-close" aria-label="닫기" onClick={() => s.setError(null)}>
            ×
          </button>
        </p>
      )}

      {/* 차례 띠 — 좌석 순서(진행 +1). 2:2 는 같은 팀이 같은 색이다. */}
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
              <span className={`om-dot om-dot-${color === 'BLACK' ? 'black' : 'white'}`} aria-label={color ? COLOR_LABEL[color] : ''} />
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

      <div className={`om-board-wrap${myTurn ? ' om-board-myturn' : ''}`}>
        <OmokBoard
          moves={s.moves}
          forbidden={turnColor === 'BLACK' && !s.result ? s.forbidden : []}
          forbiddenIsMine={myTurn && myColor === 'BLACK'}
          preview={preview}
          pending={s.pending}
          myColor={myColor}
          winLine={winLine}
          showNumbers={showNumbers}
          enabled={canPlace}
          singleClick={singleClick}
          setPreview={s.setPreview}
          onConfirm={place}
        />
      </div>

      <p className="om-status" aria-live="polite">
        {status}
        {last && s.mode === 'TEAMS_2V2' && !s.result && (
          <span className="om-last-by"> · 방금 {nameOf(last.seat)}{last.timedOut ? '(시간 초과)' : ''}</span>
        )}
      </p>

      {!spectator && mySeat >= 0 && s.result === null && !roomFinished && (
        <div className="om-controls">
          <button
            type="button"
            className="om-primary"
            disabled={!canPlace || !previewOk}
            onClick={() => preview && place(preview)}
          >
            여기에 두기
          </button>
          <label className="om-single-click">
            <input type="checkbox" checked={singleClick} onChange={toggleSingleClick} />
            마우스 한 번 클릭으로 두기
          </label>
        </div>
      )}

      {s.result && (
        <OmokMatchEnd
          result={s.result}
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
