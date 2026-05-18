import { apiRequest } from './client';

export type Tier =
  | 'BRONZE'
  | 'SILVER'
  | 'GOLD'
  | 'PLATINUM'
  | 'DIAMOND'
  | 'MASTER';

export interface UserStats {
  userId: number;
  username: string;
  winCount: number;
  loseCount: number;
  rating: number;
  tier: Tier;
  desertCount: number;
}

export interface RankEntry {
  rank: number;
  userId: number;
  username: string;
  rating: number;
  tier: Tier;
  winCount: number;
  loseCount: number;
  desertCount: number;
}

export interface RankingResponse {
  entries: RankEntry[];
}

export const usersApi = {
  stats(token: string, userId: number): Promise<UserStats> {
    return apiRequest(`/api/users/${userId}/stats`, { token });
  },
  ranking(token: string, limit = 20): Promise<RankingResponse> {
    return apiRequest(`/api/users/ranking?limit=${limit}`, { token });
  },
};
