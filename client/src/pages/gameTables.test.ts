/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다
// (features/tutorial/gameTutorials.test.ts 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

function here(relative: string): URL {
  // 리터럴 `new URL('…', import.meta.url)` 은 Vite 가 자산 주소로 바꾼다 — 인자로 받아 넘긴다.
  return new URL(relative, import.meta.url);
}

/**
 * 첫 화면(로그인·허브)이 세 게임판 코드를 받지 않게, 게임판은 `pages/gameTables` 의 지연 로딩으로만 들어온다. 어느 페이지든
 * 게임판 파일을 정적으로 import 하면 그 게임 코드가 다시 첫 번들에 섞인다.
 */
describe('게임판은 지연 로딩으로만 (번들 나누기)', () => {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  const staticTableImport =
    /^import [^;]*from '@\/features\/(tichu\/GameTable|skullking\/SkullKingTable|onecard\/OneCardTable)';/m;

  it.each(['./RoomPage.tsx', './GameHubPage.tsx', './ProfilePage.tsx', './gameTables.ts', '../App.tsx'])(
    '%s 는 게임판 파일을 정적으로 import 하지 않는다',
    (file) => {
      expect(read(here(file), 'utf-8')).not.toMatch(staticTableImport);
    },
  );

  it('gameTables 는 세 게임판을 모두 동적 import 한다', () => {
    const src = read(here('./gameTables.ts'), 'utf-8');
    for (const path of ['tichu/GameTable', 'skullking/SkullKingTable', 'onecard/OneCardTable']) {
      expect(src).toContain(`import('@/features/${path}')`);
    }
  });
});
