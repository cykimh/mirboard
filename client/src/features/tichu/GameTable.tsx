import { useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import {
  useTichuStore,
  sortedHand,
  type PassSlot,
} from '@/features/tichu/tichuStore';
import { useStompRoom } from '@/ws/useStompRoom';
import type { Card } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { t } from '@/i18n/messages';
import { CardChip } from './CardChip';
import { SortableHand } from './SortableHand';
import { SeatAvatar } from './SeatAvatar';
import { MakeWishModal } from './MakeWishModal';
import { GiveDragonTrickModal, opponentSeatsOf } from './GiveDragonTrickModal';
import { EffectsOverlay } from './EffectsOverlay';
import { useSfx } from './useSfx';
import { Badge } from '@/components/Badge';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';

interface GameTableProps {
  roomId: string;
  playerIds: number[];
  myUserId: number;
  /** true 이면 관전자 모드: 손패/액션 버튼/모달 미표시. TableView 만 시청. */
  spectator?: boolean;
  /** Phase 9D — playerIds 인덱스 중 봇이 차지한 좌석. SeatAvatar 가 🤖 표시. */
  botSeats?: number[];
  /** Phase 9D — 솔로 모드 (방 생성 시 봇 자동 채움). 헤더에 배너 표시. */
  fillWithBots?: boolean;
}

const PASS_SLOT_LABEL: Record<PassSlot, string> = {
  left: t('pass.slot.left'),
  partner: t('pass.slot.partner'),
  right: t('pass.slot.right'),
};

export function GameTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  fillWithBots = false,
}: GameTableProps) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, chatPanelOpenRef } = useStompRoom(roomId, token);
  const [chatOpen, setChatOpen] = useState(false);
  const unreadCount = useRoomChatStore((s) => s.unreadCount);
  const { muted, toggleMute } = useSfx();
  const tableView = useTichuStore((s) => s.tableView);
  const privateHand = useTichuStore((s) => s.privateHand);
  const selectedCardKeys = useTichuStore((s) => s.selectedCardKeys);
  const toggleCardSelection = useTichuStore((s) => s.toggleCardSelection);
  const clearSelection = useTichuStore((s) => s.clearSelection);
  const passSelection = useTichuStore((s) => s.passSelection);
  const activePassSlot = useTichuStore((s) => s.activePassSlot);
  const setActivePassSlot = useTichuStore((s) => s.setActivePassSlot);
  const assignPassSlot = useTichuStore((s) => s.assignPassSlot);
  const clearPassSelection = useTichuStore((s) => s.clearPassSelection);
  const reorderHand = useTichuStore((s) => s.reorderHand);
  const sortHandByRank = useTichuStore((s) => s.sortHandByRank);
  const restoreServerOrder = useTichuStore((s) => s.restoreServerOrder);
  const orderedHand = useTichuStore(sortedHand);
  const errorMessage = useTichuStore((s) => s.errorMessage);
  const roundEnded = useTichuStore((s) => s.roundEnded);
  const matchEnded = useTichuStore((s) => s.matchEnded);
  const setError = useTichuStore((s) => s.setError);
  const setRoundEnded = useTichuStore((s) => s.setRoundEnded);

  const mySeat = playerIds.indexOf(myUserId);
  const [wishModalDismissed, setWishModalDismissed] = useState(false);

  const phase = tableView?.phase ?? null;
  const dealingCardCount = tableView?.dealingCardCount ?? 0;
  const isInDealing = phase === 'DEALING';
  const isInPassing = phase === 'PASSING';
  const isInPlaying = phase === 'PLAYING';
  const iAmReady = isInDealing && (tableView?.readySeats ?? []).includes(mySeat);
  const iAmPassSubmitted =
    isInPassing && (tableView?.passingSubmittedSeats ?? []).includes(mySeat);
  const myDeclaration = tableView?.declarations?.[mySeat] ?? 'NONE';
  const myTurn = isInPlaying && tableView !== null && tableView.currentTurnSeat === mySeat;

  const myMahjongLeadActive =
    isInPlaying &&
    tableView !== null &&
    tableView.currentTopSeat === mySeat &&
    tableView.currentTop !== null &&
    tableView.currentTop.cards.length === 1 &&
    tableView.currentTop.cards[0].special === 'MAHJONG' &&
    tableView.activeWishRank === null;

  const wishContextKey = myMahjongLeadActive
    ? `${tableView.currentTopSeat}-mahjong`
    : null;

  useEffect(() => {
    setWishModalDismissed(false);
  }, [wishContextKey]);

  const showWishModal = myMahjongLeadActive && !wishModalDismissed;

  // Dragon 양도 강제 상태: Dragon 단독으로 내가 받았고, 서버가 TrickTaken 대신
  // TurnChanged(taker=mySeat) 만 발행해서 currentTurnSeat 가 다시 본인.
  const mustGiveDragon =
    isInPlaying &&
    tableView !== null &&
    tableView.currentTopSeat === mySeat &&
    tableView.currentTurnSeat === mySeat &&
    tableView.currentTop !== null &&
    tableView.currentTop.cards.length === 1 &&
    tableView.currentTop.cards[0].special === 'DRAGON';

  const selectedCards = useMemo<Card[]>(() => {
    if (!privateHand) return [];
    return privateHand.cards.filter((c) => selectedCardKeys.has(cardKey(c)));
  }, [privateHand, selectedCardKeys]);

  const passCardsBySlot = useMemo(() => {
    if (!privateHand) return { left: null, partner: null, right: null };
    const findByKey = (key: string | null): Card | null =>
      key ? privateHand.cards.find((c) => cardKey(c) === key) ?? null : null;
    return {
      left: findByKey(passSelection.left),
      partner: findByKey(passSelection.partner),
      right: findByKey(passSelection.right),
    };
  }, [privateHand, passSelection]);

  function handlePlay() {
    if (selectedCards.length === 0) {
      setError(t('play.error.pickCard'));
      return;
    }
    sendAction({ '@action': 'PLAY_CARD', cards: selectedCards });
    clearSelection();
  }

  function handlePass() {
    sendAction({ '@action': 'PASS_TRICK' });
  }

  function handleDeclareTichu() {
    sendAction({ '@action': 'DECLARE_TICHU' });
  }

  function handleDeclareGrandTichu() {
    sendAction({ '@action': 'DECLARE_GRAND_TICHU' });
  }

  function handleMakeWish(rank: number) {
    sendAction({ '@action': 'MAKE_WISH', rank });
    setWishModalDismissed(true);
  }

  function handleSkipWish() {
    setWishModalDismissed(true);
  }

  function handleGiveDragon(toSeat: number) {
    sendAction({ '@action': 'GIVE_DRAGON_TRICK', toSeat });
  }

  function handleReady() {
    sendAction({ '@action': 'READY' });
  }

  function handleSubmitPass() {
    const { left, partner, right } = passCardsBySlot;
    if (!left || !partner || !right) {
      setError(t('play.error.passSlots'));
      return;
    }
    sendAction({
      '@action': 'PASS_CARDS',
      toLeft: left,
      toPartner: partner,
      toRight: right,
    });
  }

  function handleCardClick(c: Card) {
    if (isInPassing && !iAmPassSubmitted) {
      assignPassSlot(c);
    } else if (isInPlaying) {
      toggleCardSelection(c);
    }
    // Dealing 단계에서는 카드 클릭은 의미 없음 (단지 시각 정보).
  }

  if (!tableView) {
    return <p>{t('common.loading')}</p>;
  }

  const phaseLabel =
    phase === 'DEALING'
      ? `${t('game.phase.dealing')} (${dealingCardCount}${t('seat.handCardsSuffix')})`
      : phase === 'PASSING'
      ? t('game.phase.passing')
      : phase === 'PLAYING'
      ? t('game.phase.playing')
      : t('game.phase.roundEnd');

  return (
    <div
      className="game-table-layout"
      style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}
    >
    <section className="game-table" style={{ flex: 1, minWidth: 0 }}>
      <EffectsOverlay />
      <ReconnectBanner connected={connected} />
      <header className="game-table-header">
        {fillWithBots && (
          <span className="solo-banner" title="이 방은 솔로 모드 — 빈 좌석은 봇이 자동 진행합니다">
            🤖 솔로 모드 (봇 {botSeats.length}명)
          </span>
        )}
        <span>
          {t('game.header.stomp')} {connected ? '●' : '○'}
        </span>
        <span>
          {t('game.header.mySeat')}: {mySeat}
        </span>
        <span>
          {t('game.header.round')} {tableView.roundNumber}
        </span>
        <span>
          {t('game.header.matchScore')} A {tableView.matchScores.A ?? 0} : {tableView.matchScores.B ?? 0} B
        </span>
        <span>{phaseLabel}</span>
        {isInPlaying && (
          <span>
            {t('game.header.currentTurn')}: {tableView.currentTurnSeat}
          </span>
        )}
        {tableView.activeWishRank !== null && (
          <span>
            {t('game.header.activeWish')}: {tableView.activeWishRank}
          </span>
        )}
        <button
          type="button"
          onClick={toggleMute}
          style={{
            marginLeft: 'auto',
            background: 'transparent',
            border: '1px solid #444',
            borderRadius: 4,
            padding: '2px 8px',
            cursor: 'pointer',
            fontSize: 14,
          }}
          aria-label="사운드 토글"
          title={muted ? '사운드 켜기' : '사운드 끄기'}
        >
          {muted ? '🔇' : '🔊'}
        </button>
        <button
          type="button"
          onClick={() => setChatOpen((v) => !v)}
          style={{
            background: 'transparent',
            border: '1px solid #444',
            borderRadius: 4,
            padding: '2px 8px',
            cursor: 'pointer',
            position: 'relative',
          }}
          aria-label="채팅 토글"
        >
          💬 채팅
          {unreadCount > 0 && !chatOpen && (
            <span
              style={{
                position: 'absolute',
                top: -6,
                right: -6,
                background: '#d33',
                color: '#fff',
                borderRadius: '50%',
                fontSize: 10,
                padding: '2px 6px',
                minWidth: 18,
                lineHeight: 1,
              }}
            >
              {unreadCount > 99 ? '99+' : unreadCount}
            </span>
          )}
        </button>
      </header>

      <div className="table-arena">
        {playerIds.map((uid, seat) => {
          const ready = isInDealing && tableView.readySeats.includes(seat);
          const submitted =
            isInPassing && tableView.passingSubmittedSeats.includes(seat);
          const turnHighlight = isInPlaying && seat === tableView.currentTurnSeat;
          // Phase 8E — 본인 시점 좌석 매핑. mySeat 기준 회전 후 (S/W/N/E) 배치.
          // viewIdx 0=South(본인), 1=West(우적), 2=North(파트너), 3=East(좌적).
          const viewIdx = ((seat - mySeat) + 4) % 4;
          const viewPos = ['s', 'w', 'n', 'e'][viewIdx];
          return (
            <div
              key={uid}
              className={`seat seat-${viewPos} ${turnHighlight ? 'turn' : ''}
                         ${tableView.finishingOrder.includes(seat) ? 'finished' : ''}
                         ${ready ? 'ready' : ''}
                         ${submitted ? 'submitted' : ''}`}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center' }}>
                <SeatAvatar seat={seat} size={32} isBot={botSeats.includes(seat)} />
                <span className="seat-id">#{uid}{botSeats.includes(seat) && ' (봇)'}</span>
              </div>
              <div className="hand-count">
                {t('seat.handCount')} {tableView.handCounts[seat] ?? 0}{t('seat.handCardsSuffix')}
              </div>
              {tableView.declarations[seat] && tableView.declarations[seat] !== 'NONE' && (
                <div className="declared">{tableView.declarations[seat]}</div>
              )}
              {ready && <div className="status-tag">{t('seat.ready')}</div>}
              {submitted && <div className="status-tag">{t('seat.submitted')}</div>}
            </div>
          );
        })}
        {isInPlaying && (
          <div className="table-center-trick">
            {tableView.currentTop ? (
              <>
                <div className="hand-cards">
                  {tableView.currentTop.cards.map((c) => (
                    <CardChip key={cardKey(c)} card={c} />
                  ))}
                </div>
                <div className="trick-meta">
                  <span>시트 {tableView.currentTopSeat}</span>
                  <span className="hand-type">{tableView.currentTop.type}</span>
                  {tableView.currentTop.phoenixSingle && (
                    <Badge tone="phoenix" title={t('phoenix.singleTooltip')}>
                      {t('phoenix.singleBadge')}
                    </Badge>
                  )}
                </div>
              </>
            ) : (
              <p className="trick-empty">{t('trick.leadWaiting')}</p>
            )}
          </div>
        )}
      </div>

      {!spectator && isInPassing && privateHand && !iAmPassSubmitted && (
        <div className="pass-picker">
          <h3>{t('pass.picker.title')}</h3>
          <div className="pass-slots">
            {(['left', 'partner', 'right'] as PassSlot[]).map((slot) => {
              const c = passCardsBySlot[slot];
              return (
                <button
                  type="button"
                  key={slot}
                  className={`pass-slot ${activePassSlot === slot ? 'active' : ''}`}
                  onClick={() => setActivePassSlot(slot)}
                >
                  <div className="slot-label">{PASS_SLOT_LABEL[slot]}</div>
                  {c ? (
                    <CardChip card={c} />
                  ) : (
                    <span className="slot-empty">{t('pass.slot.empty')}</span>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {!spectator && (
      <div className="my-hand">
        <div className="hand-toolbar">
          <h3>{t('hand.title')}</h3>
          <div className="hand-sort-actions">
            <button type="button" onClick={sortHandByRank}>
              {t('hand.sort.byRank')}
            </button>
            <button type="button" onClick={restoreServerOrder}>
              {t('hand.sort.restore')}
            </button>
          </div>
        </div>
        {privateHand ? (
          <SortableHand
            cards={orderedHand}
            selectedKeys={getSelectedKeys(selectedCardKeys, passSelection, isInPassing)}
            onCardClick={handleCardClick}
            onReorder={reorderHand}
          />
        ) : (
          <p>{t('hand.loading')}</p>
        )}
      </div>
      )}

      {!spectator && (
      <div className="actions">
        {isInDealing && !iAmReady && (
          <>
            {dealingCardCount === 8 && myDeclaration === 'NONE' && (
              <button type="button" onClick={handleDeclareGrandTichu}>
                {t('dealing.declareGrand')}
              </button>
            )}
            {dealingCardCount === 14 && myDeclaration === 'NONE' && (
              <button type="button" onClick={handleDeclareTichu}>
                {t('dealing.declareTichu')}
              </button>
            )}
            <button type="button" onClick={handleReady}>
              {myDeclaration === 'NONE'
                ? t('dealing.skip.noDeclare')
                : t('dealing.skip.declared')}
            </button>
          </>
        )}
        {isInDealing && iAmReady && <p className="hint">{t('dealing.waiting')}</p>}

        {isInPassing && !iAmPassSubmitted && (
          <>
            <button
              type="button"
              onClick={handleSubmitPass}
              disabled={
                !passCardsBySlot.left ||
                !passCardsBySlot.partner ||
                !passCardsBySlot.right
              }
            >
              {t('pass.submit')}
            </button>
            <button type="button" onClick={clearPassSelection}>
              {t('pass.clear')}
            </button>
          </>
        )}
        {isInPassing && iAmPassSubmitted && <p className="hint">{t('pass.waiting')}</p>}


        {isInPlaying && (
          <>
            <button
              type="button"
              onClick={handlePlay}
              disabled={!myTurn || selectedCards.length === 0}
            >
              {t('play.action.play')} ({selectedCards.length}{t('seat.handCardsSuffix')})
            </button>
            <button
              type="button"
              onClick={handlePass}
              disabled={!myTurn || !tableView.currentTop}
            >
              {t('play.action.pass')}
            </button>
            <button
              type="button"
              onClick={handleDeclareTichu}
              disabled={
                myDeclaration !== 'NONE' || (privateHand?.cards.length ?? 0) !== 14
              }
            >
              {t('play.action.declareTichu')}
            </button>
            <button
              type="button"
              onClick={clearSelection}
              disabled={selectedCards.length === 0}
            >
              {t('play.action.clearSelection')}
            </button>
          </>
        )}
      </div>
      )}

      {errorMessage && (
        <p className="error" onClick={() => setError(null)}>
          {errorMessage}
        </p>
      )}

      <MakeWishModal
        open={showWishModal}
        onConfirm={handleMakeWish}
        onSkip={handleSkipWish}
      />

      <GiveDragonTrickModal
        open={mustGiveDragon}
        opponentSeats={opponentSeatsOf(mySeat)}
        onConfirm={handleGiveDragon}
      />

      {matchEnded ? (
        <div className="match-ended">
          <h3>
            Team {matchEnded.winningTeam} {t('match.ended.titleSuffix')}
          </h3>
          <p>
            {t('match.ended.finalScore')} A {matchEnded.finalScores.A ?? 0} : {matchEnded.finalScores.B ?? 0} B
          </p>
          <p>
            {t('match.ended.roundsPlayed')}: {matchEnded.roundsPlayed}
          </p>
        </div>
      ) : (
        roundEnded && (
          <div className="round-ended">
            <h3>{t('round.ended.title')}</h3>
            <p>
              Team A {roundEnded.teamAScore} : {roundEnded.teamBScore} Team B
            </p>
            <p>
              {t('round.ended.firstFinisher')} {roundEnded.firstFinisherSeat}
            </p>
            <button type="button" onClick={() => setRoundEnded(null)}>
              {t('round.ended.continue')}
            </button>
          </div>
        )
      )}
    </section>
      {chatOpen && (
        <RoomChat
          myUserId={myUserId}
          sendChat={sendChat}
          panelOpenRef={chatPanelOpenRef}
        />
      )}
    </div>
  );
}

function getSelectedKeys(
  selected: Set<string>,
  passSelection: Record<PassSlot, string | null>,
  isInPassing: boolean,
): Set<string> {
  if (!isInPassing) return selected;
  const merged = new Set(selected);
  for (const v of Object.values(passSelection)) {
    if (v) merged.add(v);
  }
  return merged;
}
