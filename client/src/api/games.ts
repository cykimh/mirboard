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
