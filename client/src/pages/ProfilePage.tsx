import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { useAuthStore } from '@/features/auth/authStore';
import { usersApi, type UserStats } from '@/api/users';
import { gamesApi } from '@/api/games';
import { GameRecords } from '@/features/stats/GameRecords';
import { primaryGame } from '@/features/stats/primaryGame';
import { avatarSrc } from '@/api/avatar';
import { TierBadge } from '@/components/TierBadge';
import { ChangePasswordForm } from '@/features/profile/ChangePasswordForm';
import { Button } from '@/components/ui/button';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { t } from '@/i18n/messages';

/** M1/A4 — 프로필/설정 페이지. 전적·아바타·비밀번호 변경 통합(D-85). */
export function ProfilePage() {
  const navigate = useNavigate();
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const [stats, setStats] = useState<UserStats | null>(null);
  // D-115 — 게임 표시명은 카탈로그에서 (클라에 게임 이름을 박지 않는다).
  const [gameNames, setGameNames] = useState<Record<string, string>>({});

  useEffect(() => {
    if (token && user) {
      usersApi
        .stats(token, user.userId)
        .then(setStats)
        .catch(() => setStats(null));
      gamesApi
        .catalog(token)
        .then((res) => setGameNames(Object.fromEntries(res.games.map((g) => [g.id, g.displayName]))))
        .catch(() => {});
    }
  }, [token, user]);

  const primary = primaryGame(stats?.games ?? []);

  if (!user) return null;

  return (
    <div className="app-shell min-h-screen bg-background text-foreground">
      <div className="mx-auto flex max-w-2xl flex-col gap-6 px-4 py-6">
        <header className="flex items-center justify-between">
          <h1 className="text-2xl font-bold tracking-tight">내 프로필</h1>
          <Button variant="outline" size="sm" onClick={() => navigate('/games')}>
            <ArrowLeft className="h-4 w-4" />
            메인으로
          </Button>
        </header>

        <section className="flex items-center gap-4 rounded-lg border p-4">
          <Avatar className="h-16 w-16">
            <AvatarImage src={avatarSrc(user.userId)} alt="" />
            <AvatarFallback>{user.username.slice(0, 2).toUpperCase()}</AvatarFallback>
          </Avatar>
          <div className="flex flex-col gap-1">
            <span className="text-lg font-semibold">{user.username}</span>
            {primary && <TierBadge tier={primary.tier} rating={primary.rating} />}
          </div>
        </section>

        <section className="rounded-lg border p-4">
          <h2 className="mb-3 text-lg font-semibold">게임별 전적</h2>
          {stats && <GameRecords records={stats.games ?? []} names={gameNames} />}
        </section>

        <section className="rounded-lg border p-4">
          <h2 className="mb-3 text-lg font-semibold">비밀번호 변경</h2>
          {/* D-117 — 게스트는 비밀번호가 없다(서버도 403 GUEST_FORBIDDEN). */}
          {user.guest ? (
            <p className="text-sm text-muted-foreground">{t('profile.guest.noPassword')}</p>
          ) : (
            token && <ChangePasswordForm token={token} />
          )}
        </section>
      </div>
    </div>
  );
}
