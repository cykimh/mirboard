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
}

export const usersApi = {
  stats(token: string, userId: number): Promise<UserStats> {
    return apiRequest(`/api/users/${userId}/stats`, { token });
  },
};
