import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, ExternalLink, HelpCircle, LogOut, Plus } from 'lucide-react';
import { ApiError } from '@/api/client';
import { gamesApi } from '@/api/games';
import { roomsApi } from '@/api/rooms';
import { usersApi, type UserStats } from '@/api/users';
import { RankingCard } from '@/features/stats/RankingCard';
import { primaryGame } from '@/features/stats/primaryGame';
import { useAuthStore } from '@/features/auth/authStore';
import { useLobbyStomp } from '@/ws/useLobbyStomp';
import { TierBadge } from '@/components/TierBadge';
import { CreateRoomModal } from '@/features/lobby/CreateRoomModal';
import { gameWikiUrl } from '@/features/lobby/gameWiki';
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
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import {
  Avatar,
  AvatarFallback,
  AvatarImage,
} from '@/components/ui/avatar';
import { avatarSrc } from '@/api/avatar';
import { AvatarSettingsModal } from '@/features/profile/AvatarSettingsModal';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { tutorialFor } from '@/features/tutorial/gameTutorials';
import { markTutorialSeen } from '@/features/tutorial/useTutorialGate';
import { useColorblindStore } from '@/features/theme/colorblindStore';
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
  const [avatarModalOpen, setAvatarModalOpen] = useState(false);
  // D-121 — 카드별 '게임 방법'으로 연 게임. 허브는 게임을 고를 수 없어 자동 노출하지 않는다
  // (자동 노출은 그 게임 대기실 첫 입장 — RoomPage).
  const [tutorialGameId, setTutorialGameId] = useState<string | null>(null);
  const hubTutorial = tutorialFor(tutorialGameId);
  const colorblind = useColorblindStore((s) => s.enabled);
  const toggleColorblind = useColorblindStore((s) => s.toggle);
  const [avatarVersion, setAvatarVersion] = useState(0);

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
  const played = stats?.games ?? [];
  const primary = primaryGame(played);
  const totals = played.reduce(
    (acc, g) => ({
      win: acc.win + g.winCount,
      lose: acc.lose + g.loseCount,
      desert: acc.desert + g.desertCount,
    }),
    { win: 0, lose: 0, desert: 0 },
  );
  const gameName = (id: string) => games?.find((g) => g.id === id)?.displayName ?? id;

  return (
    <div className="app-shell min-h-screen bg-background text-foreground">
      <div className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-6">
        {/* 헤더 */}
        <header className="flex flex-wrap items-center justify-between gap-4">
          <h1 className="text-2xl font-bold tracking-tight">
            {t('hub.title')}
          </h1>
          <div className="flex items-center gap-3">
            {user && (
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setAvatarModalOpen(true)}
                  title="아바타 설정"
                  className="rounded-full ring-offset-background transition hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                >
                  <Avatar>
                    <AvatarImage
                      src={avatarSrc(user.userId, avatarVersion || undefined)}
                      alt=""
                    />
                    <AvatarFallback>
                      {user.username.slice(0, 2).toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                </button>
                <button
                  type="button"
                  onClick={() => navigate('/profile')}
                  className="text-sm font-medium underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded"
                  title="내 프로필"
                >
                  {user.username}
                </button>
              </div>
            )}
            {/* D-115 — 레이팅은 게임별이라 가장 많이 한 게임의 티어를 대표로, 전적은 전 게임 합. */}
            {primary && (
              <span title={`${gameName(primary.gameType)} 기준`}>
                <TierBadge tier={primary.tier} rating={primary.rating} />
              </span>
            )}
            {stats && (
              <span className="text-xs text-muted-foreground">
                {totals.win}승 {totals.lose}패
                {totals.desert > 0 && ` · 탈주 ${totals.desert}`}
              </span>
            )}
            <Button
              type="button"
              variant={colorblind ? 'default' : 'outline'}
              size="sm"
              onClick={toggleColorblind}
              aria-pressed={colorblind}
              aria-label="색약 모드"
              title="색약 모드 — 카드 슈트를 글리프로도 표시"
            >
              <Eye className="h-4 w-4" />
              <span className="hidden sm:inline">색약</span>
            </Button>
            <ThemeToggle />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
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
                    <CardHeader>
                      <CardTitle>{game.displayName}</CardTitle>
                      <CardDescription>
                        {game.shortDescription}
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="text-sm text-muted-foreground">
                      {game.minPlayers === game.maxPlayers
                        ? `${game.maxPlayers}인 플레이`
                        : `${game.minPlayers}~${game.maxPlayers}인 플레이`}
                    </CardContent>
                    <CardFooter className="mt-auto gap-3">
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

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          {/* 대기 중인 방 */}
          <Card className="lg:col-span-2">
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
              <ul className="flex flex-col gap-2">
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
                      {room.fillWithBots && (
                        <Badge
                          variant="secondary"
                          title="솔로 모드 — 빈 좌석은 봇이 자동 채움"
                        >
                          🤖 솔로
                        </Badge>
                      )}
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
                      {room.gameType} · {room.playerCount} / {room.capacity} ·{' '}
                      {room.status}
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

          {/* 로비 채팅 */}
          <Card className="flex flex-col">
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <CardTitle>로비 채팅</CardTitle>
              <Badge variant={connected ? 'default' : 'secondary'}>
                {connected ? '연결됨' : '연결 중'}
              </Badge>
            </CardHeader>
            <CardContent className="flex flex-1 flex-col gap-3">
              <ScrollArea className="h-56 rounded-md border p-2">
                <ul className="flex flex-col gap-1.5 text-sm">
                  {messages.map((m) => (
                    <li key={m.eventId} className="flex gap-2">
                      <strong className="text-primary">{m.username}</strong>
                      <span className="text-foreground/90">{m.message}</span>
                    </li>
                  ))}
                </ul>
              </ScrollArea>
              <form className="flex gap-2" onSubmit={handleSendChat}>
                <Input
                  type="text"
                  value={draft}
                  placeholder={connected ? '메시지' : '연결 중...'}
                  disabled={!connected}
                  onChange={(e) => setDraft(e.target.value)}
                />
                <Button
                  type="submit"
                  disabled={!connected || !draft.trim()}
                >
                  전송
                </Button>
              </form>
            </CardContent>
          </Card>
        </div>

        {/* 랭킹 — D-115 게임별 */}
        {token && availableGames.length > 0 && (
          <RankingCard token={token} games={availableGames} myUserId={user?.userId} />
        )}
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

      {token && user && (
        <AvatarSettingsModal
          open={avatarModalOpen}
          onClose={() => setAvatarModalOpen(false)}
          token={token}
          userId={user.userId}
          username={user.username}
          onChanged={() => setAvatarVersion((v) => v + 1)}
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
