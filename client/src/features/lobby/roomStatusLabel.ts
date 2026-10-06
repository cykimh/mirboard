import type { RoomStatus } from '@/types/api';

/**
 * D-110 — 방 상태를 보여 줄 때 enum 원문(WAITING 등) 대신 쓰는 라벨. 대기실 헤더(`RoomPage`)와 허브 방 목록
 * (`GameHubPage`)이 같이 쓴다.
 */
export const ROOM_STATUS_LABEL: Record<RoomStatus, string> = {
  WAITING: '대기 중',
  IN_GAME: '게임 중',
  FINISHED: '종료',
};
