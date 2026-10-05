<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Collection, Delete, Document, Edit, Refresh, Upload } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { policyDocApi } from '../../api/policyDoc'
import {
  MAX_POLICY_IMPORT_BYTES,
  parsePolicyDocumentImport,
  validatePolicyDocument,
} from '../../utils/policyDocImport'

const tableLoading = ref(false)
const list = ref([])
const total = ref(0)
const initialComplete = ref(false)
const loadError = ref(null)

const query = reactive({
  keyword: '',
  status: 'ACTIVE',
  pageNum: 1,
  pageSize: 10,
})

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref(null)

const formVisible = ref(false)
const formSaving = ref(false)
const formMode = ref('create')
const formModel = reactive({ docId: '', title: '', content: '' })
const formOriginalDeleted = ref(false)

const fileInput = ref(null)
const importVisible = ref(false)
const importName = ref('')
const importError = ref('')
const importRows = ref([])
const importRunning = ref(false)
// 删除/恢复包含确认弹窗，必须在等待弹窗和请求期间锁住同一页面的其他行操作。
const mutationDocId = ref('')

const showListSkeleton = computed(() => tableLoading.value && !initialComplete.value)
const validImportRows = computed(() => importRows.value.filter((row) => row.valid))
const pendingImportRows = computed(() => importRows.value.filter((row) => row.valid && row.state === 'pending'))
const failedImportRows = computed(() => importRows.value.filter((row) => row.valid && row.state === 'failed'))

function buildPageParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  const keyword = query.keyword.trim()
  if (keyword) params.keyword = keyword
  if (query.status) params.status = query.status
  return params
}

function friendlyListError(error) {
  const status = error?.response?.status
  const dataMsg = error?.response?.data?.msg
  if (status === 502 || status === 503) return '服务暂时不可用，请稍后重试。'
  if (status === 504) return '网关超时，请稍后重试。'
  if (!error?.response) return '无法连接服务器，请确认主服务已启动。'
  return dataMsg || error?.message || '政策文档列表加载失败，请重试。'
}

function errorMessage(error) {
  return error?.response?.data?.msg || error?.message || '请求失败，请重试。'
}

async function fetchList() {
  tableLoading.value = true
  loadError.value = null
  try {
    const data = await policyDocApi.page(buildPageParams())
    list.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total ?? 0)
  } catch (error) {
    list.value = []
    total.value = 0
    loadError.value = { message: friendlyListError(error) }
  } finally {
    tableLoading.value = false
    initialComplete.value = true
  }
}

function resetForm() {
  formModel.docId = ''
  formModel.title = ''
  formModel.content = ''
  formOriginalDeleted.value = false
}

function openCreate() {
  if (mutationDocId.value || formSaving.value || importRunning.value) return
  resetForm()
  formMode.value = 'create'
  formVisible.value = true
}

async function loadDetail(docId) {
  detailLoading.value = true
  detailError.value = ''
  try {
    const data = await policyDocApi.detail(docId)
    detail.value = data
    return data
  } catch (error) {
    detail.value = null
    detailError.value = errorMessage(error)
    return null
  } finally {
    detailLoading.value = false
  }
}

async function openDetail(row) {
  if (mutationDocId.value) return
  detailVisible.value = true
  await loadDetail(row.docId)
}

async function openEdit(row) {
  if (mutationDocId.value || formSaving.value) return
  const data = await loadDetail(row.docId)
  if (!data) return
  formMode.value = 'edit'
  formModel.docId = data.docId || ''
  formModel.title = data.title || ''
  formModel.content = data.content || ''
  formOriginalDeleted.value = Number(data.isDeleted) === 1
  formVisible.value = true
}

function statusLabel(value) {
  return Number(value) === 1 ? '已删除墓碑' : '生效中'
}

function statusTag(value) {
  return Number(value) === 1 ? 'info' : 'success'
}

function previewContent(value) {
  const text = String(value || '').replace(/\s+/g, ' ').trim()
  return text.length > 100 ? `${text.slice(0, 100)}…` : text || '—'
}

function dismissError() {
  loadError.value = null
}

function onSearch() {
  query.pageNum = 1
  fetchList()
}

function onReset() {
  query.keyword = ''
  query.status = 'ACTIVE'
  query.pageNum = 1
  query.pageSize = 10
  fetchList()
}

function onPageChange(page) {
  query.pageNum = page
  fetchList()
}

function onSizeChange(size) {
  query.pageSize = size
  query.pageNum = 1
  fetchList()
}

async function saveForm() {
  if (formSaving.value) return

  const payload = {
    docId: formModel.docId.trim(),
    title: formModel.title.trim(),
    content: formModel.content,
  }
  const validationError = validatePolicyDocument(payload)
  if (validationError) {
    ElMessage.warning(validationError)
    return
  }

  // 在确认弹窗前加锁，并快照模式/请求体，避免弹窗期间重复提交或切换表单影响本次请求。
  const submitMode = formMode.value
  const needsRestoreConfirmation = submitMode === 'edit' && formOriginalDeleted.value
  formSaving.value = true
  try {
    if (needsRestoreConfirmation) {
      try {
        await ElMessageBox.confirm(
          '该文档当前是已删除墓碑，保存后会恢复生效并进入 Bot 增量同步队列，确认恢复？',
          '确认恢复政策文档',
          { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' },
        )
      } catch {
        return
      }
    }

    await policyDocApi.upsert(payload)
    ElMessage.success(submitMode === 'create' ? '政策源文档已保存' : '政策源文档已更新')
    formVisible.value = false
    detailVisible.value = false
    await fetchList()
  } catch {
    // request 拦截器已展示服务端错误；保留表单，便于修正后重试。
  } finally {
    formSaving.value = false
  }
}

async function deleteRow(row) {
  const docId = String(row?.docId ?? '').trim()
  if (!docId || mutationDocId.value) return
  const displayName = row?.title || docId
  mutationDocId.value = docId
  try {
    try {
      await ElMessageBox.confirm(
        `确认软删除「${displayName}」？删除墓碑会通过 update_time 进入 Bot 增量同步。`,
        '确认软删除',
        { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
      )
    } catch {
      return
    }

    await policyDocApi.softDelete(docId)
    ElMessage.success('政策源文档已软删除')
    await fetchList()
  } catch {
    // request 拦截器已展示服务端错误。
  } finally {
    if (mutationDocId.value === docId) mutationDocId.value = ''
  }
}

async function restoreRow(row) {
  const docId = String(row?.docId ?? '').trim()
  if (!docId || mutationDocId.value) return
  mutationDocId.value = docId
  try {
    const data = await loadDetail(docId)
    if (!data) return
    const payload = { docId: data.docId, title: data.title, content: data.content }

    try {
      await ElMessageBox.confirm(
        '该文档是已删除墓碑，确认恢复后会重新生效并进入 Bot 增量同步队列？',
        '确认恢复',
        { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' },
      )
    } catch {
      return
    }

    await policyDocApi.upsert(payload)
    ElMessage.success('政策源文档已恢复')
    await fetchList()
  } catch {
    // request 拦截器已展示服务端错误。
  } finally {
    if (mutationDocId.value === docId) mutationDocId.value = ''
  }
}

function openImportPicker() {
  if (mutationDocId.value || formSaving.value || importRunning.value) return
  fileInput.value?.click()
}

async function onImportFileChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  importName.value = file.name
  importError.value = ''
  importRows.value = []

  if (file.size > MAX_POLICY_IMPORT_BYTES) {
    importError.value = 'JSON 文件不能超过 1 MiB'
    importVisible.value = true
    return
  }

  try {
    const parsed = parsePolicyDocumentImport(await file.text())
    importRows.value = parsed.rows
    importVisible.value = true
  } catch (error) {
    importError.value = error?.message || 'JSON 导入预览失败'
    importVisible.value = true
  }
}

function importRowStatus(row) {
  if (!row.valid || row.state === 'failed') return '失败'
  if (row.state === 'success') return '成功'
  return '待提交'
}

function importRowTag(row) {
  if (!row.valid || row.state === 'failed') return 'danger'
  if (row.state === 'success') return 'success'
  return 'warning'
}

async function submitImportRow(row) {
  if (!row.valid || row.state === 'submitting') return
  row.state = 'submitting'
  row.error = null
  try {
    await policyDocApi.upsert({ docId: row.docId, title: row.title, content: row.content })
    row.state = 'success'
  } catch (error) {
    row.state = 'failed'
    row.error = errorMessage(error)
  }
}

async function runImport() {
  if (importRunning.value) return
  const rows = pendingImportRows.value.length ? pendingImportRows.value : failedImportRows.value
  if (!rows.length) return
  importRunning.value = true
  try {
    for (const row of rows) {
      await submitImportRow(row)
    }
    const successCount = importRows.value.filter((row) => row.state === 'success').length
    const failedCount = importRows.value.filter((row) => !row.valid || row.state === 'failed').length
    if (failedCount) {
      ElMessage.warning(`导入完成：成功 ${successCount} 条，失败 ${failedCount} 条，可逐条重试`)
    } else {
      ElMessage.success(`导入完成：成功 ${successCount} 条`)
      await fetchList()
    }
  } finally {
    importRunning.value = false
  }
}

async function retryImportRow(row) {
  if (!row.valid || row.state !== 'failed' || importRunning.value) return
  importRunning.value = true
  try {
    await submitImportRow(row)
    if (row.state === 'success') {
      ElMessage.success(`已重试保存 ${row.docId}`)
      await fetchList()
    }
  } finally {
    importRunning.value = false
  }
}

onMounted(() => {
  fetchList()
})
</script>

<template>
  <div class="policy-doc-list portal-page">
    <PageHeader
      :icon="Collection"
      title="政策知识库"
      description="维护 Bot 使用的政策源文档；保存后由现有同步任务按更新时间增量拉取。"
      :badge="initialComplete ? `共 ${total} 条` : ''"
      badge-tone="muted"
    >
      <template #actions>
        <input
          ref="fileInput"
          class="file-picker"
          type="file"
          accept=".json,application/json"
          @change="onImportFileChange"
        />
        <el-button :icon="Upload" :disabled="Boolean(mutationDocId) || formSaving || importRunning" @click="openImportPicker">导入 JSON</el-button>
        <el-button type="primary" :icon="Document" :disabled="Boolean(mutationDocId) || formSaving || importRunning" @click="openCreate">新增文档</el-button>
      </template>
    </PageHeader>

    <el-alert
      class="sync-note"
      title="当前页面只展示源文档保存状态。Bot 会通过 update_time 水位线增量同步，页面未查询 Qdrant，不显示“已入库”状态。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-card shadow="never" class="portal-panel toolbar-card">
      <el-form :inline="true" :model="query" class="filter-form" @submit.prevent="onSearch">
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            clearable
            placeholder="docId、标题或正文"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 150px">
            <el-option label="生效中" value="ACTIVE" />
            <el-option label="已删除墓碑" value="DELETED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">搜索</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="portal-panel table-card">
      <div v-if="loadError" class="list-error-bar" role="status">
        <span class="list-error-text">{{ loadError.message }}</span>
        <div class="list-error-actions">
          <el-button type="primary" link @click="fetchList">重试</el-button>
          <el-button text @click="dismissError">关闭</el-button>
        </div>
      </div>

      <div v-if="showListSkeleton" class="list-skeleton" aria-busy="true" aria-label="加载政策文档列表">
        <el-skeleton :rows="8" animated />
      </div>

      <el-table
        v-show="!showListSkeleton"
        v-loading="tableLoading"
        class="portal-table"
        :data="list"
        row-key="docId"
        size="small"
        style="width: 100%"
      >
        <template #empty>
          <TableEmptyState
            v-if="!loadError"
            :icon="Collection"
            title="暂无政策源文档"
            description="可以新增文档，或调整关键词和状态筛选。"
          />
          <TableEmptyState v-else error title="列表未展示" description="请查看上方提示或使用重试。" />
        </template>
        <el-table-column prop="docId" label="文档 ID" min-width="180" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column label="正文摘要" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">{{ previewContent(row.content) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.isDeleted)" size="small">{{ statusLabel(row.isDeleted) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="最后保存" width="180" />
        <el-table-column label="操作" width="260" fixed="right" align="center">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button type="primary" plain size="small" :disabled="Boolean(mutationDocId)" @click="openDetail(row)">查看</el-button>
              <el-button :icon="Edit" plain size="small" :disabled="Boolean(mutationDocId) || formSaving" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="Number(row.isDeleted) !== 1"
                type="danger"
                plain
                size="small"
                :icon="Delete"
                :disabled="Boolean(mutationDocId)"
                @click="deleteRow(row)"
              >删除</el-button>
              <el-button v-else type="success" plain size="small" :icon="Refresh" :disabled="Boolean(mutationDocId)" @click="restoreRow(row)">
                恢复
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-show="!showListSkeleton" class="pager">
        <el-pagination
          :current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="政策源文档详情" size="560px" destroy-on-close class="portal-drawer">
      <div v-loading="detailLoading" class="detail-stack">
        <div v-if="detailError" class="detail-error" role="alert">{{ detailError }}</div>
        <template v-if="detail">
          <div class="detail-summary">
            <div class="detail-summary-icon" aria-hidden="true"><el-icon><Document /></el-icon></div>
            <div>
              <h3>{{ detail.title || '（无标题）' }}</h3>
              <p>{{ detail.docId }}</p>
            </div>
            <el-tag :type="statusTag(detail.isDeleted)">{{ statusLabel(detail.isDeleted) }}</el-tag>
          </div>
          <el-descriptions :column="1" border class="portal-details-in-drawer">
            <el-descriptions-item label="文档 ID">{{ detail.docId }}</el-descriptions-item>
            <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
            <el-descriptions-item label="正文"><pre class="portal-readonly-block">{{ detail.content }}</pre></el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ detail.createTime || '—' }}</el-descriptions-item>
            <el-descriptions-item label="最后保存">{{ detail.updateTime || '—' }}</el-descriptions-item>
          </el-descriptions>
          <p class="detail-sync-note">源文档保存成功后，Bot 将按更新时间增量拉取；本页面未查询 Qdrant。</p>
        </template>
      </div>
    </el-drawer>

    <el-dialog
      v-model="formVisible"
      :title="formMode === 'create' ? '新增政策文档' : '编辑政策文档'"
      width="640px"
      destroy-on-close
      class="portal-dialog-accent"
      :close-on-click-modal="!formSaving"
      :close-on-press-escape="!formSaving"
      :show-close="!formSaving"
    >
      <el-form label-width="88px" @submit.prevent="saveForm">
        <el-form-item label="文档 ID" required>
          <el-input v-model="formModel.docId" :disabled="formMode === 'edit'" maxlength="64" show-word-limit />
          <div v-if="formMode === 'edit'" class="form-hint">文档 ID 是同步契约键，编辑时不可修改。</div>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="formModel.title" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input v-model="formModel.content" type="textarea" :rows="12" placeholder="填写政策源文档正文" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="formSaving" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="formSaving" @click="saveForm">保存源文档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入政策 JSON" width="860px" destroy-on-close>
      <p class="import-note">
        {{ importName || '未选择文件' }} · 单次最多 20 条、文件不超过 1 MiB。预览校验通过后逐条保存；失败行可以单独重试。
      </p>
      <el-alert
        title="按 docId 保存：已有文档将被覆盖，已删除文档将恢复生效。请确认预览内容后再提交。"
        type="warning"
        :closable="false"
        show-icon
      />
      <div v-if="importError" class="import-error" role="alert">{{ importError }}</div>
      <el-table v-else :data="importRows" size="small" max-height="420px" row-key="index">
        <el-table-column prop="index" label="#" width="60">
          <template #default="{ row }">{{ row.index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="docId" label="文档 ID" min-width="150" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column label="校验/提交" min-width="220">
          <template #default="{ row }">
            <el-tag :type="importRowTag(row)" size="small">{{ importRowStatus(row) }}</el-tag>
            <span v-if="row.error" class="row-error">{{ row.error }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.valid && row.state === 'failed'"
              link
              type="primary"
              :disabled="importRunning"
              @click="retryImportRow(row)"
            >重试</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="import-count">可提交 {{ validImportRows.length }} 条，失败/无效 {{ importRows.filter((row) => !row.valid || row.state === 'failed').length }} 条</span>
        <el-button :disabled="importRunning" @click="importVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="importRunning"
          :disabled="importRunning || (!pendingImportRows.length && !failedImportRows.length)"
          @click="runImport"
        >确认逐条保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.file-picker {
  display: none;
}

.sync-note {
  margin: 12px 0;
}

.detail-stack {
  min-height: 160px;
}

.detail-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  margin-bottom: 16px;
  background: var(--admin-surface-muted, #f7f8fa);
  border-radius: var(--admin-radius, 8px);
}

.detail-summary-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  color: var(--admin-primary);
  background: var(--admin-primary-subtle);
  border-radius: 10px;
}

.detail-summary h3 {
  margin: 0;
  color: var(--admin-text);
  font-size: 16px;
}

.detail-summary p {
  margin: 4px 0 0;
  color: var(--admin-text-muted);
  font-size: 12px;
}

.detail-summary .el-tag {
  margin-left: auto;
}

.portal-readonly-block {
  max-height: 360px;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  line-height: 1.65;
}

.detail-sync-note,
.form-hint,
.import-note,
.import-count {
  color: var(--admin-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.detail-sync-note {
  margin: 14px 0 0;
}

.form-hint {
  margin-top: 4px;
}

.detail-error,
.import-error {
  padding: 10px 12px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-radius: 6px;
}

.import-note {
  margin: 0 0 12px;
}

.row-error {
  display: block;
  margin-top: 4px;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.4;
}

.import-count {
  margin-right: auto;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
