import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { roomsApi, type JoinOrReconnectResponse } from '@/api/rooms';
import { usersApi } from '@/api/users';
import { loadGame } from '@/api/games';
import { useAuthStore } from '@/features/auth/authStore';
import { GameTable } from '@/features/tichu/GameTable';
import { SkullKingTable } from '@/features/skullking/SkullKingTable';
import { OneCardTable } from '@/features/onecard/OneCardTable';
import { ROOM_STATUS_LABEL } from '@/features/lobby/roomStatusLabel';
import { InviteLinkButton } from '@/features/lobby/InviteLinkButton';
import { JoinBlockedPanel, type JoinBlock } from '@/features/lobby/JoinBlockedPanel';
import {
  SpectateNotice,
  markSpectateNoticeSeen,
  spectateNoticeMessage,
  spectateNoticeOf,
  spectateNoticeSeen,
  type SpectateNoticeKind,
} from '@/features/lobby/SpectateNotice';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { tutorialFor } from '@/features/tutorial/gameTutorials';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { useRoomMeta } from '@/ws/useRoomMeta';
import type { Room, RoomOption, TeamPolicy } from '@/types/api';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Separator } from '@/components/ui/separator';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

/**
 * D-120·D-129 — 종료 화면을 가진 게임. 이 세션이 IN_GAME→FINISHED 전이를 보면 게임판을 내리지 않고 결과를
 * 보여 준다. 두 게임판은 같은 props 계약(`roomFinished` 포함)을 따른다.
 */
const BOARD_HELD_GAMES: ReadonlySet<string> = new Set(['SKULL_KING', 'ONE_CARD']);

/**
 * D-133 — 첫 입장(`join-or-reconnect`)이 막힌 이유. 자리 없음은 관전을, 방 없음은 메인으로를 제안한다. 그 밖의 오류는
 * 지금처럼 서버 문구를 보인다. 서버는 꽉 찬 대기실·진행 중 방이면 이미 관전으로 받으므로 `full` 은 그 사이 자리가 찬 경합이다.
 */
function joinBlockOf(code: string): JoinBlock | null {
  if (code === 'ROOM_FULL' || code === 'GAME_ALREADY_STARTED') return 'full';
  if (code === 'ROOM_NOT_FOUND') return 'gone';
  return null;
}

/** D-122 — 유지된 게임판이 쓰는 좌석 목록 스냅샷 (FINISHED 전이 직전 IN_GAME 메타). */
interface HeldSeats {
  playerIds: number[];
  botSeats: number[];
}

/**
 * 대기실 + 게임판 컨테이너. 대기실/에러/로딩 셸은 shadcn(D-76, `.app-shell` 안). IN_GAME 은 게임별 게임판 —
 * 티츄(`GameTable`)·스컬킹(`SkullKingTable`)·원카드(`OneCardTable`)로 가르는 곳은 이 파일 한 곳뿐이고(D-103), 각
 * 게임판이 자기 소켓·sink 를 소유한다. 게임판은 `.app-shell` 밖이다(스코프 base 가 닿지 않는다 — 게임판 CSS 는 자기
 * 스코프에서 명시). 종료 화면을 가진 게임(`BOARD_HELD_GAMES`)은 이 세션이 IN_GAME→FINISHED 를 보면 게임판을 내리지
 * 않고 `roomFinished` 를 넘긴다(D-120·D-129).
 */
export function RoomPage() {
  const { roomId = '' } = useParams<{ roomId: string }>();
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const [room, setRoom] = useState<Room | null>(null);
  const [error, setError] = useState<string | null>(null);
  // D-133 — 초대 링크로 왔는데 입장이 막혔다(자리 없음·방 없음). 오류 문구 대신 패널.
  const [joinBlock, setJoinBlock] = useState<JoinBlock | null>(null);
  const [spectating, setSpectating] = useState(false);
  // D-133 결정 A — 서버가 묻지 않고 관전으로 받았을 때(꽉 찬 대기실·진행 중 방) 방 화면 위 안내. 탭에서 처음 한 번만.
  const [spectateNotice, setSpectateNotice] = useState<SpectateNoticeKind | null>(null);
  const [autoJoinAttempted, setAutoJoinAttempted] = useState(false);
  const [usernames, setUsernames] = useState<Record<number, string>>({});
  // D-106 — 이 게임이 쓰는 방 옵션. 대기실에서는 좌석 정책 **라벨**을 고르는 데만 쓴다.
  // null = 아직 모름(요청 중) → 행을 그리지 않는다. 티츄에서 "좌석 순서"가 잠깐 떴다
  // "팀 배정"으로 바뀌는 깜빡임을 막으려는 것이다.
  const [roomOptions, setRoomOptions] = useState<RoomOption[] | null>(null);
  // D-110 — 헤더용 게임 표시명. 메타를 못 받으면 null 로 두고 gameType 으로 폴백한다.
  const [gameName, setGameName] = useState<string | null>(null);
  // D-136 — "지금 N명으로 시작"의 최소 인원(게임 선언). null = 모름 → 버튼·안내를 그리지 않는다.
  const [minPlayers, setMinPlayers] = useState<number | null>(null);
  const [startError, setStartError] = useState<string | null>(null);

  // D-106 — 방의 gameType 을 알게 되면 그 게임의 옵션 집합을 받아 온다. 캐시되므로
  // 같은 게임의 방을 여러 번 드나들어도 요청은 한 번이다.
  useEffect(() => {
    const gameType = room?.gameType;
    if (!token || !gameType) return;
    let cancelled = false;
    loadGame(token, gameType)
      .then((g) => {
        if (cancelled) return;
        setRoomOptions(g.supportedRoomOptions ?? []);
        setGameName(g.displayName);
        setMinPlayers(g.minPlayers);
      })
      .catch(() => {
        // 게임 메타를 못 받아도 대기실은 동작해야 한다 — 옵션만 숨긴 채 둔다.
        if (!cancelled) setRoomOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [token, room?.gameType]);

  // `join-or-reconnect` 결과를 화면에 반영한다 — 첫 입장과 대기실 재동기화(D-134)가 같은 규칙을 쓴다.
  const applyJoinResult = useCallback(
    (r: JoinOrReconnectResponse) => {
      setRoom(r.room);
      // D-133 결정 A — 관전으로 받아졌으면 이유를 알린다. 이 탭에서 이 방 관전을 이미 알면(안내를 봤거나 허브·패널에서 관전을
      // 골랐다) 띄우지 않는다 — 새로고침에도 서버는 SPECTATING 을 다시 준다.
      if (r.mode === 'SPECTATING') {
        if (!spectateNoticeSeen(roomId)) setSpectateNotice(spectateNoticeOf(r.mode, r.room));
        markSpectateNoticeSeen(roomId);
      }
    },
    [roomId],
  );

  // Phase 8A — 진입 시 1회만 join-or-reconnect 호출. 폴링과 분리.
  useEffect(() => {
    if (!token || autoJoinAttempted) return;
    let cancelled = false;
    (async () => {
      try {
        const r = await roomsApi.joinOrReconnect(token, roomId);
        if (!cancelled) applyJoinResult(r);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError) {
          const block = joinBlockOf(err.code);
          if (block) setJoinBlock(block);
          else setError(err.message);
        }
      } finally {
        if (!cancelled) setAutoJoinAttempted(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, roomId, autoJoinAttempted, applyJoinResult]);

  useEffect(() => {
    if (!token) {
      navigate('/login');
    }
  }, [token, navigate]);

  // D-133 — 방 주소로 처음 연 탭은 서버가 넣은 방 제목으로 시작한다. SPA 는 제목을 관리하지 않으므로 방 화면을 떠날 때
  // 기본 제목으로 되돌린다(허브로 나가거나 다른 방에 가도 처음 방 이름이 탭·방문 기록에 남지 않게).
  useEffect(
    () => () => {
      document.title = 'Mirboard';
    },
    [],
  );

  // D-120 — 이 세션에서 IN_GAME→FINISHED 전이를 봤는가. 봇 방은 매치가 끝나면 서버가
  // 방을 FINISHED 로 바꾸는데, 그 메타가 마지막 게임 이벤트보다 먼저 올 수 있다. 그때
  // 게임판을 내리면 마지막 라운드 결과와 최종 점수를 못 본다. 그래서 전이를 **관측한**
  // 게임판은 유지한다 — 메타와 게임 이벤트의 도착 순서에 기대지 않는 판단이다. 새로고침으로
  // 처음부터 FINISHED 를 받으면 전이를 본 적이 없으므로 기존 종료 카드다.
  //
  // D-122 — 전이를 볼 때 **직전 IN_GAME 메타의 좌석 목록**도 함께 얼린다. 유지된 게임판의
  // 좌석 번호(엔진)는 불변인데, FINISHED 뒤 누가 나가 메타의 playerIds 가 당겨지면 이름·
  // (나)·봇 표시가 한 칸씩 밀렸다. 서버도 FINISHED 방의 좌석을 더는 당기지 않지만(D-122),
  // 게임판이 라이브 메타의 좌석 목록에 기대지 않게 하는 심층 방어다. null = 유지 안 함.
  const [heldSeats, setHeldSeats] = useState<HeldSeats | null>(null);
  const boardHeld = heldSeats !== null;
  const roomRef = useRef<Room | null>(null);
  roomRef.current = room;

  // D-134 — 대기실 화면 재동기화. 메타 소켓이 다시 연결되거나 화면이 다시 보이거나 온라인이 되면(useRoomMeta) 첫 입장과 같은
  // `join-or-reconnect` 로 방을 다시 맞춘다. 휴대폰이 백그라운드에 있던 동안 서버가 준비를 풀었거나, 유예가 지나 자리를 잃었거나
  // (꽉 찼으면 관전 + 안내), 방이 없어졌을 수 있다 — 소켓은 놓친 메타를 다시 주지 않는다. 대기실일 때만 — 게임판은 자기 resync 가
  // 있다(D-79). 방 없음은 "방이 없어졌어요", 그 밖의 오류는 조용히 넘긴다(다음 알림에 다시 — 네트워크 실패로 화면을 오류로 바꾸지
  // 않는다). 진행 중이면 겹쳐 부르지 않는다.
  const resyncingRef = useRef(false);
  const roomIdRef = useRef(roomId);
  roomIdRef.current = roomId;
  async function resyncWaitingRoom() {
    if (!token || resyncingRef.current) return;
    if (room?.status !== 'WAITING' || joinBlock || error) return;
    const requested = roomId;
    resyncingRef.current = true;
    try {
      const r = await roomsApi.joinOrReconnect(token, requested);
      if (roomIdRef.current === requested) applyJoinResult(r);
    } catch (err) {
      if (roomIdRef.current === requested && err instanceof ApiError && err.code === 'ROOM_NOT_FOUND') {
        setJoinBlock('gone');
      }
    } finally {
      resyncingRef.current = false;
    }
  }

  // Phase 13C(#3) — 2초 폴링 제거. join-or-reconnect 1회로 초기 room 확보 후
  // 방 메타 변경(참가/IN_GAME 전이/팀정책/관전/목표점수)은 WS 로 즉시 반영.
  useRoomMeta(
    roomId,
    token,
    (r) => {
      // 두 setState 는 한 렌더로 묶인다 — 게임판이 언마운트됐다 다시 붙지 않는다.
      const prev = roomRef.current;
      if (prev?.status === 'IN_GAME' && r.status === 'FINISHED') {
        setHeldSeats({ playerIds: prev.playerIds, botSeats: prev.botSeats ?? [] });
      }
      roomRef.current = r;
      setRoom(r);
    },
    () => setError('방이 종료되었습니다.'),
    resyncWaitingRoom,
  );

  // 좌석/참가자 표시용 username 일괄 조회. 참가자 집합이 바뀔 때만 재요청(최대 4명).
  const playersKey = room?.playerIds.join(',') ?? '';
  useEffect(() => {
    if (!token || !room || room.playerIds.length === 0) return;
    let cancelled = false;
    usersApi
      .names(token, room.playerIds)
      .then((res) => {
        if (cancelled) return;
        setUsernames((prev) => {
          const next = { ...prev };
          for (const n of res.names) next[n.userId] = n.username;
          return next;
        });
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, playersKey]);

  const iAmPlayer = !!(room && user && room.playerIds.includes(user.userId));
  const iAmSpectator = !!(
    room &&
    user &&
    !iAmPlayer &&
    (room.spectatorIds ?? []).includes(user.userId)
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

  // D-133 — 자리 없음 패널의 [관전하기]. 허브 관전과 같은 `spectate` 를 부르고 그 응답의 방으로 다시 그린다.
  async function handleSpectate() {
    if (!token) return;
    setSpectating(true);
    try {
      const spectated = await roomsApi.spectate(token, roomId);
      // 본인이 고른 관전 — 이 탭에서는 새로고침해도 "관전으로 들어왔어요"를 띄우지 않는다(결정 A).
      markSpectateNoticeSeen(roomId);
      setJoinBlock(null);
      setRoom(spectated);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'ROOM_NOT_FOUND') {
          setJoinBlock('gone');
        } else {
          setJoinBlock(null);
          setError(err.message);
        }
      }
    } finally {
      setSpectating(false);
    }
  }

  async function handleToggleReady(next: boolean) {
    if (!token) return;
    try {
      const updated = await roomsApi.setReady(token, roomId, next);
      setRoom(updated);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  // D-136 — 서버 거절은 원문 대신 할 일을 알린다(확인과 누름 사이의 경합 — 누가 준비를 풀었거나 들어왔거나 나갔다).
  async function handleStartNow() {
    if (!token) return;
    setStartError(null);
    try {
      const started = await roomsApi.startNow(token, roomId);
      setRoom(started);
    } catch (err) {
      if (!(err instanceof ApiError)) return;
      if (err.code === 'GAME_ALREADY_STARTED') return; // 이미 시작 — 메타가 게임판으로 옮긴다.
      setStartError(
        err.code === 'PLAYERS_NOT_READY'
          ? '아직 준비하지 않은 사람이 있어요'
          : err.code === 'NOT_ENOUGH_PLAYERS'
            ? `${minPlayers ?? 2}명 이상 모여야 해요`
            : err.code === 'ROOM_ALREADY_FULL'
              ? '정원이 다 찼어요 — 모두 준비하면 시작해요'
              : '시작하지 못했어요 — 다시 눌러 주세요',
      );
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
    if (
      !window.confirm(
        '게임을 강제로 종료할까요? 봇과 하던 이 판은 기록 없이 끝납니다.',
      )
    ) {
      return;
    }
    try {
      await roomsApi.abort(token, roomId);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
    }
  }

  const iAmHost = !!(room && user && room.hostId === user.userId);
  // D-106 정정 — 좌석 정책은 게임 중립(RANDOM 은 좌석 순서를 섞을 뿐)이라 숨기지 않는다.
  // 팀이 있는 게임에서만 "팀 배정"으로 부른다.
  const seatPolicyLabel = roomOptions?.includes('TEAMS') ? '팀 배정' : '좌석 순서';
  // D-135 — 방장 강제 종료는 게임판 머리 줄에서, 방장 말고 모두 봇인 판만(서버도 같은 조건 — HUMAN_PLAYERS_PRESENT). 강제 종료는
  // 기록을 남기지 않아 사람 판에서는 판 깨기가 된다. 예전 대기실 셸의 버튼은 게임 중에 셸이 그려지지 않아 보인 적이 없었다.
  const canAbortGame =
    !!room &&
    !!user &&
    iAmHost &&
    room.status === 'IN_GAME' &&
    room.playerIds.includes(user.userId) &&
    room.playerIds.every((uid, seat) => uid === user.userId || (room.botSeats ?? []).includes(seat));
  const onAbort = canAbortGame ? handleAbort : undefined;

  // D-136 — 친구와 하기 대기실의 "지금 N명으로 시작". 방장이고, 사람이 게임 최소 인원 이상이며 정원 미만이고, 방장 말고 모두 준비했을
  // 때만(서버도 같은 조건 — room_start_now.lua). 정원을 못 줄이는 게임(티츄 4~4)은 안내도 없다.
  const canShrink =
    !!room && !!user && iAmHost && room.status === 'WAITING' && minPlayers !== null && minPlayers < room.capacity;
  const othersReady =
    !!room && !!user && room.playerIds.every((uid) => uid === user.userId || (room.readyUserIds ?? []).includes(uid));
  const canStartNow =
    canShrink && !!room && room.playerCount >= (minPlayers ?? Infinity) && room.playerCount < room.capacity && othersReady;
  const startNowHint = canShrink && !canStartNow && !!room && room.playerCount < room.capacity;

  // D-121 — 그 게임 대기실 **첫 입장**에 튜토리얼을 1회 자동으로 띄운다(게임별 열람 키).
  // 게임 중에는 띄우지 않는다 — 턴 타이머가 흐르는 중이라 타임아웃 자동조종을 부른다.
  const tutorial = tutorialFor(room?.gameType);
  const waiting = room?.status === 'WAITING';
  const tutorialGate = useTutorialGate(tutorial?.seenKey ?? null, !!tutorial && waiting);
  const hideTutorial = tutorialGate.hide;
  // 연 채로 게임이 시작되면 기록 없이 닫는다 — 리매치로 대기실에 돌아와도 다시 열려 있지 않게.
  useEffect(() => {
    if (!waiting) hideTutorial();
  }, [waiting, hideTutorial]);

  // FR-5 — 관전 안내를 읽어 주는 상태 영역. 영역과 문구를 함께 삽입하면 일부 스크린 리더가 읽지 않으므로 아래 모든 렌더 갈래의 맨
  // 앞 같은 자리에 늘 둔다 — 첫 입장 응답 전 로딩 화면부터 같은 DOM 노드가 유지되고 문구만 들어간다. 보이는 안내 카드
  // (SpectateNotice)는 상태 영역이 아니다(같은 문구가 두 번 읽히지 않게 카드 문구는 보조 기술에서 숨긴다).
  const withLiveRegion = (body: ReactNode) => (
    <>
      <p role="status" className="sr-only" data-testid="room-live-status">
        {spectateNotice ? spectateNoticeMessage(spectateNotice) : ''}
      </p>
      {body}
    </>
  );

  if (joinBlock) {
    return withLiveRegion(
      <JoinBlockedPanel kind={joinBlock} onSpectate={handleSpectate} spectating={spectating} />,
    );
  }
  if (error) {
    return withLiveRegion(
      <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
        <Card className="w-full max-w-sm">
          <CardContent className="flex flex-col gap-4 pt-6">
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
            <Button asChild variant="outline">
              <Link to="/games">← 미르보드카페로</Link>
            </Button>
          </CardContent>
        </Card>
      </div>,
    );
  }
  if (!room || !user) {
    return withLiveRegion(
      <div className="app-shell flex min-h-screen items-center justify-center bg-background text-muted-foreground">
        방 정보 불러오는 중...
      </div>,
    );
  }

  // D-133 결정 A — 아래 세 분기(스컬킹·원카드 게임판, 티츄 게임판, 대기실·종료 셸) 모두의 맨 위. 진행 중 방은 셸 없이 게임판을 바로 그린다.
  const notice = spectateNotice && (
    <SpectateNotice kind={spectateNotice} onClose={() => setSpectateNotice(null)} />
  );

  // IN_GAME — 게임판은 레거시 레이아웃이라 .app-shell 밖이다.
  // D-103: 게임 분기는 **이 한 곳**뿐이다. 각 게임판이 자기 소켓·sink 를 소유하므로
  // 다른 게임의 코드 경로는 실행조차 되지 않는다.
  // D-120: 종료 화면을 가진 게임(스컬킹·원카드)은 이 세션에서 본 IN_GAME→FINISHED 직후에도
  // 게임판을 유지한다(위 boardHeld). 티츄는 아직 기존 동작 그대로다 — 매치 결과·'한 판 더' 게이팅이 먼저다.
  const roomFinished = room.status === 'FINISHED';
  if (
    BOARD_HELD_GAMES.has(room.gameType) &&
    (room.status === 'IN_GAME' || (boardHeld && roomFinished))
  ) {
    // D-122 — 유지된 게임판은 얼린 좌석 목록으로 그린다. 게임 중에는 라이브 메타 그대로.
    const seats: HeldSeats =
      roomFinished && heldSeats
        ? heldSeats
        : { playerIds: room.playerIds, botSeats: room.botSeats ?? [] };
    const board = {
      roomId: room.roomId,
      playerIds: seats.playerIds,
      myUserId: user.userId,
      spectator: iAmSpectator,
      botSeats: seats.botSeats,
      usernames,
      turnSeconds: room.turnSeconds ?? 0,
      spectatorCount: (room.spectatorIds ?? []).length,
      onExit: handleLeave,
      onAbort,
      practice: room.fillWithBots ?? false,
      roomFinished,
    };
    return withLiveRegion(
      <main className="room-page">
        {notice}
        {room.gameType === 'ONE_CARD' ? <OneCardTable {...board} /> : <SkullKingTable {...board} />}
      </main>,
    );
  }

  if (room.status === 'IN_GAME') {
    return withLiveRegion(
      <main className="room-page">
        {notice}
        <GameTable
          roomId={room.roomId}
          playerIds={room.playerIds}
          myUserId={user.userId}
          spectator={iAmSpectator}
          botSeats={room.botSeats ?? []}
          fillWithBots={room.fillWithBots ?? false}
          turnSeconds={room.turnSeconds ?? 0}
          stake={room.stake ?? 0}
          spectatorCount={(room.spectatorIds ?? []).length}
          usernames={usernames}
          isHost={iAmHost}
          onExit={handleLeave}
          onAbort={onAbort}
        />
      </main>,
    );
  }

  return withLiveRegion(
    <div className="app-shell min-h-screen bg-background text-foreground">
      {notice}
      <div className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-6">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">{room.name}</h1>
            <p className="text-sm text-muted-foreground">
              {gameName ?? room.gameType} ·{' '}
              {ROOM_STATUS_LABEL[room.status] ?? room.status} · {room.playerCount}/
              {room.capacity}
              {(room.spectatorIds ?? []).length > 0 &&
                ` · 👁 관전 ${(room.spectatorIds ?? []).length}`}
            </p>
          </div>
          <div className="flex gap-2">
            {tutorial && (
              <Button type="button" variant="outline" onClick={tutorialGate.show}>
                게임 방법
              </Button>
            )}
            <Button type="button" variant="outline" onClick={handleLeave}>
              나가기
            </Button>
          </div>
        </header>

        {/* D-133 — 초대는 시작 전에만(시작하면 자리가 없다). 방 주소 그대로를 공유·복사한다. 정원이 다 찼으면(봇 자리
            포함 — 봇은 자리를 비켜 주지 않는다) 받은 친구는 관전으로 들어오므로 설명을 그렇게 바꾼다. */}
        {room.status === 'WAITING' && (
          <InviteLinkButton
            roomId={room.roomId}
            roomName={room.name}
            gameName={gameName ?? room.gameType}
            full={room.playerCount >= room.capacity}
          />
        )}

        {iAmSpectator && (
          <Alert>
            <AlertDescription>
              관전 중 — 본인 손패는 표시되지 않습니다.
            </AlertDescription>
          </Alert>
        )}

        {room.status === 'WAITING' && (
          <Card>
            <CardHeader>
              <CardTitle>참가자</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <ul className="flex flex-col gap-2">
                {room.playerIds.map((id, seat) => {
                  const isReady = (room.readyUserIds ?? []).includes(id);
                  const isBot = (room.botSeats ?? []).includes(seat);
                  return (
                    <li
                      key={id}
                      className="flex items-center gap-2 rounded-md border p-2"
                    >
                      <code className="text-sm text-muted-foreground">
                        {usernames[id] ?? `#${id}`}
                      </code>
                      {id === room.hostId && <Badge>호스트</Badge>}
                      {isBot && <Badge variant="secondary">봇</Badge>}
                      <span className="flex-1" />
                      {isReady ? (
                        <Badge>✓ 준비됨</Badge>
                      ) : (
                        <Badge variant="outline">대기</Badge>
                      )}
                    </li>
                  );
                })}
              </ul>

              {roomOptions !== null && (
              <>
              <Separator />

              <div className="flex items-center gap-3">
                <span className="text-sm">{seatPolicyLabel}</span>
                {iAmHost ? (
                  <Select
                    value={room.teamPolicy}
                    onValueChange={(v) =>
                      handleTeamPolicyChange(v as TeamPolicy)
                    }
                  >
                    <SelectTrigger className="w-40" aria-label={seatPolicyLabel}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent className="app-shell">
                      <SelectItem value="SEQUENTIAL">입장 순서</SelectItem>
                      <SelectItem value="RANDOM">랜덤 셔플</SelectItem>
                    </SelectContent>
                  </Select>
                ) : (
                  <Badge
                    variant={
                      room.teamPolicy === 'RANDOM' ? 'secondary' : 'default'
                    }
                  >
                    {room.teamPolicy === 'RANDOM' ? '랜덤 셔플' : '입장 순서'}
                  </Badge>
                )}
              </div>
              </>
              )}

              {iAmPlayer && (
                <div className="flex flex-wrap items-center gap-2">
                  {(room.readyUserIds ?? []).includes(user.userId) ? (
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => handleToggleReady(false)}
                    >
                      준비 취소
                    </Button>
                  ) : (
                    <Button
                      type="button"
                      onClick={() => handleToggleReady(true)}
                    >
                      준비
                    </Button>
                  )}
                  {canStartNow && (
                    <Button type="button" variant="secondary" onClick={handleStartNow}>
                      지금 {room.playerCount}명으로 시작
                    </Button>
                  )}
                </div>
              )}
              {startError && (
                <p role="alert" className="text-sm text-destructive">
                  {startError}
                </p>
              )}

              <p className="text-sm text-muted-foreground">
                정원이 모두 모이고 전원이 준비하면 자동으로 게임이 시작됩니다.
              </p>
              {startNowHint && (
                <p className="text-sm text-muted-foreground">
                  {minPlayers}명 이상 모이고 모두 준비하면 지금 인원으로 시작할 수 있어요
                </p>
              )}
            </CardContent>
          </Card>
        )}

        {room.status === 'FINISHED' && (
          <Card>
            <CardContent className="pt-6 text-muted-foreground">
              게임이 종료되었습니다.
            </CardContent>
          </Card>
        )}
      </div>

      {tutorial && (
        <TutorialDialog
          tutorial={tutorial}
          open={tutorialGate.open}
          onClose={tutorialGate.close}
        />
      )}
    </div>,
  );
}
