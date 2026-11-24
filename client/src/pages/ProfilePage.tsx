import { GameIcon } from '@/components/GameIcon';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Pencil } from 'lucide-react';
import { useAuthStore } from '@/features/auth/authStore';
import { usersApi, type HeadToHeadEntry, type UserStats } from '@/api/users';
import { headToHeadText } from '@/features/stats/headToHead';
import { gamesApi } from '@/api/games';
import { GameRecords } from '@/features/stats/GameRecords';
import { showToast } from '@/components/toast/toastStore';
import { TierTableButton } from '@/features/stats/TierTableButton';
import { avatarSrc } from '@/api/avatar';
import { TierBadge } from '@/components/TierBadge';
import { ChangePasswordForm } from '@/features/profile/ChangePasswordForm';
import { AvatarSettingsModal } from '@/features/profile/AvatarSettingsModal';
import { Button } from '@/components/ui/button';
import { HUB_SCROLL } from '@/features/lobby/hubScroll';
import { cn } from '@/lib/utils';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { t } from '@/i18n/messages';

/** M1/A4 — 프로필/설정 페이지. 전적·아바타·비밀번호 변경 통합(D-85). D-303 — 촘촘한 배치, D-305 — 머리에 통합 계급·레이팅. */
export function ProfilePage() {
  const navigate = useNavigate();
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const [stats, setStats] = useState<UserStats | null>(null);
  // D-115 — 게임 표시명은 카탈로그에서 (클라에 게임 이름을 박지 않는다).
  const [gameNames, setGameNames] = useState<Record<string, string>>({});
  // D-154 — 프로필 이미지 설정은 여기서(메인 위쪽 아바타는 이 페이지로 오는 버튼). 바꾸면 버전으로 이미지 캐시를 넘긴다.
  const [avatarOpen, setAvatarOpen] = useState(false);
  // D-154 — 상대 전적(게스트·봇 제외, 많이 붙어 본 상대부터).
  const [rivals, setRivals] = useState<HeadToHeadEntry[] | null>(null);
  const [avatarVersion, setAvatarVersion] = useState(0);

  useEffect(() => {
    if (token && user) {
      usersApi
        .stats(token, user.userId)
        .then(setStats)
        .catch(() => setStats(null));
      usersApi
        .headToHead(token, user.userId)
        .then((res) => setRivals(res.entries))
        .catch(() => setRivals([]));
      gamesApi
        .catalog(token)
        .then((res) => setGameNames(Object.fromEntries(res.games.map((g) => [g.id, g.displayName]))))
        .catch(() => {});
    }
  }, [token, user]);

  // D-305 — 계급·레이팅은 통합 하나(명예 계급 D-198 은 서버가 `tier` 에 이미 넣는다). 게스트는 계급이 없다.
  const headerTier = user?.guest ? null : (stats?.tier ?? null);
  const headerRating = user?.guest ? null : (stats?.rating ?? null);

  if (!user) return null;

  return (
    <div className="app-shell min-h-screen bg-background text-foreground">
      <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-6">
        <header className="flex items-center justify-between">
          <h1 className="text-2xl font-bold tracking-tight">내 프로필</h1>
          <Button variant="outline" size="sm" onClick={() => navigate('/games')}>
            <ArrowLeft className="h-4 w-4" />
            메인으로
          </Button>
        </header>

        <section className="flex items-center gap-4 rounded-lg border p-4">
          {/* D-302·D-303 — 프로필 이미지는 아바타를 눌러 바꾼다(따로 버튼 없음). 오른쪽 아래 작은 연필로 누를 수 있음을 보인다.
              게스트는 못 바꾸므로(D-117) 연필 없이, 눌러도 안내 토스트만 뜬다. */}
          <button
            type="button"
            onClick={() => (user.guest ? showToast(t('hub.guest.avatarDisabled')) : setAvatarOpen(true))}
            aria-label={user.guest ? '내 아바타' : '내 아바타 — 눌러서 이미지 바꾸기'}
            title={user.guest ? undefined : '눌러서 이미지 바꾸기'}
            className="group relative shrink-0 rounded-full border-0 bg-transparent p-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            <Avatar className={cn('h-16 w-16', !user.guest && 'transition-opacity group-hover:opacity-85')}>
              <AvatarImage src={avatarSrc(user.userId, avatarVersion || undefined)} alt="" />
              <AvatarFallback>{user.username.slice(0, 2).toUpperCase()}</AvatarFallback>
            </Avatar>
            {!user.guest && (
              <span
                aria-hidden="true"
                className="absolute -bottom-0.5 -right-0.5 flex h-6 w-6 items-center justify-center rounded-full border-2 border-background bg-primary text-primary-foreground"
              >
                <Pencil className="h-3 w-3" />
              </span>
            )}
          </button>
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="truncate text-lg font-semibold">{user.username}</span>
            {/* D-305 — 통합 계급 + 레이팅 숫자. D-266 — 옆에 [계급표](내 계급 강조, 게스트는 강조 없음). */}
            <div className="flex flex-wrap items-center gap-2">
              {headerTier && <TierBadge tier={headerTier} size={24} />}
              {headerRating != null && (
                <span data-testid="profile-rating" className="text-base font-semibold tabular-nums" title="통합 레이팅">
                  {headerRating}
                  <span className="ml-0.5 text-xs font-normal text-muted-foreground">점</span>
                </span>
              )}
              <TierTableButton className="h-10" myTier={headerTier} rating={headerRating} />
            </div>
          </div>
        </section>

        {token && !user.guest && (
          <AvatarSettingsModal
            open={avatarOpen}
            onClose={() => setAvatarOpen(false)}
            token={token}
            userId={user.userId}
            username={user.username}
            onChanged={() => setAvatarVersion((v) => v + 1)}
          />
        )}

        {/* D-303 — 전적 두 카드는 촘촘하게: 작은 머리 줄 + 한 줄 칩 2열. */}
        <section aria-labelledby="records-title" className="rounded-lg border p-3">
          <h2 id="records-title" className="mb-2 text-sm font-semibold">게임별 전적</h2>
          {stats && <GameRecords records={stats.games ?? []} names={gameNames} />}
        </section>

        <section aria-labelledby="rivals-title" className="rounded-lg border p-3">
          <h2 id="rivals-title" className="mb-2 text-sm font-semibold">상대 전적</h2>
          {rivals !== null && rivals.length === 0 && (
            <p className="m-0 text-sm text-muted-foreground">아직 함께한 사람이 없어요</p>
          )}
          {rivals !== null && rivals.length > 0 && (
            // 상대가 많아도 아래 카드를 밀지 않게 높이를 묶고 안쪽에서 굴린다.
            <ul className={cn('m-0 grid max-h-64 list-none grid-cols-1 gap-1.5 p-0 text-sm sm:grid-cols-2', HUB_SCROLL)}>
              {rivals.map((r) => (
                <li
                  key={`${r.gameType}-${r.opponentId}`}
                  className="flex min-w-0 items-center gap-2 rounded-md border px-2.5 py-1.5"
                >
                  <span title={gameNames[r.gameType] ?? r.gameType} className="inline-flex shrink-0">
                    <GameIcon gameId={r.gameType} size={18} />
                  </span>
                  <span className="min-w-0 truncate font-medium">{r.opponentName}</span>
                  <span className="sr-only">{gameNames[r.gameType] ?? r.gameType}</span>
                  <span className="ml-auto shrink-0 whitespace-nowrap text-xs tabular-nums text-muted-foreground">
                    {headToHeadText(r)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="rounded-lg border p-3">
          <h2 className="mb-2 text-sm font-semibold">비밀번호 변경</h2>
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
