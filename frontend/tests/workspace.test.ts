import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createDownloadLink,
  formatBytes,
  listChunks,
  listDocuments,
  parseStatusColor,
  parseStatusLabel,
  retryParse,
  roleLabel,
  uploadDocument,
  visibilityLabel,
} from '../src/api/workspace'

/**
 * 空间与文档 API 客户端的契约测试。
 *
 * <h2>为什么这些断言值得写</h2>
 * 这一层是前端唯一"知道后端路径长什么样"的地方，而它的错误方式非常隐蔽：
 * 路径拼错、参数名写错、multipart 少一个字段名 —— 这些都不会在构建期暴露，
 * 只会在用户点下去的那一刻变成一个 404 或者"上传成功但库里没有这份文件"。
 * 这里断言的是**协议层的形状**（方法、路径、查询参数、表单字段名），
 * 不是实现细节：形状变了就是契约变了。
 *
 * <p>断言里刻意逐字写出完整路径，而不是复用被测代码里的常量 ——
 * 用同一个常量做期望值等于没测。
 */

const originalFetch = globalThis.fetch

/** 一次被拦截到的请求 */
interface Captured {
  url: string
  method: string
  contentType: string | null
  body: BodyInit | null | undefined
}

let captured: Captured[] = []

/**
 * 安装一个只记录、不真正发请求的 fetch。
 *
 * @param payload 响应体
 */
function stubFetch(payload: unknown): void {
  captured = []
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers)
    captured.push({
      url: String(input),
      method: init?.method ?? 'GET',
      contentType: headers.get('Content-Type'),
      body: init?.body,
    })
    return Response.json(payload ?? {})
  }) as typeof fetch
}

test.afterEach(() => {
  globalThis.fetch = originalFetch
})

test('listWorkspaces 之外的列表接口都把分页放在查询串里', async () => {
  stubFetch({ items: [], total: 0, page: 1, size: 20, totalPages: 0 })
  await listDocuments('ws1', 2, 10)
  await listChunks('ws1', 'doc1', 3, 50)

  assert.equal(captured[0]?.url, '/api/v1/workspaces/ws1/documents?page=2&size=10')
  // 分块的页码与页大小都要显式带上：后端有默认值，但"用默认值"与"要这一页"是两件事
  assert.equal(captured[1]?.url, '/api/v1/workspaces/ws1/documents/doc1/chunks?page=3&size=50')
  assert.equal(captured[0]?.method, 'GET')
})

test('上传走 multipart，字段名固定为 file，且不自己设置 Content-Type', async () => {
  stubFetch({ publicId: 'doc-new' })
  const file = new File(['# 标题\n正文'], 'notes.md', { type: 'text/markdown' })
  await uploadDocument('ws1', file)

  const request = captured[0]
  assert.equal(request?.url, '/api/v1/workspaces/ws1/documents')
  assert.equal(request?.method, 'POST')

  const body = request?.body
  assert.ok(body instanceof FormData, '请求体必须是 FormData')
  // 字段名是后端 MultipartFile 参数的绑定依据，写错的表现是"上传成功但没有文件"
  assert.equal(body.get('file'), file)
  assert.equal(body.getAll('file').length, 1)

  // **不设置** Content-Type：multipart 的边界由浏览器生成，手写一个会与请求体对不上
  assert.equal(request?.contentType, null)
})

test('下载链接是 POST，且路径里带空间与文档两个标识', async () => {
  stubFetch({ url: '/api/v1/document-downloads/token', direct: false, expiresAt: '2026-09-18T10:00:00Z' })
  const { data } = await createDownloadLink('ws1', 'doc1')

  assert.equal(captured[0]?.url, '/api/v1/workspaces/ws1/documents/doc1/download-link')
  assert.equal(captured[0]?.method, 'POST')
  assert.equal(data.direct, false)
})

test('重新解析是 POST 到 /parse，并且不带请求体', async () => {
  stubFetch({ publicId: 'doc1', parseStatus: 'PENDING' })
  await retryParse('ws1', 'doc1')

  assert.equal(captured[0]?.url, '/api/v1/workspaces/ws1/documents/doc1/parse')
  assert.equal(captured[0]?.method, 'POST')
  // 后端对重复调用是幂等的（任务只有一行），因此没有需要前端表达的参数
  assert.equal(captured[0]?.body, undefined)
})

test('formatBytes 用 1024 进制并切到正确的单位', () => {
  assert.equal(formatBytes(0), '0 B')
  assert.equal(formatBytes(1023), '1023 B')
  // 1024 恰好跨过第一档，是唯一会出现 "1.0 KiB" 与 "1024 B" 之争的位置
  assert.equal(formatBytes(1024), '1.0 KiB')
  assert.equal(formatBytes(1536), '1.5 KiB')
  // 10 以上不保留小数：上传上限附近的读数有意义，小数第二位没有
  assert.equal(formatBytes(10240), '10 KiB')
  assert.equal(formatBytes(10485760), '10 MiB')
})

test('状态与角色的中文标签是穷尽的', () => {
  assert.equal(parseStatusLabel('PENDING'), '等待解析')
  assert.equal(parseStatusLabel('PROCESSING'), '解析中')
  assert.equal(parseStatusLabel('READY'), '已就绪')
  assert.equal(parseStatusLabel('FAILED'), '解析失败')

  assert.equal(parseStatusColor('READY'), 'green')
  assert.equal(parseStatusColor('FAILED'), 'red')

  assert.equal(roleLabel('OWNER'), '拥有者')
  assert.equal(roleLabel('ADMIN'), '管理员')
  assert.equal(roleLabel('MEMBER'), '成员')

  assert.equal(visibilityLabel('TEAM'), '团队空间')
  assert.equal(visibilityLabel('PRIVATE'), '私有空间')
})
