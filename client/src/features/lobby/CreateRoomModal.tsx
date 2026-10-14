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
 * D-136 — 방 종류. 친구와 하기(처음 선택 — 게스트 포함)는 사람만, 봇과 연습은 방장 + 봇으로 곧바로 시작한다(서버가 방장 준비까지
 * 넣고 방 목록에 올리지 않는다 — 요청의 `fillWithBots` 가 곧 연습 방).
 */
type RoomMode = 'friends' | 'practice';

/**
 * Phase 18(#2, D-74) 방 만들기 모달 — Phase 20b(D-76) shadcn Dialog 재디자인.
 * D-136 — 맨 위 "친구와 하기 / 봇과 연습". 봇 채우기 체크박스와 게스트 봇 기본값(D-117)은 없앴다.
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
  const [targetScore, setTargetScore] = useState(1000);
  // D-130 — null = 아직 안 고름(게임이 선언한 처음 선택을 쓴다). 인원과 같은 방식.
  const [turnSeconds, setTurnSeconds] = useState<number | null>(null);
  const [stake, setStake] = useState(0);
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
  // UI 를 아예 노출하지 않고 capacity 도 보내지 않는다(요청 본문 무변경).
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

  // D-106 — 게임이 선언한 옵션만 노출한다. 스컬킹은 10라운드 고정(목표 점수 무의미)·
  // 개인전(팀 없음)·칩 정산 미지원(내기 불가)이라 셋 다 안 뜬다. 서버도 같은 집합으로
  // 검증하므로 여기서 숨기는 것은 UX 이고 강제는 서버가 한다.
  // D-136 — 연습 방은 목표 점수·판돈이 없다(목표 점수는 서버 기본, 판돈 방은 봇 금지 — D-81).
  const options = game?.supportedRoomOptions ?? [];
  const usesTargetScore = options.includes('TARGET_SCORE') && !practice;
  const usesBetting = options.includes('BETTING') && !practice;
  // 리셋 effect 는 페인트 **뒤에** 돌기 때문에, 티츄에서 판돈을 켜고 스컬킹(또는 연습)으로 바꾸면 stake 가 100 으로 남은
  // 프레임이 생긴다. usesBetting 을 곱해 그 프레임을 없앤다: 게임이 BETTING 을 안 쓰거나 연습이면 state 와 무관하게 꺼짐 —
  // 제출도 usesBetting 일 때만 stake 를 싣는다(D-136 — 연습 방은 판돈 없음).
  const stakeOn = usesBetting && stake > 0;

  // 게임을 바꾸면 이전 게임의 좌석 수·턴 제한은 무효 — 새 게임의 처음 선택으로 되돌린다.
  // D-106 — 미지원 옵션 값도 같이 되돌린다. 티츄에서 판돈을 켠 뒤 스컬킹으로 바꾸면
  // 입력은 사라지지만 state 는 남아, 그대로 보내면 서버가 거절한다.
  useEffect(() => {
    setCapacity(null);
    setTurnSeconds(null);
    if (!usesTargetScore) setTargetScore(DEFAULT_TARGET_SCORE);
    if (!usesBetting) setStake(0);
  }, [selectedGame, usesTargetScore, usesBetting]);

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
          // D-106 — 게임이 안 쓰는 옵션은 아예 보내지 않는다(D-99 의 capacity 와 같은 방식).
          // 서버는 기본값 아닌 값이 오면 UNSUPPORTED_ROOM_OPTION 으로 거절한다.
          targetScore: usesTargetScore ? targetScore : undefined,
          turnSeconds: selectedTurnSeconds,
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
              {practice && <p className="text-xs text-muted-foreground">봇 {selectedSeats - 1}명과 함께</p>}
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
              value={String(selectedTurnSeconds)}
              onValueChange={(v) => v !== '' && setTurnSeconds(Number(v))}
              className="justify-start"
            >
              {/* 서버 RoomCreationDefaultsTest 가 이 선택지 목록(0·30·60·90)을 복사해 단언한다 — 같이 바꿀 것. */}
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
                onCheckedChange={(on) => setStake(on ? DEFAULT_STAKE : 0)}
                aria-label="판돈(내기) 켜기/끄기"
              />
              <span>{stakeOn ? `내기 켜짐 — 판돈 ${stake}칩` : '내기 끔'}</span>
            </label>
            {stakeOn && (
              <p className="text-xs text-muted-foreground">
                승팀 +{stake}칩 / 패팀 −{stake}칩 (가상 칩, 현금 아님).
              </p>
            )}
          </div>
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
