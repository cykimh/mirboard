import type {
  AssetsLiquidated,
  Built,
  DiceRolled,
  EscapeFailed,
  FestivalHosted,
  LandBought,
  LetterDrawn,
  MoneyChanged,
  MoneyTransferred,
  PlayerEliminated,
  SeatOnly,
  TakenOver,
  TokenMoved,
  TollPaid,
  WhirlpoolLeft,
} from '@/types/cloudroute';
import { LETTER_TEXT, LEVEL_NAME, TILES } from './board';
import type { LogEntry } from './cloudrouteStore';

export const g = (amount: number) => `${amount.toLocaleString('ko-KR')}G`;

const MONEY_REASON: Record<MoneyChanged['reason'], string> = {
  CUSTOMS: '세관',
  LETTER: '편지',
  ESCAPE: '소용돌이 탈출',
  FLIGHT: '비행',
};

const LEAVE_TEXT: Record<WhirlpoolLeft['method'], string> = {
  DOUBLES: '더블로',
  PAID: '50G 내고',
  BUOY: '구명 부표로',
  SERVED: '세 번째 차례라 저절로',
};

/**
 * 사건 줄 한 줄(D-213) — 화면과 보조기기 알림이 같은 문장을 쓴다. 모르는 종류는 null(그 줄을 그리지 않는다).
 */
export function describe(entry: LogEntry, nameOf: (seat: number) => string): string | null {
  const tile = (id: number) => TILES[id]?.name ?? `${id}번 칸`;
  switch (entry.type) {
    case 'DICE_ROLLED': {
      const p = entry.payload as DiceRolled;
      return `${nameOf(p.seat)} 🎲 ${p.die1}·${p.die2}${p.doubles ? ' 더블!' : ''}`;
    }
    case 'TOKEN_MOVED': {
      const p = entry.payload as TokenMoved;
      const how = p.kind === 'FLY' ? '날아서' : p.kind === 'WHIRLPOOL' ? '휩쓸려' : p.kind === 'LETTER' ? '바람에' : '';
      const salary = p.salary > 0 ? ` · 급여 ${g(p.salary)}` : '';
      return `${nameOf(p.seat)} ${how ? `${how} ` : ''}${tile(p.to)}(으)로${salary}`;
    }
    case 'LAND_BOUGHT': {
      const p = entry.payload as LandBought;
      const built = p.level > 0 ? ` + ${LEVEL_NAME[p.level]}` : '';
      return `${nameOf(p.seat)} ${tile(p.tile)} 구매${built} (${g(p.paid)})`;
    }
    case 'BUILT': {
      const p = entry.payload as Built;
      return `${nameOf(p.seat)} ${tile(p.tile)}에 ${LEVEL_NAME[p.level]} (${g(p.paid)})`;
    }
    case 'TOLL_PAID': {
      const p = entry.payload as TollPaid;
      const tags = [p.monopoly && '독점', p.festival && '축제'].filter(Boolean).join('·');
      return `${nameOf(p.payer)} → ${nameOf(p.owner)} ${tile(p.tile)} 통행료 ${g(p.amount)}${tags ? ` (${tags})` : ''}`;
    }
    case 'TAKEN_OVER': {
      const p = entry.payload as TakenOver;
      return `${nameOf(p.buyer)} ${nameOf(p.seller)}의 ${tile(p.tile)} 인수 (${g(p.price)})`;
    }
    case 'LETTER_DRAWN': {
      const p = entry.payload as LetterDrawn;
      const letter = LETTER_TEXT[p.letter];
      return `${nameOf(p.seat)} 바람 편지 「${letter.name}」 — ${letter.effect}`;
    }
    case 'MONEY_CHANGED': {
      const p = entry.payload as MoneyChanged;
      // 탈출 비용은 뒤따르는 WHIRLPOOL_LEFT("50G 내고 … 탈출") 줄이 이미 말한다.
      if (p.delta === 0 || p.reason === 'ESCAPE') return null;
      return `${nameOf(p.seat)} ${MONEY_REASON[p.reason]} ${p.delta > 0 ? '+' : '−'}${g(Math.abs(p.delta))}`;
    }
    case 'MONEY_TRANSFERRED': {
      const p = entry.payload as MoneyTransferred;
      const why = p.reason === 'ISLAND_FEAST' ? '잔치' : '나눔';
      return `${nameOf(p.from)} → ${nameOf(p.to)} ${why} ${g(p.amount)}`;
    }
    case 'WHIRLPOOL_ENTERED':
      return `${nameOf((entry.payload as SeatOnly).seat)} 소용돌이에 갇힘`;
    case 'ESCAPE_FAILED': {
      const p = entry.payload as EscapeFailed;
      return `${nameOf(p.seat)} 탈출 실패 (${p.failedEscapes}/2)`;
    }
    case 'WHIRLPOOL_LEFT': {
      const p = entry.payload as WhirlpoolLeft;
      return `${nameOf(p.seat)} ${LEAVE_TEXT[p.method]} 소용돌이 탈출`;
    }
    case 'FESTIVAL_HOSTED': {
      const p = entry.payload as FestivalHosted;
      return `${nameOf(p.seat)} ${tile(p.tile)}에 등불 축제 🏮`;
    }
    case 'ASSETS_LIQUIDATED': {
      const p = entry.payload as AssetsLiquidated;
      const got = p.sales.reduce((sum, s) => sum + s.received, 0);
      return `${nameOf(p.seat)} 돈이 모자라 ${p.sales.length}건 팔아 ${g(got)} 마련`;
    }
    case 'PLAYER_ELIMINATED': {
      const p = entry.payload as PlayerEliminated;
      return `${nameOf(p.seat)} ${p.reason === 'BANKRUPT' ? '파산' : '탈주'} — 땅 ${p.returnedTiles.length}칸이 은행으로`;
    }
    default:
      return null;
  }
}
