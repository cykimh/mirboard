import { useState } from 'react';
import { t } from '@/i18n/messages';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { rankGlyph } from './handType';

interface MakeWishModalProps {
  open: boolean;
  onConfirm: (rank: number) => void;
  onSkip: () => void;
}

const RANKS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14];

/** Phase 20e(D-76) — shadcn Dialog 재디자인. 소원 지정 로직 불변. */
export function MakeWishModal({ open, onConfirm, onSkip }: MakeWishModalProps) {
  const [selected, setSelected] = useState<number | null>(null);

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onSkip()}>
      <DialogContent className="app-shell">
        <DialogHeader>
          <DialogTitle>{t('wish.title')}</DialogTitle>
          <DialogDescription>{t('wish.body')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-7 gap-2">
          {RANKS.map((r) => (
            <Button
              key={r}
              type="button"
              variant={selected === r ? 'default' : 'outline'}
              size="sm"
              onClick={() => setSelected(r)}
            >
              {rankGlyph(r)}
            </Button>
          ))}
        </div>
        <DialogFooter className="gap-2">
          <Button type="button" variant="outline" onClick={onSkip}>
            {t('wish.skip')}
          </Button>
          <Button
            type="button"
            disabled={selected === null}
            onClick={() => selected !== null && onConfirm(selected)}
          >
            {t('wish.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
