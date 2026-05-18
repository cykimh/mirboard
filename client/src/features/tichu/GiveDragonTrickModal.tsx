import { t } from '@/i18n/messages';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

interface GiveDragonTrickModalProps {
  open: boolean;
  opponentSeats: number[];
  onConfirm: (toSeat: number) => void;
}

/** Phase 20e(D-76) — shadcn Dialog 재디자인. 즉시 양도 로직 불변. */
export function GiveDragonTrickModal({
  open,
  opponentSeats,
  onConfirm,
}: GiveDragonTrickModalProps) {
  return (
    <Dialog open={open}>
      <DialogContent
        className="app-shell"
        onEscapeKeyDown={(e) => e.preventDefault()}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>{t('dragon.title')}</DialogTitle>
          <DialogDescription>{t('dragon.body')}</DialogDescription>
        </DialogHeader>
        <div className="flex gap-2">
          {opponentSeats.map((seat) => (
            <Button
              key={seat}
              type="button"
              onClick={() => onConfirm(seat)}
            >
              {t('dragon.giveTo')} {seat}
            </Button>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function opponentSeatsOf(mySeat: number): number[] {
  // Team A: 0, 2 / Team B: 1, 3. 상대 팀 두 좌석.
  return mySeat % 2 === 0 ? [1, 3] : [0, 2];
}
