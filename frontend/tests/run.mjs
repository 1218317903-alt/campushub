import { build } from 'vite'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

// 使用项目已有的 Vite 编译真实 TS 模块，测试由 Node 自带运行器执行。
await build({
  configFile: false,
  resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
  build: {
    ssr: 'tests/auth.test.ts',
    outDir: '.test-dist',
    rollupOptions: { output: { entryFileNames: 'auth.test.mjs' } },
  },
})
const result = spawnSync(process.execPath, ['--test', '.test-dist/auth.test.mjs'], { stdio: 'inherit' })
process.exit(result.status ?? 1)
