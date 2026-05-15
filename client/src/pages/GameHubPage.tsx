import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { gamesApi } from '@/api/games';
import { usersApi, type UserStats } from '@/api/users';
import { useAuthStore } from '@/features/auth/authStore';
import { TierBadge } from '@/components/TierBadge';
import type { GameSummary } from '@/types/api';

export function GameHubPage() {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const [games, setGames] = useState<GameSummary[] | null>(null);
  const [stats, setStats] = useState<UserStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token || !user) {
      navigate('/login');
      return;
    }
    gamesApi
      .catalog(token)
      .then((res) => setGames(res.games))
      .catch((err: Error) => setError(err.message));
    // Phase 8D — 본인 ELO/티어 표시. 실패해도 게임 카탈로그는 계속 보임.
    usersApi.stats(token, user.userId).then(setStats).catch(() => {});
  }, [token, user, navigate]);

  return (
    <main className="hub-page">
      <header>
        <h1>Game Hub</h1>
        <div className="user-bar" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {user && <span>{user.username}</span>}
          {stats && <TierBadge tier={stats.tier} rating={stats.rating} />}
          {stats && (
            <span style={{ fontSize: 12, opacity: 0.7 }}>
              {stats.winCount}승 {stats.loseCount}패
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
            {game.status === 'AVAILABLE' ? (
              <Link to={`/games/${game.id}/lobby`} className="play-button">
                플레이
              </Link>
            ) : (
              <span className="badge">Coming Soon</span>
            )}
          </article>
        ))}
      </section>
    </main>
  );
}
