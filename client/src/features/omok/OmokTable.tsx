import { GameIcon } from '@/components/GameIcon';
import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { GameMenu } from '@/components/GameMenu';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useSfx } from '@/features/sound/useSfx';
import { playTableSfx } from '@/features/sound/tableSfx';
import { WIN_START_AFTER_SLAM_SEC, WIN_START_SEC, WIN_STEP_SEC, setOmokSfxSeat } from './omokSfx';
import { BOARD_SIZE, COLUMN_LETTERS, type OmokPoint, type OmokStone } from '@/types/omok';
import { omokRoomSink } from './omokRoomSink';
import { useOmokStore } from './omokStore';
import { FORBIDDEN_LABEL, coordName, forbiddenAt, key, stonesOf } from './omokRules';
import { OmokBoard } from './OmokBoard';
import { OmokMatchEnd } from './OmokMatchEnd';
import { OmokPlaceButton } from './OmokPlaceButton';
import { OmokTurnCountdown } from './OmokTurnCountdown';
import { OMOK_TUTORIAL } from './tutorial/omokTutorial';
import { OMOK_FX, OMOK_TOKENS_CLASS } from './omokTheme';

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

/** 판 틀의 좌표 눈금(D-192) — 넓은 판에서만 보인다(CSS 컨테이너 쿼리). 가로 A~T(I 없음), 세로 19~1(D-193). */
const RULER_X = COLUMN_LETTERS.split('');
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
/** D-193 — 발동 배너가 떠 있는 시간. 상태가 아니라 화면 타이머라 움직임 줄이기에서도 같은 시간 뒤 사라진다. */
export const SLAM_BANNER_MS = 1600;

/** D-194 — 5목 연출의 박자(CSS `--om-step` 과 같은 값)와 시작 시각(`OmokBoard` 의 `--om-t0` 과 같은 값). 효과음·배너가 맞춘다. */
// 값은 효과음 쪽(omokSfx)이 같이 쓰므로 거기 둔다 — 진 쪽 소리가 상승음 끝을 기다려야 한다(D-267 리뷰).
export { WIN_STEP_SEC, WIN_START_SEC, WIN_START_AFTER_SLAM_SEC } from './omokSfx';
/** D-194 — 종료 배너가 떠 있는 시간(5목 / 그 밖). 화면 타이머라 움직임 줄이기에서도 같은 시간 뒤 사라진다. */
export const END_BANNER_FIVE_MS = 2600;
export const END_BANNER_QUIET_MS = 2200;

const END_BANNER_REASON = {
  FIVE: '',
  RESIGN: '기권',
  TIMEOUT: '시간 초과',
  DESERTED: '나감',
  BOARD_FULL: '판이 가득 찼어요',
  NO_LEGAL_MOVE: '흑이 둘 곳이 금수뿐',
} as const;

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
  /** D-195 — [두기]를 길게 누르는 중(미리보기 돌이 달아오른다). */
  const [charging, setCharging] = useState(false);
  const { muted, toggleMute, playChime, playSlam, playFive } = useSfx();
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

  // D-267 — 일반 동작 효과음(sink 가 이벤트마다 울린다)에 듣는 사람 좌석을 알려 준다: 내 수는 또렷하게, 남의 수는 작게, 승패는 내 편 기준.
  useEffect(() => {
    setOmokSfxSeat(mySeat);
    return () => setOmokSfxSeat(-1);
  }, [mySeat]);

  // D-193 — 강하게 둔 수가 판에 닿을 때 "쿵" + 진동. 내 수는 보낸 즉시(흐린 돌이 바로 내리친다), 남의 수는 STONE_PLACED.slam 을
  // 받은 때. 내 수가 확정되면 pendingSlam 이 slamMoves 로 옮겨 가 합이 그대로라 두 번 울리지 않는다.
  // 발동 배너("흑 강하게 두기!")도 같은 때 — 내 화면·상대·관전자 모두 같은 크기.
  const slamCount = s.slamMoves.size + (s.pendingSlam ? 1 : 0);
  const lastSlamCount = useRef(slamCount);
  const [banner, setBanner] = useState<{ id: number; seat: number; color: OmokStone } | null>(null);
  useEffect(() => {
    if (slamCount > lastSlamCount.current) {
      playSlam(SLAM_IMPACT_SEC);
      if (!muted) window.setTimeout(() => navigator.vibrate?.([70, 40, 110]), SLAM_IMPACT_SEC * 1000);
      const st = useOmokStore.getState();
      const lastMove = st.moves.length > 0 ? st.moves[st.moves.length - 1] : null;
      const who = st.pendingSlam
        ? { seat: mySeat, color: myColor }
        : lastMove
          ? { seat: lastMove.seat, color: (st.moves.length % 2 === 1 ? 'BLACK' : 'WHITE') as OmokStone }
          : null;
      if (who && who.color) setBanner({ id: slamCount, seat: who.seat, color: who.color });
    }
    lastSlamCount.current = slamCount;
  }, [slamCount, playSlam, muted, mySeat, myColor]);
  useEffect(() => {
    if (!banner) return;
    const t = window.setTimeout(() => setBanner(null), SLAM_BANNER_MS);
    return () => window.clearTimeout(t);
  }, [banner]);

  // 내 차례가 되면 알림음 + 휴대폰 진동 한 번(원카드 D-160 과 같다).
  // D-194 — 종료 배너: 이 세션이 판이 끝나는 순간을 봤을 때만(끝난 판에 들어오면 현판만). 이긴 쪽·진 쪽·관전자 모두 같은 배너를
  // 같은 크기로 본다. 5목이면 빛줄기가 다 켜진 뒤에 뜨고 박자에 맞춰 음이 올라간다. 화면만 — 상태·타이머와 무관하다.
  const result = s.result;
  const hadResult = useRef(result !== null);
  const [endBanner, setEndBanner] = useState<{ delayMs: number; ms: number } | null>(null);
  useEffect(() => {
    if (result && !hadResult.current && OMOK_FX === 'rich') {
      const five = result.reason === 'FIVE' && result.winLine.length >= 2;
      if (five) {
        const st = useOmokStore.getState();
        const bySlam = st.slamMoves.has(st.moves.length) || st.pendingSlam;
        const start = bySlam ? WIN_START_AFTER_SLAM_SEC : WIN_START_SEC;
        playFive(start, WIN_STEP_SEC, result.winLine.length);
        // 강하게 둔 수로 끝났으면 발동 배너가 사라진 뒤에 뜬다(두 배너가 겹치지 않게).
        const lineDone = Math.round((start + (result.winLine.length - 1) * WIN_STEP_SEC + 0.1) * 1000);
        setEndBanner({ delayMs: bySlam ? Math.max(lineDone, SLAM_BANNER_MS + 50) : lineDone, ms: END_BANNER_FIVE_MS });
      } else {
        // 기권·나감이 발동 배너와 겹치면 발동 배너를 걷는다(드문 경우 — 결과가 먼저다).
        setBanner(null);
        setEndBanner({ delayMs: 150, ms: END_BANNER_QUIET_MS });
      }
    }
    if (!result) setEndBanner(null);
    hadResult.current = result !== null;
  }, [result, playFive]);
  useEffect(() => {
    if (!endBanner) return;
    const t = window.setTimeout(() => setEndBanner(null), endBanner.delayMs + endBanner.ms);
    return () => window.clearTimeout(t);
  }, [endBanner]);

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

  /** 두기 — `slam` 은 [두기] 길게 누르기(D-195). 판을 다시 누르는 확정·마우스 한 번 클릭은 늘 보통. */
  const place = (p: OmokPoint, slam = false) => {
    if (!canPlace) return;
    if (stones.has(key(p))) return;
    if (myColor === 'BLACK' && forbiddenAt(s.forbidden, p)) {
      s.setPreview(p); // 확정하지 않고 이유를 보여 준다
      playTableSfx('move.rejected'); // D-267 — 금수는 서버에 보내지 않으므로 여기서 "툭"
      return;
    }
    s.markPending(p, slam);
    sendAction(slam ? { '@action': 'PLACE_STONE', x: p.x, y: p.y, slam: true } : { '@action': 'PLACE_STONE', x: p.x, y: p.y });
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
  else if (myTurn && preview && charging) status = `${coordName(preview)} — 💥 끝까지 누르고 있으면 강하게!`;
  else if (myTurn && preview) status = `${coordName(preview)} — 한 번 더 누르거나 [여기에 두기]`;
  else if (myTurn) status = '내 차례예요 — 둘 자리를 누르세요';
  else status = `${nameOf(turnSeat)}${turnColor ? `(${COLOR_LABEL[turnColor]})` : ''} 차례`;

  const seats = Array.from({ length: seatCount }, (_, i) => i);
  const marker = useTurnMarker(turnSeat, seatCount);

  return (
    <div className={`om-table ${OMOK_TOKENS_CLASS}`}>
      <header className="om-header gm-header">
        <div className="om-header-badges gm-header-info">
          <GameIcon gameId="OMOK" size={22} />
          <span className="om-badge om-badge-title">오목</span>
          {s.rule && <span className="om-badge">{RULE_LABEL[s.rule]}</span>}
          {spectator && <span className="om-badge">👀 관전 중</span>}
        </div>
        <div className="om-header-actions gm-header-actions">
          <button type="button" className="om-badge" onClick={() => setChatOpen((v) => !v)} aria-pressed={chatOpen}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          {/* D-186 — 방 정보·설정·규칙·기권·게임 종료·나가기는 ⋮ 메뉴로(머리 줄 한 줄 고정). */}
          <GameMenu
            triggerClassName="om-badge"
            info={[
              s.mode && (s.mode === 'TEAMS_2V2' ? '2:2 팀전' : '1:1'),
              practice && '봇과 연습',
              spectatorCount > 0 && `관전 ${spectatorCount}명`,
            ]}
            sound={{ muted, onToggle: toggleMute }}
            onRules={rules.show}
            onResign={!spectator && mySeat >= 0 && s.result === null && !roomFinished ? resign : undefined}
            onAbort={onAbort}
            onExit={onExit ? exit : undefined}
          />
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
            slamArmed={charging && canPlace}
            myColor={myColor}
            winLine={winLine}
            showNumbers={showNumbers}
            enabled={canPlace}
            singleClick={singleClick}
            setPreview={s.setPreview}
            onConfirm={place}
          />
          {/* D-193 — 강하게 두기 발동 배너(모두에게 같은 크기). 돌이 닿는 순간에 맞춰 튀어나온다. */}
          {banner && (
            <div
              key={banner.id}
              className={`om-slam-banner om-slam-banner-${banner.color === 'BLACK' ? 'black' : 'white'}`}
              role="status"
              data-testid="om-slam-banner"
            >
              <span className="om-slam-banner-icon" aria-hidden="true">
                💥
              </span>
              <span className="om-slam-banner-text">
                {COLOR_LABEL[banner.color]} 강하게 두기!
                <span className="om-slam-banner-who">{nameOf(banner.seat)}</span>
              </span>
            </div>
          )}
          {/* D-194 — 종료 배너(모두에게 같은 크기). 승리 줄을 가리지 않게 줄 반대쪽 절반에 뜬다. 결과는 아래 현판이 읽어 준다. */}
          {endBanner && s.result && (
            <div
              className={`om-end-banner om-end-banner-${
                s.result.winnerColor === null ? 'draw' : s.result.reason === 'FIVE' ? 'five' : 'quiet'
              }${s.result.winnerColor ? ` om-end-banner-${s.result.winnerColor === 'BLACK' ? 'black' : 'white'}` : ''}${
                winLine.length >= 2 && winLine.reduce((sum, p) => sum + p.y, 0) / winLine.length < (BOARD_SIZE - 1) / 2
                  ? ' om-end-banner-low'
                  : ''
              }`}
              style={{ animationDelay: `${endBanner.delayMs}ms`, animationDuration: `${endBanner.ms}ms` }}
              aria-hidden="true"
              data-testid="om-end-banner"
            >
              {s.result.winnerColor === null ? (
                <span className="om-end-banner-dots">
                  <span className="om-dot om-dot-black" />
                  <span className="om-dot om-dot-white" />
                </span>
              ) : (
                <span className={`om-dot om-dot-${s.result.winnerColor === 'BLACK' ? 'black' : 'white'}`} />
              )}
              <span className="om-end-banner-text">
                {s.result.winnerColor === null
                  ? '무승부'
                  : s.result.reason === 'FIVE'
                    ? `${COLOR_LABEL[s.result.winnerColor]} 5목!`
                    : `${COLOR_LABEL[s.result.winnerColor]} 승`}
                <span className="om-end-banner-who">
                  {s.result.reason === 'FIVE'
                    ? s.result.winnerSeats.map(nameOf).join(' · ')
                    : s.result.causeSeat >= 0 && s.result.winnerColor !== null
                      ? `${nameOf(s.result.causeSeat)} ${END_BANNER_REASON[s.result.reason]}`
                      : END_BANNER_REASON[s.result.reason]}
                </span>
              </span>
            </div>
          )}
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
            {/* D-195 — 짧게 누르면 보통, 꾹 누르면(게이지가 다 차면) 강하게. */}
            <OmokPlaceButton
              disabled={!canPlace || !previewOk}
              onPlace={(slam) => preview && place(preview, slam)}
              onChargeChange={setCharging}
            />
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
