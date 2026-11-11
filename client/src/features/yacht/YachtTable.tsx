import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { GameMenu } from '@/components/GameMenu';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useLiveReactions } from '@/features/chat/reactionStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { useSfx } from '@/features/sound/useSfx';
import { useVoiceChat, voiceStatusOf } from '@/features/voice/useVoiceChat';
import { MAX_ROLLS, type Category, type Held } from '@/types/yacht';
import { yachtRoomSink } from './yachtRoomSink';
import { useYachtStore } from './yachtStore';
import { DiceTray } from './DiceTray';
import { YachtScorecard } from './YachtScorecard';
import { YachtTurnCountdown } from './YachtTurnCountdown';
import { YachtMatchEnd } from './YachtMatchEnd';
import { BIG_CATEGORIES, CATEGORY_LABEL } from './yachtLabels';
import { YACHT_TUTORIAL } from './tutorial/yachtTutorial';
import { SeatDice } from './SeatDice';
import { useYachtBest } from './useYachtBest';
import { isGuestName } from '@/features/stats/playerTiers';
import { salonTheme, seatAccent, yachtThemeClass } from './yachtTheme';
import { GoldBurst } from './GoldBurst';

export { seatAccent };

/** 차례 카운트다운이 0 이 된 뒤 이만큼(+ 지터) 지나도 같은 차례면 한 번 다시 받는다(원카드 D-140 과 같은 복구). */
export const STALE_TURN_GRACE_MS = 2000;
export const STALE_TURN_JITTER_MS = 1000;
/** 기록 강조·축하 효과 시간. */
export const SCORE_FLASH_MS = 1500;

/**
 * 홀드 변경을 모아 보내는 간격. 주사위를 빠르게 여러 개 누르면 HOLD 마다 방 액션 락을 다퉈 뒤의 것이 BUSY 로 버려졌다 — 마지막 누름
 * 뒤 이만큼 기다렸다가 마스크 전체를 한 번 보낸다. 굴리기는 기다리지 않고 지금 홀드를 함께 실어 보낸다.
 */
export const HOLD_SEND_DELAY_MS = 250;
/** D-200 — BUSY 로 거절된 굴리기·기록을 다시 보내기까지. */
export const BUSY_RETRY_DELAY_MS = 150;

/** D-203 — 요트 칸 기록 축하(금빛 훑기·현수막·금가루)가 떠 있는 시간. 조작을 막지 않는다. */
export const YACHT_PARTY_MS = 2400;
/** 칩이 칸에 닿는 시점(칩 비행 길이) — 칸의 금박·불티가 이때 시작한다. CSS `yt-salon-chip` 길이와 같다. */
export const CHIP_FLIGHT_MS = 360;
/** 칩 뒤를 따르는 잔상 수. */
const CHIP_GHOSTS = 3;

interface ChipFlight {
  id: number;
  x: number;
  y: number;
  dx: number;
  dy: number;
  /** 칸 한가운데(뷰포트 좌표) — 꽂히는 불티 자리. */
  hitX: number;
  hitY: number;
}

interface Party {
  nonce: number;
  seat: number;
  points: number;
  /** 펠트 한가운데(뷰포트 좌표) — 금가루가 터지는 자리. */
  x: number;
  y: number;
  width: number;
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
  roomFinished?: boolean;
  onBackToRoom?: () => void;
  onKick?: (userId: number) => void;
  tiers?: Record<number, Tier>;
}

/**
 * 요트 게임판 조립 루트(요트 S4, 설계 `docs/plans/yacht.md` §7). 원카드처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
 *
 * <p>순차 진행이라 화면의 중심은 <b>차례인 사람의 주사위</b>다 — 전원이 같은 주사위 판을 보고, 홀드(`DICE_HELD`)와 굴림이 실시간으로
 * 움직이며, 점수표의 차례 열에 서버가 계산한 후보 점수가 흐리게 뜬다(D-181 — 기다림을 구경으로). 좌석 띠는 점수표 열과 같은 좌석
 * 순서다(점수표가 좌석 순 표라 테이블 배치 `tableLayout` 대신 띠 하나 — 좌석이 4개뿐이다).
 */
export function YachtTable({
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
  const { connected, sendAction, sendChat, sendReaction, sendVoice, subscribeVoice, chatPanelOpenRef, requestResync } =
    useStompRoom(roomId, token, yachtRoomSink);
  const [chatOpen, setChatOpen] = useState(false);
  const { muted, toggleMute, playChime } = useSfx();
  const liveReactions = useLiveReactions();
  const chatLines = useSeatChatLines(playerIds);
  const voice = useVoiceChat({ myUserId, connected, send: sendVoice, subscribe: subscribeVoice });
  const chatUnread = useRoomChatStore((st) => st.unreadCount);
  const rules = useTutorialGate(YACHT_TUTORIAL.seenKey, false);
  const animate = useCardAnimStore((st) => st.enabled);

  const s = useYachtStore();
  const seatCount = s.seats.length || playerIds.length;
  // 비공개 뷰가 없어 내 좌석은 좌석 목록에서 찾는다.
  const mySeat = spectator ? -1 : playerIds.indexOf(myUserId);
  const me = s.seats.find((x) => x.seat === mySeat) ?? null;
  const iAmOut = me?.deserted ?? false;
  const turnSeat = roomFinished ? -1 : s.turnSeat;
  // D-202 — 동시 진행이면 "내 차례" = 이번 라운드를 아직 기록하지 않음. 가운데 판(s.dice 등)은 스토어가 내 좌석 것을 비춘다.
  const simultaneous = s.simultaneous && !roomFinished;
  const myPending = simultaneous ? (s.seatTurns[mySeat]?.pending ?? false) : turnSeat === mySeat;
  const myTurn = !spectator && mySeat >= 0 && !iAmOut && s.result === null && myPending;
  const canHold = myTurn && connected && s.rolls >= 1 && s.rolls < MAX_ROLLS;
  const canRoll = myTurn && connected && s.rolls < MAX_ROLLS && !(s.rolls > 0 && s.held.every(Boolean));
  const canScore = myTurn && connected && s.rolls >= 1;
  // D-201 — 판 시작 때의 내 최고 점수(종료 화면이 "새 최고 기록!"을 가른다). 관전자·게스트는 묻지 않는다.
  const guest = isGuestName(usernames[myUserId]);
  const previousBest = useYachtBest(token, myUserId, !spectator && !guest);

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };

  // 내 차례가 되면 알림음 + 휴대폰 진동 1회(원카드 D-154·D-160 과 같다).
  const wasMyTurn = useRef(myTurn);
  useEffect(() => {
    if (myTurn && !wasMyTurn.current) {
      playChime();
      if (!muted) navigator.vibrate?.(120);
    }
    wasMyTurn.current = myTurn;
  }, [myTurn, playChime, muted]);

  // 기록 강조는 잠깐만. D-203 — 황동 칩이 주사위 자리(동시 진행의 남은 그 좌석 띠)에서 방금 적힌 칸으로 날아가 꽂힌다.
  const rootRef = useRef<HTMLDivElement>(null);
  const [flash, setFlash] = useState<typeof s.lastScore>(null);
  const [chip, setChip] = useState<ChipFlight | null>(null);
  useEffect(() => {
    const last = s.lastScore;
    if (!last) return;
    setFlash(last);
    const root = rootRef.current;
    if (salonTheme && animate && root) {
      const own = !useYachtStore.getState().simultaneous || last.seat === mySeat;
      const from = root.querySelector(own ? '.yt-dice' : `[data-yt-seat="${last.seat}"]`);
      const to = root.querySelector(`[data-yt-cell="${last.seat}:${last.category}"]`);
      if (from && to) {
        const r = root.getBoundingClientRect();
        const a = from.getBoundingClientRect();
        const b = to.getBoundingClientRect();
        const x = a.left + a.width / 2 - r.left;
        const y = a.top + a.height / 2 - r.top;
        setChip({
          id: last.nonce,
          x,
          y,
          dx: b.left + b.width / 2 - r.left - x,
          dy: b.top + b.height / 2 - r.top - y,
          hitX: b.left + b.width / 2,
          hitY: b.top + b.height / 2,
        });
      }
    }
    const timer = window.setTimeout(() => {
      setFlash(null);
      setChip(null);
    }, SCORE_FLASH_MS);
    return () => window.clearTimeout(timer);
    // 기록 하나에 한 번만 — 좌석·연출 설정이 바뀌었다고 다시 날리지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.lastScore]);

  // D-203 — 본 축하는 요트 칸에 점수를 적을 때(5개가 같게 멈춘 순간은 주사위 반짝임만).
  const feltRef = useRef<HTMLDivElement>(null);
  const [party, setParty] = useState<Party | null>(null);
  useEffect(() => {
    const last = s.lastScore;
    if (!last || last.category !== 'YACHT' || last.points <= 0) return;
    const f = feltRef.current?.getBoundingClientRect();
    setParty({
      nonce: last.nonce,
      seat: last.seat,
      points: last.points,
      x: f ? f.left + f.width / 2 : window.innerWidth / 2,
      y: f ? f.top + f.height * 0.45 : window.innerHeight / 3,
      width: f ? f.width : window.innerWidth,
    });
    const timer = window.setTimeout(() => setParty(null), YACHT_PARTY_MS);
    return () => window.clearTimeout(timer);
  }, [s.lastScore]);

  // D-203 — 차례 조명: 펠트 위 등불이 차례인 좌석 명패 쪽으로 옮겨 간다(동시 진행·판 끝은 가운데).
  const [lampX, setLampX] = useState(0);
  const lampSeat = simultaneous || s.result !== null ? -1 : turnSeat;
  useLayoutEffect(() => {
    const felt = feltRef.current;
    const seatEl = lampSeat >= 0 ? rootRef.current?.querySelector(`[data-yt-seat="${lampSeat}"]`) : null;
    if (!salonTheme || !felt || !seatEl) {
      setLampX(0);
      return;
    }
    const f = felt.getBoundingClientRect();
    const a = seatEl.getBoundingClientRect();
    setLampX(Math.round(a.left + a.width / 2 - (f.left + f.width / 2)));
  }, [lampSeat, seatCount]);

  // 멈춘 차례 복구 — 카운트다운 0 + 유예 + 지터 뒤에도 같은 차례(좌석·라운드·굴림 수)면 한 번 권위 스냅샷을 다시 받는다.
  const turnClock = s.turnClock;
  const turnKey = `${s.turnSeat}:${s.round}`;
  const counting = !roomFinished && (turnSeat >= 0 || simultaneous) && turnSeconds > 0 && turnClock !== null;
  useEffect(() => {
    if (!counting || turnClock === null) return;
    const fullTurnMs = turnSeconds * 1000;
    const deadline = turnClock.since + Math.min(turnClock.remainingMs ?? fullTurnMs, fullTurnMs);
    const jitter = Math.random() * STALE_TURN_JITTER_MS;
    const timer = window.setTimeout(() => {
      const current = useYachtStore.getState();
      if (`${current.turnSeat}:${current.round}` === turnKey && current.result === null) requestResync();
    }, Math.max(0, deadline + STALE_TURN_GRACE_MS + jitter - Date.now()));
    return () => window.clearTimeout(timer);
  }, [counting, turnClock, turnKey, turnSeconds, requestResync]);

  const setOwnSeat = s.setOwnSeat;
  useEffect(() => {
    setOwnSeat(mySeat);
  }, [mySeat, setOwnSeat]);

  // 홀드는 모아서 보낸다(HOLD_SEND_DELAY_MS) — 보낼 때 스토어의 지금 마스크를 읽는다.
  const holdTimer = useRef<number | null>(null);
  const cancelHoldSend = () => {
    if (holdTimer.current !== null) {
      window.clearTimeout(holdTimer.current);
      holdTimer.current = null;
    }
  };
  useEffect(() => cancelHoldSend, []);
  const toggleHold = (index: number) => {
    if (!canHold) return;
    // 렌더 사이의 연속 탭도 서로 덮지 않게 스토어의 지금 값에서 뒤집는다(렌더 값 s.held 는 한 박자 늦을 수 있다).
    const current = useYachtStore.getState().held;
    const next = current.map((h, i) => (i === index ? !h : h)) as Held;
    s.setHeldLocal(next);
    cancelHoldSend();
    holdTimer.current = window.setTimeout(() => {
      holdTimer.current = null;
      sendAction({ '@action': 'HOLD', held: useYachtStore.getState().held });
    }, HOLD_SEND_DELAY_MS);
  };

  // 굴리기·기록은 기억해 두었다가 BUSY 면 한 번 다시 보낸다(D-200).
  const sendTracked = (action: Record<string, unknown>) => {
    s.noteSent(action);
    sendAction(action);
  };
  // 스토어는 모듈 전역이라 다시 마운트되면 지난 값이 남아 있다 — 값이 아니라 변화에만 반응한다(원카드 resyncNonce 와 같은 방어).
  const retryNonce = s.retryNonce;
  const handledRetryNonce = useRef(retryNonce);
  useEffect(() => {
    if (retryNonce === handledRetryNonce.current) return;
    handledRetryNonce.current = retryNonce;
    const timer = window.setTimeout(() => {
      const { lastSent } = useYachtStore.getState();
      if (lastSent) sendAction(lastSent.action);
    }, BUSY_RETRY_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [retryNonce, sendAction]);

  // 굴림에 지금 홀드를 실어 보낸다 — 모으던 HOLD 는 보내지 않는다(서버가 굴림의 홀드를 먼저 적용하고 DICE_HELD 를 낸다, 룰 §4-2).
  const roll = () => {
    if (!canRoll) return;
    cancelHoldSend();
    const { rolls, held } = useYachtStore.getState();
    sendTracked(rolls === 0 ? { '@action': 'ROLL' } : { '@action': 'ROLL', held });
  };

  const score = (category: Category) => {
    if (!canScore) return;
    cancelHoldSend();
    const points = s.candidates[category];
    if (points === 0 && !window.confirm(`${CATEGORY_LABEL[category]} 칸에 0점으로 버릴까요?`)) return;
    sendTracked({ '@action': 'SCORE', category });
  };

  const exit = () => {
    if (!onExit) return;
    if (
      !spectator &&
      s.result === null &&
      !roomFinished &&
      !iAmOut &&
      !window.confirm('게임 중에 나가면 탈주로 처리되어 최하위가 됩니다. 나가시겠습니까?')
    ) {
      return;
    }
    onExit();
  };

  const announcement = flash
    ? `${nameOf(flash.seat)} ${CATEGORY_LABEL[flash.category]} ${flash.points}점${flash.auto ? ' (시간 초과 자동 기록)' : ''}`
    : '';

  return (
    <div className={`yt-table${yachtThemeClass}`} ref={rootRef}>
      <header className="yt-header gm-header">
        <div className="yt-header-badges gm-header-info">
          <span className="yt-badge yt-badge-title">요트</span>
          <span className="yt-badge">라운드 {Math.min(s.round, 12)}/12</span>
          {spectator && <span className="yt-badge">관전 모드</span>}
        </div>
        <div className="yt-header-badges gm-header-actions">
          <button type="button" className="yt-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          {/* D-186 — 방 정보·설정·규칙·반응·게임 종료·나가기는 ⋮ 메뉴로(머리 줄 한 줄 고정). */}
          <GameMenu
            triggerClassName="yt-badge"
            info={[
              `${seatCount}인`,
              practice && '봇과 연습',
              s.simultaneous && '모두 같이',
              turnSeconds > 0 && `${s.simultaneous ? '라운드' : '차례'} ${turnSeconds}초`,
              spectatorCount > 0 && `관전 ${spectatorCount}명`,
              connected ? '연결됨' : '연결 끊김',
            ]}
            sound={{ muted, onToggle: toggleMute }}
            onRules={rules.show}
            onReaction={spectator ? undefined : sendReaction}
            voice={voice}
            onAbort={onAbort && !roomFinished ? onAbort : undefined}
            onExit={onExit ? exit : undefined}
          />
        </div>
      </header>

      <ReconnectBanner connected={connected} />
      {s.errorMessage && (
        <p className="yt-error" role="alert">
          <span>{s.errorMessage}</span>
          <button type="button" className="yt-error-close" aria-label="닫기" onClick={() => s.setError(null)}>
            ×
          </button>
        </p>
      )}

      {/* 좌석 띠 — 점수표 열과 같은 좌석 순서. 차례인 좌석은 테두리. */}
      <ol className="yt-seats" aria-label="좌석">
        {s.seats.map((seat) => {
          const uid = playerIds[seat.seat];
          const isTurn = seat.seat === turnSeat;
          // D-202 — 동시 진행: 남의 주사위를 좌석 띠에 작게 실시간으로, 이번 라운드를 기록했으면 "기록함".
          const seatTurn = simultaneous ? s.seatTurns[seat.seat] : undefined;
          const done = simultaneous && !seat.deserted && seatTurn !== undefined && !seatTurn.pending;
          const disconnected = s.disconnectedSeats.has(seat.seat);
          const v = voiceStatusOf(voice, uid);
          return (
            <li
              key={seat.seat}
              className={`yt-seat${seat.seat === mySeat ? ' yt-seat-me' : ''}${isTurn ? ' yt-seat-turn' : ''}${seat.deserted ? ' yt-seat-out' : ''}${done ? ' yt-seat-done' : ''}`}
              style={{ ['--yt-accent' as string]: seatAccent(seat.seat) }}
              data-yt-seat={seat.seat}
            >
              <span className="yt-seat-chip" aria-hidden="true" />
              <span className="yt-seat-name">
                {tiers[uid] && <TierBadge tier={tiers[uid]} size={16} />}
                {nameOf(seat.seat)}
                {seat.seat === mySeat ? ' (나)' : ''}
              </span>
              <span className={`yt-seat-total${flash?.seat === seat.seat ? ' yt-seat-total-bump' : ''}`}>{seat.total}점</span>
              {isTurn && <span className="yt-seat-tag yt-seat-tag-turn">차례</span>}
              {done && <span className="yt-seat-tag">기록함 ✓</span>}
              {seatTurn?.pending && seat.seat !== mySeat ? (
                <SeatDice turn={seatTurn} />
              ) : (
                // 동시 진행에서는 주사위 줄 자리를 늘 비워 둔다 — 누가 기록해 줄이 사라질 때 좌석 띠 높이가 줄어 아래 주사위 판이
                // 위로 밀리면 누르려던 [굴리기]가 빗나간다.
                simultaneous && <span className="yt-seat-dice yt-seat-dice-empty" aria-hidden="true" />
              )}
              {seat.deserted && <span className="yt-seat-tag">탈주</span>}
              {disconnected && !seat.deserted && <span className="yt-seat-tag">끊김</span>}
              {v === 'on' && <span aria-label="음성 참여 중">🎧</span>}
              {onKick && disconnected && !roomFinished && seat.seat !== mySeat && !seat.deserted && (
                <button type="button" className="yt-seat-kick" onClick={() => onKick(uid)}>
                  내보내기
                </button>
              )}
              {chatLines[seat.seat] && (
                <span key={chatLines[seat.seat].id} className="yt-seat-chat" title={chatLines[seat.seat].text}>
                  {chatLines[seat.seat].text}
                </span>
              )}
              {liveReactions[seat.seat] && (
                <span key={liveReactions[seat.seat].id} className="yt-seat-reaction" aria-hidden="true">
                  {liveReactions[seat.seat].emoji}
                </span>
              )}
            </li>
          );
        })}
      </ol>

      <div
        ref={feltRef}
        className={`yt-felt${myTurn ? ' yt-felt-myturn' : ''}${salonTheme && animate && party ? ' yt-felt-party' : ''}`}
        style={myTurn ? { ['--yt-accent' as string]: seatAccent(mySeat) } : undefined}
      >
        {salonTheme && (
          <span
            className={`yt-lamp${lampSeat >= 0 ? ' yt-lamp-on' : ''}`}
            aria-hidden="true"
            style={{ transform: `translateX(${lampX}px)` }}
          />
        )}
        <div className="yt-felt-status">
          {!roomFinished && (turnSeat >= 0 || simultaneous) && <YachtTurnCountdown turnSeconds={turnSeconds} />}
          {myTurn && <span className="yt-badge yt-badge-turn">내 차례</span>}
          {flash && BIG_CATEGORIES.has(flash.category) && flash.points > 0 && (
            <span className="yt-badge yt-celebrate">🎉 {CATEGORY_LABEL[flash.category]}!</span>
          )}
          {!roomFinished && s.result === null && (turnSeat >= 0 || myTurn) && (
            <span className="yt-rolls" aria-hidden="true" title={`굴림 ${s.rolls}/${MAX_ROLLS}`}>
              {Array.from({ length: MAX_ROLLS }, (_, i) => (
                <span key={i} className={`yt-rolls-dot${i < s.rolls ? ' yt-rolls-dot-used' : ''}`} />
              ))}
            </span>
          )}
        </div>
        {salonTheme && animate && party && (
          <div className="yt-fx" key={party.nonce} aria-hidden="true">
            <span className="yt-fx-rays" />
            <span className="yt-fx-flash" />
            <span className="yt-fx-sweep" />
            <div className="yt-fx-banner">
              <b>요트</b>
              <i>
                {party.seat === mySeat ? '나' : nameOf(party.seat)} · {party.points}점
              </i>
            </div>
          </div>
        )}
        <DiceTray
          dice={s.dice}
          held={s.held}
          rolls={s.rolls}
          rollNonce={s.rollNonce}
          canHold={canHold}
          canRoll={canRoll}
          animate={animate}
          onToggle={toggleHold}
          onRoll={myTurn ? roll : undefined}
          turnName={turnSeat >= 0 ? nameOf(turnSeat) : null}
          note={
            s.result !== null || roomFinished
              ? '판이 끝났습니다'
              : simultaneous && !myTurn
                ? mySeat >= 0 && !iAmOut
                  ? '기록했어요 · 다른 사람을 기다리는 중'
                  : '모두 같이 굴리는 중'
                : undefined
          }
        />
      </div>

      <YachtScorecard
        seats={s.seats}
        turnSeat={simultaneous ? (myTurn ? mySeat : -1) : turnSeat}
        mySeat={mySeat}
        candidates={s.candidates}
        nameOf={nameOf}
        onScore={canScore ? score : undefined}
        lastScore={flash}
      />

      {s.result && (
        <YachtMatchEnd
          result={s.result}
          mySeat={mySeat}
          nameOf={nameOf}
          onExit={onExit}
          onBackToRoom={onBackToRoom}
          previousBest={previousBest}
          guest={guest}
        />
      )}

      {roomFinished && !s.result && (
        <section className="yt-end" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="yt-roll" onClick={exit}>
              메인으로
            </button>
          )}
        </section>
      )}

      {chip &&
        Array.from({ length: CHIP_GHOSTS + 1 }, (_, k) => (
          <span
            key={`${chip.id}:${k}`}
            className={`yt-chip${k > 0 ? ' yt-chip-ghost' : ''}`}
            aria-hidden="true"
            style={
              {
                left: chip.x,
                top: chip.y,
                '--yt-dx': `${chip.dx}px`,
                '--yt-dy': `${chip.dy}px`,
                animationDelay: `${k * 28}ms`,
                opacity: k > 0 ? 0.5 - k * 0.12 : undefined,
              } as CSSProperties
            }
          />
        ))}
      {/* 칩이 꽂히는 자리의 불티 — 요트 축하 때는 큰 폭발이 대신한다. */}
      {chip && !party && (
        <GoldBurst key={`hit:${chip.id}`} x={chip.hitX} y={chip.hitY} count={14} power={0.38} delayMs={CHIP_FLIGHT_MS - 40} />
      )}
      {salonTheme && animate && party && (
        <>
          <GoldBurst key={`p0:${party.nonce}`} x={party.x} y={party.y} count={150} />
          <GoldBurst key={`p1:${party.nonce}`} x={party.x - party.width * 0.3} y={party.y + 10} count={45} power={0.7} delayMs={260} />
          <GoldBurst key={`p2:${party.nonce}`} x={party.x + party.width * 0.3} y={party.y + 10} count={45} power={0.7} delayMs={460} />
        </>
      )}

      <div className="yt-sr-only" aria-live="polite" aria-atomic="true">
        {announcement}
      </div>

      {chatOpen && (
        <RoomChat
          myUserId={myUserId}
          sendChat={sendChat}
          panelOpenRef={chatPanelOpenRef}
          onClose={() => setChatOpen(false)}
          roomId={roomId}
        />
      )}

      <TutorialDialog tutorial={YACHT_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
