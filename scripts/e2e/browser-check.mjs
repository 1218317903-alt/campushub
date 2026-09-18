#!/usr/bin/env node
/**
 * CampusHub AI —— Phase 05 浏览器验收（空间 · 文档解析 · 跨用户越权）
 *
 * ============================================================================
 * 这个脚本要回答的问题
 * ============================================================================
 * "一个真实的人用真实浏览器，能不能把空间与文档这件事从头走完。"
 * 集成测试已经覆盖了接口层的每一条判定，但它们都不回答下面这些：
 *   · 上传之后界面到底有没有把「还在解析」这件事显示出来
 *   · 解析完成后，用户能不能自己看出解析结果对不对（分块顺序、标题路径、正文）
 *   · 下载链接在浏览器里点开是否真的拿得到字节
 *   · 别人的空间在浏览器里是不是真的「看不见」（而不是只在下拉框里被隐藏）
 * 这些只能在真实浏览器里问，因此这里用 CDP 驱动本机 Chrome 走一遍。
 *
 * ============================================================================
 * 为什么自己驱动 CDP，而不是装一套 Playwright
 * ============================================================================
 * 本机已经有 Chrome，而 Playwright 需要另外下载一份浏览器（数百 MB）。
 * 这个脚本用到的能力只有「导航、求值、取元素、设文件、截图」六件事，
 * Node 22 自带 WebSocket 客户端，因此不需要任何依赖 —— 一个零依赖的脚本
 * 比一个需要维护版本的测试框架更适合"阶段性验收"这个用途。
 * 需要更复杂的交互（拖拽、多标签时序）时再引入框架，而不是现在先引入。
 *
 * ============================================================================
 * 前置条件
 * ============================================================================
 *   1. 后端在跑：  make run          （注意本机需显式 --server.port=8080，见 docs/11 §7.7）
 *   2. 前端在跑：  make fe-dev       （Vite 5173，/api 代理到 8080）
 *   3. 演示数据已注入（local profile 默认开启），提供 demo01 / demo02 两个账号
 *
 * ============================================================================
 * 用法
 * ============================================================================
 *   node scripts/e2e/browser-check.mjs
 *   node scripts/e2e/browser-check.mjs --base-url http://127.0.0.1:5173
 *   node scripts/e2e/browser-check.mjs --headful          # 想看着它跑
 *   node scripts/e2e/browser-check.mjs --password '...'   # 演示口令非默认值时
 *
 * 退出码：全部通过为 0，任一步失败为 1（失败项会打印出来）。
 * 截图落在 target/e2e/ 下（构建产物目录，不入库）。
 */

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------

const args = process.argv.slice(2)

/**
 * 取一个命令行参数的值。
 *
 * @param {string} name 参数名（含 `--`）
 * @param {string} fallback 默认值
 * @returns {string} 取值
 */
function arg(name, fallback) {
  const index = args.indexOf(name)
  if (index === -1) {
    return fallback
  }
  const value = args[index + 1]
  if (value === undefined || value.startsWith('--')) {
    return fallback
  }
  return value
}

const BASE_URL = arg('--base-url', 'http://127.0.0.1:5173')
const CHROME = arg('--chrome', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
const PASSWORD = arg('--password', 'CampusHub-Demo-2026')
const HEADFUL = args.includes('--headful')
const DEBUG_PORT = Number(arg('--debug-port', '9222'))
const SHOT_DIR = 'target/e2e'
const STEP_TIMEOUT_MS = 20_000

// 两个演示账号。demo01 是空间拥有者，demo02 用来验证「非成员看不到」。
const OWNER = { username: 'demo01', nickname: '陈知远' }
const OUTSIDER = { username: 'demo02', nickname: '林一鸣' }

// 上传用的样本文档：两级标题，用来验证「分块带上标题路径」这件事真的成立了。
const SAMPLE_NAME = 'phase05-验收样本.md'
const SAMPLE_MARKDOWN = `# 空间文档规范

这一段落用来确认解析后的正文可以被原样读回。

## 支持的格式

纯文本、Markdown 与 PDF 会被解析成块，其余类型可以保存与下载。

### 分块规则

按标题切分，超长的段落会被二级切分。
`

// ---------------------------------------------------------------------------
// 断言与结果记录
// ---------------------------------------------------------------------------

let passed = 0
const failures = []

/**
 * 执行一个步骤并记录结果。
 *
 * @param {string} name 步骤名
 * @param {() => Promise<void>} body 步骤体，失败时抛异常
 */
async function step(name, body) {
  try {
    await body()
    passed += 1
    console.log(`ok   ${name}`)
  } catch (error) {
    failures.push({ name, message: error?.message ?? String(error) })
    console.log(`FAIL ${name}\n     ${error?.message ?? error}`)
  }
}

/**
 * 断言为真，否则抛错。
 *
 * @param {unknown} condition 条件
 * @param {string} message 失败信息
 */
function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

// ---------------------------------------------------------------------------
// CDP 客户端
// ---------------------------------------------------------------------------

/**
 * 极简 Chrome DevTools Protocol 客户端。
 *
 * <p>只实现这个脚本需要的东西：按 id 配对请求与响应、按 sessionId 分派到标签页。
 * 不做事件订阅的抽象 —— 事件交给调用方按方法名注册，避免为"通用性"多写一层。
 */
class Cdp {
  #socket
  #nextId = 1
  #pending = new Map()
  #listeners = new Map()

  constructor(socket) {
    this.#socket = socket
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data)
      if (message.id !== undefined) {
        const entry = this.#pending.get(message.id)
        if (!entry) {
          return
        }
        this.#pending.delete(message.id)
        if (message.error) {
          entry.reject(new Error(`${message.error.message} (${entry.method})`))
        } else {
          entry.resolve(message.result ?? {})
        }
        return
      }
      const handlers = this.#listeners.get(message.method)
      if (handlers) {
        for (const handler of handlers) {
          handler(message.params, message.sessionId)
        }
      }
    })
  }

  /**
   * 连接一个 CDP 端点。
   *
   * @param {string} url WebSocket 地址
   * @returns {Promise<Cdp>} 客户端
   */
  static connect(url) {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(url)
      socket.addEventListener('open', () => resolve(new Cdp(socket)))
      socket.addEventListener('error', () => reject(new Error(`无法连接 CDP 端点：${url}`)))
    })
  }

  /**
   * 发一条命令。
   *
   * @param {string} method 方法名
   * @param {object} params 参数
   * @param {string=} sessionId 会话（标签页）
   * @returns {Promise<object>} 返回结果
   */
  send(method, params = {}, sessionId) {
    const id = this.#nextId++
    const payload = sessionId ? { id, method, params, sessionId } : { id, method, params }
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject, method })
      this.#socket.send(JSON.stringify(payload))
    })
  }

  /**
   * 注册一个事件监听。
   *
   * @param {string} method 事件名
   * @param {(params: object, sessionId: string) => void} handler 处理函数
   */
  on(method, handler) {
    const handlers = this.#listeners.get(method) ?? []
    handlers.push(handler)
    this.#listeners.set(method, handlers)
  }

  /** 关闭连接 */
  close() {
    this.#socket.close()
  }
}

// ---------------------------------------------------------------------------
// 页面操作
// ---------------------------------------------------------------------------

/** 一个标签页上的操作集合 */
class Page {
  /**
   * @param {Cdp} cdp 客户端
   * @param {string} sessionId 标签页会话
   */
  constructor(cdp, sessionId) {
    this.cdp = cdp
    this.sessionId = sessionId
  }

  /**
   * 在页面里求值。
   *
   * @param {string} expression 表达式（会被包进 async IIFE，可取 await 的结果）
   * @returns {Promise<any>} 结果
   */
  async evaluate(expression) {
    const result = await this.cdp.send(
      'Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true },
      this.sessionId,
    )
    if (result.exceptionDetails) {
      throw new Error(`页面内求值失败：${result.exceptionDetails.text} ${expression.slice(0, 80)}`)
    }
    return result.result?.value
  }

  /**
   * 导航到某个路径（相对 base-url）。
   *
   * @param {string} path 路径
   */
  async goto(path) {
    const url = path.startsWith('http') ? path : `${BASE_URL}${path}`
    await this.cdp.send('Page.navigate', { url }, this.sessionId)
  }

  /**
   * 等待条件成立。
   *
   * @param {string} expression 返回布尔值的表达式
   * @param {string} label 失败时用于说明的描述
   * @param {number=} timeoutMs 超时
   */
  async waitFor(expression, label, timeoutMs = STEP_TIMEOUT_MS) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      if (await this.evaluate(expression)) {
        return
      }
      if (Date.now() > deadline) {
        const text = await this.text()
        throw new Error(`等待超时：${label}\n     当前页面文字：${text.slice(0, 300).replace(/\s+/g, ' ')}`)
      }
      await new Promise((resolve) => setTimeout(resolve, 150))
    }
  }

  /** 当前地址 */
  url() {
    return this.evaluate('location.pathname + location.search')
  }

  /** 页面可见文字 */
  text() {
    return this.evaluate('document.body.innerText')
  }

  /**
   * 等待页面文字包含某段内容。
   *
   * @param {string} needle 期待的片段
   * @param {string=} label 说明
   */
  async waitForText(needle, label = `页面出现「${needle}」`) {
    const literal = JSON.stringify(needle)
    await this.waitFor(`document.body.innerText.includes(${literal})`, label)
  }

  /**
   * 按可见文字点击一个元素。
   *
   * @param {string} selector CSS 选择器
   * @param {string} text 文字片段
   */
  async clickText(selector, text) {
    const ok = await this.evaluate(`(() => {
      const nodes = [...document.querySelectorAll(${JSON.stringify(selector)})]
      const hit = nodes.find((node) => node.innerText.includes(${JSON.stringify(text)}))
      if (!hit) return false
      hit.click()
      return true
    })()`)
    assert(ok, `找不到可点击元素：${selector} 内含「${text}」`)
  }

  /**
   * 点击一个元素（第一个匹配项）。
   *
   * @param {string} selector CSS 选择器
   */
  async click(selector) {
    const ok = await this.evaluate(`(() => {
      const hit = document.querySelector(${JSON.stringify(selector)})
      if (!hit) return false
      hit.click()
      return true
    })()`)
    assert(ok, `找不到可点击元素：${selector}`)
  }

  /**
   * 往输入框写值。
   *
   * <p>直接用 `el.value = x` 对 Vue 是无效的：v-model 监听的是 input 事件，
   * 而程序化赋值不会触发它。这里用原型上的 setter 写值，再手动派发 input 事件 ——
   * 这才是"像一个真的在用键盘的人"的最小模仿。
   *
   * @param {string} selector CSS 选择器
   * @param {string} value 值
   */
  async fill(selector, value) {
    const ok = await this.evaluate(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)})
      if (!el) return false
      const proto = el instanceof HTMLTextAreaElement
        ? HTMLTextAreaElement.prototype
        : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)})
      el.dispatchEvent(new Event('input', { bubbles: true }))
      el.dispatchEvent(new Event('change', { bubbles: true }))
      return true
    })()`)
    assert(ok, `找不到输入框：${selector}`)
  }

  /**
   * 给 `input[type=file]` 塞一个真实文件。
   *
   * <p>这是上传流程里唯一无法用 JS 模拟的一步 —— 浏览器不允许脚本设置文件选择器的值，
   * 因此必须走 CDP 的 DOM.setFileInputFiles（它等价于用户在选择器里选中了文件）。
   *
   * @param {string} selector CSS 选择器
   * @param {string} filePath 文件绝对路径
   */
  async setFileInput(selector, filePath) {
    const { root } = await this.cdp.send('DOM.getDocument', { depth: -1 }, this.sessionId)
    const { nodeId } = await this.cdp.send(
      'DOM.querySelector',
      { nodeId: root.nodeId, selector },
      this.sessionId,
    )
    assert(nodeId !== 0, `找不到文件输入框：${selector}`)
    await this.cdp.send('DOM.setFileInputFiles', { files: [filePath], nodeId }, this.sessionId)
  }

  /**
   * 截图存盘。
   *
   * @param {string} name 文件名（不含扩展名）
   */
  async screenshot(name) {
    const { data } = await this.cdp.send('Page.captureScreenshot', { format: 'png' }, this.sessionId)
    writeFileSync(join(SHOT_DIR, `${name}.png`), Buffer.from(data, 'base64'))
  }

  /** 回到顶部（滚动位置会影响截图与文字可见性） */
  scrollTop() {
    return this.evaluate('window.scrollTo(0, 0), true')
  }
}

// ---------------------------------------------------------------------------
// 浏览器生命周期
// ---------------------------------------------------------------------------

/**
 * 启动 Chrome 并打开一个空白标签页。
 *
 * @returns {Promise<{cdp: Cdp, page: Page, profileDir: string, kill: () => void}>} 句柄
 */
async function launch() {
  const profileDir = mkdtempSync(join(tmpdir(), 'camphub-e2e-'))
  const flags = [
    HEADFUL ? '--start-maximized' : '--headless=new',
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${profileDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-gpu',
    // 关闭「恢复上次会话」气泡：它会在截图上盖住页面顶部
    '--hide-crash-restore-bubble',
    'about:blank',
  ]
  const child = spawn(CHROME, flags, { stdio: 'ignore' })

  const version = await pollJson(`http://127.0.0.1:${DEBUG_PORT}/json/version`, 15_000)
  const cdp = await Cdp.connect(version.webSocketDebuggerUrl)

  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' })
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true })

  const page = new Page(cdp, sessionId)
  await cdp.send('Page.enable', {}, sessionId)
  await cdp.send('Runtime.enable', {}, sessionId)
  await cdp.send('DOM.enable', {}, sessionId)
  // 视口给足：默认 800×600 会让表格与标签页在截图上被裁掉
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 1,
    mobile: false,
  }, sessionId)

  return {
    cdp,
    page,
    profileDir,
    kill: () => {
      child.kill('SIGTERM')
    },
  }
}

/**
 * 轮询一个返回 JSON 的地址，直到它可用。
 *
 * @param {string} url 地址
 * @param {number} timeoutMs 超时
 * @returns {Promise<any>} 解析后的 JSON
 */
async function pollJson(url, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let lastError = null
  for (;;) {
    try {
      const response = await fetch(url)
      if (response.ok) {
        return await response.json()
      }
      lastError = new Error(`HTTP ${response.status}`)
    } catch (error) {
      lastError = error
    }
    if (Date.now() > deadline) {
      throw new Error(`等待 ${url} 超时：${lastError?.message ?? ''}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
}

// ---------------------------------------------------------------------------
// 业务动作
// ---------------------------------------------------------------------------

/**
 * 通过登录页登录。
 *
 * @param {Page} page 标签页
 * @param {string} username 登录名
 * @param {string} nickname 期待在顶栏看到的昵称
 */
async function login(page, username, nickname) {
  await page.goto('/login')
  await page.waitFor(`!!document.querySelector('input[placeholder="用户名或邮箱，忽略大小写"]')`, '登录页渲染完成')
  await page.fill('input[placeholder="用户名或邮箱，忽略大小写"]', username)
  await page.fill('input[placeholder="密码"]', PASSWORD)
  await page.click('.auth__submit')
  // 顶栏出现昵称才算登录成功：地址栏变化可能与渲染不是同一时刻
  await page.waitFor(`document.body.innerText.includes(${JSON.stringify(nickname)})`, `登录后顶栏出现「${nickname}」`)
}

/**
 * 退出登录。
 *
 * @param {Page} page 标签页
 */
async function logout(page) {
  await page.clickText('button', '登出')
  await page.waitFor(`document.body.innerText.includes('登录')`, '回到未登录状态')
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

mkdirSync(SHOT_DIR, { recursive: true })
const samplePath = join(mkdtempSync(join(tmpdir(), 'camphub-sample-')), SAMPLE_NAME)
writeFileSync(samplePath, SAMPLE_MARKDOWN, 'utf8')

let browser = null
/** 空间标识，跨步骤使用 */
let spaceId = ''
/** 邀请码，跨步骤使用 */
let inviteCode = ''

try {
  browser = await launch()
  const { page } = browser

  // -------------------------------------------------------------------------
  // 1. 站点可达
  // -------------------------------------------------------------------------
  await step('前端可访问且社区页渲染完成', async () => {
    await page.goto('/community')
    await page.waitForText('校园社区', '社区页出现标题')
  })

  // -------------------------------------------------------------------------
  // 2. 登录
  // -------------------------------------------------------------------------
  await step(`演示账号 ${OWNER.username} 登录`, async () => {
    await login(page, OWNER.username, OWNER.nickname)
  })

  // -------------------------------------------------------------------------
  // 3. 空间列表与创建
  // -------------------------------------------------------------------------
  await step('「我的空间」页面可用', async () => {
    await page.goto('/spaces')
    await page.waitForText('我的空间', '空间列表页出现标题')
    await page.screenshot('01-spaces-list')
  })

  await step('创建一个空间并自动进入', async () => {
    await page.clickText('button', '新建空间')
    await page.waitFor(`!!document.querySelector('input[placeholder="例如：操作系统课程小组"]')`, '创建表单展开')
    const name = `浏览器验收空间-${Date.now() % 100000}`
    await page.fill('input[placeholder="例如：操作系统课程小组"]', name)
    await page.fill('textarea', '由 scripts/e2e/browser-check.mjs 创建，用于验收 Phase 05。')
    await page.clickText('button', '创建并进入')
    await page.waitFor(`/^\\/spaces\\/[0-9a-zA-Z]+$/.test(location.pathname)`, '跳转到空间详情')
    await page.waitForText(name, '空间详情显示空间名')
    spaceId = (await page.url()).split('/').pop()
    assert(spaceId && spaceId.length > 0, '未能取到空间标识')
    await page.screenshot('02-space-detail')
  })

  // -------------------------------------------------------------------------
  // 4. 上传 → 解析 → 分块
  // -------------------------------------------------------------------------
  // 空间地址在多个步骤里都要用。步骤之间**不假设"上一步停在哪一页"** ——
  // 一个前置步骤失败后，后面的步骤如果依赖它留下的页面状态，就会一起报错，
  // 于是"1 个真的问题"会在报告里看起来像"7 个问题"。
  const spacePath = `/spaces/${spaceId}`

  /**
   * 判断文档列表里那行文档是否已经显示出解析状态。
   *
   * <p>三条状态里任意一条都算：上传接口返回时必然是 PENDING，
   * 但 worker 可能在这个断言与上传响应之间就把它推到了 READY。
   */
  const documentRowHasStatus = `(() => {
    const row = [...document.querySelectorAll('article')]
      .find((item) => item.innerText.includes(${JSON.stringify(SAMPLE_NAME)}))
    return row ? /等待解析|解析中|已就绪/.test(row.innerText) : false
  })()`

  await step('上传一份 Markdown，列表里出现解析状态', async () => {
    await page.waitForText('可直接解析', '文档面板渲染完成')
    await page.setFileInput('input[type=file]', samplePath)
    await page.waitFor(`document.body.innerText.includes(${JSON.stringify(SAMPLE_NAME)})`, '选中的文件名回显')
    await page.clickText('button', '上传')
    // 等到**列表行**出现状态，而不是等到文件名出现在页面上：
    // 后者在点击上传之前就已经成立（"已选中的文件"那一行），等于什么都没等到
    await page.waitFor(documentRowHasStatus, '文档行出现解析状态')
  })

  await step('解析在浏览器里自行推进到「已就绪」并显示分块数', async () => {
    await page.waitFor(
      `(() => {
         const rows = [...document.querySelectorAll('article')]
         const row = rows.find((r) => r.innerText.includes(${JSON.stringify(SAMPLE_NAME)}))
         return row ? row.innerText.includes('已就绪') && /\\d+ 块/.test(row.innerText) : false
       })()`,
      '文档解析完成并显示分块数',
      60_000,
    )
    await page.screenshot('03-document-ready')
  })

  await step('文档详情页展示分块、标题路径与正文', async () => {
    await page.goto(spacePath)
    await page.waitForText(SAMPLE_NAME, '空间文档列表渲染完成')
    await page.clickText('a', '分块')
    await page.waitForText('解析结果', '文档详情页渲染完成')
    await page.waitForText('#0', '出现第一个分块')
    await page.waitForText('#1', '出现第二个分块')

    const text = await page.text()
    assert(text.includes('已就绪'), '文档详情页没有显示「已就绪」')
    // 标题路径：两级标题都必须出现在分块里。分块丢掉标题会让"这一块讲的是什么"
    // 在检索阶段无从判断，而这正是分块存在的意义。
    assert(text.includes('空间文档规范'), '分块里没有出现一级标题（标题路径丢失）')
    assert(text.includes('支持的格式'), '分块里没有出现二级标题')
    // 分块数从「解析结果」里读，≥ 2 才说明真的按标题切开了
    const chunkCount = Number((text.match(/分块数\s*(\d+)/) ?? [])[1] ?? '0')
    assert(chunkCount >= 2, `分块数不合理：${chunkCount}（样本含两级标题，不可能只有一块）`)
    await page.screenshot('04-document-chunks')
  })

  await step('下载链接可直接取到字节，且内容与上传的一致', async () => {
    await page.clickText('button', '获取下载链接')
    await page.waitFor(
      `document.body.innerText.includes('/api/v1/document-downloads/') || document.body.innerText.includes('X-Amz-Signature')`,
      '出现下载链接',
    )
    const url = await page.evaluate(`(() => {
      const el = [...document.querySelectorAll('.doc__link-url')][0]
      return el ? el.innerText.trim() : ''
    })()`)
    assert(url.length > 0, '没能从页面上取到链接地址')

    // 在页面里取一次，**不带任何凭据**：链接自身携带授权，这正是"短期直链"的定义
    const result = await page.evaluate(`(async () => {
      const response = await fetch(${JSON.stringify(url)}, { credentials: 'omit' })
      const body = await response.text()
      return { status: response.status, body: body.slice(0, 400), disposition: response.headers.get('content-disposition') }
    })()`)
    assert(result.status === 200, `链接返回 ${result.status}`)
    assert(result.body.includes('空间文档规范'), `取回的内容不是这份文档：${result.body.slice(0, 120)}`)
    assert(
      (result.disposition ?? '').includes('attachment'),
      `响应头缺少 attachment（内联渲染用户上传的内容是 XSS 的入口）：${result.disposition}`,
    )
  })

  // -------------------------------------------------------------------------
  // 5. 协作笔记
  // -------------------------------------------------------------------------
  /**
   * 回到空间详情页并切到某个标签页。
   *
   * <p>判断"是否已经在空间详情页"用的是**完整路径**而不是前缀：
   * 文档详情页的路径是 `/spaces/<id>/documents/<docId>`，它同样以空间地址开头，
   * 前缀比较会误判成"已经在空间页"，于是接下来找标签栏必然失败。
   *
   * @param {string} tab 标签页标题
   */
  async function openTab(tab) {
    const onSpacePage = `/^\\/spaces\\/[^/]+$/.test(location.pathname)`
    if (!(await page.evaluate(onSpacePage))) {
      await page.goto(spacePath)
    }
    // 类名以组件实际渲染出来的为准（Arco 2.57 是 .arco-tabs-tab）：
    // 样式表里还留着 header-title 这类历史类名，照着样式表找不到元素
    await page.waitFor(`!!document.querySelector('.arco-tabs-tab')`, '空间详情标签栏渲染完成')
    await page.clickText('.arco-tabs-tab', tab)
  }

  await step('协作笔记可新建并在浏览器里渲染成 HTML', async () => {
    await openTab('协作笔记')
    await page.waitForText('新建笔记', '笔记标签页渲染完成')
    await page.clickText('button', '新建笔记')
    await page.waitFor(`!!document.querySelector('input[placeholder="笔记标题"]')`, '笔记编辑表单出现')
    await page.fill('input[placeholder="笔记标题"]', '验收笔记')
    await page.fill('textarea', '这是**加粗**的正文，用来确认服务端渲染生效。')
    await page.clickText('button', '创建')
    await page.waitForText('加粗', '笔记正文渲染完成')
    // 加粗说明服务端确实渲染了 Markdown，而不是把原文当纯文本吐回来
    const hasStrong = await page.evaluate(
      `[...document.querySelectorAll('.markdown-body strong')].some((el) => el.innerText.includes('加粗'))`,
    )
    assert(hasStrong, '正文里没有出现 <strong>，Markdown 渲染可能没生效')
    await page.screenshot('05-note')
  })

  // -------------------------------------------------------------------------
  // 6. 邀请
  // -------------------------------------------------------------------------
  await step('发出定向邀请并取到邀请码', async () => {
    await openTab('邀请')
    // 等的是这一页特有的输入框，而不是"用邀请码加入" —— 后者在空间**列表页**上，
    // 于是这条等待永远不会成立（等待一个不属于这一页的文案，等于在等超时）
    await page.waitFor(`!!document.querySelector('input[placeholder="对方的登录名"]')`, '邀请标签页渲染完成')
    await page.fill('input[placeholder="对方的登录名"]', OUTSIDER.username)
    await page.clickText('button', '邀请')
    await page.waitFor(`document.body.innerText.includes(${JSON.stringify(OUTSIDER.nickname)})`, '邀请列表出现被邀请人')
    inviteCode = await page.evaluate(`(() => {
      const el = document.querySelector('.space__code')
      return el ? el.innerText.trim() : ''
    })()`)
    assert(inviteCode.length > 0, '没能从页面上取到邀请码')
    await page.screenshot('06-invites')
  })

  await step('成员列表把拥有者标为「拥有者」', async () => {
    await openTab('成员')
    await page.waitForText('拥有者', '成员列表渲染完成')
    const text = await page.text()
    assert(text.includes(OWNER.nickname), '成员列表里没有拥有者账号')
  })

  // -------------------------------------------------------------------------
  // 7. 跨用户：非成员看不到这个空间
  // -------------------------------------------------------------------------
  await step(`非成员（${OUTSIDER.username}）直接访问空间地址得到 404 文案`, async () => {
    await logout(page)
    await login(page, OUTSIDER.username, OUTSIDER.nickname)
    await page.goto(spacePath)
    await page.waitForText('空间不存在，或者你不是它的成员', '非成员看到不可见提示')
    const text = await page.text()
    // 关键：连空间名都不该出现 —— "不可见"必须是不可见，而不是"看得见但点不进去"
    assert(!text.includes('浏览器验收空间'), '非成员看到了空间名，越权可见')
    await page.screenshot('07-outsider-404')
  })

  await step('非成员的「我的空间」里没有这个空间', async () => {
    await page.goto('/spaces')
    await page.waitForText('我的空间', '空间列表渲染完成')
    const text = await page.text()
    assert(!text.includes('浏览器验收空间'), '别人的空间出现在了我的空间列表里')
    await page.screenshot('08-outsider-list')
  })

  // -------------------------------------------------------------------------
  // 8. 兑换邀请后成为成员
  // -------------------------------------------------------------------------
  await step('用邀请码加入后可以进入空间，并能在成员列表里看到自己', async () => {
    // 没有邀请码就没什么可验的：显式失败，而不是让"请填入邀请码"这条页面提示
    // 被当成一个业务缺陷
    assert(inviteCode.length > 0, '上一步没能取到邀请码，无法继续')
    await page.fill('input[placeholder="粘贴邀请码"]', inviteCode)
    await page.clickText('button', '加入')
    await page.waitFor(`location.pathname === ${JSON.stringify(spacePath)}`, '兑换后进入空间详情')
    await page.waitForText('浏览器验收空间', '成员看到空间名')
    await page.clickText('.arco-tabs-tab', '成员')
    await page.waitFor(`document.body.innerText.includes(${JSON.stringify(OUTSIDER.nickname)})`, '成员列表出现新成员')
    await page.screenshot('09-joined')
  })

  await step('成员可以读到自己没有上传的文档及其分块', async () => {
    await openTab('文档')
    await page.waitForText(SAMPLE_NAME, '成员看到空间内的文档')
    await page.clickText('a', '分块')
    await page.waitForText('#0', '成员看到分块内容')
    const text = await page.text()
    assert(text.includes('空间文档规范'), '成员读到的分块内容不完整')
  })

  await step('伪造的空间与文档标识一律不可见', async () => {
    // 路径里同时带空间与文档两个标识，"拿着别人的标识去试"也能被第二层防线拦住：
    // 响应形状与"不存在"完全一致，因此外界无法用它来判断某个标识是否真实存在
    await page.goto(`/spaces/${'A'.repeat(22)}/documents/${'B'.repeat(22)}`)
    await page.waitForText('文档不存在或你没有访问权限', '伪造标识得到不可见提示')
  })
} finally {
  if (browser) {
    browser.cdp.close()
    browser.kill()
  }
  rmSync(samplePath, { force: true })
}

// ---------------------------------------------------------------------------
// 汇总
// ---------------------------------------------------------------------------

console.log('')
if (failures.length === 0) {
  console.log(`全部通过：${passed} 项。截图见 ${SHOT_DIR}/`)
  process.exit(0)
}
console.log(`通过 ${passed} 项，失败 ${failures.length} 项：`)
for (const failure of failures) {
  console.log(`  · ${failure.name}`)
}
process.exit(1)
