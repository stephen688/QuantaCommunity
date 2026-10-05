import test from 'node:test'
import assert from 'node:assert/strict'

const session = await import('../src/utils/admin-session.js').catch(() => ({}))

function fixture() {
  const calls = []
  const store = {
    setSession: (v) => calls.push(['session', v]),
    setSecurityContext: (v) => calls.push(['security', v]),
    hasAnyManagementRole: () => true,
    clearSession: () => calls.push(['clear']),
  }
  const api = {
    loginWithPassword: async (v) => { calls.push(['password', v]); return { id: 7, token: 'test-only-session' } },
    login: async () => { throw new Error('Development login must not run') },
    getUserInfo: async () => ({ nickName: '管理员' }),
    getSecurityContext: async () => ({ roles: ['OPERATIONS_ADMIN'], authorities: ['EVENT_READ'] }),
    logout: async () => calls.push(['logout']),
  }
  return { calls, store, api }
}

test('password login uses the supplied credentials and loads real security context', async () => {
  assert.equal(typeof session.loginAdminSession, 'function', 'Password session workflow is missing')
  const f = fixture()
  await session.loginAdminSession({ ...f, username: 'admin', password: 'synthetic-input' })
  assert.deepEqual(f.calls[0], ['password', { username: 'admin', password: 'synthetic-input' }])
  assert.deepEqual(f.calls.find(([name]) => name === 'security')[1].roles, ['OPERATIONS_ADMIN'])
  assert.equal(f.calls.filter(([name]) => name === 'session').at(-1)[1].isAdmin, true)
})

test('security context failure clears the newly created local session', async () => {
  assert.equal(typeof session.loginAdminSession, 'function')
  const f = fixture()
  f.api.getSecurityContext = async () => { throw new Error('context unavailable') }
  await assert.rejects(session.loginAdminSession({ ...f, username: 'admin', password: 'synthetic-input' }), /context unavailable/)
  assert.deepEqual(f.calls.at(-1), ['clear'])
})

test('a non-management identity cannot remain logged into the admin portal', async () => {
  assert.equal(typeof session.loginAdminSession, 'function')
  const f = fixture()
  f.api.getSecurityContext = async () => ({ roles: ['BOT'], authorities: [] })
  await assert.rejects(session.loginAdminSession({ ...f, username: 'admin', password: 'synthetic-input' }), /管理权限/)
  assert.deepEqual(f.calls.at(-1), ['clear'])
})

test('development code login is rejected when the caller is a production build', async () => {
  assert.equal(typeof session.loginAdminSession, 'function')
  const f = fixture()
  await assert.rejects(session.loginAdminSession({ ...f, useDevCode: true, isDevelopment: false, code: 'test' }), /开发/)
  assert.equal(f.calls.some(([name]) => name === 'password'), false)
})

test('logout revokes the server session before clearing the local session', async () => {
  assert.equal(typeof session.logoutAdminSession, 'function', 'Server logout workflow is missing')
  const f = fixture()
  await session.logoutAdminSession(f)
  assert.deepEqual(f.calls, [['logout'], ['clear']])
})

test('a logout network failure keeps the local session so the user can retry', async () => {
  assert.equal(typeof session.logoutAdminSession, 'function')
  const f = fixture()
  f.api.logout = async () => { throw new Error('offline') }
  await assert.rejects(session.logoutAdminSession(f), /offline/)
  assert.equal(f.calls.length, 0)
})

test('logout with an already expired session still clears the local session', async () => {
  assert.equal(typeof session.logoutAdminSession, 'function')
  const f = fixture()
  f.api.logout = async () => { throw { response: { status: 401 } } }
  await session.logoutAdminSession(f)
  assert.deepEqual(f.calls, [['clear']])
})
