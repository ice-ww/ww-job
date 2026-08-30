<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
const username = localStorage.getItem('wwjob_username') || 'admin'

const logout = () => {
  localStorage.removeItem('wwjob_token')
  localStorage.removeItem('wwjob_username')
  router.push('/login')
}
</script>

<template>
  <!-- 登录页：全屏裸渲染，不套侧边栏布局 -->
  <router-view v-if="$route.path === '/login'" />

  <!-- 控制台：侧边栏布局 -->
  <el-container v-else class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">ww-job</div>
      <el-menu
        router
        :default-active="$route.path"
        background-color="#001529"
        text-color="rgba(255,255,255,0.68)"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">概览</el-menu-item>
        <el-menu-item index="/jobs">任务管理</el-menu-item>
        <el-menu-item index="/joblogs">执行日志</el-menu-item>
        <el-menu-item index="/registries">执行器列表</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="main">
      <div class="topbar">
        <span class="username">{{ username }}</span>
        <el-button size="small" @click="logout">退出登录</el-button>
      </div>
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout { height: 100vh; }
.aside { background: #001529; color: #fff; }
.aside .logo { height: 56px; line-height: 56px; text-align: center; font-size: 18px; font-weight: bold; color: #fff; }
.aside :deep(.el-menu) { border-right: none; background: transparent; }
/* 深色底上 hover/选中态：默认浅色主题会盖住白字，覆盖为半透明白 */
.aside :deep(.el-menu-item:hover) { background-color: rgba(255,255,255,0.08); }
.aside :deep(.el-menu-item.is-active) {
  background-color: rgba(255,255,255,0.12);
  box-shadow: inset 3px 0 0 #409eff;
}
.main { background: #f5f7fa; padding: 16px; }
.topbar { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-bottom: 16px; }
.username { color: #303133; font-size: 14px; }
</style>
