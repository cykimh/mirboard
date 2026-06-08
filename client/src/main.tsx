import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
// 순서 중요: tailwind base/utilities → 레거시 토큰(다크) → theme(라이트/다크
// 오버라이드) → 손제작 styles. (Phase 20a, D-76)
import './styles/tailwind.css';
import './styles/tokens.css';
import './styles/theme.css';
import './styles.css';
import { useThemeStore } from './features/theme/themeStore';
import { useCardAnimStore } from './features/tichu/cardAnimStore';
import { useAuthStore } from './features/auth/authStore';

// 첫 페인트 전 테마 클래스 적용(라이트↔다크 깜빡임 방지).
useThemeStore.getState().init();
// 카드 애니 토글 복원(저장값 / reduced-motion 선호 반영).
useCardAnimStore.getState().init();
// 첫 렌더 전 인증 세션 복원 — ProtectedRoute 가 토큰을 보기 전에 동기 하이드레이션
// 해야 새로고침 시 /login 으로 튕기지 않는다.
useAuthStore.getState().loadFromStorage();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
