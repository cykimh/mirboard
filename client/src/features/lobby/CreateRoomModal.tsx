import { GameIcon } from '@/components/GameIcon';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { errorText } from '@/api/errorText';
import { roomsApi } from '@/api/rooms';
import { createCapacity } from '@/features/lobby/quickJoin';
import { playerCountLabel } from '@/features/lobby/playerCountLabel';
import { useAuthStore } from '@/features/auth/authStore';
import { WAKING_LABEL, useSlowNotice } from '@/features/lobby/useSlowNotice';
import type { GameSummary } from '@/types/api';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
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
 * [봇 추가]로 앉히고, 턴 제한 같은 게임 설정도 대기실에서 바꾼다(D-161 `RoomSettingsPanel`).
 * D-308 — 게임만 고른다. 방 이름은 만드는 사람의 별명(없으면 "새 방"), 인원은 게임 선택지 오른쪽에 작게.
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
  // D-308 — 방 이름 입력란은 없다. 만드는 사람의 별명을 그대로 방 이름으로.
  const roomName = useAuthStore((s) => s.user?.username)?.trim() || '새 방';
  const [creating, setCreating] = useState(false);
  // D-197 — 2초를 넘기면 쉬던 서버가 깨어나는 중이다(운영 콜드 스타트 약 30초). 버튼 글자로 알린다.
  const waking = useSlowNotice(creating);

  // 모달이 열릴 때 기본 게임을 첫 AVAILABLE 로 맞춘다.
  useEffect(() => {
    if (open && !selectedGame && availableGames.length > 0) {
      setSelectedGame(availableGames[0].id);
    }
  }, [open, selectedGame, availableGames]);

  const game = availableGames.find((g) => g.id === selectedGame);

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    if (!selectedGame) return;
    setCreating(true);
    try {
      // D-161 — 턴 제한·목표 점수·판돈은 보내지 않는다(서버가 게임 선언 기본으로 열고 대기실에서 방장이 바꾼다).
      // 정원 규칙(D-143·D-176)은 `createCapacity` — 메인 카드 [참여하기](D-189)와 같이 쓴다.
      const room = await roomsApi.create(token, roomName, selectedGame.toUpperCase(), {
        capacity: createCapacity(game),
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
      <DialogContent className="app-shell w-[calc(100%-2rem)] max-w-sm gap-3 rounded-lg p-5">
        <DialogHeader>
          <DialogTitle>새 방 만들기</DialogTitle>
        </DialogHeader>
        <form className="flex flex-col gap-3" onSubmit={handleCreate}>
          <Select value={selectedGame} onValueChange={setSelectedGame}>
            <SelectTrigger aria-label="게임 선택" className="h-11">
              <SelectValue placeholder="게임 선택" />
            </SelectTrigger>
            <SelectContent className="app-shell">
              {availableGames.map((g) => (
                <SelectItem key={g.id} value={g.id}>
                  <span className="inline-flex items-center gap-2">
                    <GameIcon gameId={g.id} size={18} />
                    {g.displayName}
                    <span className="text-xs text-muted-foreground">{playerCountLabel(g)}</span>
                  </span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <DialogFooter className="flex-row gap-2 sm:space-x-0">
            <Button
              type="button"
              variant="outline"
              className="flex-1"
              onClick={onClose}
              disabled={creating}
            >
              취소
            </Button>
            <Button type="submit" className="flex-1" disabled={creating || !selectedGame}>
              {creating ? (waking ? WAKING_LABEL : '생성 중...') : '방 만들기'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
