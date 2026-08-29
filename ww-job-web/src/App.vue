<script setup>
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">ww-job</div>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/dashboard">概览</el-menu-item>
        <el-menu-item index="/jobs">任务管理</el-menu-item>
        <el-menu-item index="/joblogs">执行日志</el-menu-item>
        <el-menu-item index="/registries">执行器列表</el-menu-item>
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
