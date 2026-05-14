import { useState } from 'react';
import { t } from '@/i18n/messages';
import { Modal } from '@/components/Modal';
import { Button } from '@/components/Button';

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

  return (
    <Modal
      open={open}
      title={t('dragon.title')}
      body={t('dragon.body')}
      actions={
        <Button
          type="button"
          variant="primary"
          disabled={selected === null}
          onClick={() => selected !== null && onConfirm(selected)}
        >
          {t('dragon.confirm')}
        </Button>
      }
    >
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
    </Modal>
  );
}

export function opponentSeatsOf(mySeat: number): number[] {
  // Team A: 0, 2 / Team B: 1, 3. 상대 팀 두 좌석.
  return mySeat % 2 === 0 ? [1, 3] : [0, 2];
}
