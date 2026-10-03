import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RankingCard } from './RankingCard';
import type { GameSummary } from '@/types/api';

const { ranking } = vi.hoisted(() => ({ ranking: vi.fn() }));
vi.mock('@/api/users', () => ({ usersApi: { ranking } }));

const game = (id: string, displayName: string): GameSummary => ({
  id,
  displayName,
  shortDescription: '',
  minPlayers: 2,
  maxPlayers: 8,
  status: 'AVAILABLE',
  supportedRoomOptions: [],
});

const entry = (userId: number, username: string, rating: number) => ({
  rank: 1,
  userId,
  username,
  rating,
  tier: 'BRONZE' as const,
  winCount: 1,
  loseCount: 0,
  desertCount: 0,
});

describe('RankingCard (D-115 — 게임별 랭킹)', () => {
  beforeEach(() => {
    ranking.mockReset();
    ranking.mockImplementation((_t: string, _l: number, gameType: string) =>
      Promise.resolve({
        gameType,
        entries: gameType === 'SKULL_KING' ? [entry(1, 'sk_king', 1300)] : [entry(2, 'tichu_pro', 1400)],
      }),
    );
  });

  it('카탈로그 게임마다 탭을 만들고 첫 게임 랭킹부터 보여 준다', async () => {
    render(<RankingCard token="tok" games={[game('SKULL_KING', '스컬킹'), game('TICHU', '티츄')]} />);

    expect(screen.getByRole('tab', { name: '스컬킹' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: '티츄' })).toBeTruthy();
    expect(await screen.findByText('sk_king')).toBeTruthy();
    expect(ranking).toHaveBeenCalledWith('tok', 20, 'SKULL_KING');
  });

  it('탭을 바꾸면 그 게임 랭킹을 다시 받는다', async () => {
    render(<RankingCard token="tok" games={[game('SKULL_KING', '스컬킹'), game('TICHU', '티츄')]} />);
    await screen.findByText('sk_king');

    fireEvent.mouseDown(screen.getByRole('tab', { name: '티츄' }));
    fireEvent.click(screen.getByRole('tab', { name: '티츄' }));

    expect(await screen.findByText('tichu_pro')).toBeTruthy();
    await waitFor(() => expect(ranking).toHaveBeenLastCalledWith('tok', 20, 'TICHU'));
    expect(screen.queryByText('sk_king')).toBeNull();
  });

  it('기록이 없으면 빈 안내', async () => {
    ranking.mockResolvedValue({ gameType: 'TICHU', entries: [] });
    render(<RankingCard token="tok" games={[game('TICHU', '티츄')]} />);

    expect(await screen.findByText(/아직 랭킹 데이터가 없습니다/)).toBeTruthy();
  });
});
