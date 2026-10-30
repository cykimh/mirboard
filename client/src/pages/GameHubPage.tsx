import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ExternalLink, HelpCircle, LogOut, Plus } from 'lucide-react';
import { ApiError } from '@/api/client';
import { errorText } from '@/api/errorText';
import { gamesApi } from '@/api/games';
import { roomsApi } from '@/api/rooms';
import { usersApi, type UserStats } from '@/api/users';
import { RankingCard } from '@/features/stats/RankingCard';
import { primaryGame } from '@/features/stats/primaryGame';
import { useAuthStore } from '@/features/auth/authStore';
import { TierBadge } from '@/components/TierBadge';
import { CreateRoomModal } from '@/features/lobby/CreateRoomModal';
import { gameWikiUrl } from '@/features/lobby/gameWiki';
import { ROOM_STATUS_LABEL } from '@/features/lobby/roomStatusLabel';
import { markSpectateNoticeSeen } from '@/features/lobby/SpectateNotice';
import { t } from '@/i18n/messages';
import type { GameSummary, Room } from '@/types/api';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Skeleton } from '@/components/ui/skeleton';
import { Separator } from '@/components/ui/separator';
import {
  Avatar,
  AvatarFallback,
  AvatarImage,
} from '@/components/ui/avatar';
import { avatarSrc } from '@/api/avatar';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { tutorialFor } from '@/features/tutorial/gameTutorials';
import { markTutorialSeen } from '@/features/tutorial/useTutorialGate';
import { ThemeToggle } from '@/components/ui/theme-toggle';
import { cn } from '@/lib/utils';

/**
 * Phase 16(#5–#7) — 통합 메인페이지. Phase 20b(D-76): shadcn/ui + Slate
 * 테마로 재디자인. 상태/effect/API/핸들러는 불변, 마크업만 교체.
 */
export function GameHubPage() {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const [games, setGames] = useState<GameSummary[] | null>(null);
  const [stats, setStats] = useState<UserStats | null>(null);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [spectateInput, setSpectateInput] = useState('');
  // D-121 — 카드별 '게임 방법'으로 연 게임. 허브는 게임을 고를 수 없어 자동 노출하지 않는다
  // (자동 노출은 그 게임 대기실 첫 입장 — RoomPage).
  const [tutorialGameId, setTutorialGameId] = useState<string | null>(null);
  const hubTutorial = tutorialFor(tutorialGameId);

  const refreshRooms = useCallback(async () => {
    if (!token) return;
    try {
      const res = await roomsApi.list(token);
      setRooms(res.rooms);
    } catch (err) {
      if (err instanceof ApiError) setError(errorText(err, '방 목록을 불러오지 못했어요'));
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
      .catch((err: unknown) => setError(errorText(err, '게임 목록을 불러오지 못했어요')));
    usersApi.stats(token, user.userId).then(setStats).catch(() => {});
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
      if (err instanceof ApiError) setError(errorText(err, '방에 들어가지 못했어요'));
    }
  }

  async function handleSpectate(event: React.FormEvent) {
    event.preventDefault();
    if (!token || !spectateInput.trim()) return;
    const roomId = spectateInput.trim();
    try {
      await roomsApi.spectate(token, roomId);
      // D-133 결정 A — 본인이 고른 관전이라 방 화면의 "…관전으로 들어왔어요" 안내를 띄우지 않는다(탭 기록).
      markSpectateNoticeSeen(roomId);
      navigate(`/rooms/${roomId}`);
    } catch (err) {
      if (err instanceof ApiError) setError(errorText(err, '관전하지 못했어요'));
    }
  }

  const availableGames = (games ?? []).filter((g) => g.status === 'AVAILABLE');
  const played = stats?.games ?? [];
  const primary = primaryGame(played);
  const gameName = (id: string) => games?.find((g) => g.id === id)?.displayName ?? id;

  return (
    <div className="app-shell min-h-screen bg-background text-foreground">
      <div className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-6">
        {/* 헤더 */}
        <header className="flex flex-wrap items-center justify-between gap-4">
          {/* D-145 — 화면에 사이트 이름을 쓰지 않는다. 제목은 보조기기에만 남긴다(오른쪽 줄은 ml-auto 로 그대로 오른쪽). */}
          <h1 className="sr-only">{t('hub.title')}</h1>
          <div className="ml-auto flex items-center gap-3">
            {user && (
              <div className="flex items-center gap-2">
                {/* D-154 — 위쪽에는 아바타만. 누르면 내 프로필(이미지 설정도 거기서). */}
                <button
                  type="button"
                  onClick={() => navigate('/profile')}
                  aria-label="내 프로필"
                  title={`내 프로필 — ${user.username}`}
                  className="rounded-full ring-offset-background transition hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                >
                  <Avatar>
                    <AvatarImage src={avatarSrc(user.userId)} alt="" />
                    <AvatarFallback>{user.username.slice(0, 2).toUpperCase()}</AvatarFallback>
                  </Avatar>
                </button>
                {user.guest && <Badge variant="secondary">{t('hub.guest.badge')}</Badge>}
              </div>
            )}
            {/* D-115 — 레이팅은 게임별이라 가장 많이 한 게임의 티어를 대표로. D-146 — 전적 합계는 뺐다(프로필에 있다). */}
            {primary && (
              <span title={`${gameName(primary.gameType)} 기준`}>
                <TierBadge tier={primary.tier} size={24} />
              </span>
            )}
            <ThemeToggle />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                // D-117 — 게스트는 비밀번호가 없어 로그아웃하면 같은 신원으로 못 돌아온다.
                if (user?.guest && !window.confirm(t('hub.guest.logoutConfirm'))) return;
                logout();
                navigate('/login');
              }}
            >
              <LogOut className="h-4 w-4" />
              로그아웃
            </Button>
          </div>
        </header>

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        {/* D-154 — 순서: 랭킹 → 대기 중인 방 → 게임 소개. */}
        {/* 랭킹 — D-115 게임별 */}
        {token && availableGames.length > 0 && (
          <RankingCard token={token} games={availableGames} myUserId={user?.userId} />
        )}
        {/* D-146 — 로비 채팅을 없애 대기 중인 방이 한 줄을 다 쓴다. */}
        <div className="grid grid-cols-1 gap-6">
          {/* 대기 중인 방 */}
          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <CardTitle>대기 중인 방</CardTitle>
              <Button
                type="button"
                size="sm"
                onClick={() => setShowCreateModal(true)}
                disabled={availableGames.length === 0}
              >
                <Plus className="h-4 w-4" />새 방 만들기
              </Button>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <ul className="m-0 flex list-none flex-col gap-2 p-0">
                {rooms.length === 0 && (
                  <li className="rounded-md border border-dashed p-4 text-center text-sm text-muted-foreground">
                    아직 방이 없습니다.
                  </li>
                )}
                {rooms.map((room) => (
                  <li
                    key={room.roomId}
                    className="flex items-center gap-3 rounded-md border p-3"
                  >
                    <span className="flex flex-1 items-center gap-2 font-medium">
                      {room.name}
                      {room.stake > 0 && (
                        <Badge
                          variant="outline"
                          title={`내기 방 — 판돈 ${room.stake}칩 (가상 칩)`}
                        >
                          💰 {room.stake}
                        </Badge>
                      )}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {/* D-130 — 대기실 헤더(D-110)처럼 카탈로그 표시 이름과 한글 상태 라벨. 모르는 게임·상태는 원문. */}
                      {gameName(room.gameType)} · {room.playerCount} / {room.capacity} ·{' '}
                      {ROOM_STATUS_LABEL[room.status] ?? room.status}
                    </span>
                    <Button
                      type="button"
                      size="sm"
                      variant="secondary"
                      onClick={() => handleJoin(room.roomId)}
                      disabled={
                        room.status !== 'WAITING' ||
                        room.playerCount >= room.capacity
                      }
                    >
                      입장
                    </Button>
                  </li>
                ))}
              </ul>

              <Separator />

              <form className="flex gap-2" onSubmit={handleSpectate}>
                <Input
                  type="text"
                  value={spectateInput}
                  placeholder="방 ID 로 관전 진입"
                  onChange={(e) => setSpectateInput(e.target.value)}
                />
                <Button
                  type="submit"
                  variant="outline"
                  disabled={!spectateInput.trim()}
                >
                  구경하기
                </Button>
              </form>
            </CardContent>
          </Card>
        </div>

        {/* 게임 카탈로그 */}
        <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {games === null && !error
            ? Array.from({ length: 3 }).map((_, i) => (
                <Card key={i}>
                  <CardHeader>
                    <Skeleton className="h-6 w-32" />
                    <Skeleton className="h-4 w-full" />
                  </CardHeader>
                  <CardContent>
                    <Skeleton className="h-4 w-24" />
                  </CardContent>
                </Card>
              ))
            : games?.map((game) => {
                const available = game.status === 'AVAILABLE';
                const wiki = gameWikiUrl(game.id);
                const hasTutorial = !!tutorialFor(game.id);
                return (
                  <Card
                    key={game.id}
                    className={cn(
                      'flex flex-col',
                      !available && 'opacity-60',
                    )}
                  >
                    {/* D-154 — 작은 카드: 여백·글자를 줄이고 소개는 두 줄까지. */}
                    <CardHeader className="space-y-1 p-4 pb-1">
                      <CardTitle className="text-base">{game.displayName}</CardTitle>
                      <CardDescription className="line-clamp-2 text-xs">
                        {game.shortDescription}
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="px-4 pb-1 text-xs text-muted-foreground">
                      {game.minPlayers === game.maxPlayers
                        ? `${game.maxPlayers}인 플레이`
                        : `${game.minPlayers}~${game.maxPlayers}인 플레이`}
                    </CardContent>
                    <CardFooter className="mt-auto gap-3 px-4 pb-3 pt-1">
                      {!available && (
                        <Badge variant="secondary">Coming Soon</Badge>
                      )}
                      {wiki && (
                        <a
                          className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
                          href={wiki}
                          target="_blank"
                          rel="noopener noreferrer"
                        >
                          자세히 <ExternalLink className="h-3.5 w-3.5" />
                        </a>
                      )}
                      {hasTutorial && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => setTutorialGameId(game.id)}
                          aria-label={`${game.displayName} 게임 방법`}
                        >
                          <HelpCircle className="h-4 w-4" />
                          게임 방법
                        </Button>
                      )}
                    </CardFooter>
                  </Card>
                );
              })}
        </section>

      </div>

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

      {/* 마운트마다 0단계부터. 닫으면 그 게임을 본 것으로 기록해 대기실에서 다시 띄우지 않는다. */}
      {hubTutorial && (
        <TutorialDialog
          tutorial={hubTutorial}
          open
          onClose={() => {
            markTutorialSeen(hubTutorial.seenKey);
            setTutorialGameId(null);
          }}
        />
      )}
    </div>
  );
}
