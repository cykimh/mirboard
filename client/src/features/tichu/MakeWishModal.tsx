import { useState } from 'react';
import { t } from '@/i18n/messages';
import { Modal } from '@/components/Modal';
import { Button } from '@/components/Button';

interface MakeWishModalProps {
  open: boolean;
  onConfirm: (rank: number) => void;
  onSkip: () => void;
}

const RANKS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14];

export function MakeWishModal({ open, onConfirm, onSkip }: MakeWishModalProps) {
  const [selected, setSelected] = useState<number | null>(null);

  return (
    <Modal
      open={open}
      title={t('wish.title')}
      body={t('wish.body')}
      actions={
        <>
          <Button type="button" onClick={onSkip}>
            {t('wish.skip')}
          </Button>
          <Button
            type="button"
            variant="primary"
            disabled={selected === null}
            onClick={() => selected !== null && onConfirm(selected)}
          >
            {t('wish.confirm')}
          </Button>
        </>
      }
    >
      <div className="wish-rank-grid">
        {RANKS.map((r) => (
          <button
            key={r}
            type="button"
            className={`wish-rank-btn ${selected === r ? 'selected' : ''}`}
            onClick={() => setSelected(r)}
          >
            {r}
          </button>
        ))}
      </div>
    </Modal>
  );
}
