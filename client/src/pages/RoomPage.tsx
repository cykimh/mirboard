import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { roomsApi } from '@/api/rooms';
import { useAuthStore } from '@/features/auth/authStore';
import { GameTable } from '@/features/tichu/GameTable';
import { useRoomMeta } from '@/ws/useRoomMeta';
import type { Room, TeamPolicy } from '@/types/api';
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
export function RoomPage() {
  const { roomId = '' } = useParams<{ roomId: string }>();
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const [room, setRoom] = useState<Room | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [autoJoinAttempted, setAutoJoinAttempted] = useState(false);

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

  // Phase 13C(#3) — 2초 폴링 제거. join-or-reconnect 1회로 초기 room 확보 후
  // 방 메타 변경(참가/IN_GAME 전이/팀정책/관전/목표점수)은 WS 로 즉시 반영.
  useRoomMeta(
    roomId,
    token,
    (r) => setRoom(r),
    () => setError('방이 종료되었습니다.'),
  );

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
  const canAbort = iAmHost && room?.status === 'IN_GAME';

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

  // IN_GAME — GameTable 은 20e 범위(레거시 레이아웃). .app-shell 밖.
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
              {room.gameType} · {room.status} · {room.playerCount}/
              {room.capacity}
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
                        #{id}
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

              <Separator />

              <div className="flex items-center gap-3">
                <span className="text-sm">팀 배정</span>
                {iAmHost ? (
                  <Select
                    value={room.teamPolicy}
                    onValueChange={(v) =>
                      handleTeamPolicyChange(v as TeamPolicy)
                    }
                  >
                    <SelectTrigger className="w-40" aria-label="팀 배정">
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
    </div>
  );
}
