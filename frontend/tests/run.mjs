import { build } from 'vite'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

/*
  前端测试入口。
  ─────────────────────────────────────────────────────────────
  用项目已有的 Vite 把真实 TS 模块编译成 ESM，再由 Node 自带的测试运行器执行。
  刻意不引入 vitest 之类的框架：当前这一层的被测对象是纯逻辑与协议形状，
  node:test 已经够用，少一个工具就少一处要跟着主版本升级的配置。

  每个 `tests/*.test.ts` 单独作为一个构建入口，编译成同名产物后一次性交给
  `node --test`。逐文件而非打包成一个：某个用例文件里的模块级状态
  （例如 http 层的认证桥）不会泄漏到另一个文件里。
*/
const suites = ['auth', 'workspace']

await build({
  configFile: false,
  resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
  build: {
    ssr: true,
    outDir: '.test-dist',
    rollupOptions: {
      input: Object.fromEntries(
        suites.map((name) => [name, fileURLToPath(new URL(`../tests/${name}.test.ts`, import.meta.url))]),
      ),
      output: { entryFileNames: '[name].test.mjs' },
    },
  },
})

const files = suites.map((name) => `.test-dist/${name}.test.mjs`)
const result = spawnSync(process.execPath, ['--test', ...files], { stdio: 'inherit' })
process.exit(result.status ?? 1)
