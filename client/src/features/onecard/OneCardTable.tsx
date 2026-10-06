import { useEffect, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { seatMinWidth, viewOrder } from '@/components/seatOrder';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { cardKey } from '@/types/onecard';
import { onecardRoomSink } from './onecardRoomSink';
import { useOneCardStore, type PressAction } from './onecardStore';
import { isSuitChange } from './onecardRules';
import { ELIMINATED_LABEL, OneCardSeat, seatAccent } from './OneCardSeat';
import { OneCardCenter, raceResultText } from './OneCardCenter';
import { OneCardHand } from './OneCardHand';
import { OneCardMatchEnd } from './OneCardMatchEnd';
import { RaceButton } from './RaceButton';
import { ONE_CARD_TUTORIAL } from './tutorial/onecardTutorial';

/** `BUSY` 로 거절된 누름을 다시 보내기까지의 간격. 창(기본 3초) 안에 두 번 재시도할 여유가 있다. */
export const PRESS_RETRY_DELAY_MS = 120;
/** "늦었어요"를 보여 주는 시간. */
export const LATE_NOTICE_MS = 1500;
/**
 * S5 — 경쟁 창이 마감 뒤 이만큼 지나도 열려 있으면 해소 이벤트를 놓쳤거나 서버 타이머가 사라진 것으로 보고 권위 스냅샷을
 * 다시 받는다(서버 resync 는 진행 킥으로 사라진 타이머를 다시 건다). 폴링 주기·왕복 시간보다 넉넉히.
 */
export const STALE_RACE_GRACE_MS = 1500;

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
  /** D-120 — 방은 FINISHED 인데 이 세션이 종료 전이를 봐서 게임판을 유지하는 중. 입력을 숨긴다. */
  roomFinished?: boolean;
}

/**
 * 원카드 게임판 조립 루트 (D-129). 스컬킹처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103) — 다른 게임의 코드
 * 경로는 실행되지 않는다. 좌석 수의 권위값은 `tableView.seats.length` 이고 `playerIds` 는 이름·아바타 조회용이다.
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
  roomFinished = false,
}: Props) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, chatPanelOpenRef, requestResync } = useStompRoom(
    roomId,
    token,
    onecardRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const chatUnread = useRoomChatStore((s) => s.unreadCount);
  // 게임판에서는 수동으로만 연다 — 턴 타이머가 계속 흐른다(D-121).
  const rules = useTutorialGate(ONE_CARD_TUTORIAL.seenKey, false);

  const s = useOneCardStore();
  const seatCount = s.seats.length || playerIds.length;
  const mySeat = spectator ? -1 : s.mySeat;
  const opponents = viewOrder(seatCount, mySeat);
  const me = s.seats.find((x) => x.seat === mySeat) ?? null;
  const iAmOut = me?.eliminated != null;
  const raceOpen = s.race !== null;
  const myTurn =
    !spectator && mySeat >= 0 && !iAmOut && s.result === null && !raceOpen && s.turnSeat === mySeat;
  const canPress = !spectator && mySeat >= 0 && !iAmOut && s.result === null && !roomFinished;

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
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

  // S5 — 낡은 창 복구. 창이 마감 + 유예 뒤에도 열려 있으면 그 창을 낡았다고 표시한다(창마다 한 번 — 스토어가 거른다).
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

  // 스토어가 다시 받기를 청하면(낡은 창 — 마감 초과·창이 열린 채 NO_RACE) 훅으로 권위 스냅샷을 받는다.
  useEffect(() => {
    if (s.resyncNonce === 0) return;
    requestResync();
  }, [s.resyncNonce, requestResync]);

  // "늦었어요"는 잠깐만.
  const clearRaceNotice = s.clearRaceNotice;
  useEffect(() => {
    if (s.raceNotice === null) return;
    const timer = window.setTimeout(clearRaceNotice, LATE_NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [s.raceNotice, clearRaceNotice]);

  const press = (action: PressAction) => {
    // 끊긴 동안은 보내지 못한다(sendAction 이 조용히 무시한다) — 대기 누름만 남겨 같은 창 동안 버튼이 굳지 않게.
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
    <div className="oc-table" style={{ ['--oc-seat-min' as string]: seatMinWidth(seatCount) }}>
      <header className="oc-header">
        <div className="oc-header-badges">
          <span className="oc-badge oc-badge-title">원카드</span>
          <span className="oc-badge">{seatCount}인</span>
          {turnSeconds > 0 && <span className="oc-badge">턴 {turnSeconds}초</span>}
          {spectatorCount > 0 && <span className="oc-badge">관전 {spectatorCount}</span>}
          {spectator && <span className="oc-badge">관전 모드</span>}
          <span className="oc-badge">{connected ? '● 연결' : '○ 끊김'}</span>
        </div>
        <div className="oc-header-badges">
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

      <div className="oc-seats">
        {opponents.map((seat) => {
          const view = s.seats.find((x) => x.seat === seat);
          if (!view) return null;
          return (
            <OneCardSeat
              key={seat}
              seat={view}
              userId={playerIds[seat]}
              username={usernames[playerIds[seat]]}
              isBot={botSeats.includes(seat)}
              isTurn={s.turnSeat === seat}
              isDisconnected={s.disconnectedSeats.has(seat)}
            />
          );
        })}
      </div>

      <OneCardCenter
        topCard={s.topCard}
        declaredSuit={s.declaredSuit}
        attackStack={s.attackStack}
        direction={s.direction}
        drawPileCount={s.drawPileCount}
        turnName={s.turnSeat >= 0 ? nameOf(s.turnSeat) : null}
        raceOpen={raceOpen}
        lastRace={s.lastRace}
        late={s.raceNotice === 'LATE'}
        nameOf={nameOf}
      />

      {s.result && (
        <OneCardMatchEnd result={s.result} mySeat={mySeat} nameOf={nameOf} onExit={onExit} />
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
            <span className="oc-me-count">손패 {s.hand.length}장</span>
            {myTurn && <span className="oc-badge oc-badge-turn">내 차례</span>}
            {myTurn && s.attackStack > 0 && (
              <span className="oc-badge oc-attack">공격받는 중 +{s.attackStack}</span>
            )}
            {me?.eliminated && (
              <span className="status-tag oc-seat-tag">탈락 — {ELIMINATED_LABEL[me.eliminated]}</span>
            )}
          </div>

          {!roomFinished && s.result === null && !iAmOut && (
            <OneCardHand
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
            />
          )}
        </>
      )}

      {s.race && canPress && (
        <RaceButton
          race={s.race}
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
    </div>
  );
}
