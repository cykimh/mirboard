import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { gamesApi } from '@/api/games';
import { useAuthStore } from '@/features/auth/authStore';
import type { GameSummary } from '@/types/api';

export function GameHubPage() {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const [games, setGames] = useState<GameSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      navigate('/login');
      return;
    }
    gamesApi
      .catalog(token)
      .then((res) => setGames(res.games))
      .catch((err: Error) => setError(err.message));
  }, [token, navigate]);

  return (
    <main className="hub-page">
      <header>
        <h1>Game Hub</h1>
        <div className="user-bar">
          {user && <span>{user.username}</span>}
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
