import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const rootDir = fileURLToPath(new URL('.', import.meta.url));

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, rootDir);
  return {
    plugins: [vue()],
    resolve: { alias: { '@': path.resolve(rootDir, 'src') } },
    server: {
      host: '0.0.0.0',
      port: 5175,
      proxy: {
        [env.VITE_APP_BASE_API || '/dev-api']: {
          target: process.env.ADMIN_API_PROXY_TARGET || 'http://127.0.0.1:8081',
          changeOrigin: true,
          rewrite: requestPath => requestPath.replace(/^\/dev-api/, '/api'),
          configure: proxy => proxy.on('proxyReq', (proxyReq, req) => {
            // Only normalize the explicitly configured public preview origin.
            // Other origins still reach the backend's normal CORS validation.
            if (process.env.ADMIN_PUBLIC_ORIGIN && req.headers.origin === process.env.ADMIN_PUBLIC_ORIGIN) {
              proxyReq.setHeader('origin', 'http://127.0.0.1:5175');
            }
          })
        }
      }
    },
    build: { sourcemap: false, chunkSizeWarningLimit: 1200 }
  };
});
