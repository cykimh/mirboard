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

/** 서버 `RoomService.DEFAULT_TARGET_SCORE` 와 같은 값 — 갈리면 안 된다. */
const DEFAULT_TARGET_SCORE = 1000;

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

  // D-106 — 게임이 선언한 옵션만 노출한다. 스컬킹은 10라운드 고정(목표 점수 무의미)·
  // 개인전(팀 없음)·칩 정산 미지원(내기 불가)이라 셋 다 안 뜬다. 서버도 같은 집합으로
  // 검증하므로 여기서 숨기는 것은 UX 이고 강제는 서버가 한다.
  const options = game?.supportedRoomOptions ?? [];
  const usesTargetScore = options.includes('TARGET_SCORE');
  const usesBetting = options.includes('BETTING');
  // 리셋 effect 는 페인트 **뒤에** 돌기 때문에, 티츄에서 판돈을 켜고 스컬킹으로 바꾸면
  // stake 가 100 으로 남은 프레임이 생긴다. 그 사이 판돈 블록은 usesBetting 으로 이미
  // 사라졌는데 봇 체크박스만 stake>0 을 보고 잠긴 채 "(판돈 방 불가)" 를 띄운다 —
  // 화면 어디에도 이유가 없는 잠금이다(실측 54ms, 60fps 기준 3프레임).
  // usesBetting 을 곱해 그 프레임을 없앤다: 게임이 BETTING 을 안 쓰면 state 와 무관하게 꺼짐.
  const stakeOn = usesBetting && stake > 0;

  // 게임을 바꾸면 이전 게임의 좌석 수는 무효 — 새 게임의 기본값으로 되돌린다.
  // D-106 — 미지원 옵션 값도 같이 되돌린다. 티츄에서 판돈을 켠 뒤 스컬킹으로 바꾸면
  // 입력은 사라지지만 state 는 남아, 그대로 보내면 서버가 거절한다.
  useEffect(() => {
    setCapacity(null);
    if (!usesTargetScore) setTargetScore(DEFAULT_TARGET_SCORE);
    if (!usesBetting) setStake(0);
  }, [selectedGame, usesTargetScore, usesBetting]);

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
          fillWithBots: stakeOn ? false : fillWithBots,
          // D-106 — 게임이 안 쓰는 옵션은 아예 보내지 않는다(D-99 의 capacity 와 같은 방식).
          // 서버는 기본값 아닌 값이 오면 UNSUPPORTED_ROOM_OPTION 으로 거절한다.
          targetScore: usesTargetScore ? targetScore : undefined,
          turnSeconds,
          stake: usesBetting ? stake : undefined,
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

          {usesTargetScore && (
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
          )}

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

          {usesBetting && (
          <div className="flex flex-col gap-1.5">
            <Label>💰 판돈 (내기)</Label>
            <label className="flex items-center gap-2 text-sm">
              <Switch
                checked={stakeOn}
                onCheckedChange={(on) => {
                  setStake(on ? DEFAULT_STAKE : 0);
                  if (on) setFillWithBots(false); // 판돈 방은 봇 금지
                }}
                aria-label="판돈(내기) 켜기/끄기"
              />
              <span>{stakeOn ? `내기 켜짐 — 판돈 ${stake}칩` : '내기 끔'}</span>
            </label>
            {stakeOn && (
              <p className="text-xs text-muted-foreground">
                승팀 +{stake}칩 / 패팀 −{stake}칩 (가상 칩, 현금 아님). 봇 참여 불가.
              </p>
            )}
          </div>
          )}

          <label className="flex items-center gap-2 text-sm">
            <Checkbox
              checked={fillWithBots}
              disabled={stakeOn}
              onCheckedChange={(c) => setFillWithBots(c === true)}
            />
            <span>
              🤖 빈 좌석 봇으로 채우기{stakeOn ? ' (판돈 방 불가)' : ''}
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
