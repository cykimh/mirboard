// REST API 응답 타입 — 서버의 `docs/api.md` 와 일치해야 함.

export type GameStatus = 'AVAILABLE' | 'COMING_SOON' | 'DISABLED';

export interface GameSummary {
  id: string;
  displayName: string;
  shortDescription: string;
  minPlayers: number;
  maxPlayers: number;
  status: GameStatus;
}

export interface CatalogResponse {
  games: GameSummary[];
}

export type RoomStatus = 'WAITING' | 'IN_GAME' | 'FINISHED';

export type TeamPolicy = 'SEQUENTIAL' | 'RANDOM' | 'MANUAL';

export interface Room {
  roomId: string;
  name: string;
  gameType: string;
  hostId: number;
  status: RoomStatus;
  capacity: number;
  playerCount: number;
  playerIds: number[];
  spectatorIds: number[];
  teamPolicy: TeamPolicy;
  createdAt: number;
  /** Phase 9B — 솔로 모드 (방 생성 시 빈 좌석을 봇으로 자동 채움). */
  fillWithBots: boolean;
  /** Phase 9B — playerIds 중 봇 user 좌석 인덱스 (서버 derived). */
  botSeats: number[];
}

export interface RoomListResponse {
  rooms: Room[];
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: number;
  user: {
    userId: number;
    username: string;
  };
}

export interface MeResponse {
  userId: number;
  username: string;
  winCount: number;
  loseCount: number;
}

export interface ApiErrorEnvelope {
  error: {
    code: string;
    message: string;
    details?: Record<string, unknown>;
  };
}
