import type { GameRecord } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';

interface Props {
  records: GameRecord[];
  /** gameType → 표시명. 카탈로그에서 받는다 — 게임 이름을 클라에 박지 않는다(D-106). */
  names: Record<string, string>;
}

/** D-115 — 프로필의 게임별 전적. */
export function GameRecords({ records, names }: Props) {
  if (records.length === 0) {
    return <p className="text-sm italic text-muted-foreground">아직 플레이한 게임이 없습니다.</p>;
  }
  return (
    <ul className="flex flex-col gap-2">
      {records.map((r) => (
        <li key={r.gameType} className="flex flex-wrap items-center gap-3 rounded-md border px-3 py-2">
          <span className="min-w-16 font-medium">{names[r.gameType] ?? r.gameType}</span>
          <TierBadge tier={r.tier} rating={r.rating} />
          <span className="text-sm text-muted-foreground">
            {r.winCount}승 {r.loseCount}패{r.desertCount > 0 && ` · 탈주 ${r.desertCount}`}
          </span>
        </li>
      ))}
    </ul>
  );
}
