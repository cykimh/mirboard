/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다
// (features/tutorial/gameTutorials.test.ts 와 같은 패턴).
import { readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * D-132 후속 m5 — `features/chat` 은 세 게임이 같이 쓰는 채팅이다. 여기서 게임 폴더를 import 하면 의존 방향이 거꾸로다 —
 * 티츄 경기장에만 뜨는 말풍선(`ArenaChatBubbles`, 티츄 좌석 매핑 `viewPosOf` 를 쓴다)은 `features/tichu` 에 둔다.
 */
function here(relative: string): URL {
  // 리터럴 `new URL('…', import.meta.url)` 은 Vite 가 자산 주소로 바꾼다 — 인자로 받아 넘긴다.
  return new URL(relative, import.meta.url);
}

describe('features/chat — 게임 폴더를 import 하지 않는다 (D-132 후속)', () => {
  it('채팅 폴더의 어떤 파일도 @/features/{tichu,skullking,onecard} 를 import 하지 않는다', () => {
    const dir = here('./');
    const list = readdirSync as unknown as (p: URL) => string[];
    const read = readFileSync as unknown as (p: URL, enc: string) => string;
    const gameImport = /from '@\/features\/(tichu|skullking|onecard)\//;

    const offenders = list(dir)
      .filter((file) => /\.tsx?$/.test(file))
      .filter((file) => gameImport.test(read(new URL(file, dir), 'utf-8')));

    expect(offenders).toEqual([]);
  });
});
