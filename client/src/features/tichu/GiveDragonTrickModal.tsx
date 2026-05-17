import { t } from '@/i18n/messages';
import { Modal } from '@/components/Modal';

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
  // Phase 13A (#7) — 좌석 클릭 시 별도 확인 없이 즉시 양도.
  return (
    <Modal open={open} title={t('dragon.title')} body={t('dragon.body')}>
      <div className="dragon-seat-choices">
        {opponentSeats.map((seat) => (
          <button
            key={seat}
            type="button"
            className="dragon-seat-btn"
            onClick={() => onConfirm(seat)}
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
