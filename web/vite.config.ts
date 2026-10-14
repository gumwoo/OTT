import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 5173은 다른 프로젝트와 겹치기 쉽다. 다른 포트로 비켜 뜨면 api의 resource-base-url과 어긋난다
    port: 5180,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8085',
      // '/content'만 쓰면 화면 경로 /contents/...까지 영상 서버로 간다
      '^/content/': 'http://localhost:8081',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
