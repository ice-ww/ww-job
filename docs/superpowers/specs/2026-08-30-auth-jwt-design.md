# 登录鉴权（自研 JWT + BCrypt）设计

**日期**：2026-08-30
**作者**：ice-ww / Claude

## 1. 背景与目标

ww-job 控制台目前**零鉴权**：任何能访问 admin 端口的人都能调 `/job/**`、`/joblog/**`、`/jobgroup/**`、`/dashboard/**` 全部管理 API，前端无登录页。作为对标 xxl-job 的求职项目，登录鉴权是必备能力，也是后端面试最高频考点（JWT 原理、无状态会话、密码哈希）。

**目标**：
1. admin 控制台 API 全部要求登录态，未登录返回 401
2. executor 机器间调用（注册/心跳/回调）**不受影响**，绝不能被登录拦截器挡掉
3. 前端加登录页 + 路由守卫 + axios 统一带 token
4. 多 admin 集群下 token 任意一台签发、任意一台可校验（无状态，无需共享 session）

**技术路线（用户已选定）**：
- 用户体系：`sys_user` 表 + 种子 admin 账号
- 密码哈希：**BCrypt**（引 `spring-security-crypto` 轻量依赖，不自研哈希）
- JWT：**完全自研**（HMAC-SHA256 手写 header.payload.signature，base64url 编码），面试可讲透底层
- 拦截器：自研 `AuthInterceptor`，注册到 Spring 拦截器链，排除 executor 专用路径

## 2. 架构总览

```
┌─────────────── ww-job-admin :8080 ───────────────┐
│                                                  │
│  POST /auth/login ──► AuthController            │
│       │  (BCrypt 校验 sys_user.password_hash)    │
│       ▼                                          │
│    签发 JWT {header.payload.signature}           │
│                                                  │
│  /job/** /joblog/** /jobgroup/** /dashboard/**   │
│       │                                          │
│       ▼                                          │
│  AuthInterceptor ──► 解析 Authorization: Bearer  │
│       │  (HMAC-SHA256 验签 + exp 校验)            │
│       ▼                                          │
│   通过 ──► 原 Controller  (username 入 attribute)│
│   失败 ──► 401 JSON  (ReturnT 风格)              │
│                                                  │
│  /registry /heartbeat /callback  (executor 专用)  │
│       │                                          │
│       ▼  放行，不拦截                              │
└──────────────────────────────────────────────────┘
```

## 3. 数据库设计

### 3.1 `sys_user` 表

在 `ww-job-admin/src/main/resources/db/schema.sql` 追加：

```sql
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（60 字符）',
    role       VARCHAR(32)  DEFAULT 'admin' COMMENT '角色（预留）',
    status     TINYINT      DEFAULT 1 COMMENT '1启用 0禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) COMMENT '控制台用户';

-- 种子管理员（默认密码 admin123，BCrypt 哈希固定串）
INSERT INTO sys_user (username, password_hash, role, status)
VALUES ('admin', '<预生成的 BCrypt 哈希>', 'admin', 1)
ON DUPLICATE KEY UPDATE username = username;
```

要点：
- `password_hash` 存 BCrypt 哈希（固定 60 字符），**绝不明文**
- 种子语句幂等：重复执行不会产生第二条 admin
- `role` / `status` 本次只落库不消费，为后续 RBAC / 用户管理页预留

### 3.2 实体 / Mapper

沿用 MyBatis-Plus 惯例：`SysUser` 实体 + `SysUserMapper extends BaseMapper<SysUser>`。

## 4. BCrypt 密码哈希

- admin pom 引入 `org.springframework.security:spring-security-crypto`（只引 crypto 模块，**不引** starter-security 全家桶）
- 用 `BCryptPasswordEncoder` 校验：`encoder.matches(rawPassword, storedHash)`
- 种子哈希的生成：本地跑一次 `BCryptPasswordEncoder().encode("admin123")` 得到固定串写入 schema.sql。BCrypt 自带随机盐，两次 encode 结果不同但都能 matches——固定串写进种子没关系

## 5. 自研 JWT（core 模块新增 `JwtUtil`）

### 5.1 为什么放 core

JWT 工具类与 Spring 无关，放 `ww-job-core` 的 `com.wwjob.core.util`，与 `CronUtil` 同级，纯静态工具、可单测。admin 依赖 core，直接用。

### 5.2 结构

JWT = `base64url(header).base64url(payload).base64url(HMAC-SHA256(前两段, secret))`

```java
public final class JwtUtil {
    // header 固定：{"alg":"HS256","typ":"JWT"}
    // payload：{"username":"admin","exp":<epochSecond>}
    public static String createToken(String username, String secret, long expireSeconds);
    // 校验签名 + exp，返回 username；非法/过期返回 null
    public static String parseToken(String token, String secret);
}
```

### 5.3 实现要点（面试可讲透）

1. **base64url**：标准 Base64 的 URL 安全变体——`+`→`-`、`/`→`_`、去掉 `=` 填充。java.util.Base64.getUrlEncoder().withoutPadding()
2. **HMAC-SHA256**：`Mac.getInstance("HmacSHA256")`，用 secret 初始化，对 `header.payload` 字节做签名
3. **exp 校验**：payload 解析出 exp（epochSecond），`exp <= now` 判过期
4. **恒定时间比较**：验签用 `MessageDigest.isEqual()` 而非 `equals()`，防时序侧信道（面试加分项）
5. **防篡改**：签名覆盖 header 和 payload 全部字节，前端改任何字段签名即失效

## 6. 登录接口

### 6.1 `AuthController`（admin 模块新增）

| 方法 | 路径 | 请求体 | 响应 |
|---|---|---|---|
| POST | `/auth/login` | `{username, password}` | 成功 `ReturnT.data({token, username, role})`；失败 `ReturnT.error("用户名或密码错误")` |

流程：
1. `sysUserMapper.selectOne(username)`
2. 用户不存在 / status=0 / `!encoder.matches(password, hash)` → 统一返回"用户名或密码错误"（**不区分**，防用户名枚举）
3. 签发 token：`JwtUtil.createToken(username, secret, expireSeconds)`

### 6.2 配置项（application.yml + local 可覆盖）

```yaml
wwjob:
  auth:
    jwt-secret: <默认开发密钥，local 可覆盖>
    jwt-expire-seconds: 86400   # 24h
```

> 安全注意：`jwt-secret` 默认值进 application.yml（可提交），生产建议在 application-local.yml 覆盖。与 MySQL 密码同一安全纪律。

## 7. `AuthInterceptor`（admin 模块新增）

### 7.1 逻辑

```java
preHandle() {
  String auth = request.getHeader("Authorization");      // "Bearer <token>"
  if (auth == null || !auth.startsWith("Bearer ")) → 401
  String username = JwtUtil.parseToken(token, secret);   // 验签 + 过期
  if (username == null) → 401
  request.setAttribute("username", username);            // Controller 可取
  return true;
}
```

401 响应体（ReturnT 风格，code=401）：`{"code":401,"msg":"未登录或登录已过期"}`。

### 7.2 注册到 WebMvcConfigurer

```java
registry.addInterceptor(authInterceptor)
        .addPathPatterns("/**")
        .excludePathPatterns(
            "/auth/login",
            "/registry/**",   // executor 注册
            "/heartbeat",     // executor 心跳
            "/callback/**"    // executor 回调
        );
```

> **关键**：exclude 列表是 executor 机器间调用的生命线。漏一个，双 admin + executor 集群全断——验证阶段必须专项测。

### 7.3 为什么不用 Spring Security

用户已选定自研。理由：符合项目"自研不依赖外部组件"定位、代码量小且每一行可讲、面试重点讲 JWT 原理而非框架配置。Spring Security 作为可选的演进方向记入 §11 非目标。

## 8. 前端改造（ww-job-web）

### 8.1 新增登录页 `views/Login.vue`

- Element Plus 表单：用户名 + 密码 + 登录按钮
- 调 `POST /auth/login`，成功把 `{token, username}` 存 localStorage（key 如 `wwjob_token`）
- 失败 ElMessage 提示"用户名或密码错误"
- 未登录/已登录访问 /login 的处理：已登录直接跳 /dashboard

### 8.2 路由守卫（`src/router/index.js`）

```js
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('wwjob_token')
  if (to.path === '/login') { next(token ? '/dashboard' : undefined); return }
  if (!token) { next('/login') } else { next() }
})
```

- `/login` 不需要守卫；其余路由无 token 一律重定向 /login
- 新增 `/login` 路由（不放进 layout，全屏居中表单）

### 8.3 axios 拦截器（`src/api/request.js`）

```js
// 请求拦截器：带上 token
config.headers.Authorization = 'Bearer ' + localStorage.getItem('wwjob_token')

// 响应拦截器：401 → 清 token + 跳 /login
if (err.response?.status === 401) {
  localStorage.removeItem('wwjob_token')
  window.location.href = '/login'
}
```

### 8.4 Layout 改造

- 侧边栏菜单加"退出登录"入口（清 token + 跳 /login）
- 顶栏可显示当前登录用户名（登录接口返回）

## 9. 多 admin 集群一致性

JWT 无状态：token 由任意 admin 用同一 secret 签发，其他 admin 用同一 secret 验签即可，**无需共享 session / 无同步开销**。双 admin 都读同一 MySQL 的 `sys_user`，登录校验一致。secret 需在**所有 admin 实例**配置相同（不同则互不认 token）。

## 10. 验证方案（端到端）

1. **未登录访问**：curl `/job/page` → 401
2. **登录**：curl POST `/auth/login`（admin/admin123）→ 返回 token
3. **带 token 访问**：curl `-H "Authorization: Bearer <token>" /job/page` → 200
4. **错误密码**：→ "用户名或密码错误"
5. **过期 token**：伪造一个 exp 已过期的 → 401（可用短过期配置快速验）
6. **篡改 token**：改 payload 里 username 再 base64url 拼回 → 401（验签失败）
7. **executor 不受影响**：双 admin + executor 起起来，job 27 每 5s 正常触发、job_log 持续新增、心跳/注册/回调日志无 401
8. **前端全流程**：浏览器访问 → 跳登录页 → 登录 → 进 dashboard → 刷新不丢登录态（localStorage）→ 退出登录 → 跳回登录页

## 11. 非目标（本次不做）

- **RBAC 权限细分**：role 字段落库但本次只存 admin，不做不同角色不同权限
- **用户管理页**：改密/增删用户本次不做（种子账号 + 手动 SQL 足够演示）
- **Spring Security**：不引入全家桶，保持自研（可演进方向，接口/拦截器已抽象出清晰边界）
- **token 刷新机制**：本次过期即重新登录（exp 24h 对演示足够）
- **HTTPS / 登录失败锁定**：生产安全措施，超出本阶段
- **executor 侧鉴权**：executor 是机器间可信网络，本次不加（registry/heartbeat/callback 继续无鉴权）

## 12. 遗留决策（用户 review 时确认）

1. **前端分工**：默认 Claude 代写前端（登录页/守卫/拦截器），用户 review——沿用历史惯例。若用户想自己写，改为用户自研 + Claude 指导。
2. **种子密码**：默认 `admin123`（文档 + README 标明，仅演示用途）。
