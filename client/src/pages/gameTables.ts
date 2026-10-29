import { lazy } from 'react';
import { loadWithReload } from '@/lib/lazyWithReload';

/**
 * 세 게임판은 따로 받는 조각이다 — 첫 화면(로그인·허브)이 게임판 코드를 받지 않는다. 게임판 파일을 정적으로 import 하면 그 게임
 * 코드가 다시 첫 번들에 섞인다(`gameTables.test` 가 원문 검사). 대기실에서 미리 받아 두면({@link preloadGameTable}) 시작할 때 기다리지 않는다.
 */
const imports = {
  TICHU: () => import('@/features/tichu/GameTable'),
  SKULL_KING: () => import('@/features/skullking/SkullKingTable'),
  ONE_CARD: () => import('@/features/onecard/OneCardTable'),
};

// 게임판에 들어갈 때만 "못 받으면 한 번 새로고침" — 배포 전에 연 탭이 사라진 조각을 찾는 경우(lazyWithReload).
export const GameTable = lazy(() => loadWithReload('tichu', imports.TICHU).then((m) => ({ default: m.GameTable })));
export const SkullKingTable = lazy(() =>
  loadWithReload('skullking', imports.SKULL_KING).then((m) => ({ default: m.SkullKingTable })),
);
export const OneCardTable = lazy(() =>
  loadWithReload('onecard', imports.ONE_CARD).then((m) => ({ default: m.OneCardTable })),
);

/**
 * 이 게임의 게임판 조각을 미리 받는다(모르는 게임은 아무것도 안 한다). 실패는 삼키고 새로고침하지 않는다 — 대기실이 잠깐의 네트워크
 * 오류로 바뀌면 안 된다. 들어갈 때 위 지연 로딩이 다시 받는다.
 */
export function preloadGameTable(gameType: string): void {
  const load = imports[gameType as keyof typeof imports];
  if (load) void load().catch(() => {});
}
