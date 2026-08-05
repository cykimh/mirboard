import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { RoundHistoryTable, type RoundHistoryRow } from './RoundHistoryTable';
import type { TichuRoomState } from './tichuStore';

interface RoundHistoryModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  roundHistory: TichuRoomState['roundHistory'];
  /** 내 팀. 관점 스왑의 기준 — 점수 칩과 같아야 한다. */
  myTeam: 'A' | 'B';
  /** 좌석 → userId. 첫 완주자 이름 표시용. */
  playerIds: number[];
  usernames: Record<number, string>;
}

/**
 * 게임판 점수 칩 → 라운드별 획득 점수 (D-108).
 *
 * 표기를 **내 팀 기준 우리/상대로 스왑**한다. 점수 칩이 이미 그 관점이라, Team A/B 를
 * 그대로 쓰면 같은 화면에서 두 관점이 부딪힌다. 매치 종료 패널은 Team A/B 를 유지하므로
 * 공용 표(`RoundHistoryTable`)는 관점을 모르고, 스왑은 여기서만 한다.
 */
export function RoundHistoryModal({
  open,
  onOpenChange,
  roundHistory,
  myTeam,
  playerIds,
  usernames,
}: RoundHistoryModalProps) {
  const finisherName = (seat: number) => {
    const userId = playerIds[seat];
    return userId === undefined ? undefined : usernames[userId];
  };

  const rows: RoundHistoryRow[] = roundHistory.map((r, i) => ({
    round: i + 1,
    left: myTeam === 'A' ? r.teamAScore : r.teamBScore,
    right: myTeam === 'A' ? r.teamBScore : r.teamAScore,
    doubleVictory: r.doubleVictory,
    finisherName: finisherName(r.firstFinisherSeat),
  }));

  const totals = rows.reduce(
    (acc, r) => ({ left: acc.left + r.left, right: acc.right + r.right }),
    { left: 0, right: 0 },
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="app-shell">
        <DialogHeader>
          <DialogTitle>라운드별 점수</DialogTitle>
          <DialogDescription>
            {rows.length > 0
              ? '지금까지 각 라운드에서 얻은 점수입니다.'
              : '아직 끝난 라운드가 없습니다.'}
          </DialogDescription>
        </DialogHeader>
        {rows.length > 0 && (
          <RoundHistoryTable
            rows={rows}
            labels={{ left: '우리', right: '상대' }}
            totals={totals}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}
