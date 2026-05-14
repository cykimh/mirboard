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
  createdAt: number;
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
