import test from 'node:test'
import assert from 'node:assert/strict'
import { canAccessAdminPage, canUseAdminAuthority, hasManagementRole } from '../src/utils/admin-access.js'

const contentAuditor = {
  roles: ['CONTENT_AUDITOR'],
  authorities: ['CONTENT_READ_ADMIN', 'CONTENT_AUDIT'],
}

const operationsAdmin = {
  roles: ['OPERATIONS_ADMIN'],
  authorities: ['USER_READ_ADMIN', 'USER_BAN', 'IDENTITY_AUDIT', 'EVENT_READ'],
}

const superAdmin = {
  roles: ['SUPER_ADMIN'],
  authorities: [
    'CONTENT_READ_ADMIN',
    'CONTENT_AUDIT',
    'CONTENT_DELETE',
    'IDENTITY_AUDIT',
    'USER_READ_ADMIN',
    'USER_BAN',
    'EVENT_READ',
    'EVENT_REPLAY',
    'AUDIT_LOG_READ',
    'ROLE_MANAGE',
  ],
}

const ordinaryUser = { roles: ['USER'], authorities: [] }

test('content auditor can read reports and audit them, while ordinary users cannot enter reports', () => {
  assert.equal(canAccessAdminPage(contentAuditor, { authority: 'CONTENT_READ_ADMIN' }), true)
  assert.equal(canUseAdminAuthority(contentAuditor, 'CONTENT_AUDIT'), true)
  assert.equal(canAccessAdminPage(ordinaryUser, { authority: 'CONTENT_READ_ADMIN' }), false)
  assert.equal(canUseAdminAuthority(ordinaryUser, 'CONTENT_AUDIT'), false)
})

test('operations admin can read events but cannot replay dead events', () => {
  assert.equal(canAccessAdminPage(operationsAdmin, { authority: 'EVENT_READ' }), true)
  assert.equal(canUseAdminAuthority(operationsAdmin, 'EVENT_REPLAY'), false)
  assert.equal(canAccessAdminPage(operationsAdmin, { authority: 'EVENT_REPLAY' }), false)
})

test('super admin can read and replay events but does not gain operations-only knowledge access', () => {
  assert.equal(canAccessAdminPage(superAdmin, { authority: 'EVENT_READ' }), true)
  assert.equal(canUseAdminAuthority(superAdmin, 'EVENT_REPLAY'), true)
  assert.equal(canAccessAdminPage(superAdmin, { roles: ['OPERATIONS_ADMIN'] }), false)
})

test('route role metadata accepts any matching role and rejects malformed or missing contexts', () => {
  assert.equal(canAccessAdminPage(operationsAdmin, { roles: ['CONTENT_AUDITOR', 'OPERATIONS_ADMIN'] }), true)
  assert.equal(canAccessAdminPage({ roles: ['USER'], authorities: [] }, { roles: ['CONTENT_AUDITOR', 'OPERATIONS_ADMIN'] }), false)
  assert.equal(canAccessAdminPage({}, { roles: ['OPERATIONS_ADMIN'] }), false)
  assert.equal(canAccessAdminPage(operationsAdmin, {}), true)
})

test('ordinary user role is not enough to enter the management shell', () => {
  assert.equal(hasManagementRole(contentAuditor), true)
  assert.equal(hasManagementRole(operationsAdmin), true)
  assert.equal(hasManagementRole(superAdmin), true)
  assert.equal(hasManagementRole(ordinaryUser), false)
})
