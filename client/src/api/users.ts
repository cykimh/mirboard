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
  /** D-81 — 가상 칩 잔액(현금 아님). */
  chipBalance: number;
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
  /** D-81 — 가상 칩 잔액. */
  chipBalance: number;
}

export interface RankingResponse {
  entries: RankEntry[];
}

export interface UserName {
  userId: number;
  username: string;
}

export interface NamesResponse {
  names: UserName[];
}

export const usersApi = {
  stats(token: string, userId: number): Promise<UserStats> {
    return apiRequest(`/api/users/${userId}/stats`, { token });
  },
  ranking(token: string, limit = 20): Promise<RankingResponse> {
    return apiRequest(`/api/users/ranking?limit=${limit}`, { token });
  },
  /** 좌석/참가자 표시용 userId→username 일괄 조회. */
  names(token: string, ids: number[]): Promise<NamesResponse> {
    return apiRequest(`/api/users/names?ids=${ids.join(',')}`, { token });
  },
};
