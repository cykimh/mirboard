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
  /** 소원을 지정하고 낸다. */
  onConfirm: (rank: number) => void;
  /** 소원 없이 낸다. */
  onSkipWish: () => void;
  /** 취소 — 아무것도 보내지 않는다 (esc / 바깥 클릭). */
  onCancel: () => void;
}

const RANKS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14];

/**
 * 소원 모달. D-108 이후 이 모달은 "이미 낸 마작에 소원을 건다"가 아니라 **아직 보내지
 * 않은 플레이에 소원을 실을지 묻는** 창이다. 그래서 dismiss(esc/바깥 클릭)는 "소원
 * 없이 내기"가 아니라 **취소**다 — 무심코 닫았을 때 카드가 나가면 안 된다.
 */
export function MakeWishModal({ open, onConfirm, onSkipWish, onCancel }: MakeWishModalProps) {
  const [selected, setSelected] = useState<number | null>(null);

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) {
          setSelected(null);
          onCancel();
        }
      }}
    >
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
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setSelected(null);
              onSkipWish();
            }}
          >
            {t('wish.skip')}
          </Button>
          <Button
            type="button"
            disabled={selected === null}
            onClick={() => {
              if (selected === null) return;
              const rank = selected;
              setSelected(null);
              onConfirm(rank);
            }}
          >
            {t('wish.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
