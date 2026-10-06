import { describe, expect, it } from 'vitest';
import { INFRA_ERROR_LABELS, errorText } from './errorLabels';

/**
 * S5 — 게임과 무관하게 인프라가 보내는 거절 코드(인게임 컨트롤러·레이트 리미터)의 한국어 문구는 한 곳에 둔다. 라벨이
 * 없으면 `CODE: 영문 메시지` 가 그대로 보였다 — Redis 장애의 INTERNAL_ERROR, 연타의 RATE_LIMITED, 끈 게임의
 * GAME_NOT_AVAILABLE 이 실제로 닿는 경로다.
 */
describe('인프라 거절 문구', () => {
  it.each([
    'BUSY',
    'GAME_NOT_STARTED',
    'GAME_NOT_IN_PROGRESS',
    'INVALID_ACTION',
    'INTERNAL_ERROR',
    'NOT_IN_ROOM',
    'ROOM_NOT_FOUND',
    'GAME_NOT_AVAILABLE',
    'RATE_LIMITED',
  ])('%s 는 한국어 문구가 있다', (code) => {
    expect(INFRA_ERROR_LABELS[code]).toMatch(/[가-힣]/);
    expect(errorText(code, 'English detail')).toBe(INFRA_ERROR_LABELS[code]);
  });

  it('게임 라벨이 먼저다', () => {
    expect(errorText('BUSY', 'x', { BUSY: '게임 문구' })).toBe('게임 문구');
  });

  it('모르는 코드는 코드와 원문을 그대로 — 새 코드를 놓치지 않게', () => {
    expect(errorText('SOMETHING_NEW', 'detail')).toBe('SOMETHING_NEW: detail');
  });
});
