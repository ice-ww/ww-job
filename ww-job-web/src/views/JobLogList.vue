<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { pageLogs, getLogDetail } from '../api/log'
import { pageJobs } from '../api/job'
import { LOG_STATUS, fmtTime } from '../constants'

const route = useRoute()

const logs = ref([])
const jobs = ref([])
const total = ref(0)
const loading = ref(false)
const query = ref({ page: 1, size: 10, jobId: null, status: null })

const statusOptions = LOG_STATUS.map((s) => ({ label: s.label, value: s.value }))
const jobOptions = computed(() => jobs.value.map((j) => ({ label: j.jobName, value: j.id })))

function statusLabel(status) {
  return LOG_STATUS.find((s) => s.value === status)?.label ?? status
}
function statusTag(status) {
  return LOG_STATUS.find((s) => s.value === status)?.tag ?? 'info'
}

async function loadLogs() {
  loading.value = true
  try {
    const params = { page: query.value.page, size: query.value.size }
    if (query.value.jobId) params.jobId = query.value.jobId
    if (query.value.status !== null && query.value.status !== undefined) params.status = query.value.status
    const res = await pageLogs(params)
    logs.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    ElMessage.error('加载日志失败，请确认 admin 已启动')
  } finally {
    loading.value = false
  }
}

async function loadJobOptions() {
  const res = await pageJobs({ page: 1, size: 1000 })
  jobs.value = res.data.records
}

function onFilterChange() {
  query.value.page = 1
  loadLogs()
}

// ---- 详情 ----
const detailVisible = ref(false)
const detail = ref(null)

async function openDetail(row) {
  const res = await getLogDetail(row.id)
  detail.value = res.data
  detailVisible.value = true
}

onMounted(async () => {
  await loadJobOptions()
  if (route.query.jobId) {
    query.value.jobId = Number(route.query.jobId)
  }
  loadLogs()
})
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-select
        v-model="query.jobId"
        placeholder="按任务筛选"
        clearable
        filterable
        style="width: 220px"
        @change="onFilterChange"
      >
        <el-option v-for="j in jobOptions" :key="j.value" :label="j.label" :value="j.value" />
      </el-select>
      <el-select
        v-model="query.status"
        placeholder="按状态筛选"
        clearable
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-button @click="loadLogs">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="logs" border stripe>
      <el-table-column prop="id" label="日志ID" width="80" />
      <el-table-column prop="jobId" label="任务ID" width="80" />
      <el-table-column prop="executorAddress" label="执行器" width="160" show-overflow-tooltip />
      <el-table-column prop="handlerName" label="Handler" min-width="130" show-overflow-tooltip />
      <el-table-column prop="triggerType" label="触发方式" width="90" />
      <el-table-column label="触发时间" width="170">
        <template #default="{ row }">{{ fmtTime(new Date(row.triggerTime).getTime()) }}</template>
      </el-table-column>
      <el-table-column label="处理时间" width="170">
        <template #default="{ row }">{{ row.handleTime ? fmtTime(new Date(row.handleTime).getTime()) : '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handleMsg" label="处理消息" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      :page-size="query.size"
      :current-page="query.page"
      @size-change="(s) => { query.size = s; query.page = 1; loadLogs() }"
      @current-change="(p) => { query.page = p; loadLogs() }"
    />

    <el-dialog v-model="detailVisible" title="日志详情" width="640px">
      <el-descriptions v-if="detail" :column="2" border>
        <el-descriptions-item label="日志ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="任务ID">{{ detail.jobId }}</el-descriptions-item>
        <el-descriptions-item label="执行器地址">{{ detail.executorAddress }}</el-descriptions-item>
        <el-descriptions-item label="Handler">{{ detail.handlerName }}</el-descriptions-item>
        <el-descriptions-item label="触发方式">{{ detail.triggerType }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(detail.status) }}</el-descriptions-item>
        <el-descriptions-item label="触发时间">{{ detail.triggerTime }}</el-descriptions-item>
        <el-descriptions-item label="处理时间">{{ detail.handleTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="handleCode">{{ detail.handleCode }}</el-descriptions-item>
        <el-descriptions-item label="分片索引">{{ detail.shardIndex ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="处理消息" :span="2">
          <span class="msg">{{ detail.handleMsg || '-' }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 12px; align-items: center; }
.pager { margin-top: 12px; justify-content: flex-end; }
.msg { white-space: pre-wrap; word-break: break-all; }
</style>
