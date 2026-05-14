import { useMemo } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useTichuStore } from '@/features/tichu/tichuStore';
import { useStompRoom } from '@/ws/useStompRoom';
import type { Card } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { CardChip } from './CardChip';

interface GameTableProps {
  roomId: string;
  playerIds: number[];
  myUserId: number;
}

export function GameTable({ roomId, playerIds, myUserId }: GameTableProps) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction } = useStompRoom(roomId, token);
  const tableView = useTichuStore((s) => s.tableView);
  const privateHand = useTichuStore((s) => s.privateHand);
  const selectedCardKeys = useTichuStore((s) => s.selectedCardKeys);
  const toggleCardSelection = useTichuStore((s) => s.toggleCardSelection);
  const clearSelection = useTichuStore((s) => s.clearSelection);
  const errorMessage = useTichuStore((s) => s.errorMessage);
  const roundEnded = useTichuStore((s) => s.roundEnded);
  const setError = useTichuStore((s) => s.setError);

  const mySeat = playerIds.indexOf(myUserId);
  const myTurn = tableView !== null && tableView.currentTurnSeat === mySeat;

  const selectedCards = useMemo<Card[]>(() => {
    if (!privateHand) return [];
    return privateHand.cards.filter((c) => selectedCardKeys.has(cardKey(c)));
  }, [privateHand, selectedCardKeys]);

  function handlePlay() {
    if (selectedCards.length === 0) {
      setError('카드를 한 장 이상 선택하세요');
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

  if (!tableView) {
    return <p>방 상태 로드 중...</p>;
  }

  return (
    <section className="game-table">
      <header className="game-table-header">
        <span>STOMP {connected ? '●' : '○'}</span>
        <span>내 시트: {mySeat}</span>
        <span>현재 차례: 시트 {tableView.currentTurnSeat}</span>
        {tableView.activeWishRank !== null && (
          <span>활성 소원: {tableView.activeWishRank}</span>
        )}
      </header>

      <div className="table-seats">
        {playerIds.map((uid, seat) => (
          <div
            key={uid}
            className={`seat ${seat === tableView.currentTurnSeat ? 'turn' : ''}
                       ${tableView.finishingOrder.includes(seat) ? 'finished' : ''}`}
          >
            <div className="seat-id">#{uid}</div>
            <div className="hand-count">손패 {tableView.handCounts[seat] ?? 0}장</div>
            {tableView.declarations[seat] && tableView.declarations[seat] !== 'NONE' && (
              <div className="declared">{tableView.declarations[seat]}</div>
            )}
          </div>
        ))}
      </div>

      <div className="trick">
        <h3>현재 트릭</h3>
        {tableView.currentTop ? (
          <div className="current-top">
            <span>시트 {tableView.currentTopSeat} —</span>
            <span className="hand-cards">
              {tableView.currentTop.cards.map((c) => (
                <CardChip key={cardKey(c)} card={c} />
              ))}
            </span>
            <span className="hand-type">{tableView.currentTop.type}</span>
          </div>
        ) : (
          <p>(리드 대기)</p>
        )}
      </div>

      <div className="my-hand">
        <h3>내 손패</h3>
        {privateHand ? (
          <div className="hand-cards">
            {privateHand.cards.map((c) => (
              <CardChip
                key={cardKey(c)}
                card={c}
                selected={selectedCardKeys.has(cardKey(c))}
                onClick={() => toggleCardSelection(c)}
              />
            ))}
          </div>
        ) : (
          <p>(손패 로드 중...)</p>
        )}
      </div>

      <div className="actions">
        <button type="button" onClick={handlePlay} disabled={!myTurn || selectedCards.length === 0}>
          내기 ({selectedCards.length}장)
        </button>
        <button type="button" onClick={handlePass} disabled={!myTurn || !tableView.currentTop}>
          패스
        </button>
        <button type="button" onClick={handleDeclareTichu} disabled={(privateHand?.cards.length ?? 0) !== 14}>
          티츄 선언
        </button>
        <button type="button" onClick={clearSelection} disabled={selectedCards.length === 0}>
          선택 해제
        </button>
      </div>

      {errorMessage && (
        <p className="error" onClick={() => setError(null)}>
          {errorMessage}
        </p>
      )}

      {roundEnded && (
        <div className="round-ended">
          <h3>라운드 종료</h3>
          <p>
            Team A {roundEnded.teamAScore} : {roundEnded.teamBScore} Team B
          </p>
          <p>첫 완주: 시트 {roundEnded.firstFinisherSeat}</p>
        </div>
      )}
    </section>
  );
}
