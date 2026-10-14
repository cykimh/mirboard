/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다(indexHtml.test 와 같은 패턴).
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import { errorText, TRANSLATED_ERROR_CODES } from './errorText';

/**
 * D-138 — 서버 오류 원문 중 영어인 것(`Room not found` 등)이 화면에 그대로 나오지 않게 클라 한 곳에서 코드별 한국어로 옮긴다. 서버 원문이
 * 한국어인 코드는 그대로 쓴다. 서버에 영어 원문의 새 코드가 생기면 아래 대조 테스트가 잡는다.
 */
const apiError = (code: string, message: string) => new ApiError(409, { error: { code, message } });

describe('errorText (D-138)', () => {
  it('영어 원문의 코드는 한국어로 옮긴다', () => {
    expect(errorText(apiError('ROOM_NOT_FOUND', 'Room not found'), '실패')).toBe('방이 없어졌어요');
    expect(errorText(apiError('BAD_CREDENTIALS', 'Invalid username or password'), '실패')).toBe('아이디 또는 비밀번호가 맞지 않아요');
  });

  it('서버 원문이 한국어면 그대로 쓴다', () => {
    expect(errorText(apiError('GUEST_DISABLED', '지금은 게스트 입장을 받지 않습니다.'), '실패')).toBe('지금은 게스트 입장을 받지 않습니다.');
  });

  it('모르는 코드의 영어 원문은 대체 문구로', () => {
    expect(errorText(apiError('SOMETHING_NEW', 'Something went wrong'), '방을 만들지 못했어요')).toBe('방을 만들지 못했어요');
  });

  it('네트워크 오류 등 ApiError 가 아니면 대체 문구로', () => {
    expect(errorText(new TypeError('Failed to fetch'), '로그인 실패')).toBe('로그인 실패');
  });
});

/** 저장소 안에서만(클라 단독 체크아웃이면 건너뜀) — 서버 소스의 `ApiErrorEnvelope.of("CODE", "영어 원문"` 은 모두 옮겨져 있어야 한다. */
describe('errorText — 서버 오류 코드와 대조 (D-138)', () => {
  // 리터럴 `new URL('…', import.meta.url)` 은 Vite 가 자산 주소로 바꾼다 — 인자로 받아 넘긴다(chatBoundary.test 와 같은 방식).
  const here = (relative: string) => new URL(relative, import.meta.url);
  const serverMain = here('../../../server/src/main/java/');
  const exists = (existsSync as unknown as (p: URL) => boolean)(serverMain);

  it.skipIf(!exists)('영어 원문인 서버 오류 코드는 모두 한국어로 옮겨져 있다', () => {
    const list = readdirSync as unknown as (p: URL, o: { recursive: true }) => string[];
    const read = readFileSync as unknown as (p: URL, enc: string) => string;
    const english = new Set<string>();
    for (const file of list(serverMain, { recursive: true })) {
      if (!file.endsWith('.java')) continue;
      const source = read(new URL(file, serverMain), 'utf-8');
      for (const m of source.matchAll(/ApiErrorEnvelope\.of\(\s*"([A-Z_]+)",\s*"([^"]*)"/g)) {
        if (!/[가-힣]/.test(m[2])) english.add(m[1]);
      }
    }
    expect(english.size).toBeGreaterThan(5); // 정규식이 아무것도 못 찾는 것을 막는다.
    expect([...english].filter((code) => !TRANSLATED_ERROR_CODES.includes(code)).sort()).toEqual([]);
  });
});
