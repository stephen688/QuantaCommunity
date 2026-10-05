import test from 'node:test'
import assert from 'node:assert/strict'

async function loadImporter() {
  try {
    return await import('../src/utils/policyDocImport.js')
  } catch {
    return null
  }
}

test('policy import parser accepts array or documents wrapper and rejects duplicate docIds', async () => {
  const importer = await loadImporter()

  assert.ok(importer, '政策导入解析器必须存在')
  if (!importer) return

  const parsed = importer.parsePolicyDocumentImport(JSON.stringify({
    documents: [
      { docId: 'policy:a', title: 'A', content: '正文 A' },
      { docId: 'policy:a', title: 'A2', content: '正文 A2' },
      { docId: 'policy:b', title: 'B', content: '正文 B' },
    ],
  }))

  assert.equal(parsed.rows.length, 3)
  assert.equal(parsed.rows[0].valid, false)
  assert.match(parsed.rows[0].error, /重复/)
  assert.equal(parsed.rows[1].valid, false)
  assert.equal(parsed.rows[2].valid, true)
})

test('policy import validator preserves the existing docId title content limits', async () => {
  const importer = await loadImporter()

  assert.ok(importer, '政策导入解析器必须存在')
  if (!importer) return

  assert.equal(importer.validatePolicyDocument({
    docId: 'x'.repeat(64),
    title: 't'.repeat(200),
    content: '正文',
  }), null)
  assert.match(importer.validatePolicyDocument({ docId: 'x'.repeat(65), title: '标题', content: '正文' }), /docId/)
  assert.match(importer.validatePolicyDocument({ docId: 'x', title: 't'.repeat(201), content: '正文' }), /title/)
  assert.match(importer.validatePolicyDocument({ docId: 'x', title: '标题', content: ' ' }), /content/)
})

test('policy import parser enforces the 1 MiB and 20 document preview limits', async () => {
  const importer = await loadImporter()

  assert.ok(importer, '政策导入解析器必须存在')
  if (!importer) return

  const tooMany = Array.from({ length: 21 }, (_, index) => ({
    docId: `policy:${index}`,
    title: '标题',
    content: '正文',
  }))
  assert.throws(() => importer.parsePolicyDocumentImport(JSON.stringify(tooMany)), /20/)
  assert.throws(() => importer.parsePolicyDocumentImport(JSON.stringify({ documents: [{
    docId: 'policy:large',
    title: '标题',
    content: 'x'.repeat(1024 * 1024),
  }] })), /1 MiB/)
})
