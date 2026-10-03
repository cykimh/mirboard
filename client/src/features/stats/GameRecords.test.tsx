import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { GameRecords } from './GameRecords';

describe('GameRecords (D-115 — 프로필 게임별 전적)', () => {
  it('게임 표시명과 전적을 게임마다 보여 준다', () => {
    render(
      <GameRecords
        records={[
          { gameType: 'TICHU', rating: 1020, tier: 'BRONZE', winCount: 3, loseCount: 1, desertCount: 0 },
          { gameType: 'SKULL_KING', rating: 985, tier: 'BRONZE', winCount: 0, loseCount: 2, desertCount: 1 },
        ]}
        names={{ TICHU: '티츄', SKULL_KING: '스컬킹' }}
      />,
    );

    expect(screen.getByText('티츄')).toBeTruthy();
    expect(screen.getByText('스컬킹')).toBeTruthy();
    expect(screen.getByText(/3승 1패/)).toBeTruthy();
    expect(screen.getByText(/0승 2패 · 탈주 1/)).toBeTruthy();
  });

  it('표시명을 모르면 게임 id 로 폴백', () => {
    render(
      <GameRecords
        records={[{ gameType: 'YACHT', rating: 1000, tier: 'BRONZE', winCount: 1, loseCount: 0, desertCount: 0 }]}
        names={{}}
      />,
    );
    expect(screen.getByText('YACHT')).toBeTruthy();
  });

  it('한 판도 안 했으면 안내 문구', () => {
    render(<GameRecords records={[]} names={{}} />);
    expect(screen.getByText(/아직 플레이한 게임이 없습니다/)).toBeTruthy();
  });
});
