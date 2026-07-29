import { useState } from 'react';
import { t } from '@/i18n/messages';
import { Button } from '@/components/ui/button';

/** P2(7) — 빠른 이모지 반응 팔레트(서버 화이트리스트와 일치). */
const REACTIONS = ['👍', '😂', '😮', '😢', '🔥', '👏', '❤️', '🎉'];

interface GameTableHeaderProps {
  fillWithBots: boolean;
  botSeats: number[];
  turnSeconds: number;
  stake: number;
  spectatorCount: number;
  connected: boolean;
  roundNumber: number;
  phaseLabel: string;
  activeWishRank: number | null;
  spectator: boolean;
  muted: boolean;
  onToggleMute: () => void;
  cardAnimEnabled: boolean;
  onToggleCardAnim: () => void;
  onSendReaction: (emoji: string) => void;
  chatOpen: boolean;
  onToggleChat: () => void;
  unreadCount: number;
}

/**
 * 게임판 헤더 — 좌측은 방 상태 배지(솔로/턴제한/판돈/연결/라운드/단계/관전/소원),
 * 우측은 토글 컨트롤(사운드·카드애니·이모지·채팅).
 *
 * 이모지 팔레트 열림 상태는 헤더 밖에서 쓰이지 않아 여기서 소유한다. 반면
 * chatOpen 은 하단 RoomChat 패널도 읽으므로 호출부가 계속 소유한다.
 *
 * D-87 에서 GameTable 에서 분리. 마크업·클래스명·문구 모두 이동 전과 동일하다.
 */
export function GameTableHeader({
  fillWithBots,
  botSeats,
  turnSeconds,
  stake,
  spectatorCount,
  connected,
  roundNumber,
  phaseLabel,
  activeWishRank,
  spectator,
  muted,
  onToggleMute,
  cardAnimEnabled,
  onToggleCardAnim,
  onSendReaction,
  chatOpen,
  onToggleChat,
  unreadCount,
}: GameTableHeaderProps) {
  const [reactionOpen, setReactionOpen] = useState(false);

  return (
    <header className="game-table-header">
      <div className="header-status">
        {fillWithBots && (
          <span className="solo-banner" title="이 방은 솔로 모드 — 빈 좌석은 봇이 자동 진행합니다">
            🤖 솔로 모드 (봇 {botSeats.length}명)
          </span>
        )}
        {turnSeconds > 0 && (
          <span className="turn-limit-badge" title="개인 턴 제한 — 초과 시 자동으로 다음 순서로 넘어갑니다">
            ⏱ 턴 제한 {turnSeconds}초
          </span>
        )}
        {stake > 0 && (
          <span className="turn-limit-badge" title="내기 방 — 판돈(가상 칩). 승팀이 가져갑니다">
            💰 판돈 {stake}칩
          </span>
        )}
        <span
          className="conn-indicator"
          title={connected ? '서버 연결됨' : '서버 연결 끊김'}
          aria-label={connected ? '서버 연결됨' : '서버 연결 끊김'}
        >
          <span className={`conn-dot ${connected ? 'on' : 'off'}`} aria-hidden="true" />
        </span>
        <span>
          {t('game.header.round')} {roundNumber}
        </span>
        <span>{phaseLabel}</span>
        {spectatorCount > 0 && (
          <span title="관전자 수">👁 관전 {spectatorCount}</span>
        )}
        {activeWishRank !== null && (
          <span>
            {t('game.header.activeWish')}: {activeWishRank}
          </span>
        )}
      </div>
      <div className="header-controls">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onToggleMute}
          aria-label="사운드 토글"
          title={muted ? '사운드 켜기' : '사운드 끄기'}
        >
          {muted ? '🔇' : '🔊'}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onToggleCardAnim}
          aria-label="카드 애니메이션 토글"
          title={cardAnimEnabled ? '카드 애니 끄기' : '카드 애니 켜기'}
        >
          {cardAnimEnabled ? '🎴' : '⏸'}
        </Button>
        {!spectator && (
          <div className="reaction-bar">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setReactionOpen((v) => !v)}
              aria-label="이모지 반응"
              title="이모지 반응"
            >
              😀
            </Button>
            {reactionOpen && (
              <div className="reaction-palette">
                {REACTIONS.map((e) => (
                  <button
                    type="button"
                    key={e}
                    onClick={() => {
                      onSendReaction(e);
                      setReactionOpen(false);
                    }}
                  >
                    {e}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="relative"
          onClick={onToggleChat}
          aria-label="채팅 토글"
        >
          💬 채팅
          {unreadCount > 0 && !chatOpen && (
            <span className="absolute -right-1.5 -top-1.5 min-w-[18px] rounded-full bg-destructive px-1.5 py-0.5 text-[10px] leading-none text-destructive-foreground">
              {unreadCount > 99 ? '99+' : unreadCount}
            </span>
          )}
        </Button>
      </div>
    </header>
  );
}
