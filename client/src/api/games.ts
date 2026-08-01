import { apiRequest } from './client';
import type { CatalogResponse, GameSummary } from '@/types/api';

export const gamesApi = {
  catalog(token: string): Promise<CatalogResponse> {
    return apiRequest('/api/games', { token });
  },
  get(token: string, gameId: string): Promise<GameSummary> {
    return apiRequest(`/api/games/${encodeURIComponent(gameId)}`, { token });
  },
};

/**
 * D-106 — 게임 메타는 프로세스 수명 동안 바뀌지 않는 정적 데이터라 한 번만 받아 캐시한다.
 * 대기실(`RoomPage`)이 팀 배정 노출 여부를 알려고 매번 요청하면, 방을 받고 → 게임을 받고 →
 * 그제서야 배치가 확정되는 워터폴이 생겨 화면이 한 박자 늦게 흔들린다.
 *
 * 진행 중인 요청도 공유해서(promise 캐시) 동시 호출이 중복 요청을 내지 않는다.
 */
const gameCache = new Map<string, Promise<GameSummary>>();

export function loadGame(token: string, gameId: string): Promise<GameSummary> {
  const key = gameId.toUpperCase();
  const hit = gameCache.get(key);
  if (hit) return hit;
  const pending = gamesApi.get(token, key).catch((err) => {
    gameCache.delete(key); // 실패는 캐시하지 않는다 — 다음 진입에서 다시 시도.
    throw err;
  });
  gameCache.set(key, pending);
  return pending;
}

/** 테스트 격리용 — 프로덕션 코드에서 부르지 않는다. */
export function clearGameCache(): void {
  gameCache.clear();
}
