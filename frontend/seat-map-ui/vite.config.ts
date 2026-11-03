import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * The dev server and the test runner of the demo client (README sections 6.7 and 23).
 *
 * The proxy is the part worth explaining. Everything under `/api` is forwarded to the gateway, so
 * the page talks to its own origin and the browser never makes a cross-origin request. That is not
 * a convenience: the gateway has no CORS configuration at all, because in the deployed stack the
 * page and the API are served from one origin, and a dev server that fetched
 * `http://localhost:8080` directly would be exercising a path production does not have.
 *
 * `SEATRUSH_API_URL` moves the target without a rebuild, which is what a demo against a stack
 * somewhere else needs.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.SEATRUSH_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
        // The seat map stream is silent for minutes at a time by design, so the proxy must not
        // decide that a quiet response is a dead one.
        timeout: 0,
        proxyTimeout: 0,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
