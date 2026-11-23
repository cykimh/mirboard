import { avatarSrc } from '@/api/avatar';
import type { GameRecord } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';

interface ParticipantCardProps {
  userId: number;
  username: string;
  host?: boolean;
  bot?: boolean;
  /** 이 방 게임의 전적. `null` 은 한 판도 안 함, `undefined` 는 아직 못 받음(빈칸). 봇은 쓰지 않는다. */
  record?: GameRecord | null;
  /** D-154 — 나와의 상대 전적(이 방 게임). 나·봇·붙어 본 적 없으면 없음. */
  versusMe?: string;
  /** D-161 — 대기실에서 준비했다(표시만 — 시작은 방장). */
  ready?: boolean;
  /**
   * D-290 — 방장에게만: 이 좌석을 비운다(봇은 빼기, 사람은 내보내기 — 확인은 부르는 쪽). 없으면 버튼을 그리지 않는다(방장 자신의 카드·
   * 방장이 아닌 사람의 화면).
   */
  onRemove?: () => void;
  /** 요청 중이면 잠근다. */
  removePending?: boolean;
}

/** 승·패(탈주가 있으면 덧붙인다) — 프로필·랭킹과 같은 셈. */
export function recordText(r: GameRecord): string {
  return `${r.winCount}승 ${r.loseCount}패${r.desertCount > 0 ? ` · 탈주 ${r.desertCount}` : ''}`;
}

/** D-272 — 방장 띠의 왕관(장식 — 글자 "방장"이 이름이다). */
function CrownIcon() {
  return (
    <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true" focusable="false" fill="currentColor">
      <path d="M2 12.5h12V14H2zM1.5 4.5l3.2 2.7L8 2.5l3.3 4.7 3.2-2.7-1.2 6.5H2.7z" />
    </svg>
  );
}

/**
 * D-146 — 대기실 참가자 한 명. 아바타·이름·봇/준비 표시와 **이 방 게임** 기준 계급·전적을 보인다(D-115 — 레이팅은 게임별). 안 해 본
 * 게임이면 "첫 판". D-174 — 계급 아이콘은 이름 왼쪽.
 *
 * <p>D-290 — 방장 화면에서는 다른 좌석 카드 오른쪽에 [✕](봇 빼기 / 사람 내보내기)가 붙는다.
 *
 * <p>D-272 — 촘촘한 가로 배치(아바타 왼쪽, 오른쪽에 이름 줄 + 표시·전적 줄)이고, **방장 표시는 카드 맨 위 띠**다(이름 아래 배지에서 옮김).
 */
export function ParticipantCard({
  userId,
  username,
  host = false,
  bot = false,
  record,
  versusMe,
  ready = false,
  onRemove,
  removePending = false,
}: ParticipantCardProps) {
  const played = !!record && record.winCount + record.loseCount > 0;
  return (
    <li
      aria-label={username}
      className={`relative flex min-w-0 flex-col overflow-hidden rounded-lg border bg-card${host ? ' border-primary/60' : ''}`}
    >
      {/* D-290 — 방장의 [✕]: 봇은 빼기, 사람은 내보내기. 카드 오른쪽 위 모서리의 작은 동그라미로 보이고(이름 자리를 덜 먹게) 누르는 곳은
          40×40(D-272)이다. */}
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          disabled={removePending}
          aria-label={bot ? `${username} 빼기` : `${username} 내보내기`}
          title={bot ? '봇 빼기' : '내보내기'}
          className="group absolute right-0 top-0 z-10 flex h-10 w-10 items-start justify-end rounded-none border-0 bg-transparent p-1 hover:bg-transparent focus-visible:outline-none disabled:opacity-50"
        >
          <span className="flex h-5 w-5 items-center justify-center rounded-full bg-muted text-muted-foreground group-hover:bg-destructive group-hover:text-destructive-foreground group-focus-visible:ring-2 group-focus-visible:ring-ring">
            <svg viewBox="0 0 12 12" width="10" height="10" aria-hidden="true" focusable="false">
              <path d="M2.5 2.5l7 7M9.5 2.5l-7 7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" fill="none" />
            </svg>
          </span>
        </button>
      )}
      {host && (
        <span
          data-testid="host-strip"
          className="flex items-center justify-center gap-1 bg-primary px-2 py-0.5 text-[11px] font-semibold leading-4 text-primary-foreground"
        >
          <CrownIcon />
          방장
        </span>
      )}
      <div className="flex min-w-0 flex-1 items-center gap-2 px-2 py-1.5">
        <Avatar className="h-9 w-9 shrink-0 text-xs">
          {!bot && <AvatarImage src={avatarSrc(userId)} alt="" />}
          <AvatarFallback>{bot ? '🤖' : username.slice(0, 2).toUpperCase()}</AvatarFallback>
        </Avatar>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          {/* D-174 — 계급 아이콘은 이름 왼쪽(게임판 좌석과 같은 자리). */}
          <span className={`flex min-w-0 items-center gap-1${onRemove ? ' pr-5' : ''}`} data-testid="participant-name">
            {!bot && record && <TierBadge tier={record.tier} size={16} />}
            <span className="truncate text-sm font-semibold leading-5" title={username}>
              {username}
            </span>
          </span>
          <div className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs leading-4 text-muted-foreground">
            {bot && (
              <Badge variant="secondary" className="px-1.5 py-0 text-[11px] leading-4">
                봇
              </Badge>
            )}
            {ready && !host && (
              <Badge
                variant="outline"
                className="border-emerald-600 px-1.5 py-0 text-[11px] leading-4 text-emerald-700 dark:text-emerald-400"
              >
                준비
              </Badge>
            )}
            {!bot && record !== undefined && (
              <>
                {played && record && <span>{recordText(record)}</span>}
                {/* D-201 — 점수를 겨루는 게임(요트)의 개인 최고 점수. 혼자만 해 본 사람은 이것만 있다. */}
                {record?.bestScore != null && <span>최고 {record.bestScore}점</span>}
                {!played && record?.bestScore == null && <span>첫 판</span>}
              </>
            )}
          </div>
          {versusMe && <span className="truncate text-xs font-medium leading-4 text-primary">나와 {versusMe}</span>}
        </div>
      </div>
    </li>
  );
}
