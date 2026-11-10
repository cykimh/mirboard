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
}

/** 승·패(탈주가 있으면 덧붙인다) — 프로필·랭킹과 같은 셈. */
export function recordText(r: GameRecord): string {
  return `${r.winCount}승 ${r.loseCount}패${r.desertCount > 0 ? ` · 탈주 ${r.desertCount}` : ''}`;
}

/**
 * D-146 — 대기실 참가자 한 명. 목록 줄 대신 카드로 아바타·이름·방장/봇 표시와 **이 방 게임** 기준 계급·전적을 보인다(D-115 — 레이팅은
 * 게임별). 안 해 본 게임이면 "첫 판". D-174 — 계급 아이콘은 이름 왼쪽.
 */
export function ParticipantCard({
  userId,
  username,
  host = false,
  bot = false,
  record,
  versusMe,
  ready = false,
}: ParticipantCardProps) {
  return (
    <li aria-label={username} className="flex flex-col items-center gap-1.5 rounded-lg border bg-card p-3 text-center">
      <Avatar className="h-12 w-12">
        {!bot && <AvatarImage src={avatarSrc(userId)} alt="" />}
        <AvatarFallback>{bot ? '🤖' : username.slice(0, 2).toUpperCase()}</AvatarFallback>
      </Avatar>
      {/* D-174 — 계급 아이콘은 이름 왼쪽(게임판 좌석과 같은 자리). */}
      <span className="flex w-full min-w-0 items-center justify-center gap-1" data-testid="participant-name">
        {!bot && record && <TierBadge tier={record.tier} size={18} />}
        <span className="truncate text-sm font-semibold" title={username}>
          {username}
        </span>
      </span>
      <div className="flex flex-wrap justify-center gap-1">
        {host && <Badge>방장</Badge>}
        {bot && <Badge variant="secondary">봇</Badge>}
        {ready && !host && (
          <Badge variant="outline" className="border-emerald-600 text-emerald-700 dark:text-emerald-400">
            준비
          </Badge>
        )}
      </div>
      {!bot && record !== undefined && (
        <div className="flex flex-col items-center gap-1">
          {record && record.winCount + record.loseCount > 0 && (
            <span className="text-xs text-muted-foreground">{recordText(record)}</span>
          )}
          {/* D-201 — 점수를 겨루는 게임(요트)의 개인 최고 점수. 혼자만 해 본 사람은 이 줄만 있다. */}
          {record?.bestScore != null && (
            <span className="text-xs text-muted-foreground">최고 {record.bestScore}점</span>
          )}
          {(!record || (record.winCount + record.loseCount === 0 && record.bestScore == null)) && (
            <span className="text-xs text-muted-foreground">첫 판</span>
          )}
        </div>
      )}
      {versusMe && <span className="text-xs font-medium text-primary">나와 {versusMe}</span>}
    </li>
  );
}
