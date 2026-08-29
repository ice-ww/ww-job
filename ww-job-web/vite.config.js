import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/job': 'http://localhost:8080',
      '/joblog': 'http://localhost:8080',
      '/jobgroup': 'http://localhost:8080',
      '/registry': 'http://localhost:8080',
      '/dashboard': 'http://localhost:8080',
    },
  },
})
