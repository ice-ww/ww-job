# 前端控制台增强实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 ww-job 前端控制台补上执行器在线列表页、Cron 可视化配置器，并打磨表单校验/分页/删除体验。

**Architecture:** 三个独立增强点——① 后端补 1 个 `GET /registry/list`（用户自研）；② 前端新增 `RegistryList.vue` 页面（执行器在线状态）+ `CronBuilder.vue` 弹窗组件（6 字段多形态配置 + cron-parser 实时预览）；③ JobList/JobLogList 打磨。全部前端代码由 Claude 代写，内联执行。

**Tech Stack:** Vue 3 `<script setup>` + JavaScript + Element Plus + Vue Router + Axios + Vite；新增依赖 `cron-parser`（v5，`CronExpressionParser`）。后端 Java 17 + Spring Boot 3.3。

**Spec:** `docs/superpowers/specs/2026-08-29-web-console-enhance-design.md`

## Global Constraints

- 前端只用 JavaScript（不用 TS）、不用 Pinia。
- cron 生成/校验统一用 `cron-parser` v5 的 `CronExpressionParser`（非 `parseExpression`）。
- 周字段**只允许** `any`（输出 `?`）或 `fixed`（输出 `MON`-`SUN` 缩写），**不做** `*/N`。
- 日/周联动：任一日或周为「指定值」时，另一字段强制为「任意」；都任意时日输出 `*`、周输出 `?`。
- 执行器在线判定阈值 = 90s（与后端 `RegistryCleaner.EXPIRE_SECONDS` 一致）。
- 后端接口 `GET /registry/list` 由**用户自研**（Claude 只给参考实现，不代写后端）。
- `vite.config.js` proxy 需补 `/registry`。
- 提交信息用 `feat:` / `docs:` 前缀；每个任务单独 commit。

---

## File Structure

| 文件 | 动作 | 职责 |
| --- | --- | --- |
| `ww-job-admin/.../controller/RegistryController.java` | 修改（用户） | + `GET /registry/list` |
| `ww-job-web/package.json` | 修改 | + `cron-parser` 依赖 |
| `ww-job-web/vite.config.js` | 修改 | proxy + `/registry` |
| `ww-job-web/src/api/registry.js` | 新增 | `listRegistries()` |
| `ww-job-web/src/router/index.js` | 修改 | + `/registries` 路由 |
| `ww-job-web/src/App.vue` | 修改 | 菜单 +「执行器列表」 |
| `ww-job-web/src/views/RegistryList.vue` | 新增 | 执行器在线列表页 |
| `ww-job-web/src/components/CronBuilder.vue` | 新增 | Cron 配置器弹窗组件 |
| `ww-job-web/src/views/JobList.vue` | 修改 | el-form rules + 🔧 配置器 + sizes + 删除回退 |
| `ww-job-web/src/views/JobLogList.vue` | 修改 | pagination sizes |
| `README.md` | 修改 | REST API 表 + 前端控制台描述 |

---

### Task 1: 后端 `GET /registry/list`（用户自研）

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/controller/RegistryController.java`

**Interfaces:**
- Produces: `GET /registry/list` → `List<JobRegistry>`（`orderByAsc("job_group_id")`），字段：`id/jobGroupId/registryKey(=appName)/registryValue(=地址)/heartbeatTime/updateTime`

- [ ] **Step 1: 用户实现**

在 `RegistryController` 注入 `JobRegistryMapper`，新增：

```java
@GetMapping("/list")
public List<JobRegistry> list() {
    return registryMapper.selectList(new QueryWrapper<JobRegistry>().orderByAsc("job_group_id"));
}
```

需要的 import：`com.wwjob.admin.entity.JobRegistry`、`com.wwjob.admin.mapper.JobRegistryMapper`、`com.baomidou.mybatisplus.core.conditions.query.QueryWrapper`、`java.util.List`。

- [ ] **Step 2: 编译验证**

```bash
mvn -pl ww-job-admin -am compile -q
```

预期：BUILD SUCCESS，无编译错误。

- [ ] **Step 3: 提交**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/controller/RegistryController.java
git commit -m "feat: RegistryController 补 GET /registry/list 查询在线执行器"
```

---

### Task 2: 前端基础设施（依赖 / proxy / API / 路由 / 菜单）

**Files:**
- Modify: `ww-job-web/package.json`
- Modify: `ww-job-web/vite.config.js`
- Create: `ww-job-web/src/api/registry.js`
- Modify: `ww-job-web/src/router/index.js`
- Modify: `ww-job-web/src/App.vue`

**Interfaces:**
- Produces: `listRegistries()`（`src/api/registry.js`）；路由 `/registries` → `RegistryList`；菜单项「执行器列表」

- [ ] **Step 1: 安装 cron-parser**

```bash
cd ww-job-web && npm install cron-parser
```

预期：`package.json` dependencies 出现 `"cron-parser": "^5.x.x"`。

- [ ] **Step 2: vite proxy 补 /registry**

`vite.config.js` 的 `server.proxy` 加一行：

```js
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/job': 'http://localhost:8080',
      '/joblog': 'http://localhost:8080',
      '/jobgroup': 'http://localhost:8080',
      '/registry': 'http://localhost:8080',
    },
  },
})
```

- [ ] **Step 3: 新建 `src/api/registry.js`**

```js
import request from './request'

export const listRegistries = () => request.get('/registry/list')
```

- [ ] **Step 4: 路由加 /registries**

`src/router/index.js`：

```js
import { createRouter, createWebHistory } from 'vue-router'
import JobList from '../views/JobList.vue'
import JobLogList from '../views/JobLogList.vue'
import RegistryList from '../views/RegistryList.vue'

const routes = [
  { path: '/', redirect: '/jobs' },
  { path: '/jobs', component: JobList },
  { path: '/joblogs', component: JobLogList },
  { path: '/registries', component: RegistryList },
]

export default createRouter({ history: createWebHistory(), routes })
```

（`RegistryList.vue` 将在 Task 3 创建，先提交路由会导致 dev server 报找不到组件——因此 Step 4 与 Task 3 合并提交，见 Task 3 Step 6。）

- [ ] **Step 5: 菜单加「执行器列表」**

`src/App.vue` 的 `el-menu`：

```html
<el-menu router :default-active="$route.path">
  <el-menu-item index="/jobs">任务管理</el-menu-item>
  <el-menu-item index="/joblogs">执行日志</el-menu-item>
  <el-menu-item index="/registries">执行器列表</el-menu-item>
</el-menu>
```

- [ ] **Step 6: 提交（仅依赖 + proxy + api + 菜单；路由等 Task 3 的页面就位后一并提交）**

```bash
git add ww-job-web/package.json ww-job-web/package-lock.json ww-job-web/vite.config.js ww-job-web/src/api/registry.js ww-job-web/src/App.vue
git commit -m "feat: 前端基础设施（cron-parser 依赖 / /registry proxy / 执行器 API / 菜单入口）"
```

---

### Task 3: 执行器在线列表页（RegistryList.vue + 路由）

**Files:**
- Create: `ww-job-web/src/views/RegistryList.vue`
- Modify: `ww-job-web/src/router/index.js`（Step 4 已写，此任务合入提交）

**Interfaces:**
- Consumes: `listRegistries()`、`listGroups()`、`fmtTime`
- Produces: 页面 `/registries`

- [ ] **Step 1: 新建 `RegistryList.vue`**

```vue
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
```

- [ ] **Step 2: 提交**

```bash
git add ww-job-web/src/views/RegistryList.vue ww-job-web/src/router/index.js
git commit -m "feat: 执行器在线列表页（分组/AppName/地址/心跳/在线状态 + 30s 轮询）"
```

---

### Task 4: Cron 配置器组件（CronBuilder.vue）

**Files:**
- Create: `ww-job-web/src/components/CronBuilder.vue`

**Interfaces:**
- Props: `modelValue: String`；Emits: `update:modelValue`、`confirm`；Expose: `open()`
- Consumes: `cron-parser` 的 `CronExpressionParser`、`fmtTime`

- [ ] **Step 1: 新建 `CronBuilder.vue`**

```vue
<script setup>
import { ref, computed, watch } from 'vue'
import { CronExpressionParser } from 'cron-parser'
import { fmtTime } from '../constants'

const props = defineProps({
  modelValue: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const dialogVisible = ref(false)
const mode = ref('normal') // 'normal' | 'advanced'
const advancedCron = ref('')

// ---- 字段状态 ----
const DEFAULT_FIELDS = () => ({
  second: { type: 'any', value: 0 },
  minute: { type: 'any', value: 0 },
  hour: { type: 'any', value: 8 },
  day: { type: 'any', value: 1 },
  month: { type: 'any', value: 1 },
  week: { type: 'any', value: 'MON' },
})
const fields = ref(DEFAULT_FIELDS())

const FIELD_OPTIONS = {
  second: Array.from({ length: 60 }, (_, i) => i),
  minute: Array.from({ length: 60 }, (_, i) => i),
  hour: Array.from({ length: 24 }, (_, i) => i),
  day: Array.from({ length: 31 }, (_, i) => i + 1),
  month: Array.from({ length: 12 }, (_, i) => i + 1),
}
const WEEK_OPTIONS = [
  { value: 'MON', label: '周一' },
  { value: 'TUE', label: '周二' },
  { value: 'WED', label: '周三' },
  { value: 'THU', label: '周四' },
  { value: 'FRI', label: '周五' },
  { value: 'SAT', label: '周六' },
  { value: 'SUN', label: '周日' },
]
const WEEK_NUM_MAP = { 1: 'MON', 2: 'TUE', 3: 'WED', 4: 'THU', 5: 'FRI', 6: 'SAT', 7: 'SUN' }

// ---- 生成 / 解析 ----
function fieldExpr(f) {
  if (f.type === 'every') return `*/${f.value}`
  if (f.type === 'fixed') return f.value
  return '*'
}
function buildCron() {
  const f = fields.value
  const daySpec = f.day.type !== 'any'
  const weekSpec = f.week.type === 'fixed'
  const day = daySpec ? fieldExpr(f.day) : weekSpec ? '?' : '*'
  const week = weekSpec ? f.week.value : '?'
  return `${fieldExpr(f.second)} ${fieldExpr(f.minute)} ${fieldExpr(f.hour)} ${day} ${fieldExpr(f.month)} ${week}`
}
const cronText = computed(() =>
  mode.value === 'normal' ? buildCron() : advancedCron.value.trim()
)

function parseSeg(seg) {
  if (seg === '*') return { type: 'any', value: 0 }
  const m = seg.match(/^\*\/(\d+)$/)
  if (m) return { type: 'every', value: Number(m[1]) }
  if (/^\d+$/.test(seg)) return { type: 'fixed', value: Number(seg) }
  return null
}
function parseCron(cron) {
  const segs = String(cron || '').trim().split(/\s+/)
  if (segs.length !== 6) return null
  const f = DEFAULT_FIELDS()
  const keys = ['second', 'minute', 'hour', 'day', 'month']
  for (let i = 0; i < 5; i++) {
    const r = parseSeg(segs[i])
    if (!r) return null
    f[keys[i]] = r
  }
  const w = segs[5]
  if (w === '?' || w === '*') {
    f.week = { type: 'any', value: 'MON' }
  } else if (WEEK_OPTIONS.some((o) => o.value === w)) {
    f.week = { type: 'fixed', value: w }
  } else if (WEEK_NUM_MAP[w]) {
    f.week = { type: 'fixed', value: WEEK_NUM_MAP[w] }
  } else {
    return null
  }
  if (f.day.type !== 'any' && f.week.type === 'fixed') return null
  return f
}

// ---- 日/周联动 ----
const dayLocked = computed(() => fields.value.week.type === 'fixed')
const weekLocked = computed(() => fields.value.day.type !== 'any')

function onFieldChange(key) {
  if (key === 'day' && weekLocked.value) {
    fields.value.week = { type: 'any', value: 'MON' }
  }
  if (key === 'week' && fields.value.week.type === 'fixed') {
    fields.value.day = { type: 'any', value: 1 }
  }
  const target = fields.value[key]
  if (target && target.type === 'every' && (!target.value || target.value < 1)) {
    target.value = 5
  }
}

// ---- 实时预览 ----
const previews = ref([])
const previewError = ref(false)
function refreshPreview() {
  const cron = cronText.value
  if (!cron) {
    previews.value = []
    previewError.value = false
    return
  }
  try {
    const it = CronExpressionParser.parse(cron, { currentDate: new Date() })
    const list = []
    for (let i = 0; i < 3; i++) list.push(fmtTime(it.next().toDate().getTime()))
    previews.value = list
    previewError.value = false
  } catch (e) {
    previews.value = []
    previewError.value = true
  }
}
watch(fields, refreshPreview, { deep: true })
watch(advancedCron, refreshPreview)

// ---- 打开 / 确定 ----
function open() {
  const parsed = parseCron(props.modelValue)
  if (parsed) {
    fields.value = parsed
    mode.value = 'normal'
  } else {
    fields.value = DEFAULT_FIELDS()
    mode.value = 'advanced'
    advancedCron.value = props.modelValue || ''
  }
  dialogVisible.value = true
  refreshPreview()
}
function onConfirm() {
  if (previewError.value) return
  emit('update:modelValue', cronText.value)
  emit('confirm', cronText.value)
  dialogVisible.value = false
}
defineExpose({ open })
</script>

<template>
  <el-dialog v-model="dialogVisible" title="Cron 配置器" width="600px">
    <div class="cron-builder">
      <div class="mode-row">
        <el-radio-group v-model="mode">
          <el-radio-button value="normal">普通</el-radio-button>
          <el-radio-button value="advanced">高级</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 高级模式 -->
      <div v-if="mode === 'advanced'">
        <el-input
          v-model="advancedCron"
          placeholder="6 段：秒 分 时 日 月 周，如 0 0/5 * * * ?"
          class="cron-input"
        />
      </div>

      <!-- 普通模式 -->
      <el-form v-else label-width="56px" label-position="left" class="field-form">
        <el-form-item label="秒">
          <el-radio-group v-model="fields.second.type" @change="onFieldChange('second')">
            <el-radio-button value="any">*</el-radio-button>
            <el-radio-button value="every">每 N 秒</el-radio-button>
            <el-radio-button value="fixed">指定</el-radio-button>
          </el-radio-group>
          <el-input-number
            v-if="fields.second.type === 'every'"
            v-model="fields.second.value" :min="1" :max="59" size="small" class="num"
          />
          <el-select
            v-else-if="fields.second.type === 'fixed'"
            v-model="fields.second.value" size="small" class="num"
          >
            <el-option v-for="v in FIELD_OPTIONS.second" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>

        <el-form-item label="分">
          <el-radio-group v-model="fields.minute.type" @change="onFieldChange('minute')">
            <el-radio-button value="any">*</el-radio-button>
            <el-radio-button value="every">每 N 分</el-radio-button>
            <el-radio-button value="fixed">指定</el-radio-button>
          </el-radio-group>
          <el-input-number
            v-if="fields.minute.type === 'every'"
            v-model="fields.minute.value" :min="1" :max="59" size="small" class="num"
          />
          <el-select
            v-else-if="fields.minute.type === 'fixed'"
            v-model="fields.minute.value" size="small" class="num"
          >
            <el-option v-for="v in FIELD_OPTIONS.minute" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>

        <el-form-item label="时">
          <el-radio-group v-model="fields.hour.type" @change="onFieldChange('hour')">
            <el-radio-button value="any">*</el-radio-button>
            <el-radio-button value="every">每 N 时</el-radio-button>
            <el-radio-button value="fixed">指定</el-radio-button>
          </el-radio-group>
          <el-input-number
            v-if="fields.hour.type === 'every'"
            v-model="fields.hour.value" :min="1" :max="23" size="small" class="num"
          />
          <el-select
            v-else-if="fields.hour.type === 'fixed'"
            v-model="fields.hour.value" size="small" class="num"
          >
            <el-option v-for="v in FIELD_OPTIONS.hour" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>

        <el-form-item label="日">
          <el-radio-group v-model="fields.day.type" @change="onFieldChange('day')">
            <el-radio-button value="any">*</el-radio-button>
            <el-radio-button value="every" :disabled="dayLocked">每 N 日</el-radio-button>
            <el-radio-button value="fixed" :disabled="dayLocked">指定</el-radio-button>
          </el-radio-group>
          <el-input-number
            v-if="fields.day.type === 'every'"
            v-model="fields.day.value" :min="1" :max="31" size="small" class="num"
          />
          <el-select
            v-else-if="fields.day.type === 'fixed'"
            v-model="fields.day.value" size="small" class="num"
          >
            <el-option v-for="v in FIELD_OPTIONS.day" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>

        <el-form-item label="月">
          <el-radio-group v-model="fields.month.type" @change="onFieldChange('month')">
            <el-radio-button value="any">*</el-radio-button>
            <el-radio-button value="every">每 N 月</el-radio-button>
            <el-radio-button value="fixed">指定</el-radio-button>
          </el-radio-group>
          <el-input-number
            v-if="fields.month.type === 'every'"
            v-model="fields.month.value" :min="1" :max="12" size="small" class="num"
          />
          <el-select
            v-else-if="fields.month.type === 'fixed'"
            v-model="fields.month.value" size="small" class="num"
          >
            <el-option v-for="v in FIELD_OPTIONS.month" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>

        <el-form-item label="周">
          <el-radio-group v-model="fields.week.type" @change="onFieldChange('week')">
            <el-radio-button value="any">不指定</el-radio-button>
            <el-radio-button value="fixed" :disabled="weekLocked">指定</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="fields.week.type === 'fixed'"
            v-model="fields.week.value" size="small" class="num"
          >
            <el-option v-for="o in WEEK_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- 实时预览 -->
      <div class="preview">
        <div class="preview-row">
          <span class="label">Cron：</span>
          <code>{{ cronText || '（空）' }}</code>
        </div>
        <div v-if="previewError" class="preview-error">⚠️ cron 表达式无法解析，请检查配置</div>
        <div v-else-if="previews.length" class="preview-row">
          <span class="label">未来触发：</span>
          <span class="times">{{ previews.join('　') }}</span>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="previewError" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.cron-builder { padding: 4px 0 8px; }
.mode-row { margin-bottom: 14px; }
.cron-input { font-family: Consolas, Menlo, monospace; }
.field-form :deep(.el-form-item) { margin-bottom: 12px; }
.field-form :deep(.el-radio-group) { margin-right: 8px; }
.num { width: 120px; }
.preview { margin-top: 14px; padding: 10px 12px; background: #f5f7fa; border-radius: 6px; }
.preview-row { display: flex; align-items: center; gap: 6px; line-height: 24px; }
.preview .label { color: #909399; font-size: 13px; white-space: nowrap; }
.preview code { font-family: Consolas, Menlo, monospace; color: #303133; }
.preview .times { color: #606266; font-size: 13px; }
.preview-error { color: #f56c6c; font-size: 13px; }
</style>
```

> Element Plus ≥ 2.6 用 `el-radio-button` 的 `value` prop（项目为 2.14.5，符合）。

- [ ] **Step 2: 提交**

```bash
git add ww-job-web/src/components/CronBuilder.vue
git commit -m "feat: Cron 配置器组件（6 字段多形态 + cron-parser 实时预览 + 日/周联动）"
```

---

### Task 5: JobList 集成（el-form rules + Cron 配置器 + sizes + 删除回退）

**Files:**
- Modify: `ww-job-web/src/views/JobList.vue`

**Interfaces:**
- Consumes: `CronBuilder.vue`（`ref.open()`）、`cron-parser` 的 `CronExpressionParser`
- Produces: 表单校验生效；Cron 输入框旁「🔧」打开配置器；分页可调每页条数；删除最后一页最后一条自动回退

- [ ] **Step 1: 重写 `JobList.vue`（完整文件，含全部改动）**

```vue
<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CronExpressionParser } from 'cron-parser'
import { pageJobs, createJob, updateJob, triggerJob, startJob, stopJob, deleteJob } from '../api/job'
import { listGroups } from '../api/group'
import { ROUTE_STRATEGIES, BLOCK_STRATEGIES, fmtTime } from '../constants'
import CronBuilder from '../components/CronBuilder.vue'

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
const formRef = ref(null)
const cronBuilderRef = ref(null)

function validateCron(rule, value, callback) {
  if (!value) {
    callback(new Error('请填写 Cron'))
    return
  }
  try {
    CronExpressionParser.parse(value)
    callback()
  } catch (e) {
    callback(new Error('Cron 格式不正确'))
  }
}

const rules = {
  jobName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  jobGroupId: [{ required: true, message: '请选择分组', trigger: 'change' }],
  handlerName: [{ required: true, message: '请输入 JobHandler', trigger: 'blur' }],
  cron: [{ validator: validateCron, trigger: 'blur' }],
}

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

function save() {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    if (editingId.value) {
      await updateJob(form.value)
      ElMessage.success('已更新')
    } else {
      await createJob({ ...form.value, triggerStatus: 0 })
      ElMessage.success('已创建，默认停用，可在列表启用')
    }
    dialogVisible.value = false
    loadJobs()
  })
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
  if (jobs.value.length === 1 && query.value.page > 1) {
    query.value.page -= 1
  }
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
      <el-table-column prop="cron" label="Cron" width="140" show-overflow-tooltip />
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
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      :page-size="query.size"
      :current-page="query.page"
      @size-change="(s) => { query.size = s; query.page = 1; loadJobs() }"
      @current-change="(p) => { query.page = p; loadJobs() }"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑任务' : '新建任务'" width="640px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="任务名称" prop="jobName">
          <el-input v-model="form.jobName" placeholder="如 alert-fail-job" />
        </el-form-item>
        <el-form-item label="分组" prop="jobGroupId">
          <el-select v-model="form.jobGroupId" placeholder="选择分组" style="width: 100%">
            <el-option v-for="g in groupOptions" :key="g.value" :label="g.label" :value="g.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="JobHandler" prop="handlerName">
          <el-input v-model="form.handlerName" placeholder="如 failDemoHandler" />
        </el-form-item>
        <el-form-item label="Cron" prop="cron">
          <div class="cron-row">
            <el-input v-model="form.cron" placeholder="6 段：秒 分 时 日 月 周，如 0 */5 * * * ?" />
            <el-button title="Cron 配置器" @click="cronBuilderRef.open()">🔧</el-button>
          </div>
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

    <CronBuilder ref="cronBuilderRef" v-model="form.cron" />
  </el-card>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 12px; align-items: center; }
.pager { margin-top: 12px; justify-content: flex-end; }
.cron-row { display: flex; gap: 8px; width: 100%; }
</style>
```

> 说明：Cron 列加 `show-overflow-tooltip`（长 cron 不撑破表格）；`save()` 改为 `formRef.validate` 回调；删除后 `jobs.length === 1 && page > 1` 时页码回退。

- [ ] **Step 2: 提交**

```bash
git add ww-job-web/src/views/JobList.vue
git commit -m "feat: 任务表单 el-form 校验 + Cron 配置器接入 + 每页条数 + 删除页码回退"
```

---

### Task 6: JobLogList 分页条数选择

**Files:**
- Modify: `ww-job-web/src/views/JobLogList.vue`

- [ ] **Step 1: 改 el-pagination**

将现有：

```html
<el-pagination
  class="pager"
  layout="total, prev, pager, next, jumper"
  :total="total"
  :page-size="query.size"
  :current-page="query.page"
  @current-change="(p) => { query.page = p; loadLogs() }"
/>
```

替换为：

```html
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
```

- [ ] **Step 2: 提交**

```bash
git add ww-job-web/src/views/JobLogList.vue
git commit -m "feat: 执行日志页分页每页条数选择"
```

---

### Task 7: 端到端验证 + README + 推送

**Files:**
- Modify: `README.md`

**前置：** admin(local) 启动 + 至少一台 executor + `ww-job-web` `npm run dev`。

- [ ] **Step 1: 验证执行器列表页**

浏览器 `http://localhost:5173/registries` → 左侧菜单出现「执行器列表」，表格显示 sample-executor：分组 title、AppName、`127.0.0.1:8081`、最后心跳（近 30s 内）、状态「在线」绿 tag。点击「仅在线」筛选正常。30s 后心跳时间自动刷新。

- [ ] **Step 2: 验证 Cron 配置器（新建任务）**

任务管理 → 新建 → Cron 输入框旁「🔧」→ 依次验证：
- 秒「每 N 秒」N=5 → 顶部 Cron 变 `0/5 * * * * ?`，预览 3 次触发为每 5 秒一次；
- 时「指定」选 8、分「指定」选 0 → `0 0 8 * * ?`，预览为每天 8:00；
- 周「指定」选周一、时 8 → 日联动为任意 → `0 0 8 ? * MON`，预览为每周一 8:00；
- 点「确定」→ 表单 cron 输入框回填生成结果。

- [ ] **Step 3: 验证 Cron 配置器（编辑回填 + 高级模式）**

- 编辑一个 cron=`0/5 * * * * ?` 的任务 → 🔧 → 应显示 秒=0(指定)、分=每5，预览一致；
- 切「高级」输入 `30 0/15 9-18 * * ?` → 预览 9-18 点每 15 分第 30 秒，确定写回；
- 输入非法串 `abc` → 红色警示、确定按钮禁用。

- [ ] **Step 4: 验证配置器 cron 创建任务 + 打磨项**

- 用配置器生成的 cron 保存新建任务 → 列表「下次触发」时间已计算（后端 Spring 解析通过）；
- 空表单点保存 → 必填红字提示（任务名称/分组/Handler/Cron）；
- cron 填 `abc` 点保存 → 「Cron 格式不正确」；
- 分页切到每页 20 条 → 表格 20 条且页码重置；
- 造 10 条任务、每页 10 条、进第 2 页删除唯一一条 → 自动回第 1 页显示剩余 9 条。

- [ ] **Step 5: 更新 README**

- REST API 表加一行：`| GET | /registry/list | 在线执行器列表 |`；
- 前端控制台一行（第 193 行附近）更新为：「前端控制台（`ww-job-web`，Vue3 + Element Plus，任务管理 / 执行日志 / 执行器在线列表 + Cron 可视化配置）」。

- [ ] **Step 6: 提交并推送**

```bash
git add README.md
git commit -m "docs: 标记执行器列表与 Cron 配置器已实现"
git push -u origin main
```

---

## 自审

- **Spec 覆盖**：D1/D8（T1/T2）、执行器页（T3）、Cron 配置器全部决策 D3-D6（T4）、打磨项 D7（T5/T6）、验证 12 场景（T7）。✓
- **Placeholder 扫描**：所有代码步骤含完整可运行代码，无「后续补」字样。✓
- **类型一致性**：`CronExpressionParser.parse`、`listRegistries()`、`cronBuilderRef.open()`、`fmtTime(ts)` 各任务签名一致；CronBuilder 的 props/emits/expose 与 JobList 用法匹配。✓
