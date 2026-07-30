import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
// D-94 — CSS 진입점 단일화. 로드 순서(tailwind → tokens → theme → parts 17개)는
// styles/index.css 안에 @import 로 명시돼 있다. 여기서 개별 import 하지 않는 이유는
// 순서가 곧 캐스케이드이고, 그 근거를 CSS 파일 한 곳에 모아두기 위해서다.
import './styles/index.css';
import { useThemeStore } from './features/theme/themeStore';
import { useCardAnimStore } from './features/tichu/cardAnimStore';
import { useColorblindStore } from './features/theme/colorblindStore';
import { useAuthStore } from './features/auth/authStore';

// 첫 페인트 전 테마 클래스 적용(라이트↔다크 깜빡임 방지).
useThemeStore.getState().init();
// 카드 애니 토글 복원(저장값 / reduced-motion 선호 반영).
useCardAnimStore.getState().init();
// A5 — 색약 모드 복원(<html> data-colorblind 동기 적용).
useColorblindStore.getState().init();
// 첫 렌더 전 인증 세션 복원 — ProtectedRoute 가 토큰을 보기 전에 동기 하이드레이션
// 해야 새로고침 시 /login 으로 튕기지 않는다.
useAuthStore.getState().loadFromStorage();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
