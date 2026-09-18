import test, { beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../src/stores/auth'
import { get, setAuthBridge } from '../src/api/http'

const values = new Map<string, string>()
const originalFetch = globalThis.fetch
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
let pinia: ReturnType<typeof createPinia>
const accessKey = 'camphub.accessToken'
const refreshKey = 'camphub.refreshToken'
function apiError(status: number, code: number): Response {
  return Response.json({ code, message: 'test error', traceId: 'test-trace',
    timestamp: '2026-09-18T00:00:00Z', path: '/api/v1/users/me', details: [] }, { status })
}
function issued(): Response {
  return Response.json({ accessToken: 'new-access', refreshToken: 'new-refresh' })
}

beforeEach(() => {
  values.clear()
  values.set(accessKey, 'old-access')
  values.set(refreshKey, 'old-refresh')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: new EventTarget() })
  pinia = createPinia()
  setActivePinia(pinia)
})
afterEach(() => {
  disposePinia(pinia)
  setAuthBridge(null)
  globalThis.fetch = originalFetch
  for (const [key, descriptor] of [['window', originalWindow], ['localStorage', originalStorage]] as const) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else Reflect.deleteProperty(globalThis, key)
  }
})

for (const failure of ['network', 'rate-limit', 'server']) {
  test(`refresh ${failure} preserves credentials and reports the actual failure`, async () => {
    const auth = useAuthStore()
    globalThis.fetch = async (url) => {
      if (String(url).endsWith('/auth/refresh')) {
        if (failure === 'network') throw new TypeError('offline')
        return apiError(failure === 'rate-limit' ? 429 : 503, failure === 'rate-limit' ? 42900 : 50300)
      }
      return apiError(401, 40101)
    }
    await assert.rejects(() => get('/v1/users/me'), (error: any) =>
      failure === 'network' ? error.name === 'NetworkError' : error.status === (failure === 'rate-limit' ? 429 : 503))
    assert.equal(auth.accessToken, 'old-access')
    assert.equal(values.get(refreshKey), 'old-refresh')
  })
}

test('revoked refresh token clears the session', async () => {
  const auth = useAuthStore()
  globalThis.fetch = async (url) => apiError(401, String(url).endsWith('/auth/refresh') ? 40104 : 40101)
  await assert.rejects(() => get('/v1/users/me'))
  assert.equal(auth.isLoggedIn, false)
  assert.equal(values.has(refreshKey), false)
})

test('concurrent requests share one refresh and retry with the new access token', async () => {
  useAuthStore()
  let refreshes = 0
  globalThis.fetch = async (url, options) => {
    if (String(url).endsWith('/auth/refresh')) {
      refreshes++
      return issued()
    }
    if (new Headers(options?.headers).get('Authorization') === 'Bearer new-access') {
      return Response.json({ publicId: 'user' })
    }
    return apiError(401, 40101)
  }
  await Promise.all([get('/v1/users/me'), get('/v1/users/me')])
  assert.equal(refreshes, 1)
  assert.equal(values.get(refreshKey), 'new-refresh')
})

test('logout during refresh cannot resurrect the old session', async () => {
  const auth = useAuthStore()
  let finish!: (response: Response) => void
  const refreshResponse = new Promise<Response>((resolve) => { finish = resolve })
  let started!: () => void
  const refreshStarted = new Promise<void>((resolve) => { started = resolve })
  globalThis.fetch = async (url) => {
    if (String(url).endsWith('/auth/refresh')) { started(); return refreshResponse }
    if (String(url).endsWith('/auth/logout')) return new Response(null, { status: 204 })
    return apiError(401, 40101)
  }
  const request = assert.rejects(() => get('/v1/users/me'))
  await refreshStarted
  await auth.logout()
  finish(issued())
  await request
  assert.equal(auth.isLoggedIn, false)
  assert.equal(values.has(refreshKey), false)
})

test('credentials rotated by another tab are reused without replaying the old refresh token', async () => {
  useAuthStore()
  let refreshes = 0
  globalThis.fetch = async (url, options) => {
    if (String(url).endsWith('/auth/refresh')) { refreshes++; return issued() }
    if (new Headers(options?.headers).get('Authorization') === 'Bearer new-access') return Response.json({ ok: true })
    values.set(accessKey, 'new-access')
    values.set(refreshKey, 'new-refresh')
    return apiError(401, 40101)
  }
  await get('/v1/users/me')
  assert.equal(refreshes, 0)
})
