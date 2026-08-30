import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 正则：只匹配 /job 或 /job/*（任务 CRUD/page），不劫持前端页面 /jobs 刷新
      '^/job(/|$)': 'http://localhost:8080',
      // 带尾斜杠：匹配 /joblog/*（日志 API），不匹配页面 /joblogs
      '/joblog/': 'http://localhost:8080',
      '/jobgroup': 'http://localhost:8080',
      '/registry': 'http://localhost:8080',
      // 精确到 API 子路径：不劫持页面 /dashboard 刷新
      '/dashboard/stats': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
    },
  },
})
