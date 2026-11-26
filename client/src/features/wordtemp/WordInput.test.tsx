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

  it('보내기 전 NFC 로 합치고 공백을 뺀다. 빈 낱말은 보내지 않는다', () => {
    const { onSubmit, input, button } = setup();
    fireEvent.change(input, { target: { value: ' 사 과 ' } });
    fireEvent.click(button);
    expect(onSubmit).toHaveBeenCalledWith('사과');
    fireEvent.change(input, { target: { value: '   ' } });
    fireEvent.click(button);
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('보내지 못했으면(끊김) 쓴 낱말을 지우지 않는다', () => {
    const { input } = setup({ onSubmit: () => false });
    fireEvent.change(input, { target: { value: '여름' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(input.value).toBe('여름');
  });

  it('응답을 기다리는 동안 버튼은 aria-disabled 이고 또 보내지 않는다. 잠기면 입력도 막힌다', () => {
    const { onSubmit, input, button } = setup({ busy: true });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toBeDisabled();
    fireEvent.change(input, { target: { value: '여름' } });
    fireEvent.click(button);
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('잠긴 입력란 — 입력·버튼 모두 꺼진다', () => {
    const { input, button } = setup({ locked: true });
    expect(input).toBeDisabled();
    expect(button).toBeDisabled();
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
