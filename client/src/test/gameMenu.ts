import { fireEvent, screen, within } from '@testing-library/react';

/**
 * D-186 — 게임판 ⋮ 메뉴 테스트 도우미. 기권·게임 종료·나가기·설정·규칙은 머리 줄이 아니라 메뉴 안에 있다.
 */

/** 메뉴를 연다(이미 열려 있으면 그대로). 열린 메뉴 요소. */
export function openGameMenu(): HTMLElement {
  const open = screen.queryByRole('menu', { name: '게임 메뉴' });
  if (open) return open;
  fireEvent.click(screen.getByRole('button', { name: '게임 메뉴' }));
  return screen.getByRole('menu', { name: '게임 메뉴' });
}

/** 메뉴를 열고 이름으로 항목(일반·켜고 끄는 항목)을 찾는다. 없으면 null. */
export function queryMenuItem(name: string | RegExp): HTMLElement | null {
  const menu = openGameMenu();
  return (
    within(menu).queryByRole('menuitem', { name }) ?? within(menu).queryByRole('menuitemcheckbox', { name })
  );
}

/** 메뉴를 열고 이름으로 항목을 찾는다. 없으면 실패. */
export function getMenuItem(name: string | RegExp): HTMLElement {
  const item = queryMenuItem(name);
  if (!item) throw new Error(`게임 메뉴에 "${String(name)}" 항목이 없다`);
  return item;
}

/** 메뉴를 열고 항목을 누른다. */
export function clickMenuItem(name: string | RegExp): void {
  fireEvent.click(getMenuItem(name));
}

/** 메뉴 맨 위 방 정보 글 줄(머리 줄에서 내린 배지). */
export function menuInfoText(): string {
  return openGameMenu().querySelector('.gm-info')?.textContent ?? '';
}

/** 메뉴가 열려 있으면 닫는다(⋮ 를 다시 누른다). */
export function closeGameMenu(): void {
  if (screen.queryByRole('menu', { name: '게임 메뉴' })) {
    fireEvent.click(screen.getByRole('button', { name: '게임 메뉴' }));
  }
}
