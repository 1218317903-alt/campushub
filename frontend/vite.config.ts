import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { ArcoResolver } from 'unplugin-vue-components/resolvers'
import Components from 'unplugin-vue-components/vite'
import { defineConfig, loadEnv } from 'vite'

/**
 * Vite 配置。
 *
 * 设计取舍：
 * 1. 开发环境通过 dev server 代理 `/api` 到后端，而不是在前端代码里硬编码完整 URL。
 *    这样前端代码永远只请求同源相对路径，生产环境也走反向代理，
 *    **不需要**为「开发/生产」写两套请求逻辑，也不会有跨域 Cookie / CORS 预检的额外问题。
 * 2. Arco 组件按需引入（见下方 Components 插件）。全量引入会把整个组件库与其样式
 *    打进产物 —— Phase 01 只用到 8 个组件，实测全量产物为 1.24 MB JS + 405 kB CSS。
 *    这不是「为想象中的规模提前优化」，而是**去掉当前就在打包的多余代码**。
 * 3. 构建产物输出到 `dist/`，由 Nginx 或后端静态资源托管，构建工具本身不参与运行时。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendOrigin = env.VITE_DEV_BACKEND_ORIGIN || 'http://127.0.0.1:8080'

  return {
    plugins: [
      vue(),
      Components({
        // 只在 SFC 模板里出现的 Arco 组件会被自动引入（含其样式）
        resolvers: [ArcoResolver({ sideEffect: true })],
        // 自动生成的类型声明文件，让编辑器能识别未显式 import 的组件
        dts: 'src/components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      // 与后端端口分开，避免开发时误连
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: backendOrigin,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: mode !== 'production',
      // 不手动上调告警阈值：阈值是用来被触发的，调高它等于把问题藏起来。
      rollupOptions: {
        output: {
          // 把体积大且低频变动的库拆成独立 chunk，让浏览器缓存能跨版本复用
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
          },
        },
      },
    },
  }
})
