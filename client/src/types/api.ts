// REST API 응답 타입 — 서버의 `docs/api.md` 와 일치해야 함.

export type GameStatus = 'AVAILABLE' | 'COMING_SOON' | 'DISABLED';

/**
 * D-106 — 게임에 따라 뜻이 있기도 없기도 한 방 설정. 서버 `RoomOption` 미러.
 * 방 만들기·대기실은 이 목록에 있는 것만 노출한다.
 */
export type RoomOption = 'TARGET_SCORE' | 'TEAMS' | 'BETTING' | 'RACE_BUTTON_SIZE' | 'RACE_BUTTON_VISIBILITY';

export interface GameSummary {
  id: string;
  displayName: string;
  shortDescription: string;
  minPlayers: number;
  maxPlayers: number;
  status: GameStatus;
  supportedRoomOptions: RoomOption[];
  /**
   * D-130 — 방 만들기 모달의 처음 선택(게임이 선언, `GameDefinition.defaultPlayers()`·`defaultTurnSeconds()`). 서버는 늘
   * 싣는다 — 선택 필드인 것은 이 필드를 모르는 픽스처와의 호환 때문이고, 없으면 maxPlayers·0(끔)으로 본다.
   */
  defaultPlayers?: number;
  defaultTurnSeconds?: number;
  /** D-154 — 방 만들기의 턴 제한 선택지(초, 0 = 끔). 없으면 끔·30·60·90. 원카드는 끔·5·10·20·30. */
  turnSecondsChoices?: number[];
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
  /** Phase 9B — 봇 채우기. D-136 — true 면 "봇과 연습" 방(방장 + 봇, 만들자마자 시작, 방 목록에 없음). */
  fillWithBots: boolean;
  /** Phase 9B — playerIds 중 봇 user 좌석 인덱스 (서버 derived). */
  botSeats: number[];
  /** Phase 12 — 매치 종료 목표점수 (기본 1000). */
  targetScore: number;
  /** Phase 13D — 개인 턴 제한 초 (0=끔). */
  turnSeconds: number;
  /** D-81 — 판돈(가상 칩). 0=내기 없음. 생성 시 고정. */
  stake: number;
  /** Phase 16(#2) — 대기실에서 준비 완료한 플레이어 id (봇은 자동 포함). D-161 — 친구 방은 표시만(시작은 방장). */
  readyUserIds: number[];
  /** D-161 — 원카드 경쟁 버튼 크기(방 설정). 옛 서버면 없다 — MEDIUM 으로 본다. */
  raceButtonSize?: RaceButtonSize;
  /** D-165 — 원카드 경쟁 버튼 찾기 난이도(투명도, 방 설정). 옛 서버면 없다 — NORMAL 로 본다. */
  raceButtonVisibility?: RaceButtonVisibility;
}

/** D-161 — 원카드 "원카드!/잡기!" 버튼 크기. */
export type RaceButtonSize = 'SMALL' | 'MEDIUM' | 'LARGE';
/** D-165 — 원카드 "원카드!/잡기!" 버튼 찾기 난이도 — 보통(불투명)·어려움(60%)·매우 어려움(35%). */
export type RaceButtonVisibility = 'NORMAL' | 'HARD' | 'VERY_HARD';

/** D-161 — 대기실에서 방장이 바꾸는 게임 설정(부분 갱신). */
export interface RoomSettingsUpdate {
  turnSeconds?: number;
  targetScore?: number;
  stake?: number;
  raceButtonSize?: RaceButtonSize;
  raceButtonVisibility?: RaceButtonVisibility;
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
    /** D-117 — 일회용 게스트 계정. D-117 이전 스냅샷에는 없으므로 optional(없으면 정회원). */
    guest?: boolean;
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
