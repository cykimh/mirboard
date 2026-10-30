import { Navigate, Route, BrowserRouter as Router, Routes, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/features/auth/authStore';
import { LoginPage } from '@/pages/LoginPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { GameHubPage } from '@/pages/GameHubPage';
import { RoomPage } from '@/pages/RoomPage';
import { ProfilePage } from '@/pages/ProfilePage';
import { Toaster } from '@/components/toast/Toaster';

/**
 * 토큰이 없으면 로그인 화면으로. D-133 — 가려던 경로(pathname + search)를 `state.from` 에 실어 보내, 초대 링크
 * (`/rooms/<방ID>`)로 온 사람이 로그인·게스트 입장·가입 뒤 그 방으로 돌아가게 한다. `state` 는 주소에 드러나지
 * 않고 새로고침하면 사라질 수 있다 — 그때는 지금처럼 허브(`LoginPage` 가 `safeReturnPath` 로 거른다).
 */
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  const location = useLocation();
  if (token) return <>{children}</>;
  return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
}

export function App() {
  // 세션 복원은 main.tsx 가 첫 렌더 전 동기로 수행(useAuthStore.loadFromStorage).
  // 여기서 useEffect 로 복원하면 ProtectedRoute 가 먼저 /login 으로 보내는 레이스 발생.
  return (
    <Router>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route
          path="/games"
          element={
            <ProtectedRoute>
              <GameHubPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/profile"
          element={
            <ProtectedRoute>
              <ProfilePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/rooms/:roomId"
          element={
            <ProtectedRoute>
              <RoomPage />
            </ProtectedRoute>
          }
        />
        <Route path="*" element={<Navigate to="/games" replace />} />
      </Routes>
      <Toaster />
    </Router>
  );
}
