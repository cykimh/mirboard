/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': '/src',
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 자주 안 바뀌는 라이브러리를 따로 떼어 배포마다 다시 받지 않게(브라우저 캐시). 게임판은 pages/gameTables 의 지연 로딩이 나눈다.
        manualChunks(id) {
          if (id.indexOf('node_modules') < 0) return undefined;
          if (/[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/.test(id)) return 'react';
          if (/[\\/]node_modules[\\/](@stomp|sockjs-client)[\\/]/.test(id)) return 'stomp';
          // 나머지는 쓰는 곳을 따라간다 — 한데 모으면 티츄 게임판만 쓰는 끌어 놓기(@dnd-kit)까지 첫 화면에 섞인다.
          return undefined;
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      // 아바타 조회는 비-/api 공개 경로(D-80) — dev 에서도 백엔드로 프록시해야 표시됨.
      '/avatars': 'http://localhost:8080',
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
