import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { GameRecords } from './GameRecords';

describe('GameRecords (D-115 — 프로필 게임별 전적)', () => {
  it('게임 표시명과 전적을 게임마다 보여 준다', () => {
    render(
      <GameRecords
        records={[
          { gameType: 'TICHU', rating: 1020, tier: 'MISSILE', winCount: 3, loseCount: 1, desertCount: 0 },
          { gameType: 'SKULL_KING', rating: 985, tier: 'BULLET', winCount: 0, loseCount: 2, desertCount: 1 },
        ]}
        names={{ TICHU: '티츄', SKULL_KING: '스컬킹' }}
      />,
    );

    expect(screen.getByText('티츄')).toBeTruthy();
    expect(screen.getByText('스컬킹')).toBeTruthy();
    expect(screen.getByText(/3승 1패/)).toBeTruthy();
    expect(screen.getByText(/0승 2패 · 탈주 1/)).toBeTruthy();
  });

  it('D-201 — 개인 최고 점수가 있으면 함께 보인다', () => {
    render(
      <GameRecords
        records={[
          { gameType: 'YACHT', rating: 1000, tier: 'MISSILE', winCount: 0, loseCount: 0, desertCount: 0, bestScore: 231 },
        ]}
        names={{ YACHT: '얏추' }}
      />,
    );
    expect(screen.getByText('최고 231점')).toBeTruthy();
    // 최고 점수만 있는 줄은 0승 0패를 쓰지 않는다(D-303).
    expect(screen.queryByText(/0승 0패/)).toBeNull();
  });

  it('D-303·D-305 — 촘촘한 한 줄 칩: 게임별 계급 배지가 없다(계급은 통합 하나)', () => {
    render(
      <GameRecords
        records={[{ gameType: 'TICHU', rating: 1020, tier: 'GOLD_STAR', winCount: 3, loseCount: 1, desertCount: 0 }]}
        names={{ TICHU: '티츄' }}
      />,
    );
    expect(screen.queryByRole('img', { name: '금별' })).toBeNull();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    // 게임별 레이팅은 기록용 — 풍선말로만.
    expect(screen.getByRole('listitem')).toHaveAttribute('title', '이 게임에서 쌓인 레이팅 1020');
  });

  it('표시명을 모르면 게임 id 로 폴백', () => {
    render(
      <GameRecords
        records={[{ gameType: 'YACHT', rating: 1000, tier: 'MISSILE', winCount: 1, loseCount: 0, desertCount: 0 }]}
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
