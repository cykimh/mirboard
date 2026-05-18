import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { roomsApi } from '@/api/rooms';
import { Modal } from '@/components/Modal';
import type { GameSummary } from '@/types/api';

interface CreateRoomModalProps {
  open: boolean;
  token: string;
  /** AVAILABLE 상태 게임만. 비어 있으면 모달이 열리지 않는다. */
  availableGames: GameSummary[];
  onClose: () => void;
  onError: (message: string) => void;
}

/**
 * Phase 18(#2, D-74) — 방 만들기를 메인 인라인 폼에서 분리한 모달.
 * 게임 선택/옵션이 모달 안에서만 이뤄지므로 메인 게임 카드의 "선택" 개념을
 * 없앤다. 제출 성공 시 생성된 방으로 이동.
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

  // 모달이 열릴 때 기본 게임을 첫 AVAILABLE 로 맞춘다.
  useEffect(() => {
    if (open && !selectedGame && availableGames.length > 0) {
      setSelectedGame(availableGames[0].id);
    }
  }, [open, selectedGame, availableGames]);

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    if (!roomName.trim() || !selectedGame) return;
    setCreating(true);
    try {
      const room = await roomsApi.create(
        token,
        roomName.trim(),
        selectedGame.toUpperCase(),
        { fillWithBots, targetScore, turnSeconds },
      );
      navigate(`/rooms/${room.roomId}`);
    } catch (err) {
      if (err instanceof ApiError) onError(err.message);
    } finally {
      setCreating(false);
    }
  }

  return (
    <Modal open={open} title="새 방 만들기">
      <form className="create" onSubmit={handleCreate}>
        <select
          value={selectedGame}
          onChange={(e) => setSelectedGame(e.target.value)}
          aria-label="게임 선택"
          required
        >
          <option value="" disabled>게임 선택</option>
          {availableGames.map((g) => (
            <option key={g.id} value={g.id}>{g.displayName}</option>
          ))}
        </select>
        <input
          type="text"
          value={roomName}
          placeholder="새 방 이름"
          onChange={(e) => setRoomName(e.target.value)}
          required
        />
        <div className="target-score-picker" role="group" aria-label="목표 점수">
          <span className="target-score-label">목표 점수</span>
          {[300, 500, 700, 1000].map((v) => (
            <button
              type="button"
              key={v}
              className={`target-score-opt ${targetScore === v ? 'active' : ''}`}
              onClick={() => setTargetScore(v)}
              aria-pressed={targetScore === v}
            >
              {v}
            </button>
          ))}
        </div>
        <div className="target-score-picker" role="group" aria-label="턴 제한">
          <span className="target-score-label">턴 제한</span>
          {[
            { v: 0, label: '끔' },
            { v: 30, label: '30초' },
            { v: 60, label: '60초' },
            { v: 90, label: '90초' },
          ].map((o) => (
            <button
              type="button"
              key={o.v}
              className={`target-score-opt ${turnSeconds === o.v ? 'active' : ''}`}
              onClick={() => setTurnSeconds(o.v)}
              aria-pressed={turnSeconds === o.v}
            >
              {o.label}
            </button>
          ))}
        </div>
        <label className="fill-bots-toggle" title="혼자 시연/연습할 때 빈 좌석을 봇으로 채움">
          <input
            type="checkbox"
            checked={fillWithBots}
            onChange={(e) => setFillWithBots(e.target.checked)}
          />
          <span>🤖 빈 좌석 봇으로 채우기</span>
        </label>
        <div className="modal-actions">
          <button type="button" onClick={onClose} disabled={creating}>
            취소
          </button>
          <button type="submit" disabled={creating || !selectedGame || !roomName.trim()}>
            {creating ? '생성 중...' : '방 만들기'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
