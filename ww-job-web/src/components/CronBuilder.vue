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
