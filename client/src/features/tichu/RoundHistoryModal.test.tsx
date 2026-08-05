import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { RoundHistoryModal } from './RoundHistoryModal';

/**
 * D-108 — 관점 스왑 회귀 고정.
 *
 * 점수 칩이 "우리/상대"를 내 팀 기준으로 보여주므로 모달도 같아야 한다. B팀 좌석에서
 * Team A 점수가 "우리" 열에 오면 같은 화면에서 두 관점이 부딪힌다 — 눈으로는 잘 안
 * 보이고 점수가 뒤집혀 읽히기만 하는 종류의 버그라 테스트로 못 박는다.
 */

const history = [
  { teamAScore: 100, teamBScore: 25, firstFinisherSeat: 0, doubleVictory: false },
  { teamAScore: 30, teamBScore: 70, firstFinisherSeat: 1, doubleVictory: false },
];

const playerIds = [11, 22, 33, 44];
const usernames = { 11: 'alice', 22: 'bob', 33: 'carol', 44: 'dave' };

function renderModal(myTeam: 'A' | 'B', roundHistory = history) {
  return render(
    <RoundHistoryModal
      open
      onOpenChange={() => {}}
      roundHistory={roundHistory}
      myTeam={myTeam}
      playerIds={playerIds}
      usernames={usernames}
    />,
  );
}

/** 모달은 portal 로 렌더되므로 container 가 아니라 document 에서 찾는다. */
function bodyRows() {
  return [...document.querySelectorAll('tbody tr')].map((tr) =>
    [...tr.querySelectorAll('td')].map((td) => td.textContent),
  );
}

describe('RoundHistoryModal — 우리/상대 관점', () => {
  it('A팀이면 teamAScore 가 우리 열', () => {
    renderModal('A');

    const rows = bodyRows();
    expect(rows[0]?.slice(0, 3)).toEqual(['1', '100', '25']);
    expect(rows[1]?.slice(0, 3)).toEqual(['2', '30', '70']);
  });

  it('B팀이면 teamBScore 가 우리 열 — 좌우가 뒤바뀐다', () => {
    renderModal('B');

    const rows = bodyRows();
    expect(rows[0]?.slice(0, 3)).toEqual(['1', '25', '100']);
    expect(rows[1]?.slice(0, 3)).toEqual(['2', '70', '30']);
  });

  it('합계는 라운드 증분의 합이고, 관점에 따라 같이 뒤바뀐다', () => {
    renderModal('B');

    const total = bodyRows().at(-1);
    expect(total?.slice(0, 3)).toEqual(['합계', '95', '130']);
  });

  it('첫 완주자 좌석을 닉네임으로 보여준다', () => {
    renderModal('A');

    const badges = [...document.querySelectorAll('.score-history-finisher')];
    expect(badges.map((b) => b.textContent?.trim())).toEqual(['🏁 alice', '🏁 bob']);
  });

  it('끝난 라운드가 없으면 표 대신 안내를 보여준다', () => {
    renderModal('A', []);

    expect(document.querySelector('table')).toBeNull();
    expect(document.body.textContent).toContain('아직 끝난 라운드가 없습니다');
  });
});
