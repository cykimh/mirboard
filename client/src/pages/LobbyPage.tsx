import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { roomsApi } from '@/api/rooms';
import { useAuthStore } from '@/features/auth/authStore';
import { useLobbyStomp } from '@/ws/useLobbyStomp';
import type { Room } from '@/types/api';

export function LobbyPage() {
  const { gameId = '' } = useParams<{ gameId: string }>();
  const token = useAuthStore((s) => s.token);
  const navigate = useNavigate();
  const [rooms, setRooms] = useState<Room[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [roomName, setRoomName] = useState('');
  const [creating, setCreating] = useState(false);
  const [spectateInput, setSpectateInput] = useState('');
  const { messages, connected, send } = useLobbyStomp(token);
  const [draft, setDraft] = useState('');

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      const res = await roomsApi.list(token, gameId.toUpperCase());
      setRooms(res.rooms);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }, [token, gameId]);

  useEffect(() => {
    if (!token) {
      navigate('/login');
      return;
    }
    refresh();
    const id = window.setInterval(refresh, 5000);
    return () => window.clearInterval(id);
  }, [token, refresh, navigate]);

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    if (!token || !roomName.trim()) return;
    setCreating(true);
    try {
      const room = await roomsApi.create(token, roomName.trim(), gameId.toUpperCase());
      navigate(`/rooms/${room.roomId}`);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    } finally {
      setCreating(false);
    }
  }

  async function handleJoin(roomId: string) {
    if (!token) return;
    try {
      await roomsApi.join(token, roomId);
      navigate(`/rooms/${roomId}`);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  async function handleSpectate(event: React.FormEvent) {
    event.preventDefault();
    if (!token || !spectateInput.trim()) return;
    const roomId = spectateInput.trim();
    try {
      await roomsApi.spectate(token, roomId);
      navigate(`/rooms/${roomId}`);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  function handleSendChat(event: React.FormEvent) {
    event.preventDefault();
    if (!draft.trim()) return;
    send(draft.trim());
    setDraft('');
  }

  return (
    <main className="lobby-page">
      <header>
        <Link to="/games">← Hub</Link>
        <h1>{gameId.toUpperCase()} 로비</h1>
      </header>

      {error && <p className="error">{error}</p>}

      <section className="rooms">
        <h2>대기 중인 방</h2>
        <ul>
          {rooms.length === 0 && <li className="empty">아직 방이 없습니다.</li>}
          {rooms.map((room) => (
            <li key={room.roomId}>
              <span className="name">{room.name}</span>
              <span className="count">
                {room.playerCount} / {room.capacity}
              </span>
              <button type="button" onClick={() => handleJoin(room.roomId)}>
                입장
              </button>
            </li>
          ))}
        </ul>

        <form className="create" onSubmit={handleCreate}>
          <input
            type="text"
            value={roomName}
            placeholder="새 방 이름"
            onChange={(e) => setRoomName(e.target.value)}
            required
          />
          <button type="submit" disabled={creating}>
            {creating ? '생성 중...' : '방 만들기'}
          </button>
        </form>

        <form className="spectate" onSubmit={handleSpectate}>
          <input
            type="text"
            value={spectateInput}
            placeholder="방 ID 로 관전 진입"
            onChange={(e) => setSpectateInput(e.target.value)}
          />
          <button type="submit" disabled={!spectateInput.trim()}>
            구경하기
          </button>
        </form>
      </section>

      <section className="chat">
        <h2>로비 채팅 {connected ? '●' : '○'}</h2>
        <ul>
          {messages.map((m) => (
            <li key={m.eventId}>
              <strong>{m.username}</strong>
              <span>{m.message}</span>
            </li>
          ))}
        </ul>
        <form onSubmit={handleSendChat}>
          <input
            type="text"
            value={draft}
            placeholder={connected ? '메시지' : '연결 중...'}
            disabled={!connected}
            onChange={(e) => setDraft(e.target.value)}
          />
          <button type="submit" disabled={!connected || !draft.trim()}>
            전송
          </button>
        </form>
      </section>
    </main>
  );
}
