import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { roomsApi } from '@/api/rooms';
import { usersApi } from '@/api/users';
import { loadGame } from '@/api/games';
import { useAuthStore } from '@/features/auth/authStore';
import { GameTable } from '@/features/tichu/GameTable';
import { SkullKingTable } from '@/features/skullking/SkullKingTable';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { tutorialFor } from '@/features/tutorial/gameTutorials';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useRoomMeta } from '@/ws/useRoomMeta';
import type { Room, RoomOption, RoomStatus, TeamPolicy } from '@/types/api';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Separator } from '@/components/ui/separator';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

/**
 * 대기실 + 게임 테이블 컨테이너. Phase 20d(D-76): 대기실/에러/로딩 셸을
 * shadcn 으로 재디자인. IN_GAME 의 GameTable 은 20e 범위라 레거시 레이아웃
 * 유지(.app-shell 밖에 둬 스코프 base 영향 없음). 상태/WS/핸들러 불변.
 */
// D-110 — 대기실 헤더에 enum 원문(WAITING 등) 대신 쓰는 라벨.
const ROOM_STATUS_LABEL: Record<RoomStatus, string> = {
  WAITING: '대기 중',
  IN_GAME: '게임 중',
  FINISHED: '종료',
};

export function RoomPage() {
  const { roomId = '' } = useParams<{ roomId: string }>();
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const [room, setRoom] = useState<Room | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [autoJoinAttempted, setAutoJoinAttempted] = useState(false);
  const [usernames, setUsernames] = useState<Record<number, string>>({});
  // D-106 — 이 게임이 쓰는 방 옵션. 대기실에서는 좌석 정책 **라벨**을 고르는 데만 쓴다.
  // null = 아직 모름(요청 중) → 행을 그리지 않는다. 티츄에서 "좌석 순서"가 잠깐 떴다
  // "팀 배정"으로 바뀌는 깜빡임을 막으려는 것이다.
  const [roomOptions, setRoomOptions] = useState<RoomOption[] | null>(null);
  // D-110 — 헤더용 게임 표시명. 메타를 못 받으면 null 로 두고 gameType 으로 폴백한다.
  const [gameName, setGameName] = useState<string | null>(null);

  // D-106 — 방의 gameType 을 알게 되면 그 게임의 옵션 집합을 받아 온다. 캐시되므로
  // 같은 게임의 방을 여러 번 드나들어도 요청은 한 번이다.
  useEffect(() => {
    const gameType = room?.gameType;
    if (!token || !gameType) return;
    let cancelled = false;
    loadGame(token, gameType)
      .then((g) => {
        if (cancelled) return;
        setRoomOptions(g.supportedRoomOptions ?? []);
        setGameName(g.displayName);
      })
      .catch(() => {
        // 게임 메타를 못 받아도 대기실은 동작해야 한다 — 옵션만 숨긴 채 둔다.
        if (!cancelled) setRoomOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [token, room?.gameType]);

  // Phase 8A — 진입 시 1회만 join-or-reconnect 호출. 폴링과 분리.
  useEffect(() => {
    if (!token || autoJoinAttempted) return;
    let cancelled = false;
    (async () => {
      try {
        const r = await roomsApi.joinOrReconnect(token, roomId);
        if (!cancelled) setRoom(r.room);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError) setError(err.message);
      } finally {
        if (!cancelled) setAutoJoinAttempted(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, roomId, autoJoinAttempted]);

  useEffect(() => {
    if (!token) {
      navigate('/login');
    }
  }, [token, navigate]);

  // D-120 — 이 세션에서 IN_GAME→FINISHED 전이를 봤는가. 봇 방은 매치가 끝나면 서버가
  // 방을 FINISHED 로 바꾸는데, 그 메타가 마지막 게임 이벤트보다 먼저 올 수 있다. 그때
  // 게임판을 내리면 마지막 라운드 결과와 최종 점수를 못 본다. 그래서 전이를 **관측한**
  // 게임판은 유지한다 — 메타와 게임 이벤트의 도착 순서에 기대지 않는 판단이다. 새로고침으로
  // 처음부터 FINISHED 를 받으면 전이를 본 적이 없으므로 기존 종료 카드다.
  const [boardHeld, setBoardHeld] = useState(false);
  const roomRef = useRef<Room | null>(null);
  roomRef.current = room;

  // Phase 13C(#3) — 2초 폴링 제거. join-or-reconnect 1회로 초기 room 확보 후
  // 방 메타 변경(참가/IN_GAME 전이/팀정책/관전/목표점수)은 WS 로 즉시 반영.
  useRoomMeta(
    roomId,
    token,
    (r) => {
      // 두 setState 는 한 렌더로 묶인다 — 게임판이 언마운트됐다 다시 붙지 않는다.
      if (roomRef.current?.status === 'IN_GAME' && r.status === 'FINISHED') {
        setBoardHeld(true);
      }
      roomRef.current = r;
      setRoom(r);
    },
    () => setError('방이 종료되었습니다.'),
  );

  // 좌석/참가자 표시용 username 일괄 조회. 참가자 집합이 바뀔 때만 재요청(최대 4명).
  const playersKey = room?.playerIds.join(',') ?? '';
  useEffect(() => {
    if (!token || !room || room.playerIds.length === 0) return;
    let cancelled = false;
    usersApi
      .names(token, room.playerIds)
      .then((res) => {
        if (cancelled) return;
        setUsernames((prev) => {
          const next = { ...prev };
          for (const n of res.names) next[n.userId] = n.username;
          return next;
        });
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, playersKey]);

  const iAmPlayer = !!(room && user && room.playerIds.includes(user.userId));
  const iAmSpectator = !!(
    room &&
    user &&
    !iAmPlayer &&
    (room.spectatorIds ?? []).includes(user.userId)
  );

  async function handleLeave() {
    if (!token) return;
    try {
      if (iAmSpectator) {
        await roomsApi.stopSpectating(token, roomId);
      } else {
        await roomsApi.leave(token, roomId);
      }
      navigate('/games');
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  async function handleToggleReady(next: boolean) {
    if (!token) return;
    try {
      const updated = await roomsApi.setReady(token, roomId, next);
      setRoom(updated);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  async function handleTeamPolicyChange(next: TeamPolicy) {
    if (!token) return;
    try {
      const updated = await roomsApi.updateTeamPolicy(token, roomId, next);
      setRoom(updated);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  async function handleAbort() {
    if (!token) return;
    if (
      !window.confirm(
        '게임을 강제로 종료하시겠습니까? 모든 참가자가 로비로 돌아갑니다.',
      )
    ) {
      return;
    }
    try {
      await roomsApi.abort(token, roomId);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  const iAmHost = !!(room && user && room.hostId === user.userId);
  // D-106 정정 — 좌석 정책은 게임 중립(RANDOM 은 좌석 순서를 섞을 뿐)이라 숨기지 않는다.
  // 팀이 있는 게임에서만 "팀 배정"으로 부른다.
  const seatPolicyLabel = roomOptions?.includes('TEAMS') ? '팀 배정' : '좌석 순서';
  const canAbort = iAmHost && room?.status === 'IN_GAME';

  // D-121 — 그 게임 대기실 **첫 입장**에 튜토리얼을 1회 자동으로 띄운다(게임별 열람 키).
  // 게임 중에는 띄우지 않는다 — 턴 타이머가 흐르는 중이라 타임아웃 자동조종을 부른다.
  const tutorial = tutorialFor(room?.gameType);
  const waiting = room?.status === 'WAITING';
  const tutorialGate = useTutorialGate(tutorial?.seenKey ?? null, !!tutorial && waiting);
  const hideTutorial = tutorialGate.hide;
  // 연 채로 게임이 시작되면 기록 없이 닫는다 — 리매치로 대기실에 돌아와도 다시 열려 있지 않게.
  useEffect(() => {
    if (!waiting) hideTutorial();
  }, [waiting, hideTutorial]);

  if (error) {
    return (
      <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
        <Card className="w-full max-w-sm">
          <CardContent className="flex flex-col gap-4 pt-6">
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
            <Button asChild variant="outline">
              <Link to="/games">← 미르보드카페로</Link>
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }
  if (!room || !user) {
    return (
      <div className="app-shell flex min-h-screen items-center justify-center bg-background text-muted-foreground">
        방 정보 불러오는 중...
      </div>
    );
  }

  // IN_GAME — 게임판은 레거시 레이아웃이라 .app-shell 밖이다.
  // D-103: 게임 분기는 **이 한 곳**뿐이다. 각 게임판이 자기 소켓·sink 를 소유하므로
  // 다른 게임의 코드 경로는 실행조차 되지 않는다.
  // D-120: 스컬킹은 이 세션에서 본 IN_GAME→FINISHED 직후에도 게임판을 유지한다(위
  // boardHeld). 티츄는 아직 기존 동작 그대로다 — 매치 결과·'한 판 더' 게이팅이 먼저다.
  const roomFinished = room.status === 'FINISHED';
  if (
    room.gameType === 'SKULL_KING' &&
    (room.status === 'IN_GAME' || (boardHeld && roomFinished))
  ) {
    return (
      <main className="room-page">
        <SkullKingTable
          roomId={room.roomId}
          playerIds={room.playerIds}
          myUserId={user.userId}
          spectator={iAmSpectator}
          botSeats={room.botSeats ?? []}
          usernames={usernames}
          turnSeconds={room.turnSeconds ?? 0}
          spectatorCount={(room.spectatorIds ?? []).length}
          onExit={handleLeave}
          roomFinished={roomFinished}
        />
      </main>
    );
  }

  if (room.status === 'IN_GAME') {
    return (
      <main className="room-page">
        <GameTable
          roomId={room.roomId}
          playerIds={room.playerIds}
          myUserId={user.userId}
          spectator={iAmSpectator}
          botSeats={room.botSeats ?? []}
          fillWithBots={room.fillWithBots ?? false}
          turnSeconds={room.turnSeconds ?? 0}
          stake={room.stake ?? 0}
          spectatorCount={(room.spectatorIds ?? []).length}
          usernames={usernames}
          isHost={iAmHost}
          onExit={handleLeave}
        />
      </main>
    );
  }

  return (
    <div className="app-shell min-h-screen bg-background text-foreground">
      <div className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-6">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">{room.name}</h1>
            <p className="text-sm text-muted-foreground">
              {gameName ?? room.gameType} ·{' '}
              {ROOM_STATUS_LABEL[room.status] ?? room.status} · {room.playerCount}/
              {room.capacity}
              {(room.spectatorIds ?? []).length > 0 &&
                ` · 👁 관전 ${(room.spectatorIds ?? []).length}`}
            </p>
          </div>
          <div className="flex gap-2">
            {canAbort && (
              <Button
                type="button"
                variant="destructive"
                onClick={handleAbort}
              >
                게임 종료
              </Button>
            )}
            {tutorial && (
              <Button type="button" variant="outline" onClick={tutorialGate.show}>
                게임 방법
              </Button>
            )}
            <Button type="button" variant="outline" onClick={handleLeave}>
              나가기
            </Button>
          </div>
        </header>

        {iAmSpectator && (
          <Alert>
            <AlertDescription>
              관전 중 — 본인 손패는 표시되지 않습니다.
            </AlertDescription>
          </Alert>
        )}

        {room.status === 'WAITING' && (
          <Card>
            <CardHeader>
              <CardTitle>참가자</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <ul className="flex flex-col gap-2">
                {room.playerIds.map((id, seat) => {
                  const isReady = (room.readyUserIds ?? []).includes(id);
                  const isBot = (room.botSeats ?? []).includes(seat);
                  return (
                    <li
                      key={id}
                      className="flex items-center gap-2 rounded-md border p-2"
                    >
                      <code className="text-sm text-muted-foreground">
                        {usernames[id] ?? `#${id}`}
                      </code>
                      {id === room.hostId && <Badge>호스트</Badge>}
                      {isBot && <Badge variant="secondary">봇</Badge>}
                      <span className="flex-1" />
                      {isReady ? (
                        <Badge>✓ 준비됨</Badge>
                      ) : (
                        <Badge variant="outline">대기</Badge>
                      )}
                    </li>
                  );
                })}
              </ul>

              {roomOptions !== null && (
              <>
              <Separator />

              <div className="flex items-center gap-3">
                <span className="text-sm">{seatPolicyLabel}</span>
                {iAmHost ? (
                  <Select
                    value={room.teamPolicy}
                    onValueChange={(v) =>
                      handleTeamPolicyChange(v as TeamPolicy)
                    }
                  >
                    <SelectTrigger className="w-40" aria-label={seatPolicyLabel}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent className="app-shell">
                      <SelectItem value="SEQUENTIAL">입장 순서</SelectItem>
                      <SelectItem value="RANDOM">랜덤 셔플</SelectItem>
                    </SelectContent>
                  </Select>
                ) : (
                  <Badge
                    variant={
                      room.teamPolicy === 'RANDOM' ? 'secondary' : 'default'
                    }
                  >
                    {room.teamPolicy === 'RANDOM' ? '랜덤 셔플' : '입장 순서'}
                  </Badge>
                )}
              </div>
              </>
              )}

              {iAmPlayer && (
                <div>
                  {(room.readyUserIds ?? []).includes(user.userId) ? (
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => handleToggleReady(false)}
                    >
                      준비 취소
                    </Button>
                  ) : (
                    <Button
                      type="button"
                      onClick={() => handleToggleReady(true)}
                    >
                      준비
                    </Button>
                  )}
                </div>
              )}

              <p className="text-sm text-muted-foreground">
                정원이 모두 모이고 전원이 준비하면 자동으로 게임이
                시작됩니다. (봇은 자동 준비)
              </p>
            </CardContent>
          </Card>
        )}

        {room.status === 'FINISHED' && (
          <Card>
            <CardContent className="pt-6 text-muted-foreground">
              게임이 종료되었습니다.
            </CardContent>
          </Card>
        )}
      </div>

      {tutorial && (
        <TutorialDialog
          tutorial={tutorial}
          open={tutorialGate.open}
          onClose={tutorialGate.close}
        />
      )}
    </div>
  );
}
