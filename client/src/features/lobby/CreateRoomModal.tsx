import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
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
import { Checkbox } from '@/components/ui/checkbox';
import { Switch } from '@/components/ui/switch';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

/** 내기(판돈) 토글 ON 시 기본 판돈(칩). 서버 허용 집합 {0,10,50,100,500} 중 하나. */
const DEFAULT_STAKE = 100;

interface CreateRoomModalProps {
  open: boolean;
  token: string;
  /** AVAILABLE 상태 게임만. 비어 있으면 모달이 열리지 않는다. */
  availableGames: GameSummary[];
  onClose: () => void;
  onError: (message: string) => void;
}

/**
 * Phase 18(#2, D-74) 방 만들기 모달 — Phase 20b(D-76) shadcn Dialog 재디자인.
 * 상태/제출 로직 불변.
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
  const [fillWithBots, setFillWithBots] = useState(false);
  const [targetScore, setTargetScore] = useState(1000);
  const [turnSeconds, setTurnSeconds] = useState(0);
  const [stake, setStake] = useState(0);
  // D-99 — 인원 가변 게임에서만 쓰는 좌석 수. null = 아직 안 고름(서버 기본값).
  const [capacity, setCapacity] = useState<number | null>(null);

  // 모달이 열릴 때 기본 게임을 첫 AVAILABLE 로 맞춘다.
  useEffect(() => {
    if (open && !selectedGame && availableGames.length > 0) {
      setSelectedGame(availableGames[0].id);
    }
  }, [open, selectedGame, availableGames]);

  const game = availableGames.find((g) => g.id === selectedGame);
  // D-99 — min<max 인 게임만 인원을 고를 수 있다. 티츄(4~4)는 선택지가 없으므로
  // UI 를 아예 노출하지 않고 capacity 도 보내지 않는다(요청 본문 무변경).
  const seatChoices =
    game && game.minPlayers < game.maxPlayers
      ? Array.from(
          { length: game.maxPlayers - game.minPlayers + 1 },
          (_, i) => game.minPlayers + i,
        )
      : [];
  // 서버 기본값(maxPlayers)과 같은 값을 기본 선택으로 — 클라·서버 기본이 갈리지 않게.
  const selectedSeats = capacity ?? game?.maxPlayers ?? 0;

  // 게임을 바꾸면 이전 게임의 좌석 수는 무효 — 새 게임의 기본값으로 되돌린다.
  useEffect(() => {
    setCapacity(null);
  }, [selectedGame]);

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    if (!roomName.trim() || !selectedGame) return;
    setCreating(true);
    try {
      const room = await roomsApi.create(
        token,
        roomName.trim(),
        selectedGame.toUpperCase(),
        {
          // 판돈 방은 봇 금지(서버도 거절) — 클라에서도 강제.
          fillWithBots: stake > 0 ? false : fillWithBots,
          targetScore,
          turnSeconds,
          stake,
          capacity: seatChoices.length > 0 ? selectedSeats : undefined,
        },
      );
      navigate(`/rooms/${room.roomId}`);
    } catch (err) {
      if (err instanceof ApiError) onError(err.message);
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

          {seatChoices.length > 0 && (
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
            </div>
          )}

          <div className="flex flex-col gap-1.5">
            <Label>목표 점수</Label>
            <ToggleGroup
              type="single"
              value={String(targetScore)}
              onValueChange={(v) => v && setTargetScore(Number(v))}
              className="justify-start"
            >
              {[300, 500, 700, 1000].map((v) => (
                <ToggleGroupItem key={v} value={String(v)}>
                  {v}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label>턴 제한</Label>
            <ToggleGroup
              type="single"
              value={String(turnSeconds)}
              onValueChange={(v) => v !== '' && setTurnSeconds(Number(v))}
              className="justify-start"
            >
              {[
                { v: 0, label: '끔' },
                { v: 30, label: '30초' },
                { v: 60, label: '60초' },
                { v: 90, label: '90초' },
              ].map((o) => (
                <ToggleGroupItem key={o.v} value={String(o.v)}>
                  {o.label}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label>💰 판돈 (내기)</Label>
            <label className="flex items-center gap-2 text-sm">
              <Switch
                checked={stake > 0}
                onCheckedChange={(on) => {
                  setStake(on ? DEFAULT_STAKE : 0);
                  if (on) setFillWithBots(false); // 판돈 방은 봇 금지
                }}
                aria-label="판돈(내기) 켜기/끄기"
              />
              <span>{stake > 0 ? `내기 켜짐 — 판돈 ${stake}칩` : '내기 끔'}</span>
            </label>
            {stake > 0 && (
              <p className="text-xs text-muted-foreground">
                승팀 +{stake}칩 / 패팀 −{stake}칩 (가상 칩, 현금 아님). 봇 참여 불가.
              </p>
            )}
          </div>

          <label className="flex items-center gap-2 text-sm">
            <Checkbox
              checked={fillWithBots}
              disabled={stake > 0}
              onCheckedChange={(c) => setFillWithBots(c === true)}
            />
            <span>
              🤖 빈 좌석 봇으로 채우기{stake > 0 ? ' (판돈 방 불가)' : ''}
            </span>
          </label>

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
              disabled={creating || !selectedGame || !roomName.trim()}
            >
              {creating ? '생성 중...' : '방 만들기'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
