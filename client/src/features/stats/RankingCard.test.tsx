import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RankingCard } from './RankingCard';
import { HUB_SCROLL } from '@/features/lobby/hubScroll';
import type { GameSummary } from '@/types/api';

const { ranking, rankingGames, stats } = vi.hoisted(() => ({ ranking: vi.fn(), rankingGames: vi.fn(), stats: vi.fn() }));
vi.mock('@/api/users', () => ({ RANKING_ALL: 'ALL', usersApi: { ranking, rankingGames, stats } }));

const game = (id: string, displayName: string): GameSummary => ({
  id,
  displayName,
  shortDescription: '',
  minPlayers: 2,
  maxPlayers: 8,
  status: 'AVAILABLE',
  supportedRoomOptions: [],
});

const entry = (userId: number, username: string, over: Record<string, unknown> = {}) => ({
  rank: 1,
  userId,
  username,
  rating: 1000,
  tier: 'MISSILE' as const,
  tierGameType: 'TICHU',
  winCount: 1,
  loseCount: 0,
  desertCount: 0,
  winRate: 100,
  recent: 'W',
  ...over,
});

const CATALOG = [game('SKULL_KING', '스컬킹'), game('ONE_CARD', '원카드'), game('TICHU', '티츄')];

const select = () => screen.getByRole('combobox', { name: '랭킹 게임' }) as HTMLSelectElement;

describe('RankingCard (D-199 — 이번 달 랭킹, 전체·게임별)', () => {
  beforeEach(() => {
    rankingGames.mockReset();
    rankingGames.mockResolvedValue({ games: [] });
    ranking.mockReset();
    ranking.mockImplementation((_t: string, _l: number, gameType: string) =>
      Promise.resolve({
        gameType,
        month: '2026-11',
        entries:
          gameType === 'ALL'
            ? [entry(1, 'all_top', { winCount: 5, loseCount: 3, winRate: 63, recent: 'WLWWL' })]
            : [entry(2, `top_${gameType}`)],
      }),
    );
  });

  it('처음에는 "전체" 랭킹을 받는다 — 게임 탭은 없고 오른쪽 위 선택 상자다', async () => {
    render(<RankingCard token="tok" games={CATALOG} />);

    expect(await screen.findByText('all_top')).toBeTruthy();
    expect(select().value).toBe('ALL');
    expect(ranking).toHaveBeenCalledTimes(1);
    expect(ranking).toHaveBeenCalledWith('tok', 20, 'ALL');
    expect(screen.queryByRole('tab')).toBeNull();
    expect(screen.getByText('이번 달 전적 · 매달 1일 0시 초기화')).toBeTruthy();
  });

  it('게임을 고르면 그 게임 랭킹을 다시 받는다', async () => {
    render(<RankingCard token="tok" games={CATALOG} />);
    await screen.findByText('all_top');

    fireEvent.change(select(), { target: { value: 'TICHU' } });

    expect(await screen.findByText('top_TICHU')).toBeTruthy();
    expect(ranking).toHaveBeenLastCalledWith('tok', 20, 'TICHU');
    expect(screen.queryByText('all_top')).toBeNull();
  });

  it('선택지는 "전체" 다음에 많이 한 게임부터 — 판수 없는 게임은 카탈로그 순서로 뒤에 (D-160)', async () => {
    rankingGames.mockResolvedValue({ games: [{ gameType: 'TICHU', plays: 40 }, { gameType: 'ONE_CARD', plays: 12 }] });
    render(<RankingCard token="tok" games={CATALOG} />);

    await waitFor(() =>
      expect(Array.from(select().options).map((o) => o.textContent)).toEqual(['전체', '티츄', '원카드', '스컬킹']),
    );
    // 순서를 받아도 고른 것은 "전체" 그대로다.
    expect(select().value).toBe('ALL');
  });

  it('판수를 못 받으면 카탈로그 순서 그대로', async () => {
    rankingGames.mockRejectedValue(new Error('down'));
    render(<RankingCard token="tok" games={CATALOG} />);

    await screen.findByText('all_top');
    expect(Array.from(select().options).map((o) => o.textContent)).toEqual(['전체', '스컬킹', '원카드', '티츄']);
  });

  it('머리 줄은 등수·유저·전적·승률·최근 5경기 — "#"·계급 칸이 없다', async () => {
    render(<RankingCard token="tok" games={CATALOG} />);
    await screen.findByText('all_top');

    const heads = screen.getAllByRole('columnheader').map((h) => h.textContent);
    expect(heads).toEqual(['등수', '유저', '전적', '승률', '최근 5경기', '탈주']);
    expect(screen.getByRole('columnheader', { name: '최근 5경기' })).toHaveAttribute(
      'title',
      '왼쪽이 오래된 경기, 오른쪽이 가장 최근 경기',
    );
  });

  it('계급 아이콘은 별명 왼쪽, 같은 칸 안에 있다 — 전체에서는 기준 게임을 밝힌다', async () => {
    render(<RankingCard token="tok" games={CATALOG} />);
    const name = await screen.findByText('all_top');

    const cell = name.closest('td')!;
    const icon = within(cell).getByRole('img', { name: '미사일' });
    expect(icon.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(name).toHaveAttribute('title', 'all_top · 계급은 티츄 기준');
  });

  it('승률과 최근 5경기 점(오래된 것 → 최근)을 그린다', async () => {
    render(<RankingCard token="tok" games={CATALOG} />);
    const row = (await screen.findByText('all_top')).closest('tr')!;

    expect(within(row).getByText('5승 3패')).toBeTruthy();
    expect(within(row).getByText('63%')).toBeTruthy();
    const dots = within(row).getByRole('img', { name: /최근 5경기\(왼쪽이 오래된 경기\): 승 패 승 승 패/ });
    expect(Array.from(dots.children).map((d) => d.getAttribute('data-result'))).toEqual(['W', 'L', 'W', 'W', 'L']);
  });

  it('다섯 판이 안 되면 왼쪽부터 채우고 빈 칸을 남긴다 — 승·패가 없으면 승률은 "-"', async () => {
    ranking.mockResolvedValue({
      gameType: 'ALL',
      entries: [entry(1, 'draw_only', { winCount: 0, loseCount: 0, winRate: null, recent: 'DW' })],
    });
    render(<RankingCard token="tok" games={CATALOG} />);
    const row = (await screen.findByText('draw_only')).closest('tr')!;

    expect(within(row).getByText('-')).toBeTruthy();
    const dots = within(row).getByRole('img', { name: /최근 2경기.*: 무 승/ });
    expect(Array.from(dots.children).map((d) => d.getAttribute('data-result'))).toEqual(['D', 'W', '', '', '']);
  });

  it('기록이 없으면 빈 안내', async () => {
    ranking.mockResolvedValue({ gameType: 'ALL', entries: [] });
    render(<RankingCard token="tok" games={CATALOG} />);

    expect(await screen.findByText('이번 달 아직 기록이 없어요')).toBeTruthy();
  });

  it('받는 중·빈 목록·목록 모두 같은 고정 높이 스크롤 상자 안이다 — 카드 높이가 바뀌지 않는다', async () => {
    render(<RankingCard token="tok" games={[]} />);

    // 받는 중 — 카탈로그(게임 목록)가 아직 없어도 자리를 잡는다.
    const box = screen.getByTestId('ranking-scroll');
    expect(within(box).getByText('불러오는 중…')).toBeTruthy();
    for (const cls of ['h-64', ...HUB_SCROLL.split(' ')]) expect(box).toHaveClass(cls);
    expect(HUB_SCROLL).toContain('overflow-y-auto');
    expect(HUB_SCROLL).toContain('[scrollbar-width:thin]');

    await screen.findByText('all_top');
    expect(screen.getByTestId('ranking-scroll')).toBe(box);
    expect(within(box).getByRole('table')).toBeTruthy();
  });

  it('내 줄은 강조한다', async () => {
    render(<RankingCard token="tok" games={CATALOG} myUserId={1} />);

    expect((await screen.findByText('all_top')).closest('tr')).toHaveClass('bg-accent');
  });
});

/** D-266 — 머리 줄 [계급표]: 고른 게임 기준으로 내 계급을 강조한다(내 전적은 열 때 받는다). */
describe('RankingCard — 계급표 (D-266)', () => {
  const rec = (gameType: string, tier: string, w: number) => ({
    gameType,
    rating: 1000,
    tier,
    winCount: w,
    loseCount: 0,
    desertCount: 0,
  });

  beforeEach(() => {
    rankingGames.mockReset();
    rankingGames.mockResolvedValue({ games: [] });
    ranking.mockReset();
    ranking.mockImplementation((_t: string, _l: number, gameType: string) =>
      Promise.resolve({ gameType, entries: [entry(9, 'someone')] }),
    );
    stats.mockReset();
    stats.mockResolvedValue({
      userId: 1,
      username: '철수',
      games: [rec('TICHU', 'SILVER_MEDAL', 9), rec('ONE_CARD', 'BULLET', 2)],
    });
  });

  const current = () =>
    within(screen.getByRole('dialog', { name: '계급표' }))
      .getAllByRole('listitem')
      .filter((li) => li.getAttribute('aria-current') === 'true')
      .map((li) => li.getAttribute('data-tier'));

  it('선택 상자 옆 버튼으로 열고, "전체"면 가장 많이 한 게임의 계급을 강조한다', async () => {
    render(<RankingCard token="tok" games={CATALOG} myUserId={1} />);
    await screen.findByText('someone');

    const button = screen.getByRole('button', { name: '계급표' });
    expect(button.parentElement).toBe(select().parentElement);
    fireEvent.click(button);

    expect(within(screen.getByRole('dialog')).getAllByRole('listitem')).toHaveLength(16);
    expect(stats).toHaveBeenCalledWith('tok', 1);
    await waitFor(() => expect(current()).toEqual(['SILVER_MEDAL']));
    expect(screen.getByRole('dialog')).toHaveTextContent('가장 많이 한 게임 기준');
  });

  it('게임을 고르면 그 게임 계급, 안 해 본 게임이면 시작 계급(미사일)', async () => {
    render(<RankingCard token="tok" games={CATALOG} myUserId={1} />);
    await screen.findByText('someone');

    fireEvent.change(select(), { target: { value: 'ONE_CARD' } });
    fireEvent.click(screen.getByRole('button', { name: '계급표' }));
    await waitFor(() => expect(current()).toEqual(['BULLET']));
    expect(screen.getByRole('dialog')).toHaveTextContent('원카드 기준');

    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    fireEvent.change(select(), { target: { value: 'SKULL_KING' } });
    fireEvent.click(screen.getByRole('button', { name: '계급표' }));
    await waitFor(() => expect(current()).toEqual(['MISSILE']));
  });
});
