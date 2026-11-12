import { useState } from 'react';
import type { Tier } from '@/api/users';
import { TierBadge, tierLabel } from '@/components/TierBadge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { STARTING_RATING, TIER_ORDER, tierRequirement } from './tierTable';

interface Props {
  /** 강조할 내 계급. 없으면(게스트·아직 모름) 강조하지 않는다. */
  myTier?: Tier | null;
  /** 내 계급의 기준(예: "티츄 기준"). */
  basis?: string;
  /** 창을 열 때 — 부르는 쪽이 내 계급을 그때 받아 올 수 있게. */
  onOpen?: () => void;
  className?: string;
}

/** 높은 것부터. 왕관 넷은 순위로, 나머지 열둘은 점수로. */
const HIGH_FIRST = [...TIER_ORDER].reverse();
const CROWNS = HIGH_FIRST.slice(0, 4);
const BY_RATING = HIGH_FIRST.slice(4);

function TierRows({ tiers, myTier }: { tiers: Tier[]; myTier?: Tier | null }) {
  return (
    <ol className="m-0 flex list-none flex-col gap-0.5 p-0">
      {tiers.map((tier) => {
        const mine = tier === myTier;
        return (
          <li
            key={tier}
            data-tier={tier}
            aria-current={mine ? 'true' : undefined}
            className={cn(
              'flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm',
              mine && 'bg-accent font-semibold text-accent-foreground ring-1 ring-primary/40',
            )}
          >
            <span className="inline-flex w-7 shrink-0 justify-center">
              <TierBadge tier={tier} size={24} />
            </span>
            <span className="min-w-0 flex-1 truncate">
              {tierLabel(tier)}
              {mine && <span className="ml-1.5 text-xs font-medium text-primary">내 계급</span>}
            </span>
            <span className="shrink-0 whitespace-nowrap text-xs tabular-nums text-muted-foreground sm:text-sm">
              {tierRequirement(tier)}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

/** D-266 — [계급표] 버튼과 16단계 표 창. 메인 랭킹 카드·프로필이 쓴다. */
export function TierTableButton({ myTier, basis, onOpen, className }: Props) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) onOpen?.();
      }}
    >
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm" className={cn('h-9 shrink-0 px-2.5', className)}>
          계급표
        </Button>
      </DialogTrigger>
      <DialogContent className="app-shell flex max-h-[85dvh] w-[calc(100%-2rem)] flex-col gap-3 rounded-lg p-4 sm:max-w-md sm:p-6">
        <DialogHeader className="pr-6 text-left">
          <DialogTitle>계급표</DialogTitle>
          <DialogDescription>
            레이팅은 게임마다 따로예요. 처음 {STARTING_RATING}점(미사일)에서 시작해 이기면 오르고 지면 내려가요. 봇이나 게스트가
            낀 판은 점수가 바뀌지 않아요.
          </DialogDescription>
        </DialogHeader>
        {myTier && (
          <p className="m-0 flex items-center gap-1.5 text-sm">
            <TierBadge tier={myTier} size={20} />
            <span>
              내 계급 <b>{tierLabel(myTier)}</b>
              {basis && <span className="text-muted-foreground"> · {basis}</span>}
            </span>
          </p>
        )}
        <div data-testid="tier-table-scroll" className="-mx-2 min-h-0 flex-1 space-y-3 overflow-y-auto px-2">
          <section aria-labelledby="tier-table-crowns">
            <h3 id="tier-table-crowns" className="m-0 mb-1 px-2 text-xs font-semibold text-muted-foreground">
              왕관 — 금훈장 이상 중 그 게임 순위로
            </h3>
            <TierRows tiers={CROWNS} myTier={myTier} />
          </section>
          <section aria-labelledby="tier-table-rating">
            <h3 id="tier-table-rating" className="m-0 mb-1 px-2 text-xs font-semibold text-muted-foreground">
              점수로 오르는 계급
            </h3>
            <TierRows tiers={BY_RATING} myTier={myTier} />
          </section>
        </div>
      </DialogContent>
    </Dialog>
  );
}
