import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { useEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { REACTIONS, useLiveReactions } from '@/features/chat/reactionStore';
import { useSeatChatLines } from '@/features/chat/useSeatChatLines';
import { useSfx } from '@/features/sound/useSfx';
import { useVoiceChat, voiceStatusOf } from '@/features/voice/useVoiceChat';
import { VoiceControls } from '@/features/voice/VoiceControls';
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

/** 좌석 색 — 점수표 열·좌석 띠·내 차례 테두리. */
const SEAT_ACCENTS = ['#5b8def', '#e85d75', '#3fa45b', '#e0b32a'];
export const seatAccent = (seat: number) => SEAT_ACCENTS[((seat % 4) + 4) % 4];

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
  const [reactionOpen, setReactionOpen] = useState(false);
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

  // 기록 강조는 잠깐만.
  const [flash, setFlash] = useState<typeof s.lastScore>(null);
  useEffect(() => {
    if (!s.lastScore) return;
    setFlash(s.lastScore);
    const timer = window.setTimeout(() => setFlash(null), SCORE_FLASH_MS);
    return () => window.clearTimeout(timer);
  }, [s.lastScore]);

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
    <div className="yt-table">
      <header className="yt-header">
        <div className="yt-header-badges">
          <span className="yt-badge yt-badge-title">요트</span>
          <span className="yt-badge">{seatCount}인</span>
          <span className="yt-badge">라운드 {Math.min(s.round, 12)}/12</span>
          {practice && <span className="yt-badge">🤖 연습</span>}
          {s.simultaneous && <span className="yt-badge">모두 같이</span>}
          {turnSeconds > 0 && (
            <span className="yt-badge">
              {s.simultaneous ? '라운드' : '차례'} {turnSeconds}초
            </span>
          )}
          {spectatorCount > 0 && <span className="yt-badge">관전 {spectatorCount}</span>}
          {spectator && <span className="yt-badge">관전 모드</span>}
          <span className="yt-badge">{connected ? '● 연결' : '○ 끊김'}</span>
        </div>
        <div className="yt-header-badges">
          <button
            type="button"
            className="yt-badge"
            onClick={toggleMute}
            aria-label="효과음"
            aria-pressed={!muted}
            title={muted ? '효과음 켜기' : '효과음 끄기'}
          >
            {muted ? '🔇' : '🔊'}
          </button>
          <VoiceControls voice={voice} buttonClassName="yt-badge" />
          {!spectator && (
            <span className="yt-reaction-bar">
              <button
                type="button"
                className="yt-badge"
                onClick={() => setReactionOpen((v) => !v)}
                aria-label="이모지 반응"
                aria-expanded={reactionOpen}
              >
                😀
              </button>
              {reactionOpen && (
                <span className="yt-reaction-palette">
                  {REACTIONS.map((e) => (
                    <button
                      type="button"
                      key={e}
                      onClick={() => {
                        sendReaction(e);
                        setReactionOpen(false);
                      }}
                    >
                      {e}
                    </button>
                  ))}
                </span>
              )}
            </span>
          )}
          <button type="button" className="yt-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <button type="button" className="yt-badge" onClick={rules.show} title="게임 방법 — 차례 시간은 계속 흐릅니다">
            규칙
          </button>
          {onAbort && !roomFinished && (
            <button type="button" className="yt-badge" onClick={onAbort}>
              게임 종료
            </button>
          )}
          {onExit && (
            <button type="button" className="yt-badge" onClick={exit}>
              나가기
            </button>
          )}
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
              className={`yt-seat${isTurn ? ' yt-seat-turn' : ''}${seat.deserted ? ' yt-seat-out' : ''}${done ? ' yt-seat-done' : ''}`}
              style={{ ['--yt-accent' as string]: seatAccent(seat.seat) }}
            >
              <span className="yt-seat-name">
                {tiers[uid] && <TierBadge tier={tiers[uid]} size={16} />}
                {nameOf(seat.seat)}
                {seat.seat === mySeat ? ' (나)' : ''}
              </span>
              <span className="yt-seat-total">{seat.total}점</span>
              {isTurn && <span className="yt-seat-tag">차례</span>}
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
        className={`yt-felt${myTurn ? ' yt-felt-myturn' : ''}`}
        style={myTurn ? { ['--yt-accent' as string]: seatAccent(mySeat) } : undefined}
      >
        <div className="yt-felt-status">
          {!roomFinished && (turnSeat >= 0 || simultaneous) && <YachtTurnCountdown turnSeconds={turnSeconds} />}
          {myTurn && <span className="yt-badge yt-badge-turn">내 차례</span>}
          {flash && BIG_CATEGORIES.has(flash.category) && flash.points > 0 && (
            <span className="yt-badge yt-celebrate">🎉 {CATEGORY_LABEL[flash.category]}!</span>
          )}
        </div>
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
          note={simultaneous && !myTurn ? (mySeat >= 0 && !iAmOut ? '기록했어요 · 다른 사람을 기다리는 중' : '모두 같이 굴리는 중') : undefined}
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
