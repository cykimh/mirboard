import { useEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { seatMinWidth, tableLayout, type TableSide } from '@/components/seatOrder';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { REACTIONS, useLiveReactions } from '@/features/chat/reactionStore';
import { useSfx } from '@/features/sound/useSfx';
import { useVoiceChat, voiceStatusOf } from '@/features/voice/useVoiceChat';
import { VoiceControls } from '@/features/voice/VoiceControls';
import { cardKey, type OneCardCard } from '@/types/onecard';
import type { RaceButtonSize } from '@/types/api';
import { onecardRoomSink } from './onecardRoomSink';
import { useOneCardStore, type PressAction } from './onecardStore';
import { isSuitChange, nextTurnSeat } from './onecardRules';
import { ELIMINATED_LABEL, OneCardSeat, seatAccent } from './OneCardSeat';
import { OneCardCardChip } from './OneCardCardChip';
import { OneCardCenter, raceResultText } from './OneCardCenter';
import { OneCardHand } from './OneCardHand';
import { OneCardMatchEnd } from './OneCardMatchEnd';
import { OneCardTurnCountdown } from './OneCardTurnCountdown';
import { RaceButton } from './RaceButton';
import { ONE_CARD_TUTORIAL } from './tutorial/onecardTutorial';
import { useCardPlayFlight } from './useCardPlayFlight';
import { useCardDrawFlight } from './useCardDrawFlight';

/** `BUSY` 로 거절된 누름을 다시 보내기까지의 간격. 창(기본 3초) 안에 두 번 재시도할 여유가 있다. */
export const PRESS_RETRY_DELAY_MS = 120;
/** "늦었어요"를 보여 주는 시간. */
export const LATE_NOTICE_MS = 1500;
/**
 * D-130 — 경쟁 창이 마감 뒤 이만큼 지나도 열려 있으면 해소 이벤트를 놓쳤거나 서버 타이머가 사라진 것으로 보고 권위 스냅샷을
 * 다시 받는다(서버 resync 는 진행 킥으로 사라진 타이머를 다시 건다). 폴링 주기·왕복 시간보다 넉넉히.
 */
export const STALE_RACE_GRACE_MS = 1500;
/**
 * D-140 — 턴 카운트다운이 0 이 된 뒤 이만큼(+ 무작위 0~{@link STALE_TURN_JITTER_MS}) 지나도 같은 차례면 시간 초과 처리를 못 받은
 * 것으로 보고 그 차례에 한 번 권위 스냅샷을 다시 받는다(서버 resync 는 진행 킥으로 사라진 턴 데드라인을 다시 건다). 지터는 폴러가
 * 밀린 순간 그 방의 모든 클라가 한꺼번에 resync 하지 않게.
 */
export const STALE_TURN_GRACE_MS = 2000;
export const STALE_TURN_JITTER_MS = 1000;

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
  /** D-136 — 봇과 연습 방(방의 fillWithBots). 머리 줄에 "🤖 혼자 연습". */
  practice?: boolean;
  /** D-135 — 방장 강제 종료(확인은 호출 측). RoomPage 가 방장이고 나머지가 모두 봇일 때만 넘긴다 — 없으면 버튼도 없다. */
  onAbort?: () => void;
  /** D-120 — 방은 FINISHED 인데 이 세션이 종료 전이를 봐서 게임판을 유지하는 중. 입력을 숨긴다. */
  roomFinished?: boolean;
  /** D-153 — 판이 끝나 방이 대기실로 돌아왔다(이 세션이 전이를 봐 결과 화면을 유지하는 중). [대기실로]가 부른다. */
  onBackToRoom?: () => void;
  /** D-161 — 방장이 대기실에서 고른 "원카드!/잡기!" 버튼 크기(방 설정). */
  raceButtonSize?: RaceButtonSize;
}

/**
 * 원카드 게임판 조립 루트 (D-129). 스컬킹처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103) — 다른 게임의 코드
 * 경로는 실행되지 않는다. 좌석 수의 권위값은 `tableView.seats.length` 이고 `playerIds` 는 이름·아바타 조회용이다.
 *
 * <p>상대는 테이블처럼 내 기준 왼쪽 옆줄 → 윗줄 → 오른쪽 옆줄에 시계 방향으로 앉고(`tableLayout`, D-132), 가운데 칸에 더미·방향
 * 화살표가 놓인다. 지금 차례는 테두리, 일반 진행 기준 다음 차례는 "다음" 표시다.
 *
 * <p>경쟁 창은 화면 위 오버레이 버튼(`RaceButton`)과 aria-live 알림으로 보여 준다. 락 경합 `BUSY` 를 받은 누름은
 * 창이 열린 동안 최대 2번 다시 보낸다(스토어가 신호를 올리고 여기서 보낸다).
 */
export function OneCardTable({
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
  raceButtonSize = 'MEDIUM',
}: Props) {
  const token = useAuthStore((s) => s.token);
  const {
    connected,
    sendAction,
    sendChat,
    sendReaction,
    sendVoice,
    subscribeVoice,
    chatPanelOpenRef,
    requestResync,
  } = useStompRoom(
    roomId,
    token,
    onecardRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  // D-154 — 티츄처럼 효과음(내 차례 알림음) 켜기/끄기와 이모지 반응.
  const { muted, toggleMute, playChime } = useSfx();
  const [reactionOpen, setReactionOpen] = useState(false);
  const liveReactions = useLiveReactions();
  // D-155 — 음성 대화(무료 P2P). 🎧 참여(듣기)·🎙 마이크. 좌석에는 참여 중 🎧 / 연결 실패 표시.
  const voice = useVoiceChat({ myUserId, connected, send: sendVoice, subscribe: subscribeVoice });
  const chatUnread = useRoomChatStore((s) => s.unreadCount);
  // 게임판에서는 수동으로만 연다 — 턴 타이머가 계속 흐른다(D-121).
  const rules = useTutorialGate(ONE_CARD_TUTORIAL.seenKey, false);

  const s = useOneCardStore();
  // D-139 — 카드 내기 비행 효과(낸 좌석 → 맨 위 카드). 카드 애니메이션 설정을 따른다.
  const tableRef = useRef<HTMLDivElement | null>(null);
  const cardAnimEnabled = useCardAnimStore((st) => st.enabled);
  const seatCount = s.seats.length || playerIds.length;
  const mySeat = spectator ? -1 : s.mySeat;
  const flight = useCardPlayFlight(tableRef, s.lastPlay, mySeat, cardAnimEnabled);
  const drawFlight = useCardDrawFlight(tableRef, s.lastDraw, mySeat, cardAnimEnabled);
  const { bottomSeat, slots } = tableLayout(seatCount, mySeat);
  const me = s.seats.find((x) => x.seat === mySeat) ?? null;
  // D-132 — 일반 진행 기준 다음 차례. 차례가 비면(카드 직후·경쟁 창·종료) 테두리와 함께 사라진다.
  const eliminated = Array.from(
    { length: seatCount },
    (_, i) => s.seats.find((x) => x.seat === i)?.eliminated != null,
  );
  // D-132 후속 m2 — 강제 종료는 결과 없이 방만 끝나 스토어의 차례가 남는다. 끝난 판에는 차례(테두리·"다음"·"내 차례"·가운데 이름)가 없다.
  const turnSeat = roomFinished ? -1 : s.turnSeat;
  const nextSeat = nextTurnSeat(turnSeat, s.direction, eliminated);
  const iAmOut = me?.eliminated != null;
  const raceOpen = s.race !== null;
  const myTurn =
    !spectator && mySeat >= 0 && !iAmOut && s.result === null && !raceOpen && turnSeat === mySeat;
  const canPress = !spectator && mySeat >= 0 && !iAmOut && s.result === null && !roomFinished;

  // D-154 — 내 차례가 되면 짧은 알림음(음소거면 무음). D-160 — 휴대폰은 한 번 짧게 진동한다(효과음 끄기를 따른다).
  const wasMyTurn = useRef(myTurn);
  useEffect(() => {
    if (myTurn && !wasMyTurn.current) {
      playChime();
      if (!muted) navigator.vibrate?.(120);
    }
    wasMyTurn.current = myTurn;
  }, [myTurn, playChime, muted]);

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };

  const seatAt = (seat: number, side: TableSide | 'bottom', slot: number) => {
    const view = s.seats.find((x) => x.seat === seat);
    if (!view) return null;
    return (
      <OneCardSeat
        key={seat}
        seat={view}
        userId={playerIds[seat]}
        username={usernames[playerIds[seat]]}
        isBot={botSeats.includes(seat)}
        isTurn={turnSeat === seat}
        isNext={nextSeat === seat}
        isDisconnected={s.disconnectedSeats.has(seat)}
        side={side}
        slot={slot}
        reaction={liveReactions[seat]?.emoji}
        voice={voiceStatusOf(voice, playerIds[seat])}
      />
    );
  };
  // 옆줄마다 묶는다 — 좌석 DOM 순서가 slots 순서(= 진행 순서)라 스크린리더가 다음 사람부터 읽는다.
  const sideRow = (side: TableSide) => {
    const row = slots.filter((x) => x.side === side);
    if (row.length === 0) return null;
    return <div className={`oc-felt-${side}`}>{row.map((x) => seatAt(x.seat, side, x.index))}</div>;
  };

  // BUSY 재시도 — 스토어가 신호(retryNonce)를 올리면 기다리던 누름을 다시 보낸다. 지금도 이 창을 기다리는 누름만
  // 보낸다: 그 사이 남이 이겨 `lost` 가 됐거나 창이 닫혔으면 서버가 NO_RACE 로 거절할 뿐이다. 클라 시계 마감은 보지
  // 않는다 — 서버는 창 끝 직후에 처리한 누름도 인정한다(D-128).
  useEffect(() => {
    if (s.retryNonce === 0) return;
    const timer = window.setTimeout(() => {
      const { press, race } = useOneCardStore.getState();
      if (press && !press.lost && race?.raceId === press.raceId) {
        sendAction({ '@action': press.action, raceId: press.raceId });
      }
    }, PRESS_RETRY_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [s.retryNonce, sendAction]);

  // D-130 — 낡은 창 복구. 창이 마감 + 유예 뒤에도 열려 있으면 그 창을 낡았다고 표시한다(창마다 한 번 — 스토어가 거른다).
  const staleRaceId = s.race?.raceId;
  const staleRaceClosesAt = s.race?.closesAt;
  useEffect(() => {
    if (staleRaceId === undefined || staleRaceClosesAt === undefined) return;
    const timer = window.setTimeout(() => {
      const { race, requestRaceResync } = useOneCardStore.getState();
      if (race?.raceId === staleRaceId) requestRaceResync(staleRaceId);
    }, Math.max(0, staleRaceClosesAt + STALE_RACE_GRACE_MS - Date.now()));
    return () => window.clearTimeout(timer);
  }, [staleRaceId, staleRaceClosesAt]);

  // D-140 — 멈춘 차례 복구. 카운트다운(아래 상태 줄)이 보이는 동안에만 — 0 + 유예 + 지터 뒤에도 같은 차례면 그 차례에 한 번.
  const turnClock = s.turnClock;
  const turnEpoch = s.turnEpoch;
  const countingTurn = !roomFinished && !raceOpen && turnSeat >= 0 && turnSeconds > 0 && turnClock !== null;
  useEffect(() => {
    if (!countingTurn || turnClock === null) return;
    const fullTurnMs = turnSeconds * 1000;
    const deadline = turnClock.since + Math.min(turnClock.remainingMs ?? fullTurnMs, fullTurnMs);
    const jitter = Math.random() * STALE_TURN_JITTER_MS;
    const timer = window.setTimeout(() => {
      const current = useOneCardStore.getState();
      if (current.turnEpoch === turnEpoch) current.requestTurnResync(turnEpoch);
    }, Math.max(0, deadline + STALE_TURN_GRACE_MS + jitter - Date.now()));
    return () => window.clearTimeout(timer);
  }, [countingTurn, turnClock, turnEpoch, turnSeconds]);

  // 스토어가 다시 받기를 청하면(낡은 창 — 마감 초과·창이 열린 채 NO_RACE) 훅으로 권위 스냅샷을 받는다. 처리한 값을 ref 로
  // 기억한다 — 스토어는 모듈 전역이라 이전 방 세션이 남긴 값을 첫 렌더가 읽을 수 있는데, 값이 아니라 변화에만 반응해야
  // 마운트 직후 REST resync 가 훅 자신의 것에 더해 한 번 더 나가지 않는다.
  const handledResyncNonce = useRef(s.resyncNonce);
  useEffect(() => {
    if (s.resyncNonce === handledResyncNonce.current) return;
    handledResyncNonce.current = s.resyncNonce;
    if (s.resyncNonce !== 0) requestResync();
  }, [s.resyncNonce, requestResync]);

  // "늦었어요"는 잠깐만.
  const clearRaceNotice = s.clearRaceNotice;
  useEffect(() => {
    if (s.raceNotice === null) return;
    const timer = window.setTimeout(clearRaceNotice, LATE_NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [s.raceNotice, clearRaceNotice]);

  const press = (action: PressAction) => {
    // 끊긴 동안은 보내지 못하므로(sendAction 이 조용히 무시한다) 누름 표식도 남기지 않는다 — 남기면 그 창 동안 버튼이 잠긴다.
    if (!connected || !s.race || s.press) return;
    s.startPress(s.race.raceId, action);
    sendAction({ '@action': action, raceId: s.race.raceId });
  };

  const playSelected = () => {
    const card = s.hand.find((c) => cardKey(c) === s.selectedKey);
    if (!card) return;
    if (isSuitChange(card) && s.suitChoice === null) return;
    sendAction({
      '@action': 'PLAY_CARD',
      card,
      ...(isSuitChange(card) ? { declaredSuit: s.suitChoice } : {}),
    });
  };

  const draw = () => sendAction({ '@action': 'DRAW' });
  // D-154 — 손패 더블클릭으로 바로 내기(7 은 손패가 거른다 — 무늬를 골라야 한다).
  const playCard = (card: OneCardCard) => sendAction({ '@action': 'PLAY_CARD', card });

  // 진행 중 나가기는 되돌릴 수 없는 탈주다 — 탈락으로 최하위가 된다(§10). 관전자·종료 뒤·이미 탈락한 좌석은 묻지 않는다.
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

  // 화면 문장과 알림 문장은 같은 것에서 나온다. 진 누름의 "늦었어요"도 화면에만 두지 않고 알린다.
  const resultText = s.lastRace ? raceResultText(s.lastRace, nameOf) : '';
  const announcement =
    s.raceNotice === 'LATE'
      ? [resultText, '늦었어요'].filter(Boolean).join(' — ')
      : s.race
        ? `${nameOf(s.race.ownerSeat)} 카드 1장! ${
            canPress ? (s.race.ownerSeat === mySeat ? '원카드! 버튼을 누르세요' : '잡기! 버튼을 누르세요') : '경쟁 중'
          }`
        : resultText;

  return (
    <div ref={tableRef} className="oc-table" style={{ ['--oc-seat-min' as string]: seatMinWidth(seatCount) }}>
      <header className="oc-header">
        <div className="oc-header-badges">
          <span className="oc-badge oc-badge-title">원카드</span>
          <span className="oc-badge">{seatCount}인</span>
          {practice && <span className="oc-badge">🤖 혼자 연습</span>}
          {turnSeconds > 0 && <span className="oc-badge">턴 {turnSeconds}초</span>}
          {spectatorCount > 0 && <span className="oc-badge">관전 {spectatorCount}</span>}
          {spectator && <span className="oc-badge">관전 모드</span>}
          <span className="oc-badge">{connected ? '● 연결' : '○ 끊김'}</span>
        </div>
        <div className="oc-header-badges">
          <button
            type="button"
            className="oc-badge"
            onClick={toggleMute}
            aria-label="효과음"
            aria-pressed={!muted}
            title={muted ? '효과음 켜기' : '효과음 끄기'}
          >
            {muted ? '🔇' : '🔊'}
          </button>
          <VoiceControls voice={voice} buttonClassName="oc-badge" />
          {!spectator && (
            <span className="oc-reaction-bar">
              <button
                type="button"
                className="oc-badge"
                onClick={() => setReactionOpen((v) => !v)}
                aria-label="이모지 반응"
                aria-expanded={reactionOpen}
                title="이모지 반응"
              >
                😀
              </button>
              {reactionOpen && (
                <span className="oc-reaction-palette">
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
          <button type="button" className="oc-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <button
            type="button"
            className="oc-badge"
            onClick={rules.show}
            title="게임 방법 다시 보기 — 턴 타이머는 계속 흐릅니다"
          >
            규칙
          </button>
          {onAbort && !roomFinished && (
            <button type="button" className="oc-badge" onClick={onAbort}>
              게임 종료
            </button>
          )}
          {onExit && (
            <button type="button" className="oc-badge" onClick={exit}>
              나가기
            </button>
          )}
        </div>
      </header>

      <ReconnectBanner connected={connected} />
      {s.errorMessage && (
        <p className="oc-error" role="alert">
          <span>{s.errorMessage}</span>
          <button type="button" className="oc-error-close" aria-label="닫기" onClick={() => s.setError(null)}>
            ×
          </button>
        </p>
      )}

      {/* D-132 — 테이블. 내 자리는 아래(아래의 "나" 줄)이고, 관전자는 좌석 0 을 아래 칸에 일반 좌석으로 둔다. */}
      <div className="oc-felt" role="group" aria-label="좌석 배치">
        {/* D-139 — 경기장 맨 위 상태 줄: 왼쪽 위 남은 턴 시간(경쟁 중·끝난 판·차례 없음이면 비움), 오른쪽 위 진행 방향(D-132 — 글리프는
            읽지 않고 글자로 읽는다, 좌석이 시계 방향으로 앉으므로 +1 이 시계 방향). */}
        <div className="oc-felt-status" role="group" aria-label="판 상태">
          <div className="oc-felt-status-left">
            {!roomFinished && !raceOpen && turnSeat >= 0 && <OneCardTurnCountdown turnSeconds={turnSeconds} />}
          </div>
          <div className="oc-felt-status-right">
            <span className="oc-badge oc-direction">
              <span className="oc-direction-arrow" aria-hidden="true">
                {s.direction >= 0 ? '↻' : '↺'}
              </span>{' '}
              {s.direction >= 0 ? '시계 방향' : '반시계 방향'}
            </span>
          </div>
        </div>
        {sideRow('left')}
        {sideRow('top')}
        {sideRow('right')}

        <OneCardCenter
          topCard={s.topCard}
          declaredSuit={s.declaredSuit}
          attackStack={s.attackStack}
          drawPileCount={s.drawPileCount}
          turnName={turnSeat >= 0 ? nameOf(turnSeat) : null}
          raceOpen={raceOpen}
          lastRace={s.lastRace}
          late={s.raceNotice === 'LATE'}
          nameOf={nameOf}
          landingUnder={flight ? flight.under : null}
          onDraw={myTurn && connected ? draw : undefined}
          discardTrail={s.discardTrail}
        />

        {bottomSeat !== null && <div className="oc-felt-bottom">{seatAt(bottomSeat, 'bottom', 0)}</div>}
      </div>

      {s.result && (
        <OneCardMatchEnd
          result={s.result}
          mySeat={mySeat}
          nameOf={nameOf}
          onExit={onExit}
          onBackToRoom={onBackToRoom}
        />
      )}

      {/* D-120 — 방은 끝났는데 결과가 없다: 강제 종료이거나 MATCH_ENDED 직전의 찰나다. */}
      {roomFinished && !s.result && (
        <section className="oc-finished-note" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="oc-play" onClick={exit}>
              메인으로
            </button>
          )}
        </section>
      )}

      {!spectator && mySeat >= 0 && (
        <>
          <div
            className={`oc-me${myTurn ? ' oc-me-turn' : ''}`}
            style={{ ['--oc-accent' as string]: seatAccent(mySeat) }}
          >
            <strong>{nameOf(mySeat)} (나)</strong>
            {liveReactions[mySeat] && (
              <span key={liveReactions[mySeat].id} className="oc-seat-reaction" aria-hidden="true">
                {liveReactions[mySeat].emoji}
              </span>
            )}
            <span className="oc-me-count">손패 {s.hand.length}장</span>
            {myTurn && <span className="oc-badge oc-badge-turn">내 차례</span>}
            {nextSeat === mySeat && <span className="oc-badge oc-seat-next">다음</span>}
            {myTurn && s.attackStack > 0 && (
              <span className="oc-badge oc-attack">공격받는 중 +{s.attackStack}</span>
            )}
            {me?.eliminated && (
              <span className="status-tag oc-seat-tag">탈락 — {ELIMINATED_LABEL[me.eliminated]}</span>
            )}
          </div>

          {!roomFinished && s.result === null && !iAmOut && (
            <OneCardHand
              accent={seatAccent(mySeat)}
              hand={s.hand}
              selectedKey={s.selectedKey}
              suitChoice={s.suitChoice}
              topCard={s.topCard}
              declaredSuit={s.declaredSuit}
              attackStack={s.attackStack}
              myTurn={myTurn}
              raceOpen={raceOpen}
              onSelect={s.selectCard}
              onSuit={s.setSuitChoice}
              onPlay={playSelected}
              onDraw={draw}
              onPlayCard={playCard}
            />
          )}
        </>
      )}

      {s.race && canPress && (
        <RaceButton
          race={s.race}
          size={raceButtonSize}
          mySeat={mySeat}
          pending={s.press !== null || !connected}
          onPress={press}
        />
      )}

      {/* 창이 열리고 닫힌 사실을 보조기기에 알린다 — 버튼에 자동 포커스를 주지 않는 대신이다. */}
      <div className="oc-sr-only" aria-live="assertive" aria-atomic="true">
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

      <TutorialDialog tutorial={ONE_CARD_TUTORIAL} open={rules.open} onClose={rules.close} />
      {/* D-139 — 날아가는 카드(장식 — 맨 위 카드와 알림이 이미 같은 정보를 준다). 처음 프레임은 출발 좌석에, settled 가 되면 맨 위 카드 자리로. */}
      {flight && (
        <div
          className={`oc-fly${flight.settled ? ' oc-fly-settled' : ''}`}
          data-from-seat={flight.fromSeat}
          aria-hidden="true"
          style={{
            left: flight.left,
            top: flight.top,
            transform: flight.settled
              ? 'translate(-50%, -50%)'
              : `translate(calc(-50% + ${flight.dx}px), calc(-50% + ${flight.dy}px)) scale(0.8)`,
          }}
        >
          <OneCardCardChip card={flight.card} />
        </div>
      )}
      {/* D-152 — 먹은 카드(장식 — 좌석 뒷면·손패가 이미 같은 정보를 준다). 더미에서 출발해 먹은 사람 자리로. 여러 장이면 장수. */}
      {drawFlight && (
        <div
          className={`oc-fly oc-fly-draw${drawFlight.settled ? ' oc-fly-settled' : ''}`}
          data-to-seat={drawFlight.toSeat}
          aria-hidden="true"
          style={{
            left: drawFlight.left,
            top: drawFlight.top,
            transform: drawFlight.settled
              ? 'translate(-50%, -50%) scale(0.6)'
              : `translate(calc(-50% + ${drawFlight.dx}px), calc(-50% + ${drawFlight.dy}px))`,
          }}
        >
          {drawFlight.count > 1 && <span className="oc-fly-draw-count">+{drawFlight.count}</span>}
        </div>
      )}
    </div>
  );
}
