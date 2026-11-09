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
 * Phase 18(#2, D-74) 방 만들기 모달 — Phase 20b(D-76) shadcn Dialog 재디자인.
 * D-174 — 방 종류(친구와 하기/봇과 연습)를 없앴다. 게임·방 이름만 고르고 늘 대기실이 있는 방을 만든다. 봇은 대기실에서 방장이
 * [빈자리 봇으로 채우기]로 앉히고, 턴 제한 같은 게임 설정도 대기실에서 바꾼다(D-161 `RoomSettingsPanel`).
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

  // 모달이 열릴 때 기본 게임을 첫 AVAILABLE 로 맞춘다.
  useEffect(() => {
    if (open && !selectedGame && availableGames.length > 0) {
      setSelectedGame(availableGames[0].id);
    }
  }, [open, selectedGame, availableGames]);

  const game = availableGames.find((g) => g.id === selectedGame);

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    if (!roomName.trim() || !selectedGame) return;
    setCreating(true);
    try {
      // D-143 — 정원은 게임 최대 인원(대기실에서 방장이 지금 인원으로 시작한다). 인원 고정 게임(티츄)은 보내지 않는다.
      // D-161 — 턴 제한·목표 점수·판돈은 보내지 않는다(서버가 게임 선언 기본으로 열고 대기실에서 방장이 바꾼다).
      const room = await roomsApi.create(token, roomName.trim(), selectedGame.toUpperCase(), {
        capacity: game && game.minPlayers < game.maxPlayers ? game.maxPlayers : undefined,
      });
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

          {game && (
            <div className="flex flex-col gap-1.5">
              <Label>인원</Label>
              <p className="text-sm text-muted-foreground">
                {game.minPlayers === game.maxPlayers
                  ? `${game.minPlayers}명 — 모이면 대기실에서 방장이 시작해요`
                  : `최소 ${game.minPlayers}명 · 최대 ${game.maxPlayers}명 — 대기실에서 방장이 시작해요`}
              </p>
            </div>
          )}

          <p className="text-xs text-muted-foreground">
            봇은 대기실에서 [빈자리 봇으로 채우기]로 앉혀요. 턴 제한 같은 게임 설정도 대기실에서 방장이 바꿔요.
          </p>

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
