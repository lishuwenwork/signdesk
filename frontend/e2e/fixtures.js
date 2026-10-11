import { test as base, expect } from '@playwright/test'

export async function authenticate(request) {
  const response = await request.post('/api/auth/login', { data: { username: 'admin', password: '123456' } })
  expect(response.status(), 'isolated fixture login').toBe(200)
}

export async function authenticatedContext(playwright) {
  const request = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:18080' })
  try { await authenticate(request); return request }
  catch (e) { await request.dispose(); throw e }
}

export const test = base.extend({
  context: async ({ context }, use) => {
    await authenticate(context.request)
    await use(context)
  },
  request: async ({ request }, use) => {
    await authenticate(request)
    await use(request)
  },
})
export { expect }
