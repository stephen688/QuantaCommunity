<script setup>

import { computed, reactive, ref, onMounted } from 'vue'

import { ElMessage } from 'element-plus'

import { ChatDotRound, Clock, CircleCheck, CircleClose } from '@element-plus/icons-vue'

import PageHeader from '../../components/PageHeader.vue'

import TableEmptyState from '../../components/TableEmptyState.vue'

import { usePermission } from '../../composables/usePermission'

import { commentApi } from '../../api/comment'

import { moderationApi } from '../../api/moderation'

import {

  buildModerationQueries,

  formatModerationLabels,

  labelModerationDecision,

  moderationDecisionTagType,

  pickModeration,

} from '../../utils/moderation'

const { hasAuthority } = usePermission()



const tableLoading = ref(false)

const list = ref([])

const total = ref(0)

const initialComplete = ref(false)

const loadError = ref(null)

const moderationMap = ref({})



const query = reactive({

  contentId: '',

  auditStatus: undefined,

  pageNum: 1,

  pageSize: 10,

})



const rejectVisible = ref(false)

const rejectLoading = ref(false)

const rejectTarget = ref(null)

const rejectReason = ref('')



function buildPageParams() {

  const p = { pageNum: query.pageNum, pageSize: query.pageSize }

  const idStr = String(query.contentId ?? '').trim()

  if (idStr) {

    const n = Number(idStr)

    if (!Number.isNaN(n) && n > 0) p.contentId = n

  }

  if (query.auditStatus !== undefined && query.auditStatus !== null && query.auditStatus !== '') {

    p.auditStatus = query.auditStatus

  }

  return p

}



async function loadModerationForRows(rows) {

  if (!Array.isArray(rows) || rows.length === 0) {

    moderationMap.value = {}

    return

  }

  try {

    const queries = buildModerationQueries(

      rows.map((row) => ({ targetType: 'COMMENT', targetId: row.commentId })),

    )

    if (queries.length === 0) {

      moderationMap.value = {}

      return

    }

    moderationMap.value = (await moderationApi.batchLatest(queries)) ?? {}

  } catch {

    moderationMap.value = {}

  }

}



async function fetchList() {

  loadError.value = null

  tableLoading.value = true

  try {

    const data = await commentApi.page(buildPageParams())

    total.value = Number(data?.total ?? 0)

    list.value = Array.isArray(data?.records) ? data.records : []

    await loadModerationForRows(list.value)

  } catch (err) {

    list.value = []

    total.value = 0

    moderationMap.value = {}

    loadError.value = { message: friendlyListError(err) }

  } finally {

    tableLoading.value = false

    initialComplete.value = true

  }

}



function friendlyListError(err) {

  const status = err?.response?.status

  const dataMsg = err?.response?.data?.msg

  if (status === 502 || status === 503) return '服务暂时不可用（网关或上游异常），请稍后重试。'

  if (status === 504) return '网关超时，请稍后重试。'

  if (!err?.response) return '无法连接服务器，请确认网络或服务是否已启动。'

  return dataMsg || err?.message || '列表加载失败，请重试。'

}



function dismissError() {

  loadError.value = null

}



function retryFetch() {

  fetchList()

}



function onSearch() {

  query.pageNum = 1

  fetchList()

}



function onReset() {

  query.contentId = ''

  query.auditStatus = undefined

  query.pageNum = 1

  query.pageSize = 10

  fetchList()

}



function onPageChange(p) {

  query.pageNum = p

  fetchList()

}



function onSizeChange(s) {

  query.pageSize = s

  query.pageNum = 1

  fetchList()

}



const auditLabel = { 0: '待审核', 1: '已通过', 2: '已驳回' }



function labelAudit(v) {

  if (v === undefined || v === null) return '-'

  return auditLabel[v] ?? String(v)

}



function aiRecord(row) {

  return pickModeration(moderationMap.value, 'COMMENT', row?.commentId)

}



function contentPreview(text) {

  if (!text) return '—'

  const t = String(text).replace(/\s+/g, ' ').trim()

  return t.length > 100 ? `${t.slice(0, 100)}…` : t

}



async function doPass(row) {

  await commentApi.audit({

    commentId: row.commentId,

    auditResult: 1,

  })

  ElMessage.success('已通过')

  await fetchList()

}



function openReject(row) {

  rejectTarget.value = row

  rejectReason.value = ''

  rejectVisible.value = true

}



async function submitReject() {

  const reason = rejectReason.value?.trim()

  if (!reason) {

    ElMessage.warning('请填写驳回原因')

    return

  }

  rejectLoading.value = true

  try {

    await commentApi.audit({

      commentId: rejectTarget.value.commentId,

      auditResult: 2,

      rejectReason: reason,

    })

    ElMessage.success('已驳回')

    rejectVisible.value = false

    await fetchList()

  } finally {

    rejectLoading.value = false

  }

}



async function doDelete(commentId) {

  await commentApi.remove(commentId)

  ElMessage.success('已删除')

  await fetchList()

}



const showListSkeleton = computed(() => tableLoading.value && !initialComplete.value)



const headerBadge = computed(() => {

  if (!initialComplete.value) return ''

  if (query.auditStatus === 0) return `待审核共 ${total.value} 条`

  return `共 ${total.value} 条`

})



const headerBadgeTone = computed(() => (query.auditStatus === 0 ? 'warning' : 'muted'))



onMounted(() => {

  fetchList()

})

</script>



<template>

  <div class="comment-list portal-page">

    <PageHeader

      :icon="ChatDotRound"

      title="评论管理"

      description="审核待审评论、处理 AI 人工复核"

      :badge="headerBadge"

      :badge-tone="headerBadgeTone"

    />

    <el-card shadow="never" class="portal-panel toolbar-card">

      <el-form :inline="true" :model="query" class="filter-form" label-width="88px" @submit.prevent="onSearch">

        <el-form-item label="帖子 ID">

          <el-input v-model="query.contentId" placeholder="可选，数字" clearable style="width: 160px" />

        </el-form-item>

        <el-form-item label="审核状态">

          <el-select v-model="query.auditStatus" placeholder="全部" clearable style="width: 140px">

            <el-option label="待审核" :value="0" />

            <el-option label="已通过" :value="1" />

            <el-option label="已驳回" :value="2" />

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

          <el-button type="primary" link class="list-error-retry" @click="retryFetch">重试</el-button>

          <el-button text class="list-error-dismiss" @click="dismissError">关闭</el-button>

        </div>

      </div>



      <div v-if="showListSkeleton" class="list-skeleton" aria-busy="true" aria-label="加载评论列表">

        <el-skeleton :rows="8" animated />

      </div>



      <el-table

        v-show="!showListSkeleton"

        v-loading="tableLoading"

        class="portal-table"

        :data="list"

        row-key="commentId"

        size="small"

        style="width: 100%"

      >

        <template #empty>

          <TableEmptyState

            v-if="!loadError"

            :icon="ChatDotRound"

            title="暂无符合条件的评论"

            description="尝试调整筛选条件后重试。"

          />

          <TableEmptyState

            v-else

            error

            title="列表未展示"

            description="请查看上方提示或使用「重试」。"

          />

        </template>

        <el-table-column prop="commentId" label="评论 ID" width="96" />

        <el-table-column prop="contentId" label="帖子 ID" width="96" />

        <el-table-column prop="answerId" label="回答 ID" width="96">

          <template #default="{ row }">{{ row.answerId ?? '—' }}</template>

        </el-table-column>

        <el-table-column prop="userId" label="用户 ID" width="100" />

        <el-table-column label="正文摘要" min-width="180" show-overflow-tooltip>

          <template #default="{ row }">{{ contentPreview(row.content) }}</template>

        </el-table-column>

        <el-table-column label="审核" width="112" align="center">

          <template #default="{ row }">

            <el-tag

              :type="row.auditStatus === 0 ? 'warning' : row.auditStatus === 2 ? 'danger' : 'success'"

              size="small"

              class="audit-tag"

            >

              <span class="audit-tag-inner">

                <el-icon v-if="row.auditStatus === 0" :size="14"><Clock /></el-icon>

                <el-icon v-else-if="row.auditStatus === 1" :size="14"><CircleCheck /></el-icon>

                <el-icon v-else :size="14"><CircleClose /></el-icon>

                {{ labelAudit(row.auditStatus) }}

              </span>

            </el-tag>

          </template>

        </el-table-column>

        <el-table-column label="AI 建议" width="140" align="center">

          <template #default="{ row }">

            <template v-if="aiRecord(row)">

              <el-tag :type="moderationDecisionTagType(aiRecord(row).decision)" size="small">

                {{ labelModerationDecision(aiRecord(row).decision) }}

              </el-tag>

            </template>

            <span v-else class="muted-text">—</span>

          </template>

        </el-table-column>

        <el-table-column prop="createTime" label="创建时间" width="170" />

        <el-table-column label="操作" width="280" fixed="right" align="center">

          <template #default="{ row }">

            <div class="row-actions">

              <template v-if="row.auditStatus === 0 && hasAuthority('CONTENT_AUDIT')">

                <el-popconfirm title="确认审核通过？" @confirm="doPass(row)">

                  <template #reference>

                    <el-button type="success" plain size="small" @click.stop>通过</el-button>

                  </template>

                </el-popconfirm>

                <el-button type="warning" plain size="small" @click.stop="openReject(row)">驳回</el-button>

              </template>

              <el-popconfirm
                v-if="hasAuthority('CONTENT_DELETE')"
                title="确认删除该评论？（软删除）"
                @confirm="doDelete(row.commentId)"
              >

                <template #reference>

                  <el-button type="danger" plain size="small" @click.stop>删除</el-button>

                </template>

              </el-popconfirm>

            </div>

          </template>

        </el-table-column>

      </el-table>



      <div v-show="!showListSkeleton" class="pager">

        <el-pagination

          :current-page="query.pageNum"

          :page-size="query.pageSize"

          :total="total"

          :page-sizes="[10, 20, 50]"

          layout="total, sizes, prev, pager, next, jumper"

          background

          @current-change="onPageChange"

          @size-change="onSizeChange"

        />

      </div>

    </el-card>



    <el-dialog

      v-model="rejectVisible"

      title="驳回评论"

      width="480px"

      class="portal-dialog-accent"

      destroy-on-close

      @closed="rejectTarget = null"

    >

      <p v-if="rejectTarget" class="dialog-hint">

        评论 ID：{{ rejectTarget.commentId }} — {{ contentPreview(rejectTarget.content) }}

      </p>

      <p v-if="rejectTarget && aiRecord(rejectTarget)" class="dialog-hint dialog-hint--ai">

        AI 建议：{{ labelModerationDecision(aiRecord(rejectTarget).decision) }}

        <template v-if="formatModerationLabels(aiRecord(rejectTarget).labels) !== '—'">

          · 标签：{{ formatModerationLabels(aiRecord(rejectTarget).labels) }}

        </template>

      </p>

      <el-input

        v-model="rejectReason"

        type="textarea"

        :rows="4"

        maxlength="500"

        show-word-limit

        placeholder="请填写驳回原因"

      />

      <template #footer>

        <el-button @click="rejectVisible = false">取消</el-button>

        <el-button type="primary" :loading="rejectLoading" @click="submitReject">提交</el-button>

      </template>

    </el-dialog>

  </div>

</template>



<style scoped>

.muted-text {

  color: var(--el-text-color-placeholder);

}

.dialog-hint--ai {

  color: var(--el-color-warning);

}

</style>


