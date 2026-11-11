import { GameIcon } from '@/components/GameIcon';
import { t } from '@/i18n/messages';
import { Button, buttonVariants } from '@/components/ui/button';
import { GameMenu } from '@/components/GameMenu';
import type { useVoiceChat } from '@/features/voice/useVoiceChat';

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
  /** D-135 — 방장 강제 종료(확인은 호출 측). 방장이고 나머지가 모두 봇일 때만 온다. */
  onAbort?: () => void;
  /** D-186 — 나가기(탈주 확인은 호출 측). */
  onExit?: () => void;
  /** D-186 — 게임 방법 다시 보기. */
  onRules?: () => void;
  /** D-156 — 음성 대화. D-186 부터 ⋮ 메뉴 안(스위치가 켜져 있을 때만). */
  voice?: ReturnType<typeof useVoiceChat>;
}

/**
 * 게임판 헤더 — D-186 부터 한 줄 고정: 왼쪽은 핵심 배지(라운드·단계·소원), 오른쪽은 채팅 + ⋮ 메뉴.
 * 방 정보(혼자 연습·턴 제한·판돈·관전·연결)와 설정(사운드·카드 애니·라이트/다크)·이모지·규칙·게임 종료·나가기는 메뉴로 옮겼다.
 *
 * chatOpen 은 하단 RoomChat 패널도 읽으므로 호출부가 소유한다. D-87 에서 GameTable 에서 분리.
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
  onAbort,
  onExit,
  onRules,
  voice,
}: GameTableHeaderProps) {
  return (
    <header className="game-table-header gm-header">
      <div className="header-status gm-header-info">
        <GameIcon gameId="TICHU" size={22} />
        <span>
          {t('game.header.round')} {roundNumber}
        </span>
        <span className="header-phase">{phaseLabel}</span>
        {activeWishRank !== null && (
          <span>
            {t('game.header.activeWish')}: {activeWishRank}
          </span>
        )}
      </div>
      <div className="header-controls gm-header-actions">
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
        <GameMenu
          triggerClassName={buttonVariants({ variant: 'outline', size: 'sm' })}
          info={[
            fillWithBots && `봇과 연습 (봇 ${botSeats.length}명)`,
            turnSeconds > 0 && `턴 제한 ${turnSeconds}초`,
            stake > 0 && `판돈 ${stake}칩`,
            spectatorCount > 0 && `관전 ${spectatorCount}명`,
            connected ? '연결됨' : '연결 끊김',
          ]}
          sound={{ muted, onToggle: onToggleMute }}
          toggles={[{ key: 'card-anim', label: '카드 애니메이션', checked: cardAnimEnabled, onToggle: onToggleCardAnim }]}
          onRules={onRules}
          onReaction={spectator ? undefined : onSendReaction}
          voice={voice}
          onAbort={onAbort}
          onExit={onExit}
        />
      </div>
    </header>
  );
}
