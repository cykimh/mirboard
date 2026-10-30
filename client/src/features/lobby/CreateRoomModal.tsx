import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { errorText } from '@/api/errorText';
import { roomsApi } from '@/api/rooms';
import type { GameSummary } from '@/types/api';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

interface CreateRoomModalProps {
  open: boolean;
  token: string;
  /** AVAILABLE 상태 게임만. 비어 있으면 모달이 열리지 않는다. */
  availableGames: GameSummary[];
  onClose: () => void;
  onError: (message: string) => void;
}

/**
 * D-136 — 방 종류. 친구와 하기(처음 선택 — 게스트 포함)는 사람만, 봇과 연습은 방장 + 봇으로 곧바로 시작한다(서버가 방장 준비까지
 * 넣고 방 목록에 올리지 않는다 — 요청의 `fillWithBots` 가 곧 연습 방).
 */
type RoomMode = 'friends' | 'practice';

/** D-154 — 게임이 턴 제한 선택지를 싣지 않았을 때(구 서버·픽스처). */
const DEFAULT_TURN_CHOICES = [0, 30, 60, 90];

/**
 * Phase 18(#2, D-74) 방 만들기 모달 — Phase 20b(D-76) shadcn Dialog 재디자인.
 * D-136 — 맨 위 "친구와 하기 / 봇과 연습". 봇 채우기 체크박스와 게스트 봇 기본값(D-117)은 없앴다.
 * D-161 — 친구와 하기는 방 종류·게임·방 이름만 고른다. 턴 제한·목표 점수·판돈·원카드! 버튼 크기는 대기실에서 방장이 바꾼다
 * (`RoomSettingsPanel`). 봇과 연습은 만들자마자 시작하므로 인원·턴 제한을 여기서 고른다(목표 점수·판돈은 연습에 없다).
 */
export function CreateRoomModal({
  open,
  token,
  availableGames,
  onClose,
  onError,
}: CreateRoomModalProps) {
  const navigate = useNavigate();
  const [selectedGame, setSelectedGame] = useState('');
  const [roomName, setRoomName] = useState('');
  const [creating, setCreating] = useState(false);
  const [mode, setMode] = useState<RoomMode>('friends');
  const practice = mode === 'practice';
  // D-130 — null = 아직 안 고름(게임이 선언한 처음 선택을 쓴다). 인원과 같은 방식.
  const [turnSeconds, setTurnSeconds] = useState<number | null>(null);
  // D-99 — 인원 가변 게임에서만 쓰는 좌석 수. null = 아직 안 고름(게임이 선언한 처음 선택).
  const [capacity, setCapacity] = useState<number | null>(null);

  // 모달이 열릴 때 기본 게임을 첫 AVAILABLE 로 맞춘다.
  useEffect(() => {
    if (open && !selectedGame && availableGames.length > 0) {
      setSelectedGame(availableGames[0].id);
    }
  }, [open, selectedGame, availableGames]);

  const game = availableGames.find((g) => g.id === selectedGame);
  // D-99 — min<max 인 게임만 인원을 고를 수 있다. 티츄(4~4)는 선택지가 없으므로
  // UI 를 아예 노출하지 않고 capacity 도 보내지 않는다(요청 본문 무변경). D-143 — 고르는 것은 봇과 연습뿐(봇 수),
  // 친구와 하기는 최소·최대 인원만 알리고 정원은 게임 최대 인원이다.
  const seatChoices =
    game && game.minPlayers < game.maxPlayers
      ? Array.from(
          { length: game.maxPlayers - game.minPlayers + 1 },
          (_, i) => game.minPlayers + i,
        )
      : [];
  // D-130 — 게임이 선언한 인원을 처음 선택으로(원카드 4, 나머지는 maxPlayers). D-131 — 서버의 capacity 생략 기본도 같은
  // 선언값이라(RoomService) 모달과 인원을 뺀 요청이 같은 방을 연다.
  const selectedSeats = capacity ?? game?.defaultPlayers ?? game?.maxPlayers ?? 0;
  // D-130 — 턴 제한도 게임이 선언한 값이 처음 선택이다(원카드 30초 — 자리 비운 사람이 판을 멈추지 않게, 나머지는 끔).
  const selectedTurnSeconds = turnSeconds ?? game?.defaultTurnSeconds ?? 0;

  // 게임을 바꾸면 이전 게임의 좌석 수·턴 제한은 무효 — 새 게임의 처음 선택으로 되돌린다.
  useEffect(() => {
    setCapacity(null);
    setTurnSeconds(null);
  }, [selectedGame]);

  // D-136 — 모달은 허브에 늘 붙어 있다. 열 때마다 처음 선택(친구와 하기)으로.
  useEffect(() => {
    if (open) setMode('friends');
  }, [open]);

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    if ((!practice && !roomName.trim()) || !selectedGame) return;
    setCreating(true);
    try {
      const room = await roomsApi.create(
        token,
        // D-136 — 연습 방은 이름을 묻지 않는다(목록에 오르지 않는다).
        practice ? `${game?.displayName ?? selectedGame} 연습` : roomName.trim(),
        selectedGame.toUpperCase(),
        {
          fillWithBots: practice,
          // D-161 — 친구 방은 턴 제한도 보내지 않는다(서버가 게임 선언 기본으로 열고, 대기실에서 방장이 바꾼다).
          // 목표 점수·판돈은 어느 방에서도 여기서 보내지 않는다(연습엔 없고, 친구 방은 대기실에서).
          turnSeconds: practice ? selectedTurnSeconds : undefined,
          // D-143 — 친구와 하기의 정원은 게임 최대 인원(대기실에서 방장이 시작한다). 봇과 연습만 고른 인원(봇 수).
          capacity: seatChoices.length > 0 ? (practice ? selectedSeats : game?.maxPlayers) : undefined,
        },
      );
      navigate(`/rooms/${room.roomId}`);
    } catch (err) {
      if (err instanceof ApiError) onError(errorText(err, '방을 만들지 못했어요'));
    } finally {
      setCreating(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="app-shell">
        <DialogHeader>
          <DialogTitle>새 방 만들기</DialogTitle>
        </DialogHeader>
        <form className="flex flex-col gap-4" onSubmit={handleCreate}>
          <div className="flex flex-col gap-1.5">
            <Label>방 종류</Label>
            <ToggleGroup
              type="single"
              aria-label="방 종류"
              value={mode}
              onValueChange={(v) => v && setMode(v as RoomMode)}
              className="justify-start"
            >
              <ToggleGroupItem value="friends">친구와 하기</ToggleGroupItem>
              <ToggleGroupItem value="practice">봇과 연습</ToggleGroupItem>
            </ToggleGroup>
            <p className="text-xs text-muted-foreground">
              {practice
                ? '봇과 바로 시작해요 — 방 목록에 오르지 않고 친구를 부를 수 없어요.'
                : '사람끼리만 해요 — 초대 링크로 친구를 불러요.'}
            </p>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label>게임</Label>
            <Select value={selectedGame} onValueChange={setSelectedGame}>
              <SelectTrigger aria-label="게임 선택">
                <SelectValue placeholder="게임 선택" />
              </SelectTrigger>
              <SelectContent className="app-shell">
                {availableGames.map((g) => (
                  <SelectItem key={g.id} value={g.id}>
                    {g.displayName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {!practice && (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="room-name">방 이름</Label>
              <Input
                id="room-name"
                type="text"
                value={roomName}
                placeholder="새 방 이름"
                onChange={(e) => setRoomName(e.target.value)}
                required
              />
            </div>
          )}

          {!practice && game && (
            <div className="flex flex-col gap-1.5">
              <Label>인원</Label>
              <p className="text-sm text-muted-foreground">
                {game.minPlayers === game.maxPlayers
                  ? `${game.minPlayers}명 — 모이면 대기실에서 방장이 시작해요`
                  : `최소 ${game.minPlayers}명 · 최대 ${game.maxPlayers}명 — 대기실에서 방장이 시작해요`}
              </p>
            </div>
          )}

          {practice && seatChoices.length > 0 && (
            <div className="flex flex-col gap-1.5">
              <Label>인원</Label>
              <ToggleGroup
                type="single"
                aria-label="인원 선택"
                value={String(selectedSeats)}
                onValueChange={(v) => v && setCapacity(Number(v))}
                className="flex-wrap justify-start"
              >
                {seatChoices.map((n) => (
                  <ToggleGroupItem key={n} value={String(n)}>
                    {n}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
              <p className="text-xs text-muted-foreground">봇 {selectedSeats - 1}명과 함께</p>
            </div>
          )}

          {practice && (
            <div className="flex flex-col gap-1.5">
              <Label>턴 제한</Label>
              <ToggleGroup
                type="single"
                value={String(selectedTurnSeconds)}
                onValueChange={(v) => v !== '' && setTurnSeconds(Number(v))}
                className="justify-start"
              >
                {/* D-154 — 선택지도 게임이 선언한다(카탈로그 turnSecondsChoices, 없으면 끔·30·60·90). */}
                {(game?.turnSecondsChoices ?? DEFAULT_TURN_CHOICES).map((v) => (
                  <ToggleGroupItem key={v} value={String(v)}>
                    {v === 0 ? '끔' : `${v}초`}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </div>
          )}

          {!practice && (
            <p className="text-xs text-muted-foreground">턴 제한 같은 게임 설정은 대기실에서 방장이 바꿔요.</p>
          )}

          <DialogFooter className="gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={onClose}
              disabled={creating}
            >
              취소
            </Button>
            <Button
              type="submit"
              disabled={creating || !selectedGame || (!practice && !roomName.trim())}
            >
              {creating ? '생성 중...' : practice ? '연습 시작' : '방 만들기'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
