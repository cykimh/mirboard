import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { gamesApi } from '@/api/games';
import { roomsApi } from '@/api/rooms';
import { usersApi, type UserStats, type RankEntry } from '@/api/users';
import { useAuthStore } from '@/features/auth/authStore';
import { useLobbyStomp } from '@/ws/useLobbyStomp';
import { TierBadge } from '@/components/TierBadge';
import { CreateRoomModal } from '@/features/lobby/CreateRoomModal';
import { gameWikiUrl } from '@/features/lobby/gameWiki';
import { t } from '@/i18n/messages';
import type { GameSummary, Room } from '@/types/api';

/**
 * Phase 16(#5–#7) — 통합 메인페이지. 게임 소개 + 방 만들기(게임 선택) +
 * 열린 방 목록/입장 + 관전 + 로비 채팅 + 유저 랭킹. 게임별 로비 페이지는
 * 제거되고 모든 진입이 여기로 일원화된다.
 */
export function GameHubPage() {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const [games, setGames] = useState<GameSummary[] | null>(null);
  const [stats, setStats] = useState<UserStats | null>(null);
  const [ranking, setRanking] = useState<RankEntry[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [spectateInput, setSpectateInput] = useState('');

  const { messages, connected, send } = useLobbyStomp(token);
  const [draft, setDraft] = useState('');

  const refreshRooms = useCallback(async () => {
    if (!token) return;
    try {
      const res = await roomsApi.list(token);
      setRooms(res.rooms);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }, [token]);

  useEffect(() => {
    if (!token || !user) {
      navigate('/login');
      return;
    }
    gamesApi
      .catalog(token)
      .then((res) => {
        setGames(res.games);
      })
      .catch((err: Error) => setError(err.message));
    usersApi.stats(token, user.userId).then(setStats).catch(() => {});
    usersApi.ranking(token, 20).then((r) => setRanking(r.entries)).catch(() => {});
  }, [token, user, navigate]);

  useEffect(() => {
    if (!token) return;
    refreshRooms();
    const id = window.setInterval(refreshRooms, 5000);
    return () => window.clearInterval(id);
  }, [token, refreshRooms]);

  async function handleJoin(roomId: string) {
    if (!token) return;
    try {
      // D-74: 멱등 경로. 이미 참가 상태(나갔다 재입장 등)여도 RECONNECTED
      // 로 정상 진입 — ALREADY_IN_ROOM 재입장 버그 제거.
      await roomsApi.joinOrReconnect(token, roomId);
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

  const availableGames = (games ?? []).filter((g) => g.status === 'AVAILABLE');

  return (
    <main className="hub-page">
      <header>
        <h1>{t('hub.title')}</h1>
        <div className="user-bar" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {user && <span>{user.username}</span>}
          {stats && <TierBadge tier={stats.tier} rating={stats.rating} />}
          {stats && (
            <span style={{ fontSize: 12, opacity: 0.7 }}>
              {stats.winCount}승 {stats.loseCount}패
              {stats.desertCount > 0 && ` · 탈주 ${stats.desertCount}`}
            </span>
          )}
          <button type="button" onClick={() => { logout(); navigate('/login'); }}>
            로그아웃
          </button>
        </div>
      </header>

      {error && <p className="error">{error}</p>}
      {games === null && !error && <p>카탈로그 불러오는 중...</p>}

      <section className="game-grid">
        {games?.map((game) => (
          <article
            key={game.id}
            className={`game-card ${game.status.toLowerCase()}`}
            aria-disabled={game.status !== 'AVAILABLE'}
          >
            <h2>{game.displayName}</h2>
            <p>{game.shortDescription}</p>
            <p className="meta">
              {game.minPlayers === game.maxPlayers
                ? `${game.maxPlayers}인 플레이`
                : `${game.minPlayers}~${game.maxPlayers}인 플레이`}
            </p>
            <div className="game-card-foot">
              {game.status !== 'AVAILABLE' && (
                <span className="badge">Coming Soon</span>
              )}
              {gameWikiUrl(game.id) && (
                <a
                  className="game-wiki-link"
                  href={gameWikiUrl(game.id)}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  자세히 ↗
                </a>
              )}
            </div>
          </article>
        ))}
      </section>

      <section className="rooms">
        <div className="rooms-head">
          <h2>대기 중인 방</h2>
          <button
            type="button"
            className="create-room-btn"
            onClick={() => setShowCreateModal(true)}
            disabled={availableGames.length === 0}
          >
            + 새 방 만들기
          </button>
        </div>
        <ul>
          {rooms.length === 0 && <li className="empty">아직 방이 없습니다.</li>}
          {rooms.map((room) => (
            <li key={room.roomId}>
              <span className="name">
                {room.name}
                {room.fillWithBots && (
                  <span className="solo-badge" title="솔로 모드 — 빈 좌석은 봇이 자동 채움">🤖 솔로</span>
                )}
              </span>
              <span className="count">
                {room.gameType} · {room.playerCount} / {room.capacity} · {room.status}
              </span>
              <button
                type="button"
                onClick={() => handleJoin(room.roomId)}
                disabled={room.status !== 'WAITING' || room.playerCount >= room.capacity}
              >
                입장
              </button>
            </li>
          ))}
        </ul>

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

      <section className="ranking">
        <h2>랭킹</h2>
        {ranking.length === 0 ? (
          <p className="empty">아직 랭킹 데이터가 없습니다.</p>
        ) : (
          <table className="ranking-table">
            <thead>
              <tr>
                <th>#</th>
                <th>유저</th>
                <th>티어</th>
                <th>레이팅</th>
                <th>전적</th>
                <th>탈주</th>
              </tr>
            </thead>
            <tbody>
              {ranking.map((e) => (
                <tr key={e.userId} className={user && e.userId === user.userId ? 'me' : ''}>
                  <td>{e.rank}</td>
                  <td>{e.username}</td>
                  <td>{e.tier}</td>
                  <td>{e.rating}</td>
                  <td>{e.winCount}승 {e.loseCount}패</td>
                  <td>{e.desertCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
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

      {token && (
        <CreateRoomModal
          open={showCreateModal}
          token={token}
          availableGames={availableGames}
          onClose={() => setShowCreateModal(false)}
          onError={(msg) => {
            setError(msg);
            setShowCreateModal(false);
          }}
        />
      )}
    </main>
  );
}
