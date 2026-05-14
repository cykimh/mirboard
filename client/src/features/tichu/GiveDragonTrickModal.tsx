import { useState } from 'react';
import { t } from '@/i18n/messages';

interface GiveDragonTrickModalProps {
  open: boolean;
  opponentSeats: number[];
  onConfirm: (toSeat: number) => void;
}

export function GiveDragonTrickModal({
  open,
  opponentSeats,
  onConfirm,
}: GiveDragonTrickModalProps) {
  const [selected, setSelected] = useState<number | null>(null);

  if (!open) return null;

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal dragon-modal">
        <h3>{t('dragon.title')}</h3>
        <p className="modal-body">{t('dragon.body')}</p>
        <div className="dragon-seat-choices">
          {opponentSeats.map((seat) => (
            <button
              key={seat}
              type="button"
              className={`dragon-seat-btn ${selected === seat ? 'selected' : ''}`}
              onClick={() => setSelected(seat)}
            >
              {t('dragon.giveTo')} {seat}
            </button>
          ))}
        </div>
        <div className="modal-actions">
          <button
            type="button"
            disabled={selected === null}
            onClick={() => selected !== null && onConfirm(selected)}
          >
            {t('dragon.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

export function opponentSeatsOf(mySeat: number): number[] {
  // Team A: 0, 2 / Team B: 1, 3. 상대 팀 두 좌석.
  return mySeat % 2 === 0 ? [1, 3] : [0, 2];
}
