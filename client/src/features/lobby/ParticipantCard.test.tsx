import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ParticipantCard } from './ParticipantCard';
import type { GameRecord } from '@/api/users';

const RECORD: GameRecord = { gameType: 'ONE_CARD', rating: 1010, tier: 'MISSILE', winCount: 3, loseCount: 2, desertCount: 0 };

function renderCard(props: Partial<Parameters<typeof ParticipantCard>[0]> = {}) {
  render(
    <ul>
      <ParticipantCard userId={7} username="철수" record={RECORD} {...props} />
    </ul>,
  );
  return screen.getByTestId('participant-name');
}

/** D-174 — 대기실 참가자 카드의 계급 아이콘은 이름 왼쪽(게임판 좌석과 같은 자리), 전적은 그 아래. */
describe('ParticipantCard — 계급은 이름 왼쪽 (D-174)', () => {
  it('계급 아이콘이 이름 앞(왼쪽)에 같은 줄로 붙는다', () => {
    const row = renderCard();
    const name = within(row).getByText('철수');
    const icon = row.firstElementChild as Element;
    expect(icon).not.toBe(name);
    expect(icon.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText('3승 2패')).toBeInTheDocument();
  });

  it('봇은 이름만', () => {
    expect(renderCard({ bot: true }).children).toHaveLength(1);
  });

  it('첫 판이면 이름만 있고 "첫 판"을 아래에 쓴다', () => {
    const row = renderCard({ record: null });
    expect(row.children).toHaveLength(1);
    expect(screen.getByText('첫 판')).toBeInTheDocument();
  });

  it('D-201 — 최고 점수가 있으면 전적 아래에, 혼자만 해 봤으면 그 줄만', () => {
    renderCard({ record: { ...RECORD, gameType: 'YACHT', bestScore: 245 } });
    expect(screen.getByText('3승 2패')).toBeInTheDocument();
    expect(screen.getByText('최고 245점')).toBeInTheDocument();
  });

  it('D-201 — 0승 0패 + 최고 점수(혼자만 한 요트)면 "첫 판"·승패 없이 최고만', () => {
    renderCard({ record: { ...RECORD, gameType: 'YACHT', winCount: 0, loseCount: 0, bestScore: 199 } });
    expect(screen.getByText('최고 199점')).toBeInTheDocument();
    expect(screen.queryByText('첫 판')).toBeNull();
    expect(screen.queryByText('0승 0패')).toBeNull();
  });
});

/** D-272 — 방장 표시는 이름 아래 배지가 아니라 방장 카드 맨 위 띠다. */
describe('ParticipantCard — 방장 띠 (D-272)', () => {
  it('방장 카드는 맨 위(첫 자식)에 "방장" 띠가 있고 이름은 그 아래다', () => {
    const row = renderCard({ host: true });
    const card = screen.getByRole('listitem', { name: '철수' });
    const strip = screen.getByTestId('host-strip');
    expect(card.firstElementChild).toBe(strip);
    expect(strip).toHaveTextContent('방장');
    expect(strip.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('방장이 아니면 띠가 없다', () => {
    renderCard({ ready: true });
    expect(screen.queryByTestId('host-strip')).toBeNull();
    expect(screen.getByText('준비')).toBeInTheDocument();
  });
});
