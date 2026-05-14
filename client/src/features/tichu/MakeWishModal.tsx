import { useState } from 'react';
import { t } from '@/i18n/messages';

interface MakeWishModalProps {
  open: boolean;
  onConfirm: (rank: number) => void;
  onSkip: () => void;
}

const RANKS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14];

export function MakeWishModal({ open, onConfirm, onSkip }: MakeWishModalProps) {
  const [selected, setSelected] = useState<number | null>(null);

  if (!open) return null;

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal wish-modal">
        <h3>{t('wish.title')}</h3>
        <p className="modal-body">{t('wish.body')}</p>
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
        <div className="modal-actions">
          <button type="button" onClick={onSkip}>
            {t('wish.skip')}
          </button>
          <button
            type="button"
            disabled={selected === null}
            onClick={() => selected !== null && onConfirm(selected)}
          >
            {t('wish.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
}
