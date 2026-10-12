import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useReactionStore } from '@/features/chat/reactionStore';
import { ReactionFloats } from './ReactionFloats';

/**
 * D-132 — 이모지 반응도 좌석 클래스·카드 비행과 같은 자리(`viewPosOf`)에서 떠오른다(설계 성공 기준 4). 경기장 CSS 에서
 * `reaction-e` 가 왼쪽, `reaction-w` 가 오른쪽이다.
 */
describe('ReactionFloats — 이모지 자리 (D-132 시계 방향)', () => {
  beforeEach(() => {
    useReactionStore.getState().reset();
  });

  it('직전 차례(+3)가 보낸 이모지는 오른쪽(w)에서 떠오른다', () => {
    useReactionStore.getState().add(3, '👍');
    const { container } = render(<ReactionFloats mySeat={0} />);

    expect(container.querySelector('.reaction-w')).toHaveTextContent('👍');
    expect(container.querySelector('.reaction-e')).toBeNull();
  });

  it('관전자(−1)에게는 좌석 0 이 왼쪽(e)이다 — 아래 좌석(3) 다음', () => {
    useReactionStore.getState().add(0, '🎉');
    const { container } = render(<ReactionFloats mySeat={-1} />);

    expect(container.querySelector('.reaction-e')).toHaveTextContent('🎉');
    expect(container.querySelector('.reaction-w')).toBeNull();
  });
});
