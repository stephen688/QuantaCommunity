/**
 * 政策源文档 JSON 导入校验。
 *
 * <p>导入只在管理端预览并逐条调用源文档 upsert 接口；这里不负责 Qdrant ingest，也不把批量提交
 * 伪装成原子事务。</p>
 */
export const MAX_POLICY_IMPORT_BYTES = 1024 * 1024
export const MAX_POLICY_IMPORT_DOCUMENTS = 20

/**
 * 校验单条政策文档，边界与 BotContentSyncServiceImpl.validatePolicyDoc 保持一致。
 *
 * @param {unknown} document
 * @returns {string|null} 失败原因，合法时返回 null
 */
export function validatePolicyDocument(document) {
  if (!document || typeof document !== 'object') return '文档必须是对象'

  const docId = String(document.docId ?? '').trim()
  const title = String(document.title ?? '').trim()
  const content = String(document.content ?? '')

  if (!docId) return 'docId 不能为空'
  if (docId.length > 64) return 'docId 不能超过 64 个字符'
  if (!title) return 'title 不能为空'
  if (title.length > 200) return 'title 不能超过 200 个字符'
  if (!content.trim()) return 'content 不能为空'
  return null
}

/**
 * 解析数组或 { documents: [] } 形式的 JSON，并为每行生成可预览的校验结果。
 *
 * @param {string} jsonText JSON 文本
 * @returns {{ rows: Array<{index:number, docId:string, title:string, content:string, valid:boolean, error:string|null, state:string}>, documents:Array }} 预览结果
 */
export function parsePolicyDocumentImport(jsonText) {
  if (typeof jsonText !== 'string') throw new Error('导入内容必须是 JSON 文本')
  if (new TextEncoder().encode(jsonText).length > MAX_POLICY_IMPORT_BYTES) {
    throw new Error('JSON 文件不能超过 1 MiB')
  }

  let parsed
  try {
    parsed = JSON.parse(jsonText)
  } catch {
    throw new Error('JSON 格式非法')
  }

  const documents = Array.isArray(parsed) ? parsed : parsed?.documents
  if (!Array.isArray(documents)) {
    throw new Error('JSON 必须是数组或包含 documents 数组的对象')
  }
  if (documents.length === 0) throw new Error('documents 不能为空')
  if (documents.length > MAX_POLICY_IMPORT_DOCUMENTS) {
    throw new Error(`单次最多导入 ${MAX_POLICY_IMPORT_DOCUMENTS} 条文档`)
  }

  const rows = documents.map((document, index) => {
    const safeDocument = document && typeof document === 'object' ? document : {}
    const row = {
      index,
      docId: String(safeDocument.docId ?? '').trim(),
      title: String(safeDocument.title ?? '').trim(),
      content: String(safeDocument.content ?? ''),
      valid: false,
      error: validatePolicyDocument(safeDocument),
      state: 'pending',
    }
    row.valid = !row.error
    return row
  })

  const counts = new Map()
  rows.forEach((row) => counts.set(row.docId, (counts.get(row.docId) ?? 0) + 1))
  rows.forEach((row) => {
    if (row.docId && counts.get(row.docId) > 1) {
      row.valid = false
      row.error = 'docId 在导入文件中重复'
    }
  })

  return { rows, documents }
}
