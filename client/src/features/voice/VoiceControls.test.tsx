import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { VoiceControls } from './VoiceControls';

/** D-159 — 음성 대화는 한동안 끈다. 버튼은 남지만 누를 수 없고, 이유를 title 로 알린다. */
describe('VoiceControls — 꺼진 스위치 (D-159)', () => {
  it('🎧·🎙 둘 다 비활성이고 누르지 못한다', () => {
    const voice = {
      joined: false,
      micOn: false,
      members: [],
      peerState: {},
      join: vi.fn(),
      leave: vi.fn(),
      toggleMic: vi.fn(),
    };
    render(<VoiceControls voice={voice} buttonClassName="b" />);

    const join = screen.getByRole('button', { name: '음성 대화' });
    expect(join).toBeDisabled();
    expect(join).toHaveAttribute('title', '음성 대화는 잠시 쉬어요');
    expect(screen.getByRole('button', { name: '마이크' })).toBeDisabled();

    join.click();
    expect(voice.join).not.toHaveBeenCalled();
  });
});
