import { ITEM_HINTS, ITEM_NAMES, SHELL_NAMES, type Item, type Shell } from '@/types/artillery';

export const ITEM_ICONS: Record<Item, string> = { WATERING_CAN: '💧', PINWHEEL: '🌀', TWIN_SEED: '🌱' };
export const SHELL_ICONS: Record<Shell, string> = { PEA: '🫛', PUMPKIN: '🎃', VINE: '🌿', CHESTNUT: '🌰', SPORE: '🌼' };
const ITEM_ORDER: Item[] = ['WATERING_CAN', 'PINWHEEL', 'TWIN_SEED'];

interface Props {
  shell: Shell;
  /** 고를 수 있는 탄과 남은 수(−1 = 무제한). 콩알·호박탄·내 유닛 특수탄. */
  shells: { shell: Shell; count: number }[];
  /** 남은 아이템과 이번 발에 끼운 것(한 차례 하나, D-224). */
  items: Item[];
  item: Item | null;
  enabled: boolean;
  onShell: (shell: Shell) => void;
  onItem: (item: Item | null) => void;
}

/**
 * 전장 위 HUD 의 내 탄·아이템 칩 (D-253) — 내 차례에만 전장 아래쪽에 겹쳐 뜬다. 탭으로 고르고(탄은 하나, 아이템은 한 차례 하나 — 다시 누르면 뺀다),
 * 쓴 아이템은 보이되 누를 수 없다. 칩 하나가 44px 이상이다.
 */
export function ArtilleryLoadout({ shell, shells, items, item, enabled, onShell, onItem }: Props) {
  return (
    <div className="ar-loadout">
      <div className="ar-shells" role="radiogroup" aria-label="탄">
        {shells.map(({ shell: sh, count }) => (
          <button
            key={sh}
            type="button"
            role="radio"
            aria-checked={shell === sh}
            aria-label={`${SHELL_NAMES[sh]}${count >= 0 ? ` ×${count}` : ''}`}
            title={SHELL_NAMES[sh]}
            className={`ar-chip${shell === sh ? ' ar-chip-on' : ''}`}
            disabled={!enabled || count === 0}
            onClick={() => onShell(sh)}
          >
            <span aria-hidden="true">{SHELL_ICONS[sh]}</span>
            {/* 이름은 고른 탄에만(좁은 화면에 칩 여섯이 들어가게) — 읽기는 aria-label. */}
            <span className="ar-chip-name" aria-hidden="true">
              {SHELL_NAMES[sh]}
            </span>
            {count >= 0 && (
              <span className="ar-chip-count" aria-hidden="true">
                {count}
              </span>
            )}
          </button>
        ))}
      </div>
      <div className="ar-items" role="group" aria-label="아이템 — 한 차례 하나">
        {ITEM_ORDER.map((it) => {
          const left = items.includes(it);
          return (
            <button
              key={it}
              type="button"
              aria-pressed={item === it}
              aria-label={`${ITEM_NAMES[it]} — ${ITEM_HINTS[it]}${left ? '' : ' (씀)'}`}
              className={`ar-chip ar-item${item === it ? ' ar-chip-on' : ''}`}
              disabled={!enabled || !left}
              title={`${ITEM_NAMES[it]} — ${ITEM_HINTS[it]}${left ? '' : ' (씀)'}`}
              onClick={() => onItem(item === it ? null : it)}
            >
              <span aria-hidden="true">{ITEM_ICONS[it]}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
