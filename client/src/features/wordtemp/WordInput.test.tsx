import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { WordInput } from './WordInput';

function setup(over: Partial<React.ComponentProps<typeof WordInput>> = {}) {
  const onSubmit = vi.fn((_: string) => true);
  render(<WordInput label="힌트 낱말" placeholder="힌트" submitLabel="힌트 내기" onSubmit={onSubmit} {...over} />);
  const input = screen.getByRole('textbox', { name: '힌트 낱말' }) as HTMLInputElement;
  return { onSubmit, input, button: screen.getByRole('button', { name: '힌트 내기' }) };
}

describe('WordInput — 한글 입력', () => {
  it('조합 중의 Enter 는 보내지 않는다 — 조합이 끝난 뒤의 Enter 만 보낸다', () => {
    const { onSubmit, input } = setup();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '사ㄱ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).not.toHaveBeenCalled();
    expect(input.value).toBe('사ㄱ');

    fireEvent.change(input, { target: { value: '사과' } });
    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith('사과');
    expect(input.value).toBe('');
  });

  it('isComposing 인 Enter·keyCode 229 인 Enter(사파리)도 버린다', () => {
    const { onSubmit, input } = setup();
    fireEvent.change(input, { target: { value: '바다' } });
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 });
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
    expect(onSubmit).toHaveBeenCalledWith('바다');
  });

  it('버튼 — 조합 중이면 글자를 확정시킨 뒤의 낱말을 보내고 포커스는 입력란에 남는다', () => {
    const { onSubmit, input, button } = setup();
    input.focus();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '갈매기' } });
    fireEvent.click(button);
    expect(onSubmit).toHaveBeenCalledWith('갈매기');
    expect(document.activeElement).toBe(input);
    expect(input.value).toBe('');
    // 조합 기록이 남아 다음 Enter 를 삼키지 않는다
    fireEvent.change(input, { target: { value: '파도' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).toHaveBeenLastCalledWith('파도');
  });

  it('보내기 전 NFC 로 합치고 공백·문장부호를 뺀다(서버와 같은 규칙)', () => {
    const { onSubmit, input, button } = setup();
    fireEvent.change(input, { target: { value: ' 사 과! ' } });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(onSubmit).toHaveBeenCalledWith('사과');
  });

  // ── D-323: 버튼은 "지금 쓴 글이 지금 보낼 수 있을 때"에만 켜진다 ──
  it('빈 입력란·공백만 — 버튼이 꺼져 있고 Enter 도 보내지 않는다(까닭 줄은 비어 있다)', () => {
    const { onSubmit, input, button } = setup();
    expect(button).toBeDisabled();
    fireEvent.change(input, { target: { value: '   ' } });
    expect(button).toBeDisabled();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).toHaveTextContent('');
    fireEvent.change(input, { target: { value: '물' } });
    expect(button).toBeEnabled();
  });

  it('서버가 안 받는 꼴 — 버튼이 꺼지고 까닭을 말한다(한글 아님·11자 이상·자음만)', () => {
    const { onSubmit, input, button } = setup();
    const why = screen.getByRole('status');
    fireEvent.change(input, { target: { value: 'sea' } });
    expect(button).toBeDisabled();
    expect(why).toHaveTextContent('한글만 쓸 수 있어요');
    fireEvent.change(input, { target: { value: '바다1' } });
    expect(why).toHaveTextContent('한글만 쓸 수 있어요');
    fireEvent.change(input, { target: { value: '가나다라마바사아자차카' } });
    expect(button).toBeDisabled();
    expect(why).toHaveTextContent('10자까지 쓸 수 있어요');
    fireEvent.change(input, { target: { value: '가나다라마바사아자차' } });
    expect(button).toBeEnabled();
    expect(why).toHaveTextContent('');
    fireEvent.change(input, { target: { value: 'ㅋㅋ' } });
    expect(button).toBeDisabled();
    expect(why).toHaveTextContent('자음·모음만으로는 낼 수 없어요');
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.click(button);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('쓰는 중(조합 중)의 자음·모음은 재촉하지 않고, 글자가 되는 순간 버튼이 켜진다', () => {
    const { input, button } = setup();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: 'ㅂ' } });
    expect(button).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('');
    fireEvent.change(input, { target: { value: '바' } });
    expect(button).toBeEnabled();
    fireEvent.change(input, { target: { value: '바ㄷ' } });
    expect(button).toBeDisabled();
    fireEvent.change(input, { target: { value: '바다' } });
    expect(button).toBeEnabled();
  });

  it('고유 명사·품사는 화면이 가리지 않는다 — 한글 1~10자면 무엇이든 켜진다', () => {
    const { onSubmit, input, button } = setup();
    for (const word of ['서울', '세종대왕', '달리다', '빨갛게', '롯데월드']) {
      fireEvent.change(input, { target: { value: word } });
      expect(button, word).toBeEnabled();
      fireEvent.click(button);
      expect(onSubmit).toHaveBeenLastCalledWith(word);
    }
  });

  it('보내지 못했으면(끊김) 쓴 낱말을 지우지 않는다', () => {
    const { input } = setup({ onSubmit: () => false });
    fireEvent.change(input, { target: { value: '여름' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(input.value).toBe('여름');
  });

  it('응답을 기다리는 동안 버튼은 aria-disabled("보내는 중…")이고 또 보내지 않는다', () => {
    const { onSubmit, input, button } = setup({ busy: true });
    fireEvent.change(input, { target: { value: '여름' } });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('보내는 중…');
    fireEvent.click(button);
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('잠긴 입력란 — 입력·버튼 모두 꺼지고 까닭을 보여 준다', () => {
    const { input, button } = setup({ locked: true, lockedReason: '채점 중…' });
    expect(input).toBeDisabled();
    expect(button).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('채점 중…');
  });

  it('자동 완성·맞춤법 제안·자동 대문자를 끈다(글자 선택 막기의 예외인 진짜 입력란이다)', () => {
    const { input } = setup();
    expect(input.tagName).toBe('INPUT');
    expect(input).toHaveAttribute('autocomplete', 'off');
    expect(input).toHaveAttribute('autocorrect', 'off');
    expect(input).toHaveAttribute('autocapitalize', 'off');
    expect(input).toHaveAttribute('spellcheck', 'false');
    expect(input).toHaveAttribute('lang', 'ko');
  });
});
