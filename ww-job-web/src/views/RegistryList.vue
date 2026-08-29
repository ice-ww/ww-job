<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listRegistries } from '../api/registry'
import { listGroups } from '../api/group'
import { fmtTime } from '../constants'

const ONLINE_SECONDS = 90

const registries = ref([])
const groups = ref([])
const loading = ref(false)
const filter = ref('all') // 'all' | 'online'

const groupOptions = computed(() =>
  groups.value.map((g) => ({ label: g.title, value: g.id }))
)

function isOnline(r) {
  return Date.now() - new Date(r.heartbeatTime).getTime() < ONLINE_SECONDS * 1000
}
function groupTitle(id) {
  return groupOptions.value.find((g) => g.value === id)?.label || String(id)
}
const filtered = computed(() =>
  filter.value === 'online' ? registries.value.filter(isOnline) : registries.value
)

async function load() {
  loading.value = true
  try {
    const res = await listRegistries()
    registries.value = res.data
  } catch (e) {
    ElMessage.error('加载执行器列表失败，请确认 admin 已启动')
  } finally {
    loading.value = false
  }
}

let timer = null
onMounted(() => {
  listGroups().then((res) => { groups.value = res.data })
  load()
  timer = setInterval(load, 30000) // 30s 轮询心跳状态
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-select v-model="filter" style="width: 140px">
        <el-option label="全部" value="all" />
        <el-option label="仅在线" value="online" />
      </el-select>
      <el-button @click="load">刷新</el-button>
      <span class="hint">执行器每 30s 心跳一次，超过 90s 无心跳判离线，并由调度中心自动剔除</span>
    </div>

    <el-table v-loading="loading" :data="filtered" border stripe>
      <el-table-column label="分组" width="160">
        <template #default="{ row }">{{ groupTitle(row.jobGroupId) }}</template>
      </el-table-column>
      <el-table-column prop="registryKey" label="AppName" min-width="160" show-overflow-tooltip />
      <el-table-column prop="registryValue" label="执行器地址" min-width="160" show-overflow-tooltip />
      <el-table-column label="最后心跳" width="180">
        <template #default="{ row }">{{ fmtTime(new Date(row.heartbeatTime).getTime()) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="isOnline(row) ? 'success' : 'danger'">
            {{ isOnline(row) ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 12px; align-items: center; }
.hint { color: #909399; font-size: 12px; }
</style>
