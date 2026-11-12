import { apiRequest } from './client';

/** D-164·D-167 — 포트리스2식 16단계(낮은 것부터). 서버 `Tier` 미러 — 점수 구간 + 금훈장 이상은 순위로 왕관. */
export type Tier =
  | 'SKULL'
  | 'BULLET'
  | 'MISSILE'
  | 'BRONZE_STAR'
  | 'SILVER_STAR'
  | 'GOLD_STAR'
  | 'BRONZE_MEDAL'
  | 'SILVER_MEDAL'
  | 'GOLD_MEDAL'
  | 'BRONZE_BADGE'
  | 'SILVER_BADGE'
  | 'GOLD_BADGE'
  | 'BRONZE_CROWN'
  | 'SILVER_CROWN'
  | 'GOLD_CROWN'
  | 'STAR_CROWN';

/** D-115 — 한 게임의 전적. */
export interface GameRecord {
  gameType: string;
  rating: number;
  tier: Tier;
  winCount: number;
  loseCount: number;
  desertCount: number;
  /** D-201 — 개인 최고 점수(점수를 겨루는 게임만 — 요트). 셀 판이 없거나 게스트면 null. 이 값만 있는 게임은 0승 0패 줄이다. */
  bestScore?: number | null;
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
  /** D-198 — 명예 계정이면 모든 게임에서 보일 계급(`games` 에 없는 게임에도), 아니면 null. 옛 서버면 없다. */
  fixedTier?: Tier | null;
}

/** D-199 — 랭킹 "전체"(모든 게임 합)를 가리키는 `gameType` 값. */
export const RANKING_ALL = 'ALL';

/** D-199·D-209 — 이번 달 랭킹 한 줄. 승·패·탈주·승률·최근 결과는 이번 달 것, 계급·점수는 누적 레이팅 것. */
export interface RankEntry {
  rank: number;
  userId: number;
  username: string;
  /** 계급 근거 게임의 누적 레이팅. */
  rating: number;
  tier: Tier;
  /** 계급 근거 게임 — 전체 랭킹이면 그 사람이 가장 많이 한 게임(없으면 null). */
  tierGameType?: string | null;
  winCount: number;
  loseCount: number;
  desertCount: number;
  /** 이번 달 승률(%). 승·패가 없으면 null. */
  winRate: number | null;
  /** 이번 달 최근 결과, 오래된 것 → 최근, 최대 5글자(W 승 · L 패 · D 무). */
  recent: string;
}

export interface RankingResponse {
  /** 게임 id 또는 `ALL`. */
  gameType: string;
  /** 집계한 달 "YYYY-MM"(Asia/Seoul). */
  month?: string;
  entries: RankEntry[];
}

/** D-160 — 게임별 판수(승+패 합, 봇 제외). 많이 한 게임부터, 한 판도 없는 게임은 없다. */
export interface GamePlaysResponse {
  games: { gameType: string; plays: number }[];
}

export interface UserName {
  userId: number;
  username: string;
}

export interface NamesResponse {
  names: UserName[];
}

/** D-154 — 상대 전적 한 줄(사람만 — 게스트·봇은 서버가 뺀다). */
export interface HeadToHeadEntry {
  gameType: string;
  opponentId: number;
  opponentName: string;
  wins: number;
  losses: number;
  draws: number;
}

export interface HeadToHeadResponse {
  entries: HeadToHeadEntry[];
}

export const usersApi = {
  stats(token: string, userId: number): Promise<UserStats> {
    return apiRequest(`/api/users/${userId}/stats`, { token });
  },
  /** D-199 — 이번 달 랭킹. `gameType` 은 게임 id 또는 `RANKING_ALL`(전체). 이번 달에 한 판이라도 기록된 사람만. */
  ranking(token: string, limit: number, gameType: string): Promise<RankingResponse> {
    return apiRequest(
      `/api/users/ranking?limit=${limit}&gameType=${encodeURIComponent(gameType)}`,
      { token },
    );
  },
  /** D-160 — 랭킹 게임 선택지 순서용 게임별 판수. */
  rankingGames(token: string): Promise<GamePlaysResponse> {
    return apiRequest('/api/users/ranking/games', { token });
  },
  /** D-154 — 상대 전적. gameType 을 주면 그 게임만. */
  headToHead(token: string, userId: number, gameType?: string): Promise<HeadToHeadResponse> {
    const q = gameType ? `?gameType=${encodeURIComponent(gameType)}` : '';
    return apiRequest(`/api/users/${userId}/head-to-head${q}`, { token });
  },
  /** 좌석/참가자 표시용 userId→username 일괄 조회. */
  names(token: string, ids: number[]): Promise<NamesResponse> {
    return apiRequest(`/api/users/names?ids=${ids.join(',')}`, { token });
  },
};
