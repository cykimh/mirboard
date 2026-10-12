import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { ArenaChatBubbles } from './ArenaChatBubbles';
import { useRoomChatStore } from './roomChatStore';

/**
 * D-132 — 말풍선도 좌석 클래스·카드 비행과 같은 자리(`viewPosOf`)에서 뜬다(설계 성공 기준 4). 매핑이 갈라지면 상대 좌석은
 * 왼쪽인데 말풍선은 오른쪽에 뜬다 — 말풍선 클래스로 고정한다(경기장 CSS 에서 `-e` 가 왼쪽, `-w` 가 오른쪽).
 */
const PLAYERS = [100, 101, 102, 103];

function say(userId: number, message: string) {
  useRoomChatStore.getState().appendIncoming(
    { eventId: `e-${userId}`, ts: Date.now(), userId, username: `u${userId}`, message },
    false,
  );
}

describe('ArenaChatBubbles — 말풍선 자리 (D-132 시계 방향)', () => {
  beforeEach(() => {
    useRoomChatStore.getState().reset('r-1');
  });

  it('내 다음 차례(+1)의 말풍선은 왼쪽(e)에 뜬다', () => {
    say(101, '안녕');
    const { container } = render(<ArenaChatBubbles playerIds={PLAYERS} mySeat={0} />);

    expect(container.querySelector('.arena-bubble-e')).toHaveTextContent('안녕');
    expect(container.querySelector('.arena-bubble-w')).toBeNull();
  });

  it('직전 차례(+3)의 말풍선은 오른쪽(w)에 뜬다', () => {
    say(103, '반가워');
    const { container } = render(<ArenaChatBubbles playerIds={PLAYERS} mySeat={0} />);

    expect(container.querySelector('.arena-bubble-w')).toHaveTextContent('반가워');
    expect(container.querySelector('.arena-bubble-e')).toBeNull();
  });
});
