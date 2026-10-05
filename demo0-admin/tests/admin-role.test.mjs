import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ADMIN_ROLE_OPTIONS,
  isCurrentUser,
  isProtectedSelfRevoke,
  isRoleMutationDisabled,
} from '../src/utils/admin-role.js'

test('role controls expose exactly the three server-managed roles', () => {
  assert.deepEqual(
    ADMIN_ROLE_OPTIONS.map((role) => role.code),
    ['CONTENT_AUDITOR', 'OPERATIONS_ADMIN', 'SUPER_ADMIN'],
  )
})

test('self revoke is blocked only for the sole super-admin role', () => {
  assert.equal(isCurrentUser(7, '7'), true)
  assert.equal(
    isProtectedSelfRevoke({
      targetUserId: 7,
      currentUserId: '7',
      roleCode: 'SUPER_ADMIN',
      roles: ['SUPER_ADMIN'],
    }),
    true,
  )
  assert.equal(
    isProtectedSelfRevoke({
      targetUserId: 7,
      currentUserId: '7',
      roleCode: 'SUPER_ADMIN',
      roles: ['SUPER_ADMIN', 'OPERATIONS_ADMIN'],
    }),
    false,
  )
  assert.equal(
    isProtectedSelfRevoke({
      targetUserId: 8,
      currentUserId: '7',
      roleCode: 'SUPER_ADMIN',
      roles: ['SUPER_ADMIN'],
    }),
    false,
  )
})

test('role mutation stays disabled until roles are loaded or while another action is busy', () => {
  assert.equal(
    isRoleMutationDisabled({
      hasRoleManage: true,
      rolesLoaded: false,
      busy: false,
      protectedSelfRevoke: false,
    }),
    true,
  )
  assert.equal(
    isRoleMutationDisabled({
      hasRoleManage: true,
      rolesLoaded: true,
      busy: true,
      protectedSelfRevoke: false,
    }),
    true,
  )
  assert.equal(
    isRoleMutationDisabled({
      hasRoleManage: true,
      rolesLoaded: true,
      busy: false,
      protectedSelfRevoke: false,
    }),
    false,
  )
})
