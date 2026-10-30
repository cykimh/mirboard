import { useEffect, useRef } from 'react';
import type { JoinOrReconnectResponse } from '@/api/rooms';
import type { Room } from '@/types/api';
import { Button } from '@/components/ui/button';

/**
 * 관전으로 받아진 이유 — `started` 는 게임이 이미 시작됨(봇과 연습), `nextRound` 는 친구 방이 진행 중이라 이 판이 끝나면 자동으로 앉음
 * (D-153), `full` 은 시작 전인데 정원이 다 참(봇 자리 포함).
 */
export type SpectateNoticeKind = 'started' | 'nextRound' | 'full';

/**
 * D-133 결정 A — 서버 `join-or-reconnect` 가 관전으로 받았을 때(`mode=SPECTATING`) 방 상태로 이유를 고른다. 이미 관전자여도
 * `SPECTATING` 이 오므로 상태 기준 문구로 쓴다(새로 받아졌든 원래 관전자든 사실). 빈자리가 있는 대기실 관전은 본인이 고른 것이라,
 * 끝난 방은 종료 카드가 설명하므로 안내하지 않는다.
 */
export function spectateNoticeOf(
  mode: JoinOrReconnectResponse['mode'],
  room: Pick<Room, 'status' | 'playerCount' | 'capacity'> & Partial<Pick<Room, 'fillWithBots'>>,
): SpectateNoticeKind | null {
  if (mode !== 'SPECTATING') return null;
  if (room.status === 'IN_GAME') return room.fillWithBots ? 'started' : 'nextRound';
  if (room.status === 'WAITING' && room.playerCount >= room.capacity) return 'full';
  return null;
}

const SEEN_PREFIX = 'mirboard.spectateNotice.';

/**
 * 이 탭에서 이 방의 관전을 이미 알고 있다 — 안내를 띄웠거나, 허브·"관전할까요?" 패널에서 본인이 관전을 골랐다. 새로고침·허브 관전에
 * 다시 띄우지 않으려는 탭 단위(sessionStorage) 기록이다. 저장소가 막히면(사생활 보호 창 등) 조용히 넘어간다 — 안내가 한 번 더 뜰 뿐이다.
 */
export function markSpectateNoticeSeen(roomId: string): void {
  try {
    sessionStorage.setItem(SEEN_PREFIX + roomId, '1');
  } catch {
    // 기록 못 하면 다음에 한 번 더 뜬다.
  }
}

export function spectateNoticeSeen(roomId: string): boolean {
  try {
    return sessionStorage.getItem(SEEN_PREFIX + roomId) === '1';
  } catch {
    return false;
  }
}

/** 안내가 떠 있는 시간. */
const NOTICE_MS = 8000;

const MESSAGES: Record<SpectateNoticeKind, string> = {
  started: '게임이 이미 시작돼 관전으로 들어왔어요',
  nextRound: '게임이 이미 시작돼 관전 중이에요 — 이 판이 끝나면 다음 판에 함께해요',
  full: '자리가 없어 관전으로 들어왔어요',
};

/** 안내 문구. 방 화면이 늘 두는 상태 영역(스크린 리더용)과 보이는 카드가 같은 문구를 쓴다(FR-5). */
export function spectateNoticeMessage(kind: SpectateNoticeKind): string {
  return MESSAGES[kind];
}

interface SpectateNoticeProps {
  kind: SpectateNoticeKind;
  onClose: () => void;
}

/**
 * D-133 결정 A — "…관전으로 들어왔어요" 안내. 방 화면 맨 위에 떠 있다(게임판 채팅 100 위, 원카드 경쟁 레이어 150·패스 받은 카드 200
 * 아래). 게임판은 `.app-shell` 밖이라 카드 자체에 `app-shell` 을 붙여 색·테두리 토큰을 쓴다(바깥 줄에 붙이면 전체 폭이 칠해진다).
 * 바깥 줄은 클릭을 통과시킨다. 8초 뒤 저절로 내린다.
 *
 * <p>FR-5 — 카드는 상태 영역(`role="status"`)이 아니다. 안내와 함께 삽입되는 영역은 일부 스크린 리더가 읽지 않으므로, 읽어 주는 것은
 * 방 화면(`RoomPage`)이 로딩 때부터 늘 두는 숨은 상태 영역이고 같은 문구가 그리로 들어간다. 그래서 카드의 문구는 보조 기술에서
 * 숨긴다(두 번 읽히지 않게) — [닫기] 버튼은 그대로 닿는다.
 */
export function SpectateNotice({ kind, onClose }: SpectateNoticeProps) {
  // 부모가 렌더마다 새 함수를 넘겨도 타이머를 다시 걸지 않는다(방 메타·이름 갱신으로 자주 다시 그린다).
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const timer = setTimeout(() => closeRef.current(), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [kind]);

  return (
    <div className="pointer-events-none fixed inset-x-0 top-3 z-[120] flex justify-center px-4">
      <div className="app-shell pointer-events-auto flex max-w-md items-center gap-3 rounded-lg border bg-background px-4 py-2 text-sm shadow-lg">
        <span aria-hidden="true">{MESSAGES[kind]}</span>
        <Button type="button" size="sm" variant="ghost" onClick={onClose}>
          닫기
        </Button>
      </div>
    </div>
  );
}
