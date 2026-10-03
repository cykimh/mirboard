import { apiRequest } from './client';

export type Tier =
  | 'BRONZE'
  | 'SILVER'
  | 'GOLD'
  | 'PLATINUM'
  | 'DIAMOND'
  | 'MASTER';

/** D-115 — 한 게임의 전적. */
export interface GameRecord {
  gameType: string;
  rating: number;
  tier: Tier;
  winCount: number;
  loseCount: number;
  desertCount: number;
}

/**
 * 최상위 winCount~desertCount 는 D-115 이전 호환용(= TICHU). 게임별 값은 `games`
 * (한 판이라도 한 게임만).
 */
export interface UserStats {
  userId: number;
  username: string;
  winCount: number;
  loseCount: number;
  rating: number;
  tier: Tier;
  desertCount: number;
  games: GameRecord[];
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
  gameType: string;
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
  /** D-115 — 게임별 랭킹. 그 게임을 한 판이라도 한 사람만. */
  ranking(token: string, limit: number, gameType: string): Promise<RankingResponse> {
    return apiRequest(
      `/api/users/ranking?limit=${limit}&gameType=${encodeURIComponent(gameType)}`,
      { token },
    );
  },
  /** 좌석/참가자 표시용 userId→username 일괄 조회. */
  names(token: string, ids: number[]): Promise<NamesResponse> {
    return apiRequest(`/api/users/names?ids=${ids.join(',')}`, { token });
  },
};
