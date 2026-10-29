import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { loadWithReload, RELOAD_FLAG_PREFIX } from './lazyWithReload';

/**
 * 게임판 조각 지연 로딩 — 배포하면 예전 해시의 조각 파일이 사라져, 배포 전에 연 탭이 게임판에 들어갈 때 조각을 못 받는다.
 * 그때 한 번만 새로고침해 새 index.html 을 받게 한다. 같은 탭에서 또 실패하면(새로고침해도 없다 — 진짜 장애) 오류를 그대로 던진다.
 */
describe('loadWithReload', () => {
  const reload = vi.fn();

  beforeEach(() => {
    reload.mockReset();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    sessionStorage.clear();
  });

  it('잘 받으면 그대로 돌려주고 새로고침 표시를 지운다', async () => {
    sessionStorage.setItem(`${RELOAD_FLAG_PREFIX}tichu`, '1');

    await expect(loadWithReload('tichu', () => Promise.resolve('ok'), reload)).resolves.toBe('ok');

    expect(reload).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(`${RELOAD_FLAG_PREFIX}tichu`)).toBeNull();
  });

  it('처음 실패하면 표시를 남기고 한 번 새로고침한다(응답은 끝나지 않는다 — 곧 페이지가 바뀐다)', async () => {
    let settled = false;
    void loadWithReload('tichu', () => Promise.reject(new Error('chunk')), reload).then(
      () => (settled = true),
      () => (settled = true),
    );
    await new Promise((r) => setTimeout(r, 0));

    expect(reload).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(`${RELOAD_FLAG_PREFIX}tichu`)).toBe('1');
    expect(settled).toBe(false);
  });

  it('새로고침 뒤에도 실패하면 오류를 그대로 던진다 — 새로고침을 되풀이하지 않는다', async () => {
    sessionStorage.setItem(`${RELOAD_FLAG_PREFIX}tichu`, '1');
    const boom = new Error('chunk');

    await expect(loadWithReload('tichu', () => Promise.reject(boom), reload)).rejects.toBe(boom);

    expect(reload).not.toHaveBeenCalled();
  });

  it('저장소를 못 쓰면(사생활 보호 창) 새로고침 없이 오류를 던진다 — 무한 새로고침보다 낫다', async () => {
    const blocked = () => {
      throw new Error('blocked');
    };
    vi.stubGlobal('sessionStorage', { getItem: blocked, setItem: blocked, removeItem: blocked });
    const boom = new Error('chunk');

    await expect(loadWithReload('tichu', () => Promise.reject(boom), reload)).rejects.toBe(boom);

    expect(reload).not.toHaveBeenCalled();
  });
});
