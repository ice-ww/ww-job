<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getStats } from '../api/dashboard'

const router = useRouter()
const stats = ref(null)
const loading = ref(false)

function fmt(n) {
  return (n ?? 0).toLocaleString()
}

const cards = [
  { key: 'jobTotal', label: '任务总数', to: '/jobs', sub: () => `启用 ${fmt(stats.value?.jobEnabled)} · 停用 ${fmt(stats.value?.jobDisabled)}` },
  { key: 'executorOnline', label: '在线执行器', to: '/registries', sub: () => `共 ${fmt(stats.value?.executorTotal)} 台注册` },
  { key: 'logTotalToday', label: '今日触发', to: '/joblogs' },
  { key: 'logSuccessToday', label: '今日成功', to: '/joblogs?status=1', color: '#67c23a' },
  { key: 'logFailToday', label: '今日失败', to: '/joblogs?status=2', color: '#f56c6c' },
  { key: 'logUnknownToday', label: '今日未知', to: '/joblogs?status=3', color: '#e6a23c' },
]

async function load() {
  loading.value = true
  try {
    const res = await getStats()
    stats.value = res.data
  } catch (e) {
    ElMessage.error('加载概览数据失败，请确认 admin 已启动')
  } finally {
    loading.value = false
  }
}

let timer = null
onMounted(() => {
  load()
  timer = setInterval(load, 30000) // 30s 轮询
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <span class="hint">概览每 30s 自动刷新</span>
    </div>

    <el-row :gutter="16" v-loading="loading">
      <el-col v-for="c in cards" :key="c.key" :span="4">
        <el-card class="stat-card" shadow="hover" @click="router.push(c.to)">
          <div class="num" :style="c.color ? { color: c.color } : {}">{{ fmt(stats?.[c.key]) }}</div>
          <div class="label">{{ c.label }}</div>
          <div v-if="c.sub" class="sub">{{ c.sub() }}</div>
        </el-card>
      </el-col>
    </el-row>

    <div class="section-title">今日失败 TOP 5</div>
    <el-table :data="stats?.failTop || []" border stripe>
      <el-table-column label="任务ID" width="90" prop="jobId" />
      <el-table-column label="任务名" prop="jobName" min-width="160" show-overflow-tooltip />
      <el-table-column label="JobHandler" prop="handlerName" min-width="160" show-overflow-tooltip />
      <el-table-column label="失败次数" width="110" prop="failCount" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/joblogs?jobId=${row.jobId}`)">查看日志</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span class="empty">今日无失败任务 🎉</span>
      </template>
    </el-table>
  </el-card>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 12px; align-items: center; }
.hint { color: #909399; font-size: 12px; }
.stat-card { cursor: pointer; text-align: center; }
.num { font-size: 28px; font-weight: 600; line-height: 1.2; }
.label { color: #909399; margin-top: 6px; font-size: 14px; }
.sub { color: #c0c4cc; margin-top: 8px; font-size: 12px; }
.section-title { margin: 20px 0 12px; font-weight: 600; color: #303133; }
.empty { color: #909399; }
</style>
