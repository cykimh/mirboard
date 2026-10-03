import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import type { CompletedRoundView } from '@/types/skullking';
import { SkullRoundHistoryTable } from './SkullRoundHistoryTable';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  rounds: CompletedRoundView[];
  seats: number[];
  /** 합계 행 — 진행 중 누적 점수(권위값). */
  totals: Record<number, number>;
  mySeat: number;
  nameOf: (seat: number) => string;
  desertedSeats?: number[];
}

/**
 * 게임판 헤더 '점수표' → 라운드×좌석 점수표 (D-120). 티츄 `RoundHistoryModal`(D-108)과 같은
 * shadcn Dialog 패턴이다. 게임판(.app-shell 밖)과 달리 모달은 portal 로 body 에 붙으므로
 * `.app-shell` 을 직접 달아 shadcn 스코프 base 를 받는다.
 *
 * 8인이면 열이 9개라 모달 폭을 넘는다 — 표는 가로 스크롤 래퍼 안에 있고 라운드 열은 고정된다.
 */
export function SkullRoundHistoryModal({
  open,
  onOpenChange,
  rounds,
  seats,
  totals,
  mySeat,
  nameOf,
  desertedSeats,
}: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="app-shell sk-history-modal">
        <DialogHeader>
          <DialogTitle>라운드별 점수</DialogTitle>
          <DialogDescription>
            {rounds.length > 0
              ? '끝난 라운드의 점수입니다. 아래 작은 숫자는 예측/획득입니다.'
              : '아직 끝난 라운드가 없습니다.'}
          </DialogDescription>
        </DialogHeader>
        {rounds.length > 0 && (
          <SkullRoundHistoryTable
            rounds={rounds}
            seats={seats}
            totals={totals}
            mySeat={mySeat}
            nameOf={nameOf}
            desertedSeats={desertedSeats}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}
