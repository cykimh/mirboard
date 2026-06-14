import { useEffect } from 'react';
import type { Card } from '@/types/tichu';
import { CardChip } from './CardChip';

interface PassReceivedModalProps {
  received: { card: Card; fromSeat: number }[];
  playerIds: number[];
  usernames: Record<number, string>;
  onClose: () => void;
}

/**
 * P4(5) — 패스 직후 "누구한테 무슨 카드 받았는지" 짧은 모달. 4.5초 후/클릭 시 닫힘.
 * 데이터는 본인 큐 CARDS_RECEIVED 에서만 와서 상태은닉 유지(본인이 받은 것만).
 */
export function PassReceivedModal({
  received,
  playerIds,
  usernames,
  onClose,
}: PassReceivedModalProps) {
  useEffect(() => {
    const id = window.setTimeout(onClose, 4500);
    return () => window.clearTimeout(id);
  }, [onClose]);

  return (
    <div className="pass-received-backdrop" onClick={onClose}>
      <div className="pass-received-modal" onClick={(e) => e.stopPropagation()}>
        <h3>받은 카드</h3>
        <div className="pass-received-list">
          {received.map((r, i) => {
            const giver =
              usernames[playerIds[r.fromSeat]] ?? `#${playerIds[r.fromSeat] ?? r.fromSeat}`;
            return (
              <div key={i} className="pass-received-item">
                <CardChip card={r.card} />
                <span className="pass-received-from">← {giver}</span>
              </div>
            );
          })}
        </div>
        <button type="button" className="pass-received-close" onClick={onClose}>
          확인
        </button>
      </div>
    </div>
  );
}
