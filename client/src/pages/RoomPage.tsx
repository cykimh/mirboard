import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { roomsApi } from '@/api/rooms';
import { useAuthStore } from '@/features/auth/authStore';
import { GameTable } from '@/features/tichu/GameTable';
import { useRoomMeta } from '@/ws/useRoomMeta';
import { Button } from '@/components/Button';
import { Badge } from '@/components/Badge';
import type { Room, TeamPolicy } from '@/types/api';

/**
 * 대기실 + 게임 테이블 컨테이너. status=WAITING 일 때는 참가자 목록을, IN_GAME
 * 으로 전이되면 {@link GameTable} 을 렌더링한다. Phase 13C — 방 메타는 폴링 대신
 * `/topic/room/{id}/meta` WS 구독으로 즉시 갱신.
 *
 * Phase 8A — 진입 시 자동으로 `/join-or-reconnect` 호출해서 본인 좌석을 복원하거나
 * IN_GAME 방엔 spectator 로 흡수. 직접 링크 공유 시나리오 지원.
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
    return () => { cancelled = true; };
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
    if (!window.confirm('게임을 강제로 종료하시겠습니까? 모든 참가자가 로비로 돌아갑니다.')) {
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
        <div style={{ display: 'flex', gap: 8 }}>
          {canAbort && (
            <Button type="button" variant="danger" onClick={handleAbort}>
              게임 종료
            </Button>
          )}
          <Button type="button" variant="subtle" onClick={handleLeave}>나가기</Button>
        </div>
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '8px 0' }}>
            <span style={{ fontSize: 13 }}>팀 배정:</span>
            {iAmHost ? (
              <select
                value={room.teamPolicy}
                onChange={(e) => handleTeamPolicyChange(e.target.value as TeamPolicy)}
              >
                <option value="SEQUENTIAL">입장 순서</option>
                <option value="RANDOM">랜덤 셔플</option>
              </select>
            ) : (
              <Badge tone={room.teamPolicy === 'RANDOM' ? 'warning' : 'accent'}>
                {room.teamPolicy === 'RANDOM' ? '랜덤 셔플' : '입장 순서'}
              </Badge>
            )}
          </div>
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
          botSeats={room.botSeats ?? []}
          fillWithBots={room.fillWithBots ?? false}
        />
      )}

      {room.status === 'FINISHED' && (
        <p>게임이 종료되었습니다.</p>
      )}
    </main>
  );
}
