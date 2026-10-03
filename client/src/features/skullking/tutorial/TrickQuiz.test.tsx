/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다.
import { readFileSync } from 'node:fs';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TrickQuiz } from './TrickQuiz';

/**
 * D-121 — '누가 이길까?' 연습. 정답은 정적 데이터다(클라가 판정하지 않는다). 문제별 정답을
 * 여기서 고정해 룰 문서(§7·§7.1·§13)와 어긋나면 빨개지게 한다.
 */

/**
 * 원문 읽기. 경로를 **인자로** 받는 이유: `new URL('./x.tsx', import.meta.url)` 처럼 정적
 * 문자열을 쓰면 Vite 가 자산 URL(http://…)로 바꿔 써서 readFileSync 가 거부한다.
 */
function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

const status = () => screen.getByRole('status');
const next = () => fireEvent.click(screen.getByRole('button', { name: '다음 문제' }));

describe('TrickQuiz', () => {
  it('정답을 고르면 정답, 오답이면 정답 카드를 알려 준다 (Q1 — 검정 으뜸)', () => {
    render(<TrickQuiz />);

    fireEvent.click(screen.getByRole('button', { name: '검정 2' }));
    expect(status()).toHaveTextContent('정답!');
    expect(status()).toHaveTextContent('+10');

    fireEvent.click(screen.getByRole('button', { name: '노랑 14' }));
    expect(status()).toHaveTextContent('정답은 검정 2');
  });

  it.each([
    [0, '검정 2'],
    [1, '보라 11'],
    [2, '인어'],
    [3, '티그리스 (해적 선언)'],
  ])('문제 %i 의 정답은 %s', (index, answer) => {
    render(<TrickQuiz />);
    for (let i = 0; i < index; i++) next();

    fireEvent.click(screen.getByRole('button', { name: answer }));
    expect(status()).toHaveTextContent('정답!');
  });

  it('다음 문제로 4문제를 돌고 처음으로 돌아온다', () => {
    render(<TrickQuiz />);
    expect(screen.getByText('문제 1 / 4')).toBeInTheDocument();

    next();
    expect(screen.getByText('문제 2 / 4')).toBeInTheDocument();
    next();
    next();
    expect(screen.getByText('문제 4 / 4')).toBeInTheDocument();
    next();
    expect(screen.getByText('문제 1 / 4')).toBeInTheDocument();
  });

  it('문제를 넘기면 선택과 해설이 초기화된다', () => {
    render(<TrickQuiz />);
    fireEvent.click(screen.getByRole('button', { name: '검정 2' }));
    next();
    expect(status()).not.toHaveTextContent('정답');
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0);
  });

  it('3자 트릭의 보너스는 +40 뿐이라고 설명한다 (§13-⑨)', () => {
    render(<TrickQuiz />);
    next();
    next();
    fireEvent.click(screen.getByRole('button', { name: '인어' }));
    expect(status()).toHaveTextContent('+40');
    expect(status()).not.toHaveTextContent('+90');
  });

  it('순수 컴포넌트다 — 소켓·스토어를 쓰지 않는다 (Server-Authoritative 와 무관)', () => {
    expect(readSource('./TrickQuiz.tsx')).not.toMatch(/@\/ws\/|Store'|sendAction/);
  });
});
