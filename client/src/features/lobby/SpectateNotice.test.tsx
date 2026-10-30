import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SpectateNotice,
  markSpectateNoticeSeen,
  spectateNoticeMessage,
  spectateNoticeOf,
  spectateNoticeSeen,
} from './SpectateNotice';
import type { Room } from '@/types/api';

/**
 * D-133 결정 A — 서버 `join-or-reconnect` 는 꽉 찬 대기실·진행 중 방이면 묻지 않고 관전으로 받는다(`mode=SPECTATING`). 그렇게 들어온
 * 사람에게 방 화면 위에 이유를 알린다. 빈자리가 있는 대기실 관전은 본인이 고른 것이고, 끝난 방은 종료 카드가 설명한다.
 */

function room(over: Partial<Room>): Room {
  return {
    roomId: 'r1',
    name: '방',
    gameType: 'ONE_CARD',
    hostId: 2,
    status: 'WAITING',
    capacity: 2,
    playerCount: 2,
    playerIds: [2, 3],
    spectatorIds: [1],
    teamPolicy: 'SEQUENTIAL',
    createdAt: 0,
    fillWithBots: false,
    botSeats: [],
    targetScore: 1000,
    turnSeconds: 0,
    stake: 0,
    readyUserIds: [],
    ...over,
  };
}

describe('spectateNoticeOf (D-133 결정 A)', () => {
  // D-153 — 친구 방은 판이 끝나면 대기실로 돌아가 관전자가 자동으로 앉는다. 봇과 연습 방은 한 판으로 끝난다.
  it('관전으로 받아졌고 게임이 시작된 친구 방이면 nextRound', () => {
    expect(spectateNoticeOf('SPECTATING', room({ status: 'IN_GAME' }))).toBe('nextRound');
  });

  it('봇과 연습 방이면 started', () => {
    expect(spectateNoticeOf('SPECTATING', room({ status: 'IN_GAME', fillWithBots: true }))).toBe('started');
  });

  it('관전으로 받아졌고 꽉 찬 대기실이면 full', () => {
    expect(spectateNoticeOf('SPECTATING', room({}))).toBe('full');
  });

  it('빈자리가 있는 대기실 관전은 본인이 고른 것이라 없다', () => {
    expect(spectateNoticeOf('SPECTATING', room({ capacity: 4 }))).toBeNull();
  });

  it('끝난 방은 종료 카드가 설명하므로 없다', () => {
    expect(spectateNoticeOf('SPECTATING', room({ status: 'FINISHED' }))).toBeNull();
  });

  it.each(['JOINED', 'RECONNECTED'] as const)('%s 면 없다', (mode) => {
    expect(spectateNoticeOf(mode, room({ status: 'IN_GAME' }))).toBeNull();
  });
});

describe('관전 안내 열람 기록 (D-133 결정 A)', () => {
  beforeEach(() => sessionStorage.clear());

  it('방마다 탭 안에서 기록한다', () => {
    expect(spectateNoticeSeen('r1')).toBe(false);
    markSpectateNoticeSeen('r1');
    expect(spectateNoticeSeen('r1')).toBe(true);
    expect(spectateNoticeSeen('r2')).toBe(false);
  });

  it('저장소가 막혀도(사생활 보호 창) 던지지 않고 본 적 없는 것으로 본다', () => {
    const original = globalThis.sessionStorage;
    Object.defineProperty(globalThis, 'sessionStorage', {
      get() {
        throw new DOMException('blocked', 'SecurityError');
      },
      configurable: true,
    });
    try {
      expect(() => markSpectateNoticeSeen('r1')).not.toThrow();
      expect(spectateNoticeSeen('r1')).toBe(false);
    } finally {
      Object.defineProperty(globalThis, 'sessionStorage', { value: original, configurable: true, writable: true });
    }
  });
});

describe('SpectateNotice (D-133 결정 A)', () => {
  afterEach(() => vi.useRealTimers());

  it.each([
    ['started', '게임이 이미 시작돼 관전으로 들어왔어요'],
    ['nextRound', '게임이 이미 시작돼 관전 중이에요 — 이 판이 끝나면 다음 판에 함께해요'],
    ['full', '자리가 없어 관전으로 들어왔어요'],
  ] as const)('%s 문구', (kind, text) => {
    render(<SpectateNotice kind={kind} onClose={() => {}} />);

    expect(screen.getByText(text)).toBeInTheDocument();
    expect(spectateNoticeMessage(kind)).toBe(text);
  });

  // FR-5 — 카드는 안내와 함께 삽입되므로 라이브 영역이 될 수 없다(일부 스크린 리더가 읽지 않는다). 읽어 주는 것은 방 화면이 늘 두는
  // 상태 영역이고(RoomPage), 카드는 보이는 안내 + [닫기]뿐이다 — 상태 영역이 둘이 되지 않게.
  it('카드 자체는 상태 영역(role=status)이 아니다 — 방 화면의 상태 영역이 읽는다', () => {
    render(<SpectateNotice kind="started" onClose={() => {}} />);

    expect(screen.queryByRole('status')).toBeNull();
  });

  it('[닫기]를 누르면 onClose', () => {
    const onClose = vi.fn();
    render(<SpectateNotice kind="full" onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('8초 뒤 저절로 내린다', () => {
    vi.useFakeTimers();
    const onClose = vi.fn();
    render(<SpectateNotice kind="started" onClose={onClose} />);

    act(() => vi.advanceTimersByTime(7_999));
    expect(onClose).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
