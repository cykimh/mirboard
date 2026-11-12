import { useEffect, useMemo, useState } from 'react';
import { RANKING_ALL, usersApi, type RankEntry, type Tier, type UserStats } from '@/api/users';
import type { GameSummary } from '@/types/api';
import { TierBadge } from '@/components/TierBadge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { HUB_SCROLL } from '@/features/lobby/hubScroll';
import { cn } from '@/lib/utils';
import { isGuestName, STARTING_TIER } from './playerTiers';
import { primaryGame } from './primaryGame';
import { TierTableButton } from './TierTableButton';

interface Props {
  token: string;
  /** 게임 선택지. 게임 이름을 클라에 박지 않는다(D-106). 순서는 많이 한 게임부터(D-160), 판수 없는 게임은 이 순서로 뒤에. */
  games: GameSummary[];
  myUserId?: number;
}

const RECENT_SLOTS = 5;
const RESULT_WORD: Record<string, string> = { W: '승', L: '패', D: '무' };

/** 최근 결과 점 다섯 칸. 왼쪽이 오래된 경기, 오른쪽이 가장 최근. 승 = 채운 점, 패 = 빈 점, 무 = 작은 줄. */
function RecentDots({ recent }: { recent: string }) {
  const results = recent.slice(-RECENT_SLOTS).split('').filter((c) => c in RESULT_WORD);
  const label =
    results.length === 0
      ? '최근 경기 없음'
      : `최근 ${results.length}경기(왼쪽이 오래된 경기): ${results.map((c) => RESULT_WORD[c]).join(' ')}`;
  return (
    <span role="img" aria-label={label} title={label} className="inline-flex items-center gap-[3px]">
      {Array.from({ length: RECENT_SLOTS }, (_, i) => {
        const c = results[i];
        return (
          <span
            key={i}
            aria-hidden
            data-result={c ?? ''}
            className={cn(
              'box-border inline-block h-2 w-2 rounded-full',
              c === 'W' && 'bg-emerald-500',
              c === 'L' && 'border-[1.5px] border-muted-foreground/70',
              c === 'D' && 'h-[3px] rounded-sm bg-muted-foreground/60',
            )}
          />
        );
      })}
    </span>
  );
}

/**
 * D-266 — 계급표에서 강조할 내 계급. 고른 게임의 계급, "전체"면 가장 많이 한 게임 것(프로필 헤더와 같은 규칙 — 서버 전체 랭킹의
 * 계급도 같다). 안 해 본 게임은 시작 계급, 게스트는 없음.
 */
function myTierOf(stats: UserStats, gameType: string): Tier | null {
  if (isGuestName(stats.username)) return null;
  if (stats.fixedTier) return stats.fixedTier;
  const records = stats.games ?? [];
  const rec = gameType === RANKING_ALL ? primaryGame(records) : records.find((g) => g.gameType === gameType);
  return rec?.tier ?? STARTING_TIER;
}

/**
 * D-199·D-209 — 이번 달 랭킹. 오른쪽 위에서 게임을 고른다 — 기본은 "전체"(모든 게임 합), 선택지는 많이 한 게임부터(D-160).
 * 순서는 서버가 정한다(이번 달 승수 → 승률 → 판수). 계급은 누적 레이팅 것이라 별명 왼쪽에 아이콘으로만 둔다.
 */
export function RankingCard({ token, games, myUserId }: Props) {
  const [gameType, setGameType] = useState<string>(RANKING_ALL);
  /** null = 아직 한 번도 못 받았다(받는 중). 게임을 바꾸는 동안에는 앞 목록을 그대로 둔다 — 깜빡이지 않게. */
  const [entries, setEntries] = useState<RankEntry[] | null>(null);
  /** 게임별 판수. null = 아직 받는 중(카탈로그 순서로 둔다). */
  const [plays, setPlays] = useState<Record<string, number> | null>(null);
  /** D-266 — 내 전적. 계급표를 열 때만 받는다(랭킹 20위 밖이어도 내 계급을 강조하게). */
  const [myStats, setMyStats] = useState<UserStats | null>(null);

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

  useEffect(() => {
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

  const gameName = (id: string) => games.find((g) => g.id === id)?.displayName ?? id;

  // 내 전적을 받기 전에는 목록 속 내 줄의 계급으로.
  const myEntryTier = entries?.find((e) => e.userId === myUserId)?.tier ?? null;
  const myTier = myStats ? myTierOf(myStats, gameType) : myEntryTier;
  const loadMyStats = () => {
    if (myUserId === undefined) return;
    usersApi
      .stats(token, myUserId)
      .then(setMyStats)
      .catch(() => {});
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0 pb-3">
        <div className="min-w-0 space-y-1">
          <CardTitle>랭킹</CardTitle>
          <p className="m-0 text-xs text-muted-foreground">이번 달 전적 · 매달 1일 0시 초기화</p>
        </div>
        {/* D-266 — [계급표]는 게임 선택 상자 왼쪽. 휴대폰 폭에서 둘이 한 줄에 들도록 선택 상자 폭을 줄였다. */}
        <div className="flex shrink-0 items-center gap-1.5">
          <TierTableButton
            myTier={myTier}
            basis={gameType === RANKING_ALL ? '가장 많이 한 게임 기준' : `${gameName(gameType)} 기준`}
            onOpen={loadMyStats}
          />
          <select
            aria-label="랭킹 게임"
            value={gameType}
            onChange={(e) => setGameType(e.target.value)}
            className="h-9 max-w-[7.5rem] shrink-0 rounded-md border border-input bg-background px-2 text-sm text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring sm:max-w-[9.5rem]"
          >
            <option value={RANKING_ALL}>전체</option>
            {ordered.map((g) => (
              <option key={g.id} value={g.id}>
                {g.displayName}
              </option>
            ))}
          </select>
        </div>
      </CardHeader>
      <CardContent className="px-2 sm:px-6">
        {/* 받는 중·빈 목록·긴 목록 모두 같은 높이의 상자 안에서 — 카드 높이가 바뀌지 않는다. */}
        <div data-testid="ranking-scroll" className={cn('h-64', HUB_SCROLL)}>
          {entries === null ? (
            <p className="m-0 flex h-full items-center justify-center text-sm text-muted-foreground">불러오는 중…</p>
          ) : entries.length === 0 ? (
            <p className="m-0 flex h-full items-center justify-center text-sm text-muted-foreground">
              이번 달 아직 기록이 없어요
            </p>
          ) : (
            // D-164 — 휴대폰 폭에서 칸은 한 줄로, 탈주는 넓은 화면만. shadcn Table 의 겹 스크롤 상자를 쓰지 않는다(머리 줄 고정).
            <table className="w-full caption-bottom text-[13px] sm:text-sm">
              <TableHeader className="[&_th]:sticky [&_th]:top-0 [&_th]:z-10 [&_th]:bg-card">
                <TableRow>
                  <TableHead className="w-7 whitespace-nowrap px-0.5 sm:w-8 sm:px-2">등수</TableHead>
                  <TableHead className="px-1 sm:px-2">유저</TableHead>
                  <TableHead className="px-1 sm:px-2">전적</TableHead>
                  <TableHead className="px-1 sm:px-2">승률</TableHead>
                  <TableHead
                    className="whitespace-nowrap px-1 text-xs sm:px-2 sm:text-sm"
                    title="왼쪽이 오래된 경기, 오른쪽이 가장 최근 경기"
                  >
                    최근 5경기
                  </TableHead>
                  <TableHead className="hidden px-2 sm:table-cell">탈주</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map((e) => (
                  <TableRow
                    key={e.userId}
                    className={cn(e.userId === myUserId && 'bg-accent font-semibold')}
                  >
                    <TableCell className="px-0.5 py-2 text-center sm:px-2">{e.rank}</TableCell>
                    <TableCell className="px-1 py-2 sm:px-2">
                      <span className="flex items-center gap-1.5">
                        <span className="inline-flex shrink-0">
                          <TierBadge tier={e.tier} size={22} />
                        </span>
                        {/* 계급은 누적 레이팅 — 전체 랭킹이면 가장 많이 한 게임 기준이라 별명 풍선말에 그 게임을 밝힌다. */}
                        <span
                          className="max-w-[4rem] truncate sm:max-w-[12rem]"
                          title={
                            gameType === RANKING_ALL && e.tierGameType
                              ? `${e.username} · 계급은 ${gameName(e.tierGameType)} 기준`
                              : e.username
                          }
                        >
                          {e.username}
                        </span>
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap px-1 py-2 sm:px-2">
                      {e.winCount}승 {e.loseCount}패
                    </TableCell>
                    <TableCell className="whitespace-nowrap px-1 py-2 tabular-nums sm:px-2">
                      {e.winRate == null ? '-' : `${e.winRate}%`}
                    </TableCell>
                    <TableCell className="px-1 py-2 sm:px-2">
                      <RecentDots recent={e.recent ?? ''} />
                    </TableCell>
                    <TableCell className="hidden px-2 py-2 sm:table-cell">{e.desertCount}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </table>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
