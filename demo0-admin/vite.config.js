import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://localhost:9191'

  return {
    plugins: [
      vue(),
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        resolvers: [ElementPlusResolver()],
        dts: false,
      }),
      Components({
        resolvers: [ElementPlusResolver()],
        dts: false,
      }),
    ],
    server: {
      proxy: {
        '/admin': {
          target: proxyTarget,
          changeOrigin: true,
        },
        '/user': {
          target: proxyTarget,
          changeOrigin: true,
          bypass: (req) => {
            if (req.url === '/user' || req.url === '/user/' || req.url?.startsWith('/user/?')) return '/index.html'
          },
        },
        '/content': {
          target: proxyTarget,
          changeOrigin: true,
          bypass: (req) => {
            if (req.url === '/content' || req.url === '/content/' || req.url?.startsWith('/content/?')) return '/index.html'
          },
        },
        '/answer': {
          target: proxyTarget,
          changeOrigin: true,
          bypass: (req) => {
            if (req.url === '/answer' || req.url === '/answer/' || req.url?.startsWith('/answer/?')) return '/index.html'
          },
        },
        '/comment': {
          target: proxyTarget,
          changeOrigin: true,
          bypass: (req) => {
            if (req.url === '/comment' || req.url === '/comment/' || req.url?.startsWith('/comment/?')) return '/index.html'
          },
        },
      },
    },
  }
})
