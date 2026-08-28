# 前端控制台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建 `ww-job-web` Vue3 控制台（任务管理 + 执行日志两页），并给后端补两个小接口支撑删除与分组筛选。

**Architecture:** 仓库根新建独立 Vite 工程 `ww-job-web/`（不进 Maven）。开发期 Vite dev(5173) 通过 proxy 把 `/job` `/joblog` `/jobgroup` 转发到 admin(8080)，前后端同源、无 CORS。后端 `JobController` 补 `DELETE /job/{id}` 与 `/job/page` 的 `jobGroupId` 可选参数。

**Tech Stack:** Vue 3（`<script setup>` 组合式 API）+ JavaScript + Vue Router 4 + Element Plus + Axios + Vite。

**Spec:** `docs/superpowers/specs/2026-08-29-web-console-design.md`

## Global Constraints

- 前端放 `ww-job-web/` 独立 Vite 工程，**不进 Maven**，不注册进根 `pom.xml`
- Vue3 `<script setup>` + **JavaScript**（不用 TS），不用 Pinia
- 依赖：`element-plus`、`vue-router@4`、`axios`
- Vite proxy：`/job` `/joblog` `/jobgroup` → `http://localhost:8080`
- 前端代码由 Claude 代写；**后端两个接口（Task 1）由用户自研**，Claude 只指导
- 前端不做单元测试（管理台 UI 测试性价比低），以 spec §8 端到端验证表为准
- 路由策略固定值：`round_robin`(默认) / `random` / `failover` / `sharding`；阻塞策略：`serial`(默认) / `SINGLE`
- 日志状态映射：0 运行中(primary) / 1 成功(success) / 2 失败(danger) / 3 未知(warning)
- 新建任务 body 显式带 `triggerStatus: 0`
- 根 `.gitignore` 追加 `ww-job-web/node_modules/`、`ww-job-web/dist/`
- 时间戳（JobInfo.triggerNextTime/triggerLastTime，Long 毫秒）前端统一 `fmtTime()` 转 `YYYY-MM-DD HH:mm:ss`

---

### Task 1: 后端补强（DELETE + jobGroupId 筛选）— 用户自研

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/controller/JobController.java`

**Interfaces:**
- Consumes: 现有 `JobInfoMapper`、`QueryWrapper`、`Page`（均已在文件中 import）
- Produces: `DELETE /job/{id}`（删一行 job_info，返回 `ReturnT<String>`）；`GET /job/page` 增加可选参数 `jobGroupId`

- [ ] **Step 1: 在 `JobController` 加删除接口**

仿照文件里 `start`/`stop` 的写法，新增：

```java
@DeleteMapping("/{id}")
public ReturnT<String> delete(@PathVariable Long id) {
    jobInfoMapper.deleteById(id);
    return ReturnT.success();
}
```

- [ ] **Step 2: 改造 `page` 方法加 `jobGroupId` 可选参数**

现有代码是直接 `selectPage(new Page<>(page, size), new QueryWrapper<JobInfo>().orderByDesc("id"))`。改成：

```java
@GetMapping("/page")
public Page<JobInfo> page(@RequestParam(defaultValue = "1") long page,
                          @RequestParam(defaultValue = "10") long size,
                          @RequestParam(required = false) Long jobGroupId) {
    QueryWrapper<JobInfo> qw = new QueryWrapper<>();
    if (jobGroupId != null) qw.eq("job_group_id", jobGroupId);
    qw.orderByDesc("id");
    return jobInfoMapper.selectPage(new Page<>(page, size), qw);
}
```

- [ ] **Step 3: 编译**

Run（仓库根）：`mvn -pl ww-job-admin -am compile -q`
Expected: BUILD SUCCESS，无报错

- [ ] **Step 4: curl 验证两个接口**

启动 admin（local profile）：`mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local`

```bash
# ① 分页带 jobGroupId 筛选（拿真实分组 id 替换 1）
curl.exe "http://localhost:8080/job/page?page=1&size=5&jobGroupId=1"
# ② 删除一个任务（拿真实任务 id 替换，如 9999）
curl.exe -X DELETE "http://localhost:8080/job/9999"
```

Expected: ① 只返回该分组的任务；② 返回 `{"code":200,...}` 且再查 `GET /job/page` 无该 id

- [ ] **Step 5: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/controller/JobController.java
git commit -m "feat: JobController 补 DELETE /job/{id} 与 /job/page 的 jobGroupId 筛选"
```

---

### Task 2: 前端脚手架 — Claude 代写

**Files:**
- Create: `ww-job-web/`（Vite 模板生成）
- Create: `ww-job-web/vite.config.js`
- Modify: `.gitignore`（追加两行）

**Interfaces:**
- Produces: 可 `npm run dev` 启动的 Vite+Vue3 空壳工程，proxy 已配好（后续任务都在其上叠加）

- [ ] **Step 1: 用 Vite 模板生成工程**

仓库根运行（生成 `ww-job-web/` 目录）：

```bash
npm create vite@latest ww-job-web -- --template vue
```

Expected: 生成标准 Vite Vue 模板（含 `src/`、`index.html`、`package.json`、`vite.config.js`）

- [ ] **Step 2: 清理模板多余文件**

删除模板自带的示例组件与样式（避免干扰后续）：

```bash
rm -f ww-job-web/src/components/HelloWorld.vue ww-job-web/src/style.css
```

`src/App.vue` 与 `src/assets/vue.svg` 等保留（Task 3 会覆盖 App.vue）。

- [ ] **Step 3: 安装依赖**

```bash
cd ww-job-web
npm install
npm install element-plus vue-router@4 axios
```

Expected: `package.json` 的 dependencies 出现 `element-plus`、`vue-router`、`axios`

- [ ] **Step 4: 写 `vite.config.js`（proxy 联调配置）**

覆盖文件内容：

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/job': 'http://localhost:8080',
      '/joblog': 'http://localhost:8080',
      '/jobgroup': 'http://localhost:8080',
    },
  },
})
```

- [ ] **Step 5: 根 `.gitignore` 追加前端产物**

在 `.gitignore` 末尾追加：

```
# 前端
ww-job-web/node_modules/
ww-job-web/dist/
```

- [ ] **Step 6: 启动验证**

```bash
cd ww-job-web && npm run dev
```

浏览器开 `http://localhost:5173`，Expected: 看到 Vite 默认页面（Vue logo + counter 示例）说明工程能跑。之后 Ctrl+C 停掉。

- [ ] **Step 7: Commit**

```bash
git add .gitignore ww-job-web
git commit -m "chore: 前端脚手架 ww-job-web（Vite+Vue3+Element Plus+Router+Axios）"
```

---

### Task 3: 前端基础框架（路由 / API 层 / 布局 / 常量）— Claude 代写

**Files:**
- Create: `ww-job-web/src/main.js`
- Create: `ww-job-web/src/router/index.js`
- Create: `ww-job-web/src/api/request.js`
- Create: `ww-job-web/src/api/job.js`
- Create: `ww-job-web/src/api/log.js`
- Create: `ww-job-web/src/api/group.js`
- Create: `ww-job-web/src/constants.js`
- Overwrite: `ww-job-web/src/App.vue`
- Create: `ww-job-web/src/views/JobList.vue`、`ww-job-web/src/views/JobLogList.vue`（先放最小空壳，Task 4/5 填内容）

**Interfaces:**
- Consumes: Task 2 的工程骨架
- Produces:
  - 路由：`/` 重定向 `/jobs`；`/jobs` → JobList；`/joblogs` → JobLogList
  - API 函数（后续页面使用）：`pageJobs(params)`、`createJob(data)`、`updateJob(data)`、`triggerJob(id)`、`startJob(id)`、`stopJob(id)`、`deleteJob(id)`、`pageLogs(params)`、`getLogDetail(id)`、`listGroups()`
  - 常量：`ROUTE_STRATEGIES`、`BLOCK_STRATEGIES`、`LOG_STATUS`、`fmtTime(ts)`

- [ ] **Step 1: `src/main.js`**

```js
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

createApp(App).use(router).use(ElementPlus).mount('#app')
```

- [ ] **Step 2: `src/router/index.js`**

```js
import { createRouter, createWebHistory } from 'vue-router'
import JobList from '../views/JobList.vue'
import JobLogList from '../views/JobLogList.vue'

const routes = [
  { path: '/', redirect: '/jobs' },
  { path: '/jobs', component: JobList },
  { path: '/joblogs', component: JobLogList },
]

export default createRouter({ history: createWebHistory(), routes })
```

- [ ] **Step 3: `src/api/request.js`**

```js
import axios from 'axios'

const request = axios.create({ baseURL: '/' })

request.interceptors.response.use(
  (res) => res,
  (err) => {
    console.error('请求失败', err)
    return Promise.reject(err)
  }
)

export default request
```

- [ ] **Step 4: `src/api/job.js`、`log.js`、`group.js`**

`job.js`：
```js
import request from './request'

export const pageJobs = (params) => request.get('/job/page', { params })
export const createJob = (data) => request.post('/job', data)
export const updateJob = (data) => request.put('/job', data)
export const triggerJob = (id) => request.post(`/job/${id}/trigger`)
export const startJob = (id) => request.post(`/job/${id}/start`)
export const stopJob = (id) => request.post(`/job/${id}/stop`)
export const deleteJob = (id) => request.delete(`/job/${id}`)
```

`log.js`：
```js
import request from './request'

export const pageLogs = (params) => request.get('/joblog/page', { params })
export const getLogDetail = (id) => request.get(`/joblog/${id}`)
```

`group.js`：
```js
import request from './request'

export const listGroups = () => request.get('/jobgroup/list')
```

- [ ] **Step 5: `src/constants.js`**

```js
export const ROUTE_STRATEGIES = [
  { value: 'round_robin', label: '轮询' },
  { value: 'random', label: '随机' },
  { value: 'failover', label: '故障转移' },
  { value: 'sharding', label: '分片广播' },
]

export const BLOCK_STRATEGIES = [
  { value: 'serial', label: '串行' },
  { value: 'SINGLE', label: '单机互斥' },
]

export const LOG_STATUS = [
  { value: 0, label: '运行中', tag: 'primary' },
  { value: 1, label: '成功', tag: 'success' },
  { value: 2, label: '失败', tag: 'danger' },
  { value: 3, label: '未知', tag: 'warning' },
]

export const fmtTime = (ts) => {
  if (!ts) return '-'
  const d = new Date(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
```

- [ ] **Step 6: `src/App.vue`（布局：左菜单 + 右路由视图）**

```vue
<script setup>
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">ww-job</div>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/jobs">任务管理</el-menu-item>
        <el-menu-item index="/joblogs">执行日志</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout { height: 100vh; }
.aside { background: #001529; color: #fff; }
.aside .logo { height: 56px; line-height: 56px; text-align: center; font-size: 18px; font-weight: bold; color: #fff; }
.aside :deep(.el-menu) { border-right: none; background: transparent; }
.main { background: #f5f7fa; padding: 16px; }
</style>
```

- [ ] **Step 7: 两个视图放最小空壳（Task 4/5 填）**

`src/views/JobList.vue`：
```vue
<template>
  <el-card>任务管理（待实现）</el-card>
</template>
```

`src/views/JobLogList.vue`：
```vue
<template>
  <el-card>执行日志（待实现）</el-card>
</template>
```

- [ ] **Step 8: 启动验证**

`cd ww-job-web && npm run dev`，浏览器 5173。
Expected: 左侧深色菜单（任务管理/执行日志）+ 右侧内容区；点两个菜单项能在两个空壳页间切换，地址栏变为 `/jobs` `/joblogs`，无红色报错。之后 Ctrl+C。

- [ ] **Step 9: Commit**

```bash
git add ww-job-web/src
git commit -m "feat: 前端基础框架（路由/API 层/布局/常量映射）"
```

---

### Task 4: 任务管理页 — Claude 代写

**Files:**
- Overwrite: `ww-job-web/src/views/JobList.vue`

**Interfaces:**
- Consumes: Task 3 的 `pageJobs/createJob/updateJob/triggerJob/startJob/stopJob/deleteJob`、`listGroups`、`ROUTE_STRATEGIES/BLOCK_STRATEGIES/fmtTime`
- Produces: 任务列表（分页 + 分组筛选）、新建/编辑弹窗、操作列（触发/启停/删除/跳日志）

- [ ] **Step 1: 完整实现 `src/views/JobList.vue`**

```vue
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
```

- [ ] **Step 2: 启动验证**

admin(local, 8080) 起着、有 executor 在线、库里已有任务。`cd ww-job-web && npm run dev` → 5173 → 任务管理页。

Expected: 表格出现现有任务（名称/分组/Cron/状态等正确）；分组下拉筛选生效；翻页正常；点「新建任务」弹窗能填能存；点「编辑/触发/启用停用/删除」各功能无报错。之后 Ctrl+C。

- [ ] **Step 3: Commit**

```bash
git add ww-job-web/src/views/JobList.vue
git commit -m "feat: 任务管理页（列表/分组筛选/新建编辑/启停/触发/删除）"
```

---

### Task 5: 执行日志页 — Claude 代写

**Files:**
- Overwrite: `ww-job-web/src/views/JobLogList.vue`

**Interfaces:**
- Consumes: Task 3 的 `pageLogs/getLogDetail`、Task 3 的 `pageJobs`（作任务筛选下拉数据源）、`LOG_STATUS/fmtTime`
- Produces: 日志列表（按任务/状态筛选）、详情弹窗、支持路由 `query.jobId` 预填筛选

- [ ] **Step 1: 完整实现 `src/views/JobLogList.vue`**

```vue
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
        <el-option v-for="s in statusOptions" :key="String(s.value)" :label="s.label" :value="s.value" />
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
      layout="total, prev, pager, next, jumper"
      :total="total"
      :page-size="query.size"
      :current-page="query.page"
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
```

- [ ] **Step 2: 启动验证**

admin(local) 起着、已触发过任务（库里有 job_log）。`npm run dev` → 日志页。

Expected: 日志列表展示；按任务/状态筛选生效；点「详情」弹窗完整字段；从任务管理页点「日志」按钮跳转过来自动带 jobId 筛选。之后 Ctrl+C。

- [ ] **Step 3: Commit**

```bash
git add ww-job-web/src/views/JobLogList.vue
git commit -m "feat: 执行日志页（任务/状态筛选/详情/路由预填 jobId）"
```

---

### Task 6: 端到端验证（spec §8 八场景）— 用户配合

**Files:** 无代码改动

**Interfaces:**
- Consumes: Task 1-5 全部产物；admin(local, 8080) + 至少一台 executor 在线

- [ ] **Step 1: 起环境**

- admin：`mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local`（8080）
- 一台 executor：`ww-job-executor-samples`（如 8081），保证有 handler（含 `failDemoHandler`）
- 前端：`cd ww-job-web && npm run dev`（5173）

- [ ] **Step 2: 按 spec §8 验证表逐场景走**

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 打开任务管理页 | 现有任务列表、分组 title、分页正常 |
| 2 | 新建任务（填全必填） | 列表出现，状态「停用」，下次触发时间已算 |
| 3 | 启用 + 手动触发 + 点「日志」 | 状态变「启用」；日志页自动带 jobId 筛选 |
| 4 | 日志页按状态筛 成功/失败/未知 | 各状态 tag 颜色与结果正确 |
| 5 | 触发 failDemoHandler 任务 | 日志页出现 status=2，handleMsg=「模拟业务失败」 |
| 6 | 编辑任务改 cron | 保存后下次触发时间刷新 |
| 7 | 删除任务（confirm） | 列表消失，`job_info` 无该行 |
| 8 | 分组筛选下拉 | 只显示该分组任务 |

Expected: 8 项全过；若某场景不符，回查代码定位（前端看 Console/Network，后端看日志）。

- [ ] **Step 3: 收尾记录（把实测结果回填 spec §8 表格）**

在 `docs/superpowers/specs/2026-08-29-web-console-design.md` §8 表格后追加一段「实测记录：8 场景全过（日期）」；如发现 bug，补「验证中发现并修复的问题」小节。改完 commit：

```bash
git add docs/superpowers/specs/2026-08-29-web-console-design.md
git commit -m "docs: 前端控制台端到端验证结果回填"
```

---

### Task 7: README 标记 + 收尾

**Files:**
- Modify: `README.md`（第 193 行「前端控制台」勾选 + 前端目录说明）

- [ ] **Step 1: README 打勾**

README 中 `- [ ] 前端控制台` → `- [x] 前端控制台`；若有「模块/目录」清单，在 `ww-job-web` 对应位置标注（Vue3 + Element Plus + Vite 控制台，任务管理 + 执行日志）。

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: README 标记前端控制台已实现"
```

- [ ] **Step 3: 推送**

```bash
git push
```
