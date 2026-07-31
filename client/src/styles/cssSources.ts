/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. 이 모듈은 **테스트에서만** import 되고
// vitest 는 Node 에서 돌기 때문에 런타임은 안전하다. Vite `?raw` 를 쓰지 않는 이유는
// vitest 기본 설정(css:false)에서 CSS import 가 빈 문자열이 되기 때문 (D-103).
import { readFileSync } from 'node:fs';

/** 이 파일 기준 상대 경로의 CSS 원문. `import.meta.url` 이라 `__dirname` 이 필요 없다. */
function readCss(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

export const skullkingCssSource = readCss('./parts/18-skullking-table.css');
export const indexCssSource = readCss('./index.css');
