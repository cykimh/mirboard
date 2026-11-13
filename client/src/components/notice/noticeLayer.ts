/**
 * D-269 — 화면 위에 뜨는 안내(재연결·거절 안내·토스트)가 함께 쌓이는 한 층. body 바로 아래에 하나만 두고 포털로 그려 넣는다.
 * 화면 위 가운데 고정이라 게임판 흐름의 자리를 차지하지 않고, 층 자체는 눌림을 통과시킨다(누를 것이 있는 안내만 받는다).
 * 게임판은 `.app-shell` 밖이라 박스 크기·글꼴은 안내마다 직접 정한다. z-index 는 게임 메뉴(1000)보다 위, 티츄 화면 연출(9999)보다 아래.
 */
export const NOTICE_LAYER_ID = 'floating-notices';

const LAYER_CLASS =
  'pointer-events-none fixed inset-x-0 top-0 z-[1100] box-border flex flex-col items-center px-4';

/** 층을 찾고, 없으면 만든다. 테스트가 body 를 비워도 다시 만들도록 매번 문서에서 찾는다. */
export function noticeLayer(): HTMLElement {
  const found = document.getElementById(NOTICE_LAYER_ID);
  if (found) return found;
  const layer = document.createElement('div');
  layer.id = NOTICE_LAYER_ID;
  layer.className = LAYER_CLASS;
  // 노치·상태 막대 아래로(iOS 안전 영역) + 위 여백 12px.
  // Radix 모달은 열릴 때 body 의 다른 자식에 aria-hidden 을 붙이는데(aria-hidden 패키지), `[aria-live]` 요소는 건너뛴다.
  // 층에 aria-live 를 적어 두면 대화상자가 열려 있어도 안내가 읽힌다. "off" 는 div 의 기본값이라 층 자체는 읽지 않는다
  // (읽는 것은 안의 role="alert"·"status").
  layer.setAttribute('aria-live', 'off');
  layer.style.paddingTop = 'calc(env(safe-area-inset-top, 0px) + 12px)';
  document.body.appendChild(layer);
  return layer;
}
