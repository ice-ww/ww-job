# ww-job 前端控制台（Web Console）设计

> 日期：2026-08-29
> 状态：设计已对齐，待实现
> 背景：后端链路已完整（注册/心跳→调度→分发→回调→日志→重试→告警），但只能靠 curl / SQL 操作。README 第 193 行「前端控制台」自建仓起未实现——**前端从未创建**，仓库无任何 Vue/前端代码。需要一个可视化控制台管理任务、查看执行日志。

---

## 1. 现状与目标

**现状**：
- 任务操作（新建/编辑/启停/手动触发）只能 `curl` 调 REST API；
- 执行结果只能查 `job_log` 表或收告警邮件；
- 仓库中**无任何前端代码**（无 vue/frontend/web/ui/static 目录）。

**目标**：提供一个 Vue3 管理控制台，两个页面覆盖日常运维闭环：
1. **任务管理**：列表 + 新建/编辑/删除 + 启停 + 手动触发 + 按分组筛选 + 跳转日志；
2. **执行日志**：列表 + 按任务/状态筛选 + 详情。

**协作分工（重要）**：**前端代码由 Claude 代写**（用户 2026-08-28 明确要求，精力放在后端）；**后端两个补强接口由用户自研**（保持「用户写后端、Claude 指导」的既有约定）。设计与计划流程照常走 spec → plan。

---

## 2. 关键决策记录

| # | 决策点 | 结论 |
| --- | --- | --- |
| D1 | 前端工程放哪 | **仓库根新建 `ww-job-web/` 独立 Vite 工程，不进 Maven 构建**。前后端一处托管学习成本低，Maven 构建不受污染。部署方式（nginx / 拷入 static）记为边界，本次不做 |
| D2 | 技术栈 | Vue 3 组合式 API（`<script setup>`）+ **JavaScript**（不用 TS）+ Vue Router + Element Plus + Axios + Vite。用户后端方向，JS 学习曲线低；两页规模 TS 收益小 |
| D3 | 状态管理 | **不用 Pinia**。两页独立，日志页通过路由 `query` 接收 `jobId` 即可 |
| D4 | 跨域方案 | **Vite dev server proxy**：`/job` `/joblog` `/jobgroup` → `http://localhost:8080`（admin 端口）。前后端同源，无 CORS 配置 |
| D5 | 任务删除 | 后端现无 DELETE 接口，**补一个 `DELETE /job/{id}`**。边界：只删 `job_info` 行，不做运行中保护（YAGNI，第一版简单删除） |
| D6 | 任务按分组筛选 | 后端 `GET /job/page` 现只有 page/size，**补可选 `jobGroupId` 参数**做 `eq` 过滤 |
| D7 | 表单下拉取值 | 路由策略固定四项：`round_robin`(默认)/`random`/`failover`/`sharding`；阻塞策略两项：`serial`(默认)/`SINGLE`。值来自 `ExecutorRouterService.route()` 的 switch 与 `JobDecisionService` 的判定 |
| D8 | 新建任务初始状态 | `triggerStatus=0`（停用），用户建完手动启用——避免建任务即被调度 |

---

## 3. 后端现有 API 面（前端可对接）

| 领域 | 现有接口 | 说明 |
| --- | --- | --- |
| 任务 | `POST /job` | 新建（body=JobInfo，服务端算 triggerNextTime） |
| 任务 | `PUT /job` | 更新 |
| 任务 | `GET /job/page?page&size` | 分页列表（`orderByDesc(id)`，**本次补 jobGroupId 可选参数**） |
| 任务 | `POST /job/{id}/trigger` | 手动触发 |
| 任务 | `POST /job/{id}/start` / `stop` | 启用 / 停用 |
| 任务 | `DELETE /job/{id}` | **本次新增** |
| 日志 | `GET /joblog/page?page&size&jobId&status` | 分页，已支持 jobId/status 过滤 |
| 日志 | `GET /joblog/{id}` | 详情 |
| 分组 | `GET /jobgroup/list` | 分组列表（下拉数据源） |

**后端补强（用户自研，共 2 处，均在 `JobController`）**：

1. `DELETE /job/{id}`
   ```java
   @DeleteMapping("/{id}")
   public ReturnT<String> delete(@PathVariable Long id) {
       jobInfoMapper.deleteById(id);
       return ReturnT.success();
   }
   ```
2. `page` 增加可选 `jobGroupId`：
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

---

## 4. 工程结构与目录（`ww-job-web/`）

```
ww-job-web/
├── index.html
├── package.json
├── vite.config.js          # proxy: /job /joblog /jobgroup → http://localhost:8080
├── src/
│   ├── main.js             # createApp + ElementPlus + router
│   ├── App.vue             # el-container：左侧菜单 + 右侧 router-view
│   ├── router/index.js     # /jobs、/joblogs 两条路由
│   ├── api/
│   │   ├── request.js      # Axios 实例（baseURL='/'，走 proxy）+ 响应拦截
│   │   ├── job.js          # 任务 CRUD / trigger / start / stop / page
│   │   ├── log.js          # 日志 page / detail
│   │   └── group.js        # group list
│   ├── views/
│   │   ├── JobList.vue     # 任务管理页
│   │   └── JobLogList.vue  # 执行日志页
│   └── constants.js        # 状态/策略枚举 → 中文标签与 el-tag 颜色映射
```

> `ww-job-web/` 是独立 npm 工程，不注册进根 `pom.xml`，不影响 Maven 构建。
> 在根 `.gitignore` 补 `ww-job-web/node_modules/`、`ww-job-web/dist/`。

---

## 5. 页面设计

### 5.1 布局（App.vue）

- `el-container`：左侧 `el-aside`（logo「ww-job」+ `el-menu` 两个菜单项：任务管理、执行日志），右侧 `el-main` 渲染 `router-view`。
- 不设顶部导航/用户区（YAGNI，无登录）。

### 5.2 任务管理页（JobList.vue）

**工具栏**：`新建任务`按钮、按分组筛选 `el-select`（数据源 `GET /jobgroup/list`，显示 title、值为 id，可清空=全部）、`刷新`按钮。

**表格列**：ID、任务名称、分组（title）、JobHandler、Cron、路由策略、阻塞策略、状态（`triggerStatus`：1 启用绿色 / 0 停用灰色）、下次触发时间（时间戳转 `YYYY-MM-DD HH:mm:ss`）、上次触发时间、操作。

**操作列**：`触发`（confirm 后 `POST /{id}/trigger`）、`启动/停用`（按当前状态切换调 `/start` 或 `/stop`）、`编辑`、`删除`（`el-popconfirm` 二次确认，调 `DELETE /job/{id}`）、`日志`（`router.push('/joblogs?jobId='+id)`）。

**新建/编辑表单**（`el-dialog` + `el-form`）字段：
- jobName（必填）、jobGroupId（下拉，必填）、handlerName（必填）、cron（必填，格式校验规则：`秒 分 时 日 月 周`）、jobDesc、executorParam（多行）、routeStrategy（下拉 4 项，默认 round_robin）、blockStrategy（下拉 2 项，默认 serial）、retryCount（el-input-number，默认 0）、timeout（el-input-number，默认 0）、alarmConfig（逗号分隔邮箱，提示文案）。

新建提交 `POST /job`（body 含 `triggerStatus:0`）；编辑提交 `PUT /job`（body 含 id + 全字段）。成功后刷新列表 + `ElMessage.success`。

### 5.3 执行日志页（JobLogList.vue）

**工具栏**：按任务筛选 `el-select`（数据源 `GET /job/page?size=1000`，显示 jobName，值为 id，可清空）、按状态筛选 `el-select`（全部 / 运行中 / 成功 / 失败 / 未知 → 值 0/1/2/3，可清空）、`刷新`按钮。

**读取路由 query**：`onMounted` 时若 `route.query.jobId` 存在，自动填入任务筛选下拉并触发查询（支持从任务列表「日志」按钮跳转）。

**表格列**：日志ID、任务ID、执行器地址、Handler、触发方式、触发时间、处理时间、状态（`el-tag`：0 运行中蓝 / 1 成功绿 / 2 失败红 / 3 未知橙）、处理消息（截断，超出 tooltip）、操作：`详情`。

**详情弹窗**（`el-dialog`）：展示全部字段——日志ID、任务ID、分组ID、执行器地址、Handler、触发方式、触发/处理时间、handleCode、handleMsg（完整文本，`white-space: pre-wrap`）、状态、shardIndex（分片索引）。

---

## 6. 联调与启动

1. admin 以 local profile 启动（端口 8080）：
   `mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local`
2. 启动任一 executor（如 `ww-job-executor-samples`），保证有在线执行器可触发。
3. `ww-job-web` 下 `npm install && npm run dev`（默认 5173），浏览器打开 `http://localhost:5173`。
4. Vite proxy 把 `/job*` `/joblog*` `/jobgroup*` 转发到 8080，前端无跨域配置。

---

## 7. 边界（明确记录）

1. **部署方式本次不做**：只保证本地 dev 联调；`npm run build` 产物 `dist/` 如何托管（nginx / 拷入 admin static）记入非目标。
2. **任务删除不保护运行中状态**：`DELETE /job/{id}` 直接删 `job_info` 行，不影响已入队/执行中的日志。
3. **无登录鉴权**：控制台任何功能直接可调（本地学习项目）。
4. **无执行器在线列表页**：注册中心只有 executor 注册/心跳接口，缺查询 API，第一版不做此页面。
5. **无分组管理页**：分组只作为任务下拉数据源（`GET /jobgroup/list`）。
6. **无告警历史页**：失败告警走邮件，无记录表可展示。

---

## 8. 验证方案（端到端）

前置：admin(local) + 一台 executor + `ww-job-web` dev server 起来。

| # | 场景 | 预期 |
| --- | --- | --- |
| 1 | 任务管理页列表加载 | 显示现有任务，分页正常，分组 title 正确渲染 |
| 2 | 新建任务（填表单，triggerStatus=0） | 列表出现新任务，状态「停用」，下次触发时间已计算 |
| 3 | 启用该任务 + 手动触发 | 状态变「启用」；「日志」按钮跳日志页并自动带 jobId 筛选 |
| 4 | 日志页按状态筛「成功/失败/未知」 | 各状态 tag 颜色与筛选结果正确 |
| 5 | 用 failDemoHandler 任务触发 | 日志页出现 status=2 记录，handleMsg 显示「模拟业务失败」 |
| 6 | 编辑任务（改 cron） | 保存后列表下次触发时间刷新 |
| 7 | 删除任务（popconfirm 确认） | 列表消失，`job_info` 中无该行 |
| 8 | 分组筛选下拉 | 选某分组后列表只显示该组任务 |

### 实测记录（2026-08-29）

8 场景全过 ✅：列表加载、新建（默认停用 + 下次触发已算）、启用 + 触发、日志跳转带 jobId 预填、按状态筛选、failDemoHandler 触发显示 status=2「模拟业务失败」、编辑改 cron 下次触发刷新、删除（popconfirm）、分组筛选。

另实测「handler 名填错」场景（表单填 `DemoHandler`，实际注册为 `demoHandler`）→ executor 返回「handler 未注册: DemoHandler」→ 日志正确落 status=2 失败，详情弹窗完整展示 handleCode=500 与错误消息——失败路径与错误消息展示均符合预期。

---

## 9. 非目标（本次不做）

- 执行器在线/离线列表页、分组管理页、登录鉴权、告警历史页
- TypeScript、Pinia、国际化、主题切换、批量操作
- 生产部署（nginx / Docker 集成前端）
- 任务删除的运行中保护、删除日志（级联）
