import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { roomsApi } from '@/api/rooms';
import { useAuthStore } from '@/features/auth/authStore';
import { GameTable } from '@/features/tichu/GameTable';
import { Button } from '@/components/Button';
import { Badge } from '@/components/Badge';
import type { Room } from '@/types/api';

/**
 * 대기실 + 게임 테이블 컨테이너. status=WAITING 일 때는 참가자 목록을, IN_GAME
 * 으로 전이되면 {@link GameTable} 을 렌더링한다. 폴링으로 방 메타를 2초마다 갱신.
 */
export function RoomPage() {
  const { roomId = '' } = useParams<{ roomId: string }>();
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const [room, setRoom] = useState<Room | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      navigate('/login');
      return;
    }
    let cancelled = false;
    async function poll() {
      try {
        const r = await roomsApi.get(token!, roomId);
        if (!cancelled) setRoom(r);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError) setError(err.message);
      }
    }
    poll();
    const id = window.setInterval(poll, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [token, roomId, navigate]);

  const iAmPlayer = !!(room && user && room.playerIds.includes(user.userId));
  const iAmSpectator = !!(
    room && user && !iAmPlayer && (room.spectatorIds ?? []).includes(user.userId)
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

  if (error) {
    return (
      <main className="room-page">
        <p className="error">{error}</p>
        <Link to="/games">← Hub</Link>
      </main>
    );
  }
  if (!room || !user) {
    return <main className="room-page"><p>방 정보 불러오는 중...</p></main>;
  }

  return (
    <main className="room-page">
      <header>
        <h1>{room.name}</h1>
        <Button type="button" variant="subtle" onClick={handleLeave}>나가기</Button>
      </header>
      <p className="meta">
        {room.gameType} · {room.status} · {room.playerCount}/{room.capacity}
      </p>

      {room.status === 'WAITING' && (
        <>
          <h2>참가자</h2>
          <ul>
            {room.playerIds.map((id) => (
              <li key={id}>
                <code>#{id}</code>
                {id === room.hostId && <Badge tone="accent">호스트</Badge>}
              </li>
            ))}
          </ul>
          <p className="hint">4/4 모이면 자동으로 게임이 시작됩니다.</p>
        </>
      )}

      {iAmSpectator && (
        <p className="spectator-banner">관전 중 — 본인 손패는 표시되지 않습니다.</p>
      )}

      {room.status === 'IN_GAME' && (
        <GameTable
          roomId={room.roomId}
          playerIds={room.playerIds}
          myUserId={user.userId}
          spectator={iAmSpectator}
        />
      )}

      {room.status === 'FINISHED' && (
        <p>게임이 종료되었습니다.</p>
      )}
    </main>
  );
}
