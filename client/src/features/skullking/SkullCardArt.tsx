import type { SkullSuit, SpecialKind } from '@/types/skullking';

/**
 * "촛불 선장실" 카드 그림 (D-231). 전부 이 저장소에서 직접 그린 SVG 다 — 상용 스컬킹 카드나 다른 해적 작품의 그림·캐릭터를
 * 따라 그리지 않았다(무늬 상징도 따로 골랐다: 닻·등불·금화·검은 깃발). 그림은 꾸밈이라 `aria-hidden` 이고, 카드 이름은
 * 칩의 `aria-label`·글자가 읽는다. 작은 칸(트릭 레일 38px)에서도 읽히게 선보다 면 위주로 그린다.
 */

/** 무늬 상징 — 카드 글자색(`currentColor`)을 따른다. */
export function SuitEmblem({ suit }: { suit: SkullSuit }) {
  return (
    <svg className="sk-emblem" viewBox="0 0 24 24" aria-hidden focusable="false">
      {suit === 'GREEN' && (
        // 닻
        <g fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <circle cx="12" cy="4.6" r="2" strokeWidth="1.8" />
          <path d="M12 6.6v13.2M8 10.2h8" />
          <path d="M4.6 13.6c0 3.9 3.2 6.2 7.4 6.2s7.4-2.3 7.4-6.2" />
          <path d="M3 15.2l1.6-3.4 2.3 2.6zM21 15.2l-1.6-3.4-2.3 2.6z" fill="currentColor" strokeWidth="0.6" />
        </g>
      )}
      {suit === 'PURPLE' && (
        // 등불
        <g fill="currentColor">
          <path d="M9.4 4.8a2.600 2.600 0 0 1 5.2 0" fill="none" stroke="currentColor" strokeWidth="1.6" />
          <rect x="8" y="5" width="8" height="2.2" rx="1" />
          <path d="M7.4 8h9.2l1 9H6.4z" opacity="0.32" />
          <path d="M12 9.800c1.700 1.700 2.400 3 2.400 4.300a2.400 2.400 0 0 1-4.800 0c0-1.300.7-2.600 2.400-4.300z" />
          <rect x="6" y="17" width="12" height="2.8" rx="1.2" />
        </g>
      )}
      {suit === 'YELLOW' && (
        // 금화
        <g fill="currentColor">
          <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
          <circle cx="12" cy="12" r="6.4" opacity="0.22" />
          <path d="M12 7l1.500 3.100 3.400.4-2.500 2.400.6 3.400-3-1.700-3 1.700.6-3.400-2.500-2.400 3.400-.4z" />
        </g>
      )}
      {suit === 'BLACK' && (
        // 검은 깃발 — 으뜸패. 깃대·테두리는 글자색, 깃발 면은 검정.
        <g>
          <path d="M5.5 3v18.500" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <path
            d="M6.500 4.200c3.200-1.600 5.400 1.600 8.600 0 2-.9 3.200-.6 4.400-1v10c-1.200.4-2.400.1-4.400 1-3.200 1.600-5.400-1.600-8.600 0z"
            fill="#0d0c10"
            stroke="currentColor"
            strokeWidth="1.3"
            strokeLinejoin="round"
          />
          <circle cx="12.8" cy="7.6" r="1.7" fill="currentColor" />
          <path d="M10.300 10.400l5 1.300M15.300 10.400l-5 1.300" stroke="currentColor" strokeWidth="1.1" strokeLinecap="round" />
        </g>
      )}
    </svg>
  );
}

/** 특수 카드 그림 — 양피지 위에 놓이는 고정 색 그림(라이트·다크 공통). */
export function SpecialArt({ kind }: { kind: SpecialKind }) {
  return (
    <svg className="sk-art-svg" viewBox="0 0 48 48" aria-hidden focusable="false">
      {kind === 'PIRATE' && (
        // 해적 — 삼각모·안대·수염, 뒤에 엇갈린 칼 두 자루
        <g>
          <path d="M8 41L35 11" stroke="#aeb4c0" strokeWidth="3" strokeLinecap="round" />
          <path d="M40 41L13 11" stroke="#cfd4de" strokeWidth="3" strokeLinecap="round" />
          <circle cx="24" cy="27" r="11" fill="#e3b68c" />
          <path d="M13.200 28c1 9.200 5.600 13.400 10.800 13.400S33.800 37.200 34.800 28c-3 3-6.200 4-10.800 4s-7.800-1-10.800-4z" fill="#5a3320" />
          <path d="M6.500 20.500C9.500 11 16.600 6.500 24 6.500S38.500 11 41.500 20.500c-5.200-3-11.300-4.200-17.500-4.200S11.700 17.500 6.500 20.500z" fill="#2b2230" />
          <path d="M8.300 19.300c5-2.700 10.200-3.600 15.700-3.600s10.700.9 15.700 3.600" stroke="#d9a441" strokeWidth="1.7" fill="none" />
          <circle cx="24" cy="11.3" r="2.3" fill="#d9a441" />
          <path d="M13.400 21.200l21.600 4.600" stroke="#1b1620" strokeWidth="1.5" />
          <ellipse cx="29" cy="25.6" rx="3.5" ry="3.1" fill="#1b1620" />
          <circle cx="19.4" cy="25.2" r="1.6" fill="#1b1620" />
          <path d="M20 33.200c2.500 1.700 5.500 1.700 8 0" stroke="#f3e2c8" strokeWidth="1.5" fill="none" strokeLinecap="round" />
        </g>
      )}
      {kind === 'MERMAID' && (
        // 인어 — 붉은 머리, 청록 꼬리, 물결
        <g>
          <path d="M22.500 27c-5.500 5-3.500 11.500 4 12.300 5 .5 8-2.300 8.800-6.300" fill="none" stroke="#2f9c8f" strokeWidth="6.5" strokeLinecap="round" />
          <path d="M34.500 33l7.500-6-1.500 6.500 3.500 4.500-8-1z" fill="#57c2ae" />
          <path d="M18.800 17c-2 3.200-2 7.200 1 10.500h7.400c2-4 1-8-1-10.500z" fill="#e8bd97" />
          <path d="M18.600 21.300h8.900v3.400h-8.600z" fill="#7a5bb5" />
          <circle cx="23" cy="12" r="5.6" fill="#e8bd97" />
          <path d="M16.800 12.500C16.300 7 19.500 3.800 23.500 3.800s7.500 3 6.500 8.400c2.200 5.200 1 10.300-2 13.300.2-5-.8-9.200-2.800-12.300-3 1-5.200.2-6.400-1-1 4-.8 8.200.2 12.300-3.200-3-3.400-8-2.200-12z" fill="#c9503c" />
          <circle cx="21.6" cy="12.8" r="0.9" fill="#3a2418" />
          <circle cx="25.2" cy="12.8" r="0.9" fill="#3a2418" />
          <path d="M3 41.500c4-3 7-3 10.500 0s7 3 10.500 0 7-3 10.500 0 7 3 10.500 0V47H3z" fill="#3d8fa6" opacity="0.7" />
        </g>
      )}
      {kind === 'SKULL_KING' && (
        // 스컬킹 — 왕관 쓴 해골, 눈에 불씨
        <g>
          <circle cx="24" cy="27" r="20" fill="#2a2030" />
          <path d="M12 16.500l2.500-11 5.500 6.500 4-8.500 4 8.500 5.500-6.500 2.500 11z" fill="#e2b33c" stroke="#8a6414" strokeWidth="1" strokeLinejoin="round" />
          <circle cx="24" cy="12.5" r="1.7" fill="#c9412f" />
          <path d="M11 28c0-8.200 6-12.300 13-12.300S37 19.800 37 28c0 4-2 6.600-4 8.100v4.400c0 1.400-1.100 2.500-2.500 2.500h-13c-1.400 0-2.500-1.100-2.500-2.500v-4.400c-2-1.500-4-4.100-4-8.100z" fill="#f3ead6" />
          <ellipse cx="18.6" cy="28" rx="3.7" ry="4.1" fill="#2a2030" />
          <ellipse cx="29.4" cy="28" rx="3.7" ry="4.1" fill="#2a2030" />
          <circle cx="18.6" cy="28.3" r="1.2" fill="#e8622f" />
          <circle cx="29.4" cy="28.3" r="1.2" fill="#e8622f" />
          <path d="M24 31.800l-1.900 3.600h3.800z" fill="#2a2030" />
          <path d="M19.200 38v5M22.400 38v5M25.600 38v5M28.800 38v5" stroke="#2a2030" strokeWidth="1.2" />
        </g>
      )}
      {kind === 'TIGRESS' && (
        // 티그리스 — 붉은 두건을 두른 호랑이, 금 귀고리
        <g>
          <path d="M9.500 17l2-10 8.500 5.500zM38.500 17l-2-10-8.500 5.500z" fill="#e28a2b" />
          <ellipse cx="24" cy="27" rx="15" ry="14" fill="#f09a34" />
          <ellipse cx="24" cy="34" rx="8" ry="6" fill="#fbead2" />
          <path d="M9.600 25.500l5 1.500M9.800 30.500l5-.6M38.400 25.500l-5 1.500M38.200 30.500l-5-.6" stroke="#3a2416" strokeWidth="2" strokeLinecap="round" />
          <path d="M15.300 25.800c2-2.200 4.600-2.200 6.200 0-2 1.600-4.200 1.600-6.200 0zM26.500 25.800c1.600-2.200 4.200-2.200 6.200 0-2 1.600-4.200 1.600-6.200 0z" fill="#fdf3c4" stroke="#3a2416" strokeWidth="0.9" />
          <ellipse cx="18.4" cy="25.6" rx="0.9" ry="1.5" fill="#3a2416" />
          <ellipse cx="29.6" cy="25.6" rx="0.9" ry="1.5" fill="#3a2416" />
          <path d="M22 31h4l-2 2.600z" fill="#c2543d" />
          <path d="M24 33.600v1.800c-1.500 1.600-3.500 1.600-4.600.2M24 35.400c1.500 1.600 3.500 1.600 4.600.2" stroke="#3a2416" strokeWidth="1.1" fill="none" strokeLinecap="round" />
          <path d="M9 20.500C13 14 18.300 11.300 24 11.300S35 14 39 20.500c-5-2.200-9.800-3-15-3s-10 .8-15 3z" fill="#b8323a" />
          <path d="M38 18.800l6 .7-3.600 3.200zM39 21l3.600 5.400-4.800-2.400z" fill="#9c2630" />
          <circle cx="17" cy="15.8" r="1" fill="#f6d9c0" />
          <circle cx="24" cy="14.2" r="1" fill="#f6d9c0" />
          <circle cx="31" cy="15.8" r="1" fill="#f6d9c0" />
          <circle cx="10.2" cy="33.5" r="2.1" fill="none" stroke="#e2b33c" strokeWidth="1.4" />
        </g>
      )}
      {kind === 'ESCAPE' && (
        // 탈출 — 백기를 올린 조각배
        <g>
          <path d="M21.500 6.500V32" stroke="#6b4a2a" strokeWidth="2.2" strokeLinecap="round" />
          <path d="M22.600 8c4.200-2.200 7.200 2.200 11.400 0 2.200-1 4-.8 5.500-1.200v11.400c-1.500.4-3.300.2-5.500 1.200-4.200 2.200-7.200-2.200-11.400 0z" fill="#fdfcf5" stroke="#a9a089" strokeWidth="1.1" strokeLinejoin="round" />
          <path d="M8.500 31.500h31l-4.500 9.500h-22z" fill="#8a5a33" />
          <path d="M8 31.500h32" stroke="#5c3a1e" strokeWidth="2.4" strokeLinecap="round" />
          <path d="M3 42c4-3 7-3 10.500 0s7 3 10.500 0 7-3 10.500 0 7 3 10.500 0v5H3z" fill="#4c9ab4" opacity="0.8" />
        </g>
      )}
    </svg>
  );
}

/** 매치 종료 축하 — 금화가 넘치는 보물 상자. */
export function TreasureChestArt() {
  return (
    <svg className="sk-fx-chest" viewBox="0 0 64 48" aria-hidden focusable="false">
      <ellipse cx="32" cy="20" rx="20" ry="9" fill="#f7d774" opacity="0.5" />
      <path d="M12 20c2-9 9-14 20-14s18 5 20 14z" fill="#7a4a24" stroke="#c8963a" strokeWidth="2" />
      <circle cx="22" cy="19" r="4" fill="#f1c84b" stroke="#a87a1c" strokeWidth="1" />
      <circle cx="31" cy="16.5" r="4.5" fill="#f7d774" stroke="#a87a1c" strokeWidth="1" />
      <circle cx="41" cy="19" r="4" fill="#f1c84b" stroke="#a87a1c" strokeWidth="1" />
      <circle cx="36" cy="21" r="3.5" fill="#e9b93a" stroke="#a87a1c" strokeWidth="1" />
      <rect x="10" y="22" width="44" height="22" rx="3" fill="#8a5a33" stroke="#c8963a" strokeWidth="2" />
      <path d="M21 22v22M43 22v22" stroke="#c8963a" strokeWidth="2.5" />
      <rect x="28" y="27" width="8" height="9" rx="2" fill="#e2b33c" stroke="#8a6414" strokeWidth="1" />
      <circle cx="32" cy="31" r="1.4" fill="#5c3a1e" />
    </svg>
  );
}
