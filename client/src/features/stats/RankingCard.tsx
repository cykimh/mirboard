import { useEffect, useMemo, useState } from 'react';
import { usersApi, type RankEntry } from '@/api/users';
import type { GameSummary } from '@/types/api';
import { TierBadge } from '@/components/TierBadge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';

interface Props {
  token: string;
  /** 탭 목록. 게임 이름을 클라에 박지 않는다(D-106). 순서는 많이 한 게임부터(D-160), 판수 없는 게임은 이 순서로 뒤에. */
  games: GameSummary[];
  myUserId?: number;
}

/**
 * D-115 — 게임별 랭킹. 탭을 고르면 그 게임 랭킹을 받아 온다. D-160 — 탭은 전체에서 많이 한 게임부터이고 처음 탭도 그 게임이다.
 * 판수를 받기 전에는 랭킹을 받지 않는다 — 카탈로그 첫 게임을 먼저 그렸다가 바꾸면 깜빡인다. 판수를 못 받으면 카탈로그 순서다.
 */
export function RankingCard({ token, games, myUserId }: Props) {
  const [selected, setSelected] = useState<string | null>(null);
  const [entries, setEntries] = useState<RankEntry[]>([]);
  /** 게임별 판수. null = 아직 받는 중. */
  const [plays, setPlays] = useState<Record<string, number> | null>(null);

  useEffect(() => {
    let cancelled = false;
    usersApi
      .rankingGames(token)
      .then((r) => {
        if (!cancelled) setPlays(Object.fromEntries(r.games.map((g) => [g.gameType, g.plays])));
      })
      .catch(() => {
        if (!cancelled) setPlays({});
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const ordered = useMemo(
    () =>
      plays === null
        ? games
        : games
            .map((g, i) => ({ g, i }))
            .sort((a, b) => (plays[b.g.id] ?? 0) - (plays[a.g.id] ?? 0) || a.i - b.i)
            .map(({ g }) => g),
    [games, plays],
  );
  const gameType = selected ?? (plays === null ? null : (ordered[0]?.id ?? null));

  useEffect(() => {
    if (!gameType) return;
    let cancelled = false;
    usersApi
      .ranking(token, 20, gameType)
      .then((r) => {
        if (!cancelled) setEntries(r.entries);
      })
      .catch(() => {
        if (!cancelled) setEntries([]);
      });
    return () => {
      cancelled = true;
    };
  }, [token, gameType]);

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2 space-y-0">
        <CardTitle>랭킹</CardTitle>
        <div role="tablist" aria-label="랭킹 게임" className="flex gap-1">
          {ordered.map((g) => (
            <button
              key={g.id}
              type="button"
              role="tab"
              aria-selected={g.id === gameType}
              onClick={() => setSelected(g.id)}
              className={cn(
                'rounded-md border px-3 py-1 text-sm',
                g.id === gameType
                  ? 'border-primary bg-primary text-primary-foreground'
                  : 'hover:bg-accent',
              )}
            >
              {g.displayName}
            </button>
          ))}
        </div>
      </CardHeader>
      <CardContent>
        {entries.length === 0 ? (
          <p className="text-sm italic text-muted-foreground">아직 랭킹 데이터가 없습니다.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-10">#</TableHead>
                <TableHead>유저</TableHead>
                <TableHead>티어</TableHead>
                <TableHead>레이팅</TableHead>
                <TableHead>전적</TableHead>
                <TableHead>탈주</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {entries.map((e) => (
                <TableRow
                  key={e.userId}
                  className={cn(e.userId === myUserId && 'bg-accent font-semibold')}
                >
                  <TableCell>{e.rank}</TableCell>
                  <TableCell>{e.username}</TableCell>
                  <TableCell>
                    <TierBadge tier={e.tier} />
                  </TableCell>
                  <TableCell>{e.rating}</TableCell>
                  <TableCell>
                    {e.winCount}승 {e.loseCount}패
                  </TableCell>
                  <TableCell>{e.desertCount}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}
