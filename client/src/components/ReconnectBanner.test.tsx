import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReconnectBanner } from './ReconnectBanner';

/**
 * D-135 — 오래 끊겼을 때의 안내. 예전 문구("호스트가 게임을 강제 종료할 수 있습니다")는 방장 강제 종료가 봇 판만이 된 뒤 사람
 * 판에서 사실이 아니다 — 끊긴 사람에게 실제로 일어나는 일은 탈주 유예(D-158, 기본 3분)다. 안내는 유예 안쪽인 1분에 뜬다.
 */
describe('ReconnectBanner — 장시간 끊김 안내 (D-135)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('1분 넘게 끊기면 3분 뒤 탈주 처리를 알리고 방장 강제 종료를 말하지 않는다', () => {
    render(<ReconnectBanner connected={false} />);
    act(() => {
      vi.advanceTimersByTime(60 * 1000 + 1000);
    });

    expect(screen.getByRole('status')).toHaveTextContent('3분 넘게 끊기면 탈주로 처리');
    expect(screen.getByRole('status')).not.toHaveTextContent('강제 종료');
  });

  it('잠깐(1분 미만) 끊김에는 재연결 중만 보인다', () => {
    render(<ReconnectBanner connected={false} />);
    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(screen.getByRole('status')).toHaveTextContent('재연결 중');
    expect(screen.getByRole('status')).not.toHaveTextContent('탈주로 처리');
  });
});
