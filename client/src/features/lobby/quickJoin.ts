import type { GameSummary, Room } from '../../types/api';

/**
 * D-189 — 메인 게임 카드 [참여하기]의 규칙. 화면(GameHubPage)은 이 순서대로 `join-or-reconnect` 를 시도하고, 다 안 되면 방을 만든다.
 * 게임 이름을 모른다 — 방 목록(`GET /api/rooms`)과 카탈로그 값만 본다.
 */

/**
 * 그 게임의 들어갈 수 있는 방을 시도할 순서대로 돌려준다.
 * - 내가 이미 앉아 있는 대기실이 맨 앞(다시 들어가기 — 방을 또 만들지 않는다).
 * - 그다음은 WAITING 이고 빈자리가 있는 방. **사람이 많은 방 먼저**(곧 시작할 방을 채운다), 같으면 **먼저 만든 방**(오래 기다린 방).
 * 비밀번호 방은 없다(방에 그런 설정이 없음). 봇과 연습 방(`fillWithBots`)은 목록에 오지 않지만 와도 뺀다.
 */
export function joinCandidates(rooms: Room[], gameId: string, myUserId?: number): Room[] {
  const wanted = gameId.toUpperCase();
  const mine = (room: Room) => myUserId != null && room.playerIds.includes(myUserId);
  return rooms
    .filter(
      (room) =>
        room.gameType.toUpperCase() === wanted &&
        room.status === 'WAITING' &&
        !room.fillWithBots &&
        (mine(room) || room.playerCount < room.capacity),
    )
    .sort(
      (a, b) =>
        Number(mine(b)) - Number(mine(a)) ||
        b.playerCount - a.playerCount ||
        a.createdAt - b.createdAt,
    );
}

/** 모달 없이 만드는 방의 이름 — 방 만들기 모달에는 기본 이름이 없어(직접 입력) 여기서 정한다. 예: "미르님의 원카드 방". */
export function quickRoomName(username: string, game: Pick<GameSummary, 'displayName'>): string {
  return `${username}님의 ${game.displayName} 방`;
}

/**
 * 방을 만들 때 보낼 정원(없으면 보내지 않는다 — 서버가 게임 선언으로 정한다). 방 만들기 모달과 [참여하기]가 같이 쓴다.
 * D-143 — 정원은 게임 최대 인원(대기실에서 방장이 지금 인원으로 시작한다). 인원 고정 게임(티츄)은 보내지 않는다.
 * D-176 — 정원을 방 설정이 정하는 게임(값을 가진 방 옵션을 선언 — 오목의 1:1/2:2)도 보내지 않는다. 최대 인원을 보내면
 * 기본 설정 정원(오목 1:1 = 2)을 넘어 거절된다.
 */
export function createCapacity(
  game: Pick<GameSummary, 'minPlayers' | 'maxPlayers' | 'roomOptionChoices'> | undefined,
): number | undefined {
  if (!game) return undefined;
  const capacityBySettings = (game.roomOptionChoices ?? []).length > 0;
  return !capacityBySettings && game.minPlayers < game.maxPlayers ? game.maxPlayers : undefined;
}
