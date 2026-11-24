import { GameIcon } from '@/components/GameIcon';
import type { GameRecord } from '@/api/users';

interface Props {
  records: GameRecord[];
  /** gameType → 표시명. 카탈로그에서 받는다 — 게임 이름을 클라에 박지 않는다(D-106). */
  names: Record<string, string>;
}

/**
 * D-115 — 프로필의 게임별 전적. D-303 — 촘촘하게: 한 게임 = 한 줄 칩(아이콘·이름 왼쪽, 전적 오른쪽), 넓은 화면은 2열.
 * D-305 — 계급은 통합 하나라 게임마다 계급 배지를 두지 않는다(게임별 레이팅은 기록용 — 풍선말로만).
 */
export function GameRecords({ records, names }: Props) {
  if (records.length === 0) {
    return <p className="m-0 text-sm italic text-muted-foreground">아직 플레이한 게임이 없습니다.</p>;
  }
  return (
    <ul className="m-0 grid list-none grid-cols-1 gap-1.5 p-0 sm:grid-cols-2">
      {records.map((r) => {
        const played = r.winCount + r.loseCount > 0;
        return (
          <li
            key={r.gameType}
            title={`이 게임에서 쌓인 레이팅 ${r.rating}`}
            className="flex min-w-0 items-center gap-2 rounded-md border px-2.5 py-1.5 text-sm"
          >
            <GameIcon gameId={r.gameType} size={20} />
            <span className="min-w-0 truncate font-medium">{names[r.gameType] ?? r.gameType}</span>
            <span className="ml-auto flex shrink-0 items-center gap-1.5 whitespace-nowrap text-xs tabular-nums text-muted-foreground">
              {/* D-201 — 최고 점수만 있는 줄(혼자만 한 얏추)은 0승 0패를 쓰지 않는다. */}
              {(played || r.bestScore == null) && (
                <span>
                  {r.winCount}승 {r.loseCount}패{r.desertCount > 0 && ` · 탈주 ${r.desertCount}`}
                </span>
              )}
              {/* D-201 — 개인 최고 점수(요트). */}
              {r.bestScore != null && <span className="font-medium text-foreground">최고 {r.bestScore}점</span>}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
