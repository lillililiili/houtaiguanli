import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const rootDir = fileURLToPath(new URL('.', import.meta.url));


// Written into built HTML, so preview checks the build-time API instead of guessing from its environment.
function startupMetadata() {
  let tags;
  return {
    name: 'startup-metadata',
    configResolved(config) {
      const apiBase = String(config.env.VITE_APP_BASE_API || (config.env.DEV ? '/dev-api' : '/api')).replace(/\/$/, '') + '/v1';
      tags = Object.entries({
        'uav-api-base': apiBase,
        'uav-source-id': createHash('sha256').update(path.resolve(rootDir)).digest('hex'),
        'uav-runtime-mode': config.command === 'build' ? 'built' : 'dev'
      }).map(([name, content]) => ({ tag: 'meta', attrs: { name, content }, injectTo: 'head' }));
    },
    transformIndexHtml() { return tags; }
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, rootDir);
  const mapProxyTarget = process.env.ADMIN_MAP_PROXY_TARGET || 'http://127.0.0.1:5173';
  const config = {
    plugins: [vue(), startupMetadata()],
    resolve: { alias: { '@': path.resolve(rootDir, 'src') } },
    server: {
      host: '0.0.0.0',
      port: 5175,
      proxy: {
        '/map-data': { target: mapProxyTarget, changeOrigin: true },
        '/map-config.json': { target: mapProxyTarget, changeOrigin: true },
        [env.VITE_APP_BASE_API || '/dev-api']: {
          target: process.env.ADMIN_API_PROXY_TARGET || 'http://127.0.0.1:8081',
          changeOrigin: true,
          rewrite: requestPath => requestPath.replace(/^\/dev-api/, '/api'),
          configure: proxy => proxy.on('proxyReq', (proxyReq, req) => {
            // The edge proxy owns the client address; never append a browser-supplied chain.
            proxyReq.setHeader('X-Forwarded-For', req.socket.remoteAddress || '');
            proxyReq.setHeader('X-Forwarded-Proto', req.socket.encrypted ? 'https' : 'http');
            proxyReq.removeHeader('Forwarded');
            proxyReq.removeHeader('X-Real-IP');
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
  // Built pages default to /api, while development pages default to /dev-api.
  // Keep map forwarding and the same trusted request-header handling in preview.
  config.preview = {
    proxy: {
      ...config.server.proxy,
      '/api': config.server.proxy[env.VITE_APP_BASE_API || '/dev-api']
    }
  };
  return config;
});
