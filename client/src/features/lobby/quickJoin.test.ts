import { describe, expect, it } from 'vitest';
import type { Room } from '../../types/api';
import { createCapacity, joinCandidates, quickRoomName } from './quickJoin';

function room(roomId: string, over: Partial<Room> = {}): Room {
  return {
    roomId,
    name: roomId,
    gameType: 'ONE_CARD',
    hostId: 9,
    status: 'WAITING',
    capacity: 6,
    playerCount: 1,
    playerIds: [9],
    spectatorIds: [],
    teamPolicy: 'SEQUENTIAL',
    createdAt: 0,
    fillWithBots: false,
    botSeats: [],
    targetScore: 1000,
    turnSeconds: 0,
    stake: 0,
    readyUserIds: [],
    ...over,
  };
}

/** D-189 — [참여하기]가 들어갈 방을 고르는 순서. */
describe('joinCandidates (D-189)', () => {
  it('그 게임의 WAITING·빈자리 방만 — 꽉 찬 방·시작한 방·다른 게임·봇과 연습 방은 뺀다', () => {
    const rooms = [
      room('ok'),
      room('full', { playerCount: 6 }),
      room('started', { status: 'IN_GAME' }),
      room('other', { gameType: 'TICHU' }),
      room('practice', { fillWithBots: true }),
    ];
    expect(joinCandidates(rooms, 'ONE_CARD', 1).map((r) => r.roomId)).toEqual(['ok']);
  });

  it('사람이 많은 방 먼저, 같으면 먼저 만든 방', () => {
    const rooms = [
      room('two-new', { playerCount: 2, createdAt: 50 }),
      room('one', { playerCount: 1, createdAt: 1 }),
      room('four', { playerCount: 4, createdAt: 99 }),
      room('two-old', { playerCount: 2, createdAt: 10 }),
    ];
    expect(joinCandidates(rooms, 'ONE_CARD', 1).map((r) => r.roomId)).toEqual(['four', 'two-old', 'two-new', 'one']);
  });

  it('내가 이미 앉은 대기실이 맨 앞 — 꽉 찼어도 다시 들어간다', () => {
    const rooms = [
      room('big', { playerCount: 5 }),
      room('mine', { playerCount: 2, capacity: 2, playerIds: [9, 1] }),
    ];
    expect(joinCandidates(rooms, 'ONE_CARD', 1).map((r) => r.roomId)).toEqual(['mine', 'big']);
  });

  it('게임 id 는 대소문자를 가리지 않는다', () => {
    expect(joinCandidates([room('a')], 'one_card')).toHaveLength(1);
  });
});

describe('quickRoomName·createCapacity (D-189)', () => {
  it('방 이름은 "{별명}님의 {게임} 방"', () => {
    expect(quickRoomName('미르', { displayName: '원카드' })).toBe('미르님의 원카드 방');
  });

  it('정원 — 가변 인원 게임은 최대 인원, 인원 고정·설정이 정원을 정하는 게임은 보내지 않는다', () => {
    expect(createCapacity({ minPlayers: 2, maxPlayers: 6 })).toBe(6);
    expect(createCapacity({ minPlayers: 4, maxPlayers: 4 })).toBeUndefined();
    expect(
      createCapacity({ minPlayers: 2, maxPlayers: 4, roomOptionChoices: [{} as never] }),
    ).toBeUndefined();
    expect(createCapacity(undefined)).toBeUndefined();
  });
});
