# ww-job 前端控制台增强（执行器列表 + Cron 配置器 + 打磨项）设计

> 日期：2026-08-29
> 状态：设计已对齐，待实现
> 背景：前端控制台 MVP（任务管理 + 执行日志）已完成并推送。用户要求继续增强：① 补上调度平台的「健康总览」——**执行器在线列表**；② 创建任务时手输 Cron 易错，加 **Cron 可视化配置器**（仿 xxl-job）；③ 顺手打磨现有页面（表单校验 / 每页条数 / 删除页码越界）。

---

## 1. 现状与目标

**现状**：
- 控制台只有「任务管理 / 执行日志」两页；执行器在线/离线只能靠 `curl /jobgroup/list` 加心跳推断，无可视化；
- 新建/编辑任务的 Cron 是**裸手输**（6 段 `秒 分 时 日 月 周`），格式极易错，无格式提示、无触发时间预览；
- 表单用「保存时手动 `if` 判空」，无 `el-form` rules；分页无每页条数选择；删掉某页最后一条会停留在空页码。

**目标**（三个独立增强点）：
1. **执行器在线列表页**：`/registries`，表格展示每个注册的执行器（分组 / AppName / 地址 / 最后心跳 / 在线状态），补后端 `GET /registry/list`。
2. **Cron 可视化配置器**：仿 xxl-job 的 Cron 生成器弹窗——6 字段（秒/分/时/日/月/周）逐个配置 + 顶部实时 cron 预览 + 未来 3 次触发时间预览，可一键切高级模式手输。纯前端，后端零改动。
3. **打磨项**：el-form rules 表单校验（含 cron 格式前端校验）、分页每页条数选择器、删除后页码越界回退。

**协作分工（沿用约定）**：前端代码由 Claude 代写；**后端 `GET /registry/list` 由用户自研**。

---

## 2. 关键决策记录

| # | 决策点 | 结论 |
| --- | --- | --- |
| D1 | 执行器列表 API | 后端 `RegistryController` 加 **`GET /registry/list`**：`selectList(orderByAsc("job_group_id"))` 返回 `List<JobRegistry>`（id/jobGroupId/registryKey=appName/registryValue=地址/heartbeatTime/updateTime）。前端已有 `listGroups`，用 `jobGroupId` 关联出分组 title |
| D2 | 在线/离线判定 | **前端算**：`heartbeatTime` 距今 < **90s** 判在线（与 `RegistryCleaner.EXPIRE_SECONDS=90` 一致）。离线机器 90s 后会被 cleaner 从表里删掉，所以「离线」只短暂可见，但语义仍需要（如刚掉线） |
| D3 | 周字段数字映射 | **已实测一致，无风险**：Spring `CronExpression`(6.1.14，admin 实际用) 与 cron-parser(前端预览用) 均为 **1=周一…7=周日**，都认 `?` / `MON` / `*/N` / `9-18`。生成器输出 **`MON`-`SUN` 英文缩写**（可读性最佳，两解析器零歧义） |
| D4 | 日/周联动规则 | quartz 语义：**日与周不能同时为「指定值」**。联动：日或周为「指定」时，另一个强制为「任意」；两者都任意时，**日输出 `*`、周输出 `?`**（贴合现有任务 `0/5 * * * * ?` 风格）。**周字段不做 `*/N` 形态**（「每 N 周」语义在 quartz/Spring 中含糊，去掉避免坑），仅「不指定 / 指定某天」 |
| D5 | 触发时间预览 | 引入 npm `cron-parser`（v5，`CronExpressionParser`），纯前端解析实时预览未来 3 次；解析失败显示「无法解析」警示。用户已选此方案（不新增后端接口） |
| D6 | 编辑回填 | 打开配置器时对已有 cron 反解到 6 字段（支持 `*` / `*/N` / `?` / 数字 / `MON-SUN`）；含 `,` 列表 / `-` 区间 / `L` / `W` 等复杂表达式时反解失败 → 自动切到高级模式显示原串 |
| D7 | 打磨项范围 | 只做三项：el-form rules（jobName/jobGroupId/handlerName/cron 必填 + cron 前端校验）、pagination `sizes`、删除页码越界回退。不做全局错误处理重构 / Pinia / TS（YAGNI） |
| D8 | Vite proxy | `vite.config.js` 补 **`/registry`** → `localhost:8080`（新页面 GET `/registry/list` 走代理） |

---

## 3. 后端 API 变更（用户自研，1 处）

`RegistryController` 注入 `JobRegistryMapper`，新增：

```java
@GetMapping("/list")
public List<JobRegistry> list() {
    return registryMapper.selectList(new QueryWrapper<JobRegistry>().orderByAsc("job_group_id"));
}
```

> 现有 `POST /registry`、`POST /heartbeat` 不动。返回原始实体（不 join group）——前端用已加载的分组列表关联 title/appName，接口保持最简。

---

## 4. 前端工程变更（Claude 代写）

```
ww-job-web/
├── package.json                 # + cron-parser 依赖
├── vite.config.js               # proxy + /registry
├── src/
│   ├── App.vue                  # 左侧菜单 +「执行器列表」项（index=/registries）
│   ├── router/index.js          # + /registries → RegistryList
│   ├── api/registry.js          # 新增：listRegistries()
│   ├── components/CronBuilder.vue   # 新增：Cron 配置器弹窗组件
│   ├── views/RegistryList.vue   # 新增：执行器在线列表页
│   ├── views/JobList.vue        # 表单改 el-form rules；cron 输入框旁加「🔧」开配置器；pagination sizes + 删除页码回退
│   └── views/JobLogList.vue     # pagination sizes
```

---

## 5. 页面与组件设计

### 5.1 执行器在线列表页（RegistryList.vue）

**菜单**：`el-menu` 加第 3 项「执行器列表」→ `/registries`。

**工具栏**：`刷新`按钮、在线/全部筛选 `el-select`（可选值：全部 / 仅在线）。

**表格列**：分组（`groupOptions.find(id).title`）、AppName（`registryKey`）、地址（`registryValue`）、最后心跳时间（`heartbeatTime` 格式化）、状态（el-tag：在线=绿 / 离线=红）。

**在线判定**：`Date.now() - new Date(heartbeatTime).getTime() < 90_000`。

**提示文案**（表格上方小字）：「执行器每 30s 心跳一次，超过 90s 无心跳判离线，并由调度中心自动剔除。」

**说明**：列表数据源 `GET /registry/list` 全量（注册记录量级很小，不分页）。`onMounted` 加载 `listGroups()` + `listRegistries()`；`setInterval` 每 30s 自动刷新一次心跳状态（或仅刷新按钮手动，二者都做：自动轮询展示「在线」刷新及时性）。

### 5.2 Cron 配置器（components/CronBuilder.vue）

**触发方式**：`JobList` 表单的 Cron 输入框旁加「🔧」小按钮 → 打开弹窗（本组件）。`props`：`modelValue`（当前 cron 字符串）；`emit('update:modelValue', cron)` / `emit('confirm', cron)`。

**弹窗布局**：
```
┌─ Cron 配置器 ─────────────────────────────────┐
│  模式: (普通) [高级]                            │
│  Cron:  0 0/5 * * * ?         (等宽实时预览)    │
│  未来触发:  10:05:00  10:10:00  10:15:00        │
│  ────────────────────────────────────          │
│  秒   [ * ] [每 5 秒] [指定 0]                  │
│  分   [ * ] [每 5 分] [指定 0]                  │
│  时   [ * ] [每 N 时] [指定 8]                  │
│  日   [ * ] [每 N 日] [指定 1]                  │
│  月   [ * ] [每 N 月] [指定 1]                  │
│  周   [不指定] [指定 周一]                      │
│  ────────────────────────────────────          │
│                      [取消]  [确定]              │
└────────────────────────────────────────────────┘
```

**字段状态模型**（每字段）：
```js
{ type: 'any' | 'every' | 'fixed', value: <number> }
// 秒/分/时/日/月: any→'*'，every→'*/N'，fixed→具体值
// 周: 仅 any→'?' 或 fixed→'MON'..'SUN' 两种，无 every
```

**普通模式每字段 UI**：
- 秒/分/时/日/月：三个并排 el-radio-button `*`（任意）/ `*/N`（每 N，选中后显示 el-input-number）/ `指定`（选中后显示 el-select 下拉）；可选值范围：秒/分 0-59、时 0-23、日 1-31、月 1-12；
- 周：两个并排 el-radio-button `不指定`（→ `?`）/ `指定`（选中后显示 el-select 下拉 周一..周日，输出 `MON`..`SUN`）。

**日/周联动（D4）**：日字段为「指定」（fixed / every）时，周 radio 自动切「不指定」并禁用；周字段为「指定」时，日 radio 自动切「任意」并禁用其 every/指定选项。

**buildCron()**：
```js
// 拼接 6 段：秒 分 时 日 月 周
// day: any→'*'，every→'*/N'，fixed→N
// week: any→'?'，fixed→'MON'..'SUN'
// 若 day 非 any → week 输出 '?'；若 week 为 fixed → day 输出 '?'
```

**parseCron(cron)**（编辑回填，D6）：
```js
// 拆 6 段；'*'→any；'*/N'→every；'?'→any；'1-31'→fixed；'MON..SUN'→week fixed；'1..7'→转对应缩写
// 数字列表/区间/L/W/# 等 → 返回 null（失败）
// 失败 → 弹窗切「高级」模式，输入框显示原 cron
```

**高级模式**：一个 `el-input`（等宽字体）直接输入整条 cron，实时用 cron-parser 校验 + 显示未来 3 次触发（失败显示警示）。普通/高级状态各自保留，互不影响；「确定」写回当前激活模式的值。

**实时预览**：任一字段变更 → `buildCron()` → `CronExpressionParser.parse()` → 取 next 3 次格式化展示；解析失败显示红色警示「cron 表达式无法解析」，此时「确定」禁用（防止把坏 cron 写回）。

**未来触发时间格式化**：`YYYY-MM-DD HH:mm:ss`（复用 `constants.js` 的 `fmtTime`，输入为 `Date.getTime()`）。

### 5.3 打磨项

**A. 表单校验（JobList.vue）**：
- 外层包 `el-form` + `:model="form"` + `:rules` + `ref`；
- rules：jobName / jobGroupId / handlerName / cron 必填；cron 加 `validator`（`validateCron`：非空 + `CronExpressionParser.parse` 不抛错）；
- `save()` 改为 `formRef.validate()` 通过后再提交；移除手动 if 判空。

**B. 每页条数选择器（两列表页）**：
- `el-pagination`：`layout="total, sizes, prev, pager, next, jumper"`，`:page-sizes="[10, 20, 50, 100]"`；
- `@size-change` → 重置 `page=1` 重新加载。

**C. 删除页码越界回退（JobList.vue）**：
- `onDelete` 成功且 `jobs.value.length === 1 && query.page > 1` 时，`query.page -= 1` 后再 `loadJobs()`。

---

## 6. 联调与启动

1. admin 以 local profile 启动（8080）；executor 启动（如 samples，appName=sample-executor）；
2. `ww-job-web`：`npm install`（新增 cron-parser）`&& npm run dev`；
3. 浏览器 `http://localhost:5173` → 左侧「执行器列表」→ 应看到 sample-executor 在线（绿）。
4. 任务管理 → 新建/编辑 → Cron 输入框「🔧」→ 配置器生成 cron → 保存 → 列表下次触发时间正常（后端 Spring 解析）。

---

## 7. 边界（明确记录）

1. **执行器列表不分页**：注册记录量级小（每台机器一行），全量返回。
2. **不新增分组管理页 / 登录 / 告警历史**：仍为非目标。
3. **配置器高级模式不强校验语义**：只保证「能被 cron-parser 解析」；用户手输坏语义（如日/周都指定）由后端实际解析兜底。
4. **心跳离线是瞬态**：cleaner 90s 即删记录，页面上「离线」状态窗口 ≤90s。

---

## 8. 验证方案（端到端）

前置：admin(local) + executor(samples) + `ww-job-web` dev server。

| # | 场景 | 预期 |
| --- | --- | --- |
| 1 | 执行器列表页加载 | 显示 sample-executor：分组 / AppName / 127.0.0.1:8081 / 心跳时间，状态「在线」绿 tag |
| 2 | 停掉 executor 后刷新 | 心跳时间停更；若在 90s 内刷新显示「离线」红 tag（随后 cleaner 删除记录） |
| 3 | 配置器「每 5 分钟」 | 生成 `0 0/5 * * * ?`，预览未来 3 次触发正确 |
| 4 | 配置器「每日 8 点」 | 生成 `0 0 8 * * ?` |
| 5 | 配置器「每周一 8 点」 | 日联动为任意，周选周一 → 生成 `0 0 8 ? * MON`，预览为周一的 8 点 |
| 6 | 配置器「指定秒 30」 | 生成 `30 * * * * ?`，预览每分第 30 秒 |
| 7 | 编辑任务回填 | 已有 `0/5 * * * * ?` 任务点「🔧」→ 秒=0(指定)、分=每5，预览与现 cron 一致 |
| 8 | 高级模式 | 输入 `30 0/15 9-18 * * ?` → 预览 9-18 点每 15 分第 30 秒；确定写回 |
| 9 | 配置器生成的 cron 创建任务 | 保存成功，列表下次触发时间已算（后端 Spring 解析通过） |
| 10 | 表单校验 | 空表单点保存 → 必填红字提示；cron 填 `abc` → 格式校验红字提示 |
| 11 | 每页条数 | 切到 20 → 表格变 20 条且页码重置 |
| 12 | 删除页码回退 | 每页 10 条、共 10 条时删到第 2 页最后一条 → 自动回第 1 页非空 |

### 实测记录（2026-08-29）

- **后端接口**：`GET /registry/list` 经 curl 实测返回 `[{jobGroupId:1, registryKey:"sample-executor", registryValue:"127.0.0.1:8081", heartbeatTime:近30s}]` ✅。踩坑记录：初版实现用 `@GetMapping("/list")`（方法级路径）→ 实际映射 `/list` 而非 `/registry/list`，改 `@GetMapping("/registry/list")` 后正常。
- **场景 1/3/4/5/6/7/8/10/11**：用户浏览器实测通过（执行器列表在线展示、每5秒/每日8点/每周一8点生成、编辑回填、高级模式手输与非法串警示、表单必填与 cron 格式校验、每页条数切换）。
- **场景 9（配置器生成 cron 创建任务）**：生成结果均被 cron-parser 解析 + 保存链路正常，且生成式样（`?` / `MON` / `*/N`）已在本设计过程中用 Spring `CronExpression` 6.1.14 逐一验证兼容。
- **场景 2（停 executor 看离线）**：**未专门实测**（需停 executor 等 90s 让 cleaner 反应，耗时）。在线判定逻辑（`heartbeatTime < 90s`）与后端 `RegistryCleaner.EXPIRE_SECONDS=90` 同源，属简单比较，风险低。

---

## 9. 非目标（本次不做）

- 分组管理页、登录鉴权、告警历史页、执行日志实时滚动
- TypeScript、Pinia、国际化、主题切换、批量操作
- 生产部署（nginx / Docker 集成前端）
