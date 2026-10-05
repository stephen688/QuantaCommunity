import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { compileScript, parse } from 'vue/compiler-sfc'
import { createRenderer } from 'vue'

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const generatedComponentPath = path.join(packageRoot, '.user-list-race-generated.mjs')
const stubModulePath = path.join(packageRoot, '.user-list-race-stubs.mjs')
const componentStubPath = path.join(packageRoot, '.user-list-race-components.mjs')

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

async function loadUserListComponent(control) {
  globalThis.__userListRaceControl = control
  const sourcePath = path.join(packageRoot, 'src/views/user/UserList.vue')
  const source = await readFile(sourcePath, 'utf8')
  const { descriptor, errors } = parse(source, { filename: sourcePath })
  assert.deepEqual(errors, [], 'UserList.vue must remain a valid SFC')
  const compiled = compileScript(descriptor, { id: 'user-list-race-test' })
  const code = compiled.content
    .replaceAll("from 'element-plus'", "from './.user-list-race-stubs.mjs'")
    .replaceAll("from '@element-plus/icons-vue'", "from './.user-list-race-components.mjs'")
    .replaceAll("from '../../components/PageHeader.vue'", "from './.user-list-race-components.mjs'")
    .replaceAll("from '../../components/TableEmptyState.vue'", "from './.user-list-race-components.mjs'")
    .replaceAll("from '../../composables/usePermission'", "from './.user-list-race-stubs.mjs'")
    .replaceAll("from '../../api/user'", "from './.user-list-race-stubs.mjs'")
    .replaceAll("from '../../api/adminRole'", "from './.user-list-race-stubs.mjs'")
    .replaceAll("from '../../router'", "from './.user-list-race-stubs.mjs'")
    .replaceAll("from '../../utils/admin-role.js'", "from './src/utils/admin-role.js'")

  await writeFile(stubModulePath, `
const c = globalThis.__userListRaceControl
export const ElMessage = { warning() {}, success() {}, error() {} }
export const ElMessageBox = { confirm: async () => undefined }
export const userApi = {
  page: async () => ({ records: [], total: 0 }),
  getById: (id) => c.details.get(String(id)),
  ban: async () => undefined,
  unban: async () => undefined,
}
export const adminRoleApi = {
  getUserRoles: (id) => {
    c.roleCalls.push(String(id))
    return c.roles.get(String(id))
  },
  grant: async () => undefined,
  revoke: async () => undefined,
}
const userStore = { userId: '999', clearSession() {} }
export function usePermission() {
  return { hasAuthority: () => true, userStore }
}
export default {
  currentRoute: { value: { fullPath: '/users' } },
  replace: async () => undefined,
}
`)
  await writeFile(componentStubPath, `
const Stub = { setup: () => () => null }
export const User = Stub
export default Stub
`)
  await writeFile(generatedComponentPath, code)
  const module = await import(`${pathToFileURL(generatedComponentPath).href}?t=${Date.now()}`)
  return module.default
}

function createHostRenderer() {
  return createRenderer({
    patchProp(node, key, _previous, next) {
      node.props[key] = next
    },
    insert(node, parent, anchor) {
      node.parent = parent
      if (!parent.children) parent.children = []
      if (anchor) {
        const index = parent.children.indexOf(anchor)
        parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
      } else {
        parent.children.push(node)
      }
    },
    remove(node) {
      const index = node.parent?.children?.indexOf(node) ?? -1
      if (index >= 0) node.parent.children.splice(index, 1)
    },
    createElement(type) {
      return { type, props: {}, children: [], parent: null }
    },
    createText(text) {
      return { type: '#text', text, parent: null }
    },
    createComment(text) {
      return { type: '#comment', text, parent: null }
    },
    setText(node, text) {
      node.text = text
    },
    setElementText(node, text) {
      node.children = [{ type: '#text', text, parent: node }]
    },
    parentNode(node) {
      return node.parent
    },
    nextSibling(node) {
      const siblings = node.parent?.children || []
      return siblings[siblings.indexOf(node) + 1] || null
    },
    querySelector() {
      return null
    },
    setScopeId() {},
    cloneNode(node) {
      return { ...node, props: { ...node.props }, children: [...node.children] }
    },
    insertStaticContent(content, parent, anchor) {
      const node = { type: '#static', text: content, parent: null }
      node.parent = parent
      this.insert(node, parent, anchor)
      return [node, node]
    },
  })
}

test('UserList ignores late detail and role responses from older drawer requests', async () => {
  const control = {
    details: new Map(),
    roles: new Map(),
    roleCalls: [],
  }
  const UserList = await loadUserListComponent(control)
  const renderer = createHostRenderer()
  const root = { type: 'root', props: {}, children: [], parent: null }
  const app = renderer.createApp(UserList)
  for (const name of [
    'el-input', 'el-form-item', 'el-option', 'el-select', 'el-button', 'el-form',
    'el-card', 'el-skeleton', 'el-table-column', 'el-avatar', 'el-tag',
    'el-popconfirm', 'el-table', 'el-pagination', 'el-image', 'el-icon',
    'el-descriptions-item', 'el-descriptions', 'el-drawer',
  ]) {
    app.component(name, { setup: () => () => null })
  }
  app.directive('loading', {})
  app.mount(root)

  try {
    const state = root._vnode.component.setupState
    const detailOne = deferred()
    const rolesOne = deferred()
    const detailTwo = deferred()
    const rolesTwo = deferred()
    control.details.set('1', detailOne.promise)
    control.roles.set('1', rolesOne.promise)
    control.details.set('2', detailTwo.promise)
    control.roles.set('2', rolesTwo.promise)

    const firstOpen = state.openDetail({ id: 1 })
    await settle()
    detailOne.resolve({ id: 1, nickName: '旧详情' })
    await settle()
    assert.deepEqual(control.roleCalls, ['1'])

    const secondOpen = state.openDetail({ id: 2 })
    await settle()
    detailTwo.resolve({ id: 2, nickName: '新详情' })
    await settle()
    assert.deepEqual(state.detail, { id: 2, nickName: '新详情', roles: null })
    assert.equal(state.roleReadState, 'loading')
    assert.equal(state.roleMutationDisabled('SUPER_ADMIN'), true)

    rolesOne.resolve(['SUPER_ADMIN'])
    await firstOpen
    assert.deepEqual(state.detail, { id: 2, nickName: '新详情', roles: null })
    assert.equal(state.roleReadState, 'loading')
    assert.equal(state.roleMutationDisabled('SUPER_ADMIN'), true)

    rolesTwo.resolve(['OPERATIONS_ADMIN'])
    await secondOpen
    assert.equal(state.detail.id, 2)
    assert.equal(state.detail.nickName, '新详情')
    assert.deepEqual(state.detail.roles, ['OPERATIONS_ADMIN'])
    assert.equal(state.roleReadState, 'ready')
    assert.equal(state.roleReadError, '')

    const detailThree = deferred()
    const detailFour = deferred()
    control.details.set('3', detailThree.promise)
    control.details.set('4', detailFour.promise)
    control.roles.set('4', Promise.resolve([]))
    const thirdOpen = state.openDetail({ id: 3 })
    await settle()
    const fourthOpen = state.openDetail({ id: 4 })
    detailFour.resolve({ id: 4, nickName: '当前详情' })
    await fourthOpen
    detailThree.resolve({ id: 3, nickName: '迟到详情' })
    await thirdOpen
    assert.equal(state.detail.id, 4)
    assert.equal(state.detail.nickName, '当前详情')
  } finally {
    app.unmount()
    await rm(generatedComponentPath, { force: true })
    await rm(stubModulePath, { force: true })
    await rm(componentStubPath, { force: true })
    delete globalThis.__userListRaceControl
  }
})
