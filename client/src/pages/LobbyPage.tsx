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
  const [fillWithBots, setFillWithBots] = useState(false);
  const [targetScore, setTargetScore] = useState(1000);
  const [turnSeconds, setTurnSeconds] = useState(0);
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
      const room = await roomsApi.create(token, roomName.trim(), gameId.toUpperCase(), {
        fillWithBots,
        targetScore,
        turnSeconds,
      });
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
              <span className="name">
                {room.name}
                {room.fillWithBots && <span className="solo-badge" title="솔로 모드 — 빈 좌석은 봇이 자동 채움">🤖 솔로</span>}
              </span>
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
          <div className="target-score-picker" role="group" aria-label="목표 점수">
            <span className="target-score-label">목표 점수</span>
            {[300, 500, 700, 1000].map((v) => (
              <button
                type="button"
                key={v}
                className={`target-score-opt ${targetScore === v ? 'active' : ''}`}
                onClick={() => setTargetScore(v)}
                aria-pressed={targetScore === v}
              >
                {v}
              </button>
            ))}
          </div>
          <div className="target-score-picker" role="group" aria-label="턴 제한">
            <span className="target-score-label">턴 제한</span>
            {[
              { v: 0, label: '끔' },
              { v: 30, label: '30초' },
              { v: 60, label: '60초' },
              { v: 90, label: '90초' },
            ].map((o) => (
              <button
                type="button"
                key={o.v}
                className={`target-score-opt ${turnSeconds === o.v ? 'active' : ''}`}
                onClick={() => setTurnSeconds(o.v)}
                aria-pressed={turnSeconds === o.v}
              >
                {o.label}
              </button>
            ))}
          </div>
          <label className="fill-bots-toggle" title="혼자 시연/연습할 때 빈 좌석을 봇으로 채워 즉시 게임 시작">
            <input
              type="checkbox"
              checked={fillWithBots}
              onChange={(e) => setFillWithBots(e.target.checked)}
            />
            <span>🤖 빈 좌석 봇으로 채우기</span>
          </label>
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
