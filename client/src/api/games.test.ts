import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearGameCache, gamesApi, loadGame } from './games';
import type { GameSummary } from '@/types/api';

/**
 * D-106 — `loadGame` 의 모듈 전역 캐시 계약. 대기실이 방마다 게임 메타를 다시 받지
 * 않게 하려는 캐시라, 깨지면 요청이 늘 뿐 화면은 멀쩡해서 눈에 안 띈다.
 *
 * `loadGame` 은 모듈 안에서 `gamesApi.get(...)` 을 **속성 접근**으로 호출하므로
 * `spyOn(gamesApi, 'get')` 이 호출 시점에 가로챈다(모듈 전체를 모킹하면 정작 검증
 * 대상인 `loadGame` 이 가짜가 되어 아무것도 테스트하지 못한다).
 */
function summary(id: string): GameSummary {
  return {
    id,
    displayName: id,
    shortDescription: '',
    minPlayers: 2,
    maxPlayers: 8,
    status: 'AVAILABLE',
    supportedRoomOptions: [],
  };
}

describe('loadGame — 게임 메타 캐시 (D-106)', () => {
  beforeEach(() => {
    clearGameCache();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    clearGameCache();
  });

  it('같은 게임을 두 번 요청해도 서버는 한 번만 부른다', async () => {
    const get = vi.spyOn(gamesApi, 'get').mockResolvedValue(summary('TICHU'));

    await loadGame('tok', 'TICHU');
    await loadGame('tok', 'TICHU');

    expect(get).toHaveBeenCalledTimes(1);
  });

  it('진행 중인 요청을 공유한다 — 동시 호출이 중복 요청을 내지 않는다', async () => {
    let resolve!: (g: GameSummary) => void;
    const get = vi
      .spyOn(gamesApi, 'get')
      .mockReturnValue(new Promise<GameSummary>((r) => (resolve = r)));

    const a = loadGame('tok', 'TICHU');
    const b = loadGame('tok', 'TICHU'); // 아직 첫 요청이 안 끝났다
    resolve(summary('TICHU'));

    expect(await a).toBe(await b);
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('실패는 캐시하지 않는다 — 다음 진입에서 다시 시도한다', async () => {
    const get = vi.spyOn(gamesApi, 'get').mockRejectedValueOnce(new Error('boom'));

    await expect(loadGame('tok', 'TICHU')).rejects.toThrow('boom');
    get.mockResolvedValueOnce(summary('TICHU'));
    await expect(loadGame('tok', 'TICHU')).resolves.toMatchObject({ id: 'TICHU' });

    expect(get).toHaveBeenCalledTimes(2);
  });

  it('gameId 를 대문자로 정규화해 캐시한다', async () => {
    const get = vi.spyOn(gamesApi, 'get').mockResolvedValue(summary('TICHU'));

    await loadGame('tok', 'tichu');
    await loadGame('tok', 'TICHU');

    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('tok', 'TICHU');
  });
});
