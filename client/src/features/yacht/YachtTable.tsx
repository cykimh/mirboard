import { GameIcon } from '@/components/GameIcon';
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
import { MAX_ROLLS, NO_HOLD, type Category, type Held } from '@/types/yacht';
import { yachtRoomSink } from './yachtRoomSink';
import { useYachtStore } from './yachtStore';
import { DiceTray, ROLL_ANIMATION_MS } from './DiceTray';
import { YachtScorecard } from './YachtScorecard';
import { YachtTurnCountdown } from './YachtTurnCountdown';
import { YachtMatchEnd } from './YachtMatchEnd';
import {
  BIG_CATEGORIES,
  CATEGORY_LABEL,
  MID_CATEGORIES,
  celebrationDice,
  madeHand,
  type MadeHand,
  type MadeTier,
} from './yachtLabels';
import { YachtDie } from './YachtDie';
import { YACHT_TUTORIAL } from './tutorial/yachtTutorial';
import { SeatDice } from './SeatDice';
import { useYachtBest } from './useYachtBest';
import { isGuestName } from '@/features/stats/playerTiers';
import { salonTheme, seatAccent, yachtThemeClass } from './yachtTheme';
import { GoldBurst } from './GoldBurst';
import { showToast } from '@/components/toast/toastStore';
import {
  loadMotionPreference,
  motionShakeSupported,
  requestMotionPermission,
  saveMotionPreference,
} from './useDeviceShake';

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
export const YACHT_PARTY_MS = 2600;
/** D-204 — 중간 축하(라지 스트레이트·풀 하우스 현수막, 상단 보너스 명패)가 떠 있는 시간. */
export const MID_PARTY_MS = 1800;
/** D-208 — 굴림이 족보로 멈췄을 때 고리·꼬리표가 떠 있는 시간(세기별). 조작을 막지 않는다. */
export const MADE_FX_MS: Record<MadeTier, number> = { light: 1200, mid: 1500, big: 1800 };
/**
 * D-205 — 남의 판을 구경하다가도 내 차례를 놓치지 않게: 아직 기록하지 않았는데 라운드 마감이 이만큼 남으면 내 판으로 되돌린다
 * (이때부터는 남의 판을 열 수도 없다).
 */
export const WATCH_RETURN_BEFORE_MS = 10_000;
/** 점수 숫자가 칸에 닿는 시점(비행 길이) — 칸의 금박·불티가 이때 시작한다. CSS `yt-salon-chip` 길이와 같다(D-204 — 숫자가 읽히게 조금 길게). */
export const CHIP_FLIGHT_MS = 480;
/** 숫자 뒤를 따르는 잔상 수. */
const CHIP_GHOSTS = 3;
/** D-204 — 샴페인 기포·금 띠 조각 수(요트 축하, CSS transform 만). */
const BUBBLES = 16;
const STREAMERS = 8;

/** D-204 — 샴페인 잔(직접 그린 SVG, 요트 현수막 양옆에서 부딪힌다). */
function Coupe() {
  return (
    <svg viewBox="0 0 40 56" focusable="false">
      <path d="M5 6 H35 Q35 26 20 28 Q5 26 5 6 Z" fill="rgba(255,246,208,.16)" stroke="#f6e2a4" strokeWidth="2" />
      <path d="M8 13 H32 Q31 24 20 25.5 Q9 24 8 13 Z" fill="#f3d27a" fillOpacity=".85" />
      <path d="M20 28 V48 M10 51 H30" fill="none" stroke="#f6e2a4" strokeWidth="2.4" strokeLinecap="round" />
      <circle cx="15" cy="18" r="1.4" fill="#fff" />
      <circle cx="23" cy="20.5" r="1" fill="#fff" />
      <circle cx="25" cy="15.5" r="1.2" fill="#fff" />
    </svg>
  );
}

/** D-204 — 펠트 위 모서리에서 풀려 내려오는 금 리본. */
function Ribbon() {
  return (
    <svg viewBox="0 0 80 170" focusable="false">
      <path
        d="M4 -4 C44 22 4 52 36 78 S28 132 62 166"
        pathLength={1}
        fill="none"
        stroke="#b8892f"
        strokeWidth="9"
        strokeLinecap="round"
      />
      <path
        d="M4 -4 C44 22 4 52 36 78 S28 132 62 166"
        pathLength={1}
        fill="none"
        stroke="#f6e2a4"
        strokeWidth="5"
        strokeLinecap="round"
      />
    </svg>
  );
}

interface ChipFlight {
  id: number;
  x: number;
  y: number;
  dx: number;
  dy: number;
  /** 칸 한가운데(뷰포트 좌표) — 꽂히는 불티 자리. */
  hitX: number;
  hitY: number;
  points: number;
}

interface Party {
  nonce: number;
  /** 요트 = 큰 축하, mid = 라지 스트레이트·풀 하우스, bonus = 상단 63 보너스가 처음 붙음. */
  kind: 'yacht' | 'mid' | 'bonus';
  category: Category;
  seat: number;
  points: number;
  /** 현수막에 놓을 주사위(mid·yacht) — 기억한 굴림이 족보와 안 맞으면 null. */
  dice: number[] | null;
  /** 펠트가 화면 밖이라 축하 층을 화면에 띄운다. */
  float: boolean;
  /** bonus — 보너스 칸 한가운데(뷰포트 좌표). */
  cellX: number;
  cellY: number;
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
 * 얏추(코드 이름 요트 — D-207 표시 이름만 바꿈) 게임판 조립 루트(요트 S4, 설계 `docs/plans/yacht.md` §7). 원카드처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103).
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
  const { muted, toggleMute, playChime, playYacht, playMade, playRoll, startShake } = useSfx();
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
  // D-205 — 좌석을 눌러 그 사람 판 보기(모두 같이 굴릴 때만 — 차례대로면 가운데 판이 이미 차례인 사람 것이다). 보기만 한다:
  // 구경하는 동안에는 내 굴리기·고정·기록을 아예 보내지 않는다. 쓰는 값은 전부 이미 받은 공개 상태(`seatTurns`)다.
  const [watchSeat, setWatchSeat] = useState<number | null>(null);
  const canWatch = simultaneous && s.result === null;
  const watchable = (seat: number) =>
    canWatch && seat !== mySeat && s.seatTurns[seat] !== undefined && !(s.seats.find((x) => x.seat === seat)?.deserted ?? false);
  const watching = watchSeat !== null && watchable(watchSeat) ? watchSeat : null;
  const watched = watching !== null ? s.seatTurns[watching] : null;
  const canHold = myTurn && watching === null && connected && s.rolls >= 1 && s.rolls < MAX_ROLLS;
  const canRoll = myTurn && watching === null && connected && s.rolls < MAX_ROLLS && !(s.rolls > 0 && s.held.every(Boolean));
  const canScore = myTurn && watching === null && connected && s.rolls >= 1;
  // D-201 — 판 시작 때의 내 최고 점수(종료 화면이 "새 최고 기록!"을 가른다). 관전자·게스트는 묻지 않는다.
  const guest = isGuestName(usernames[myUserId]);
  const previousBest = useYachtBest(token, myUserId, !spectator && !guest);

  // D-264 — 휴대폰 흔들어 굴리기는 ⋮ 메뉴에서 켠다(터치 기기 + DeviceMotion 이 있고 내가 앉아 있을 때만 항목이 있다). iOS 는 그 항목을
  // 누른 자리에서 권한을 묻는다 — 거절·미지원이면 안내만 하고 [흔들기]·[굴리기]는 그대로다.
  const [motionShake, setMotionShake] = useState(loadMotionPreference);
  const motionOffered = !spectator && mySeat >= 0 && motionShakeSupported();
  const toggleMotionShake = () => {
    if (motionShake) {
      setMotionShake(false);
      saveMotionPreference(false);
      return;
    }
    void requestMotionPermission().then((ok) => {
      if (!ok) {
        showToast('이 기기에서는 휴대폰 흔들기를 쓸 수 없어요 · [흔들기]를 꾹 눌러 주세요');
        return;
      }
      setMotionShake(true);
      saveMotionPreference(true);
      showToast('내 차례에 휴대폰을 흔들다 멈추면 주사위를 굴려요');
    });
  };

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

  // D-205 — 구경하다 새 라운드가 시작되면(내가 다시 굴릴 차례) 바로 내 판으로 돌아온다. 위 알림음·진동과 같은 순간이다.
  const wasMyTurnForWatch = useRef(myTurn);
  useEffect(() => {
    if (myTurn && !wasMyTurnForWatch.current) setWatchSeat(null);
    wasMyTurnForWatch.current = myTurn;
  }, [myTurn]);
  // 아직 기록하지 않은 채 구경 중이면 마감 `WATCH_RETURN_BEFORE_MS` 전에 내 판으로 되돌리고 알린다(시간 제한이 없는 방은 버튼 문구로만 알린다).
  const watchingOnMyTurn = watching !== null && myTurn;
  const clockForWatch = s.turnClock;
  useEffect(() => {
    if (!watchingOnMyTurn || turnSeconds <= 0 || clockForWatch === null) return;
    const fullTurnMs = turnSeconds * 1000;
    const deadline = clockForWatch.since + Math.min(clockForWatch.remainingMs ?? fullTurnMs, fullTurnMs);
    const timer = window.setTimeout(() => {
      setWatchSeat(null);
      showToast('시간이 얼마 남지 않아 내 판으로 돌아왔어요');
    }, Math.max(0, deadline - WATCH_RETURN_BEFORE_MS - Date.now()));
    return () => window.clearTimeout(timer);
  }, [watchingOnMyTurn, clockForWatch, turnSeconds]);
  // 구경하는 판의 굴림 신호 — 그 사람의 굴림 수가 오를 때만 가운데 주사위가 구른다(판을 바꿔 볼 때는 구르지 않는다).
  const watchRoll = useRef({ seat: -1, rolls: 0, nonce: 0 });
  if (watching !== null && watched) {
    const w = watchRoll.current;
    if (w.seat === watching && watched.rolls > w.rolls) w.nonce += 1;
    w.seat = watching;
    w.rolls = watched.rolls;
  } else {
    watchRoll.current.seat = -1;
  }
  const selectSeat = (seat: number) => setWatchSeat(seat === mySeat || seat === watching ? null : seat);

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
          points: last.points,
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

  // D-204 — 좌석마다 마지막 굴림을 화면이 기억한다(기록하면 스토어의 주사위는 바로 비므로 축하 현수막이 이것을 쓴다). 그림용일 뿐이다.
  const seenDice = useRef<Record<number, number[]>>({});
  if (s.simultaneous) {
    for (const t of Object.values(s.seatTurns)) if (t.dice) seenDice.current[t.seat] = t.dice;
  } else if (s.dice && s.turnSeat >= 0) {
    seenDice.current[s.turnSeat] = s.dice;
  }
  // D-204 — 좌석마다 직전 상단 보너스. 0 에서 처음 붙는 기록만 축하한다(처음 그릴 때·다시 받을 때는 모름 = 축하 없음).
  const seenBonus = useRef<Record<number, number>>({});

  // D-203 — 본 축하는 요트 칸에 점수를 적을 때(5개가 같게 멈춘 순간은 주사위 반짝임만). D-204 — 라지 스트레이트·풀 하우스는 중간 축하,
  // 상단 보너스가 처음 붙는 기록은 보너스 축하(위 칸 기록이라 족보 축하와 겹치지 않는다).
  // D-206 — 펠트·가운데 판의 축하는 <b>내 기록</b>만이다. 남의 기록은 요트(0점 아님)일 때만 그 사람 좌석 명패에서 작게 알리고
  // (`seatParty`), 남의 라지 스트레이트·풀 하우스·보너스는 축하하지 않는다.
  const feltRef = useRef<HTMLDivElement>(null);
  const [party, setParty] = useState<Party | null>(null);
  const [seatParty, setSeatParty] = useState<{ seat: number; nonce: number; points: number } | null>(null);
  // D-208 — 내 굴림이 족보로 멈춘 순간의 알림(고리·꼬리표·짧은 소리). 굴림 하나에 한 번.
  const [made, setMade] = useState<(MadeHand & { nonce: number; x: number; y: number }) | null>(null);
  const madeTimer = useRef(0);
  const madeStopTimer = useRef(0);
  // 걷는 타이머는 효과 정리에 맡기지 않는다 — 축하가 떠 있는 동안 축하 없는 다음 기록이 오면 정리가 타이머만 지워 축하가 남는다
  // (모두 같이 굴리면 기록이 잇달아 온다).
  const partyTimer = useRef(0);
  const seatPartyTimer = useRef(0);
  useEffect(
    () => () => {
      window.clearTimeout(partyTimer.current);
      window.clearTimeout(seatPartyTimer.current);
      window.clearTimeout(madeTimer.current);
      window.clearTimeout(madeStopTimer.current);
    },
    [],
  );

  // D-208 — 굴림 신호(`rollNonce`)가 <b>오를 때만</b> 반응한다. 스토어는 모듈 전역이라 다시 마운트돼도 지난 값이 남고(값이 아니라
  // 변화에만), 고정 변경·다시 받기(resync)·다시 연결은 신호를 올리지 않는다. 굴림 연출이 끝나는 때에 스토어의 지금 값을 읽어 판정한다 —
  // 그 사이 기록했거나(주사위가 빔) 다음 굴림이 왔으면 건너뛴다. 족보인지는 서버 후보 점수가 정한다(`madeHand`).
  const rollNonce = s.rollNonce;
  const handledRollNonce = useRef(rollNonce);
  // 멈추는 순간의 나(좌석·구경 여부)를 읽는다 — 굴린 뒤 남의 판을 열었으면 알리지 않는다.
  const madeGate = useRef({ mySeat, watching });
  madeGate.current = { mySeat, watching };
  useEffect(() => {
    const before = handledRollNonce.current;
    handledRollNonce.current = rollNonce;
    if (rollNonce <= before) return;
    window.clearTimeout(madeStopTimer.current);
    window.clearTimeout(madeTimer.current);
    setMade(null);
    madeStopTimer.current = window.setTimeout(
      () => {
        const st = useYachtStore.getState();
        const gate = madeGate.current;
        if (st.rollNonce !== rollNonce || st.result !== null || !st.dice || st.rolls < 1) return;
        // 내 굴림만 — 차례대로면 지금 차례가 나, 모두 같이면 가운데 판(굴림 신호)이 내 좌석 것.
        if (gate.mySeat < 0 || gate.watching !== null) return;
        if (st.simultaneous ? st.ownSeat !== gate.mySeat : st.turnSeat !== gate.mySeat) return;
        const hand = madeHand(st.dice, st.candidates);
        if (!hand) return;
        // 소리는 연출 설정과 무관하게 음소거만 따른다(D-207 과 같다).
        playMade(hand.tier);
        const r = rootRef.current?.querySelector('.yt-dice')?.getBoundingClientRect();
        setMade({
          ...hand,
          nonce: rollNonce,
          x: r ? r.left + r.width / 2 : window.innerWidth / 2,
          y: r ? r.top + r.height / 2 : window.innerHeight / 2,
        });
        madeTimer.current = window.setTimeout(() => setMade(null), MADE_FX_MS[hand.tier]);
      },
      animate ? ROLL_ANIMATION_MS : 0,
    );
    // 굴림 하나에 한 번만 — 타이머는 다음 굴림·언마운트가 걷는다(효과 정리에 맡기면 다른 값이 바뀔 때 알림이 사라진다).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rollNonce]);
  useEffect(() => {
    const last = s.lastScore;
    if (!last) return;
    // D-208 — 내가 기록하면 굴림 알림은 바로 걷는다(기록 축하와 겹치지 않게).
    if (last.seat === mySeat) {
      window.clearTimeout(madeStopTimer.current);
      window.clearTimeout(madeTimer.current);
      setMade(null);
    }
    if (last.seat !== mySeat) {
      if (last.category !== 'YACHT' || last.points <= 0) return;
      // D-207 — 남의 요트는 작은 두 음만(명패 꼬리표와 같이). 연출 설정과 무관하게 음소거만 따른다.
      playYacht(false);
      setSeatParty({ seat: last.seat, nonce: last.nonce, points: last.points });
      window.clearTimeout(seatPartyTimer.current);
      seatPartyTimer.current = window.setTimeout(() => setSeatParty(null), YACHT_PARTY_MS);
      return;
    }
    const seatNow = useYachtStore.getState().seats.find((x) => x.seat === last.seat);
    const bonusNew = seenBonus.current[last.seat] === 0 && (seatNow?.bonus ?? 0) > 0;
    const kind: Party['kind'] | null =
      last.points > 0 && last.category === 'YACHT'
        ? 'yacht'
        : last.points > 0 && MID_CATEGORIES.has(last.category)
          ? 'mid'
          : bonusNew
            ? 'bonus'
            : null;
    if (!kind) return;
    // D-207 — 내 요트는 팡파르. 움직임을 끈 화면에서도 소리는 난다(음소거만 따른다).
    if (kind === 'yacht') playYacht(true);
    const f = feltRef.current?.getBoundingClientRect();
    const cell = rootRef.current?.querySelector(`[data-yt-bonus="${last.seat}"]`)?.getBoundingClientRect();
    // 펠트 한가운데가 화면 안에 있나 — 아니면 화면 위쪽에 띄운다.
    const float = f ? f.top + f.height / 2 < 0 || f.top + f.height / 2 > window.innerHeight : false;
    setParty({
      nonce: last.nonce,
      kind,
      float,
      category: last.category,
      seat: last.seat,
      points: kind === 'bonus' ? (seatNow?.bonus ?? 0) : last.points,
      dice: kind !== 'bonus' ? celebrationDice(last.category, seenDice.current[last.seat]) : null,
      cellX: cell ? cell.left + cell.width / 2 : 0,
      cellY: cell ? cell.top + cell.height / 2 : 0,
      x: f && !float ? f.left + f.width / 2 : window.innerWidth / 2,
      y: f && !float ? f.top + f.height * 0.45 : window.innerHeight * 0.3,
      width: f && !float ? f.width : window.innerWidth,
    });
    window.clearTimeout(partyTimer.current);
    partyTimer.current = window.setTimeout(() => setParty(null), kind === 'yacht' ? YACHT_PARTY_MS : MID_PARTY_MS);
    // 기록 하나에 한 번만(위 기록 강조와 같다).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.lastScore]);
  // 위 효과가 직전 값을 읽은 뒤에 갱신한다(같은 커밋에서 선언 순서대로 돈다).
  useEffect(() => {
    for (const x of s.seats) seenBonus.current[x.seat] = x.bonus;
  }, [s.seats]);

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

  const bonusParty = party?.kind === 'bonus' ? party : null;
  // D-208 — 그 굴림이 아직 내 판에 떠 있을 때만(다음 굴림·기록·남의 판 구경이면 걷힌다).
  const madeShown = made && watching === null && myTurn && s.rollNonce === made.nonce && s.dice !== null ? made : null;
  // 가운데 판이 비추는 굴림 수 — 구경 중이면 그 사람 것.
  const trayRolls = watched ? watched.rolls : s.rolls;
  const announcement = flash
    ? `${nameOf(flash.seat)} ${CATEGORY_LABEL[flash.category]} ${flash.points}점${flash.auto ? ' (시간 초과 자동 기록)' : ''}${bonusParty ? ` · 상단 보너스 +${bonusParty.points}` : ''}`
    : madeShown
      ? `${CATEGORY_LABEL[madeShown.category]}!`
      : '';
  const fx = salonTheme && animate ? party : null;
  const partyName = party ? (party.seat === mySeat ? '나' : nameOf(party.seat)) : '';

  // D-204 — 축하 층. 펠트가 화면에 보이면 펠트 안에, 휴대폰에서 점수표 아래쪽을 누르느라 펠트가 화면 밖이면 화면 위쪽에 띄운다
  // (`yt-fx-float` — 스크롤을 건드리지 않는다, 조작을 막지 않는다).
  const fxLayer = (
    <>
        {fx && fx.kind === 'yacht' && (
          <div
            className={`yt-fx yt-fx-yacht${fx.dice ? ' yt-fx-yacht-dice' : ''}${fx.float ? ' yt-fx-float' : ''}`}
            key={fx.nonce}
            aria-hidden="true"
          >
            <span className="yt-fx-rays" />
            <span className="yt-fx-flash" />
            {/* D-207 — 다섯 음이 끝나는 박자에 두 번째 섬광과 금빛 충격 고리 둘. */}
            <span className="yt-fx-flash yt-fx-flash-2" />
            <span className="yt-fx-ring" />
            <span className="yt-fx-ring yt-fx-ring-2" />
            <span className="yt-fx-sweep" />
            {/* D-204 — 샴페인 기포가 아래에서 올라오고, 금 리본이 두 모서리에서 풀리고, 금 띠 조각이 흩날린다. */}
            <span className="yt-fx-bubbles">
              {Array.from({ length: BUBBLES }, (_, i) => (
                <i
                  key={i}
                  style={
                    {
                      left: `${(i * 37 + 9) % 96}%`,
                      '--yt-b-size': `${5 + ((i * 7) % 9)}px`,
                      '--yt-b-delay': `${i * 75}ms`,
                      '--yt-b-dur': `${1300 + (i % 5) * 160}ms`,
                      '--yt-b-drift': `${((i * 13) % 31) - 15}px`,
                    } as CSSProperties
                  }
                />
              ))}
            </span>
            <span className="yt-fx-ribbon yt-fx-ribbon-l">
              <Ribbon />
            </span>
            <span className="yt-fx-ribbon yt-fx-ribbon-r">
              <Ribbon />
            </span>
            <span className="yt-fx-streamers">
              {Array.from({ length: STREAMERS }, (_, i) => (
                <i
                  key={i}
                  style={
                    {
                      left: `${6 + i * 12.5}%`,
                      '--yt-st-delay': `${120 + ((i * 5) % 8) * 70}ms`,
                      '--yt-st-turn': `${i % 2 === 0 ? 540 : -460}deg`,
                      '--yt-st-drift': `${((i * 17) % 41) - 20}px`,
                    } as CSSProperties
                  }
                />
              ))}
            </span>
            {/* 현수막은 clip-path 로 펼쳐지므로 잔은 그 밖(위)에 둔다. */}
            <span className="yt-fx-coupes">
              <span className="yt-fx-coupe yt-fx-coupe-l">
                <Coupe />
              </span>
              <span className="yt-fx-coupe yt-fx-coupe-r">
                <Coupe />
              </span>
            </span>
            <div className="yt-fx-banner">
              <b>요트</b>
              {/* D-207 — 그 굴림의 같은 눈 다섯 개가 효과음 다섯 음과 같은 박자로 하나씩 놓인다. */}
              {fx.dice && (
                <span className="yt-fx-dice yt-fx-dice-five">
                  {fx.dice.map((d, i) => (
                    <span key={i} className="yt-fx-die" style={{ ['--yt-i' as string]: i } as CSSProperties}>
                      <YachtDie face={d} mini />
                    </span>
                  ))}
                </span>
              )}
              <i>
                {partyName} · {fx.points}점
              </i>
            </div>
          </div>
        )}
        {fx && fx.kind !== 'yacht' && (
          <div className={`yt-fx yt-fx-${fx.kind}${fx.float ? ' yt-fx-float' : ''}`} key={fx.nonce} aria-hidden="true">
            <span className="yt-fx-sweep" />
            <div className="yt-fx-banner yt-fx-banner-mid">
              <b>{fx.kind === 'bonus' ? '상단 보너스' : CATEGORY_LABEL[fx.category]}</b>
              {fx.dice && (
                <span className={`yt-fx-dice${fx.category === 'LARGE_STRAIGHT' ? ' yt-fx-dice-stair' : ' yt-fx-dice-house'}`}>
                  {fx.dice.map((d, i) => (
                    <span key={i} className="yt-fx-die" style={{ ['--yt-i' as string]: i } as CSSProperties}>
                      <YachtDie face={d} mini />
                    </span>
                  ))}
                </span>
              )}
              <i>
                {partyName} · {fx.kind === 'bonus' ? `+${fx.points}` : `${fx.points}점`}
              </i>
            </div>
          </div>
        )}
    </>
  );

  return (
    <div className={`yt-table${yachtThemeClass}`} ref={rootRef}>
      <header className="yt-header gm-header">
        <div className="yt-header-badges gm-header-info">
          <GameIcon gameId="YACHT" size={22} />
          <span className="yt-badge yt-badge-title">얏추</span>
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
            toggles={
              motionOffered
                ? [{ key: 'motion-shake', label: '휴대폰 흔들어 굴리기', checked: motionShake, onToggle: toggleMotionShake }]
                : undefined
            }
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

      {/* 좌석 띠 — 점수표 열과 같은 좌석 순서. 차례인 좌석은 테두리.
          D-205 — 명패 높이는 늘 같다: 이름·점수 한 줄(+ 모두 같이면 주사위 줄 한 칸)뿐이고, "차례"·"끊김"·"탈주"·내보내기·채팅 한 줄은
          전부 명패 모서리에 걸친 겹침 층이라 붙었다 사라져도 아래 주사위 판·점수표가 밀리지 않는다. "기록함 ✓" 는 주사위 줄 자리에 들어간다. */}
      <ol className="yt-seats" aria-label="좌석">
        {s.seats.map((seat) => {
          const uid = playerIds[seat.seat];
          const isTurn = seat.seat === turnSeat;
          // D-202 — 동시 진행: 남의 주사위를 좌석 띠에 작게 실시간으로, 이번 라운드를 기록했으면 "기록함".
          const seatTurn = simultaneous ? s.seatTurns[seat.seat] : undefined;
          const done = simultaneous && !seat.deserted && seatTurn !== undefined && !seatTurn.pending;
          const disconnected = s.disconnectedSeats.has(seat.seat);
          const v = voiceStatusOf(voice, uid);
          const isMe = seat.seat === mySeat;
          const isWatched = seat.seat === watching;
          // 누를 수 있는 명패 — 남의 명패는 그 사람 판 보기, 내 명패는 (구경 중일 때) 내 판으로.
          const selectable = watchable(seat.seat) || (isMe && watching !== null);
          const yachtParty = seatParty?.seat === seat.seat ? seatParty : null;
          // 내 주사위는 가운데 판에 있다 — 남의 판을 구경하는 동안에만 내 명패에 작게 보인다.
          const showDice = seatTurn?.pending && (!isMe || watching !== null);
          const body = (
            <>
              <span className="yt-seat-head">
                <span className="yt-seat-chip" aria-hidden="true" />
                <span className="yt-seat-name">
                  {tiers[uid] && <TierBadge tier={tiers[uid]} size={16} />}
                  {nameOf(seat.seat)}
                  {isMe ? ' (나)' : ''}
                </span>
                {v === 'on' && (
                  <span className="yt-seat-voice" role="img" aria-label="음성 참여 중">
                    🎧
                  </span>
                )}
                <span className={`yt-seat-total${flash?.seat === seat.seat ? ' yt-seat-total-bump' : ''}`}>{seat.total}점</span>
              </span>
              {/* 동시 진행에서는 주사위 줄 자리를 늘 둔다 — 누가 기록해 줄이 사라질 때 좌석 띠 높이가 줄어 아래 주사위 판이 위로 밀리면
                  누르려던 [굴리기]가 빗나간다. 기록한 좌석은 같은 자리에 "기록함 ✓". */}
              {simultaneous &&
                (showDice && seatTurn ? (
                  <SeatDice turn={seatTurn} animate={animate} />
                ) : done ? (
                  <span className="yt-seat-dice yt-seat-dice-done">
                    <span className="yt-seat-tag">기록함 ✓</span>
                  </span>
                ) : (
                  <span className="yt-seat-dice yt-seat-dice-empty" aria-hidden="true" />
                ))}
            </>
          );
          return (
            <li
              key={seat.seat}
              className={`yt-seat${isMe ? ' yt-seat-me' : ''}${isTurn ? ' yt-seat-turn' : ''}${seat.deserted ? ' yt-seat-out' : ''}${done ? ' yt-seat-done' : ''}${isWatched ? ' yt-seat-watch' : ''}${yachtParty && animate ? ' yt-seat-party' : ''}`}
              style={{ ['--yt-accent' as string]: seatAccent(seat.seat) }}
              data-yt-seat={seat.seat}
            >
              {selectable ? (
                <button
                  type="button"
                  className="yt-seat-view yt-seat-pick"
                  aria-pressed={isMe ? false : isWatched}
                  title={isMe ? '내 판으로 돌아가기' : isWatched ? '그만 보기' : `${nameOf(seat.seat)} 판 보기`}
                  onClick={() => selectSeat(seat.seat)}
                >
                  {body}
                </button>
              ) : (
                <div className="yt-seat-view">{body}</div>
              )}
              {/* 겹침 층 — 줄을 차지하지 않는다(D-205). */}
              {isTurn && <span className="yt-seat-tag yt-seat-tag-turn">차례</span>}
              {yachtParty && (
                <span key={yachtParty.nonce} className="yt-seat-tag yt-seat-yacht">
                  요트! {yachtParty.points}점
                </span>
              )}
              <span className="yt-seat-flags">
                {isWatched && <span className="yt-seat-tag yt-seat-tag-watch">보는 중</span>}
                {seat.deserted && <span className="yt-seat-tag">탈주</span>}
                {disconnected && !seat.deserted && <span className="yt-seat-tag">끊김</span>}
                {onKick && disconnected && !roomFinished && !isMe && !seat.deserted && (
                  <button type="button" className="yt-seat-kick" onClick={() => onKick(uid)}>
                    내보내기
                  </button>
                )}
              </span>
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
        className={`yt-felt${myTurn && watching === null ? ' yt-felt-myturn' : ''}${watching !== null ? ' yt-felt-watch' : ''}${fx ? (fx.kind === 'yacht' ? ' yt-felt-party' : ' yt-felt-cheer') : ''}`}
        style={
          watching !== null
            ? { ['--yt-accent' as string]: seatAccent(watching) }
            : myTurn
              ? { ['--yt-accent' as string]: seatAccent(mySeat) }
              : undefined
        }
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
          {watching !== null && <span className="yt-badge yt-badge-watch">{nameOf(watching)} 판 보는 중</span>}
          {/* D-206 — 상태 줄 축하 배지도 내 기록만. */}
          {flash && flash.seat === mySeat && BIG_CATEGORIES.has(flash.category) && flash.points > 0 && (
            <span className="yt-badge yt-celebrate">🎉 {CATEGORY_LABEL[flash.category]}!</span>
          )}
          {bonusParty && <span className="yt-badge yt-celebrate">보너스 +{bonusParty.points}!</span>}
          {!roomFinished && s.result === null && (turnSeat >= 0 || myTurn || watched?.pending) && (
            <span className="yt-rolls" aria-hidden="true" title={`굴림 ${trayRolls}/${MAX_ROLLS}`}>
              {Array.from({ length: MAX_ROLLS }, (_, i) => (
                <span key={i} className={`yt-rolls-dot${i < trayRolls ? ' yt-rolls-dot-used' : ''}`} />
              ))}
            </span>
          )}
        </div>
        {fx && !fx.float && fxLayer}
        <DiceTray
          dice={watched ? watched.dice : s.dice}
          held={watched ? (watched.held ?? NO_HOLD) : s.held}
          rolls={trayRolls}
          rollNonce={watched ? watchRoll.current.nonce : s.rollNonce}
          viewKey={watching ?? -1}
          canHold={canHold}
          canRoll={canRoll}
          animate={animate}
          onToggle={watching === null ? toggleHold : undefined}
          onRoll={myTurn && watching === null ? roll : undefined}
          // D-264 — 가운데 판에서 구르는 굴림의 소리. 내 굴림은 또렷하게, 가운데 판에 보이는 남의 굴림(차례대로의 남의 차례·남의 판
          // 구경)은 작게. 모두 같이 굴릴 때 좌석 띠에서만 구르는 남의 굴림은 소리가 없다. 연출 설정과 무관하게 음소거만 따른다.
          onRollSound={() => playRoll(mySeat >= 0 && watching === null && (simultaneous || turnSeat === mySeat))}
          onShakeSound={startShake}
          motionShake={motionOffered && motionShake}
          turnName={turnSeat >= 0 ? nameOf(turnSeat) : null}
          note={
            s.result !== null || roomFinished
              ? '판이 끝났습니다'
              : watching !== null && watched
                ? watched.pending
                  ? `${nameOf(watching)} · 굴림 ${watched.rolls}/${MAX_ROLLS}`
                  : `${nameOf(watching)} · 기록함 ✓`
                : simultaneous && !myTurn
                  ? mySeat >= 0 && !iAmOut
                    ? '기록했어요 · 다른 사람을 기다리는 중'
                    : '모두 같이 굴리는 중 · 좌석을 누르면 그 판을 봐요'
                  : undefined
          }
          onBack={watching !== null ? () => setWatchSeat(null) : undefined}
          backLabel={spectator || mySeat < 0 ? '그만 보기' : myTurn ? '내 차례 · 내 판으로' : '내 판으로'}
          backUrgent={myTurn}
          made={
            madeShown
              ? {
                  nonce: madeShown.nonce,
                  tier: madeShown.tier,
                  label: `${CATEGORY_LABEL[madeShown.category]}!`,
                  indices: madeShown.indices,
                  ms: MADE_FX_MS[madeShown.tier],
                }
              : null
          }
        />
      </div>

      <YachtScorecard
        seats={s.seats}
        turnSeat={watching !== null ? watching : simultaneous ? (myTurn ? mySeat : -1) : turnSeat}
        mySeat={mySeat}
        candidates={watched ? (watched.pending ? watched.candidates : {}) : s.candidates}
        nameOf={nameOf}
        onScore={canScore ? score : undefined}
        lastScore={flash}
        bonusSeat={bonusParty ? bonusParty.seat : null}
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

      {fx && fx.float && fxLayer}

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
                animationDelay: `${k * 32}ms`,
                opacity: k > 0 ? 0.5 - k * 0.12 : undefined,
              } as CSSProperties
            }
          >
            {/* D-204 — 날아가는 것은 점수 숫자다(황동 원반 위). 잔상은 빛만. */}
            {k === 0 && <b>{chip.points}</b>}
          </span>
        ))}
      {/* 숫자가 꽂히는 자리의 불티 — 요트 축하 때는 큰 폭발이 대신한다. */}
      {chip && party?.kind !== 'yacht' && (
        <GoldBurst key={`hit:${chip.id}`} x={chip.hitX} y={chip.hitY} count={14} power={0.38} delayMs={CHIP_FLIGHT_MS - 40} />
      )}
      {fx && fx.kind === 'yacht' && (
        <>
          <GoldBurst key={`p0:${fx.nonce}`} x={fx.x} y={fx.y} count={150} />
          <GoldBurst key={`p1:${fx.nonce}`} x={fx.x - fx.width * 0.3} y={fx.y + 10} count={45} power={0.7} delayMs={260} />
          <GoldBurst key={`p2:${fx.nonce}`} x={fx.x + fx.width * 0.3} y={fx.y + 10} count={45} power={0.7} delayMs={460} />
          <GoldBurst key={`p3:${fx.nonce}`} x={fx.x} y={fx.y - 6} count={70} power={0.85} delayMs={900} />
        </>
      )}
      {/* D-208 — 요트로 멈춘 순간의 작은 금가루(기록할 때의 큰 폭발과 다르다). */}
      {madeShown && madeShown.tier === 'big' && salonTheme && animate && (
        <GoldBurst key={`made:${madeShown.nonce}`} x={madeShown.x} y={madeShown.y} count={28} power={0.45} />
      )}
      {/* 현수막 글자를 가리지 않게 조금 아래에서. */}
      {fx && fx.kind === 'mid' && <GoldBurst key={`m:${fx.nonce}`} x={fx.x} y={fx.y + 48} count={56} power={0.6} delayMs={120} />}
      {fx && fx.kind === 'bonus' && fx.cellX > 0 && (
        <GoldBurst key={`b:${fx.nonce}`} x={fx.cellX} y={fx.cellY} count={36} power={0.5} delayMs={CHIP_FLIGHT_MS + 260} />
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
