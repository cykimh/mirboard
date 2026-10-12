import { describe, expect, it } from 'vitest';
import { viewPosOf } from './seatView';

/**
 * 티츄 본인 시점 매핑 (D-132 — 시계 방향). 서버는 +1 이 다음 차례이고 룰의 패스 "좌" 도 +1 이다. 위에서 내려다볼 때 다음
 * 차례가 내 **왼쪽**에 앉아야 하므로 +1 → 'e'(`.seat-e` 가 왼쪽), +3 → 'w'(오른쪽)다. 지금까지는 거꾸로 그렸다.
 */
describe('viewPosOf — 시계 방향 (D-132)', () => {
  it('나는 아래, 다음 차례(+1)는 왼쪽 e, 파트너(+2)는 위 n, 직전 차례(+3)는 오른쪽 w', () => {
    expect(viewPosOf(0, 0)).toBe('s');
    expect(viewPosOf(1, 0)).toBe('e');
    expect(viewPosOf(2, 0)).toBe('n');
    expect(viewPosOf(3, 0)).toBe('w');
  });

  it('내 좌석이 바뀌어도 상대 위치는 좌석 차이로만 정해진다', () => {
    for (let mySeat = 0; mySeat < 4; mySeat++) {
      expect(viewPosOf(mySeat, mySeat), `mySeat=${mySeat}`).toBe('s');
      expect(viewPosOf((mySeat + 1) % 4, mySeat), `mySeat=${mySeat}`).toBe('e');
      expect(viewPosOf((mySeat + 2) % 4, mySeat), `mySeat=${mySeat}`).toBe('n');
      expect(viewPosOf((mySeat + 3) % 4, mySeat), `mySeat=${mySeat}`).toBe('w');
    }
  });

  it('관전자(mySeat −1)는 기존 식 그대로 좌석 3 이 아래에 오고, 그 다음 좌석 0 이 왼쪽이다', () => {
    expect(viewPosOf(3, -1)).toBe('s');
    expect(viewPosOf(0, -1)).toBe('e');
    expect(viewPosOf(1, -1)).toBe('n');
    expect(viewPosOf(2, -1)).toBe('w');
  });
});
