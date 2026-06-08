import { Navigate, Route, BrowserRouter as Router, Routes } from 'react-router-dom';
import { useAuthStore } from '@/features/auth/authStore';
import { LoginPage } from '@/pages/LoginPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { GameHubPage } from '@/pages/GameHubPage';
import { RoomPage } from '@/pages/RoomPage';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  return token ? <>{children}</> : <Navigate to="/login" replace />;
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
          path="/rooms/:roomId"
          element={
            <ProtectedRoute>
              <RoomPage />
            </ProtectedRoute>
          }
        />
        <Route path="*" element={<Navigate to="/games" replace />} />
      </Routes>
    </Router>
  );
}
