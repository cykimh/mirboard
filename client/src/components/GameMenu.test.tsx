import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GameMenu } from './GameMenu';
import { useThemeStore } from '@/features/theme/themeStore';

/** D-186 — 게임판 ⋮ 메뉴(게임 중립). */
describe('GameMenu', () => {
  afterEach(() => {
    act(() => useThemeStore.getState().setTheme('light'));
  });

  const trigger = () => screen.getByRole('button', { name: '게임 메뉴' });
  const menu = () => screen.queryByRole('menu', { name: '게임 메뉴' });

  it('⋮ 는 닫힌 메뉴를 알리고, 누르면 열려 첫 항목에 초점이 간다', () => {
    render(<GameMenu sound={{ muted: false, onToggle: vi.fn() }} onExit={vi.fn()} />);

    expect(trigger()).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger()).toHaveAttribute('aria-expanded', 'false');
    expect(menu()).toBeNull();

    fireEvent.click(trigger());
    expect(trigger()).toHaveAttribute('aria-expanded', 'true');
    expect(trigger()).toHaveAttribute('aria-controls', menu()!.id);
    expect(document.activeElement).toBe(within(menu()!).getAllByRole('menuitemcheckbox')[0]);
  });

  it('Esc 로 닫히고 초점은 ⋮ 로 돌아간다', () => {
    render(<GameMenu onExit={vi.fn()} />);
    fireEvent.click(trigger());

    fireEvent.keyDown(menu()!, { key: 'Escape' });
    expect(menu()).toBeNull();
    expect(document.activeElement).toBe(trigger());
  });

  it('바깥을 누르면 닫히고, 메뉴 안을 누르면 닫히지 않는다', () => {
    render(
      <div>
        <p>바깥</p>
        <GameMenu onExit={vi.fn()} />
      </div>,
    );
    fireEvent.click(trigger());
    fireEvent.pointerDown(menu()!);
    expect(menu()).not.toBeNull();

    fireEvent.pointerDown(screen.getByText('바깥'));
    expect(menu()).toBeNull();
  });

  it('위·아래·Home·End 로 항목을 옮긴다(끝에서 돌아간다)', () => {
    render(<GameMenu onRules={vi.fn()} onAbort={vi.fn()} onExit={vi.fn()} />);
    fireEvent.click(trigger());
    const items = [
      ...within(menu()!).getAllByRole('menuitemcheckbox'),
      ...within(menu()!).getAllByRole('menuitem'),
    ];
    // 다크 모드 → 규칙 보기 → 게임 종료 → 나가기
    expect(items.map((x) => x.textContent)).toEqual(['다크 모드끔', '규칙 보기', '게임 종료', '나가기']);
    expect(document.activeElement).toBe(items[0]);

    fireEvent.keyDown(menu()!, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(items[1]);
    fireEvent.keyDown(menu()!, { key: 'End' });
    expect(document.activeElement).toBe(items[3]);
    fireEvent.keyDown(menu()!, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(items[0]);
    fireEvent.keyDown(menu()!, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(items[3]);
    fireEvent.keyDown(menu()!, { key: 'Home' });
    expect(document.activeElement).toBe(items[0]);
  });

  it('⋮ 에서 아래 화살표로도 연다', () => {
    render(<GameMenu onExit={vi.fn()} />);
    fireEvent.keyDown(trigger(), { key: 'ArrowDown' });
    expect(menu()).not.toBeNull();
  });

  it('항목을 고르면 먼저 닫고 부른다 — 확인 창이 열린 메뉴 위에 뜨지 않는다', () => {
    let openWhenCalled: boolean | null = null;
    const onExit = vi.fn(() => {
      openWhenCalled = screen.queryByRole('menu') !== null;
    });
    render(<GameMenu onExit={onExit} />);
    fireEvent.click(trigger());
    fireEvent.click(within(menu()!).getByRole('menuitem', { name: '나가기' }));

    expect(onExit).toHaveBeenCalledTimes(1);
    expect(openWhenCalled).toBe(false);
    expect(menu()).toBeNull();
  });

  it('켜고 끄는 설정은 메뉴를 닫지 않고 상태를 aria-checked 로 알린다', () => {
    const onToggle = vi.fn();
    const { rerender } = render(<GameMenu sound={{ muted: false, onToggle }} />);
    fireEvent.click(trigger());
    const sound = within(menu()!).getByRole('menuitemcheckbox', { name: '효과음' });
    expect(sound).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(sound);
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(menu()).not.toBeNull();

    rerender(<GameMenu sound={{ muted: true, onToggle }} />);
    expect(within(menu()!).getByRole('menuitemcheckbox', { name: '효과음' })).toHaveAttribute('aria-checked', 'false');
  });

  it('다크 모드 항목은 앱 테마를 바꾼다', () => {
    render(<GameMenu />);
    fireEvent.click(trigger());
    const dark = within(menu()!).getByRole('menuitemcheckbox', { name: '다크 모드' });
    expect(dark).toHaveAttribute('aria-checked', 'false');

    fireEvent.click(dark);
    expect(useThemeStore.getState().theme).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(within(menu()!).getByRole('menuitemcheckbox', { name: '다크 모드' })).toHaveAttribute('aria-checked', 'true');
  });

  it('넘긴 것만 그린다 — 기권·게임 종료·반응·방 정보', () => {
    const onReaction = vi.fn();
    const { rerender } = render(<GameMenu info={['4인', false, null, '연결됨']} onReaction={onReaction} />);
    fireEvent.click(trigger());
    expect(menu()!.querySelector('.gm-info')).toHaveTextContent('4인 · 연결됨');
    expect(within(menu()!).queryByRole('menuitem', { name: '기권' })).toBeNull();
    expect(within(menu()!).queryByRole('menuitem', { name: '게임 종료' })).toBeNull();

    fireEvent.click(within(menu()!).getByRole('menuitem', { name: '반응 👍' }));
    expect(onReaction).toHaveBeenCalledWith('👍');

    rerender(<GameMenu onResign={vi.fn()} onAbort={vi.fn()} />);
    fireEvent.click(trigger());
    expect(within(menu()!).getByRole('menuitem', { name: '기권' })).toBeInTheDocument();
    expect(within(menu()!).getByRole('menuitem', { name: '게임 종료' })).toBeInTheDocument();
    expect(within(menu()!).queryByRole('group', { name: '이모지 반응' })).toBeNull();
  });

  it('메뉴 누르기는 게임판 쪽 클릭 처리로 올라가지 않는다(포털이어도 React 트리로 거슬러 오른다)', () => {
    const onTableClick = vi.fn();
    render(
      <div onClick={onTableClick}>
        <GameMenu onRules={vi.fn()} />
      </div>,
    );
    fireEvent.click(trigger());
    onTableClick.mockClear();
    fireEvent.click(within(menu()!).getByRole('menuitem', { name: '규칙 보기' }));
    expect(onTableClick).not.toHaveBeenCalled();
  });
});
