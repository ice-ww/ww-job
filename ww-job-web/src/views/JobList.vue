<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageJobs, createJob, updateJob, triggerJob, startJob, stopJob, deleteJob } from '../api/job'
import { listGroups } from '../api/group'
import { ROUTE_STRATEGIES, BLOCK_STRATEGIES, fmtTime } from '../constants'

const router = useRouter()

const jobs = ref([])
const groups = ref([])
const total = ref(0)
const loading = ref(false)
const query = ref({ page: 1, size: 10, jobGroupId: null })

const groupOptions = computed(() =>
  groups.value.map((g) => ({ label: g.title, value: g.id }))
)

async function loadJobs() {
  loading.value = true
  try {
    const params = { page: query.value.page, size: query.value.size }
    if (query.value.jobGroupId) params.jobGroupId = query.value.jobGroupId
    const res = await pageJobs(params)
    jobs.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    ElMessage.error('加载任务失败，请确认 admin 已启动')
  } finally {
    loading.value = false
  }
}

async function loadGroups() {
  const res = await listGroups()
  groups.value = res.data
}

function onFilterChange() {
  query.value.page = 1
  loadJobs()
}

// ---- 新建 / 编辑弹窗 ----
const dialogVisible = ref(false)
const editingId = ref(null)
const form = ref({})

function openCreate() {
  editingId.value = null
  form.value = {
    jobName: '', jobGroupId: null, handlerName: '', cron: '',
    jobDesc: '', executorParam: '', routeStrategy: 'round_robin',
    blockStrategy: 'serial', retryCount: 0, timeout: 0, alarmConfig: '',
  }
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.jobName || !form.value.jobGroupId || !form.value.handlerName || !form.value.cron) {
    ElMessage.warning('请填写必填项：任务名称 / 分组 / Handler / Cron')
    return
  }
  if (editingId.value) {
    await updateJob(form.value)
    ElMessage.success('已更新')
  } else {
    await createJob({ ...form.value, triggerStatus: 0 })
    ElMessage.success('已创建，默认停用，可在列表启用')
  }
  dialogVisible.value = false
  loadJobs()
}

// ---- 操作 ----
async function onTrigger(row) {
  await ElMessageBox.confirm(`确认手动触发任务「${row.jobName}」？`, '触发', { type: 'warning' })
  await triggerJob(row.id)
  ElMessage.success('已触发')
}

async function onToggle(row) {
  if (row.triggerStatus === 1) {
    await stopJob(row.id)
  } else {
    await startJob(row.id)
  }
  loadJobs()
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除任务「${row.jobName}」？此操作不可恢复。`, '删除', { type: 'error' })
  await deleteJob(row.id)
  ElMessage.success('已删除')
  loadJobs()
}

function goLogs(row) {
  router.push({ path: '/joblogs', query: { jobId: row.id } })
}

onMounted(() => {
  loadJobs()
  loadGroups()
})
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建任务</el-button>
      <el-select
        v-model="query.jobGroupId"
        placeholder="按分组筛选"
        clearable
        style="width: 200px"
        @change="onFilterChange"
      >
        <el-option v-for="g in groupOptions" :key="g.value" :label="g.label" :value="g.value" />
      </el-select>
      <el-button @click="loadJobs">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="jobs" border stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="jobName" label="任务名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="分组" width="120">
        <template #default="{ row }">
          {{ groupOptions.find((g) => g.value === row.jobGroupId)?.label || row.jobGroupId }}
        </template>
      </el-table-column>
      <el-table-column prop="handlerName" label="Handler" min-width="140" show-overflow-tooltip />
      <el-table-column prop="cron" label="Cron" width="140" />
      <el-table-column label="路由策略" width="100">
        <template #default="{ row }">
          {{ ROUTE_STRATEGIES.find((s) => s.value === row.routeStrategy)?.label || row.routeStrategy }}
        </template>
      </el-table-column>
      <el-table-column label="阻塞策略" width="100">
        <template #default="{ row }">
          {{ BLOCK_STRATEGIES.find((s) => s.value === row.blockStrategy)?.label || row.blockStrategy }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.triggerStatus === 1 ? 'success' : 'info'">
            {{ row.triggerStatus === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下次触发" width="170">
        <template #default="{ row }">{{ fmtTime(row.triggerNextTime) }}</template>
      </el-table-column>
      <el-table-column label="上次触发" width="170">
        <template #default="{ row }">{{ fmtTime(row.triggerLastTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onTrigger(row)">触发</el-button>
          <el-button link :type="row.triggerStatus === 1 ? 'warning' : 'success'" @click="onToggle(row)">
            {{ row.triggerStatus === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          <el-button link type="info" @click="goLogs(row)">日志</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next, jumper"
      :total="total"
      :page-size="query.size"
      :current-page="query.page"
      @current-change="(p) => { query.page = p; loadJobs() }"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑任务' : '新建任务'" width="640px">
      <el-form label-width="110px">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.jobName" placeholder="如 alert-fail-job" />
        </el-form-item>
        <el-form-item label="分组" required>
          <el-select v-model="form.jobGroupId" placeholder="选择分组" style="width: 100%">
            <el-option v-for="g in groupOptions" :key="g.value" :label="g.label" :value="g.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="JobHandler" required>
          <el-input v-model="form.handlerName" placeholder="如 failDemoHandler" />
        </el-form-item>
        <el-form-item label="Cron" required>
          <el-input v-model="form.cron" placeholder="6 段：秒 分 时 日 月 周，如 0 */5 * * * ?" />
        </el-form-item>
        <el-form-item label="任务描述">
          <el-input v-model="form.jobDesc" />
        </el-form-item>
        <el-form-item label="执行参数">
          <el-input v-model="form.executorParam" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="路由策略">
          <el-select v-model="form.routeStrategy" style="width: 100%">
            <el-option v-for="s in ROUTE_STRATEGIES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="阻塞策略">
          <el-select v-model="form.blockStrategy" style="width: 100%">
            <el-option v-for="s in BLOCK_STRATEGIES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="失败重试次数">
          <el-input-number v-model="form.retryCount" :min="0" :max="10" />
        </el-form-item>
        <el-form-item label="超时(秒)">
          <el-input-number v-model="form.timeout" :min="0" :max="600" />
        </el-form-item>
        <el-form-item label="告警邮箱">
          <el-input v-model="form.alarmConfig" placeholder="逗号分隔，如 a@qq.com,b@qq.com" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 12px; align-items: center; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
