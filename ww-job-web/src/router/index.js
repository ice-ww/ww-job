import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import JobList from '../views/JobList.vue'
import JobLogList from '../views/JobLogList.vue'
import RegistryList from '../views/RegistryList.vue'
import Login from '../views/Login.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', component: Login },
  { path: '/dashboard', component: Dashboard },
  { path: '/jobs', component: JobList },
  { path: '/joblogs', component: JobLogList },
  { path: '/registries', component: RegistryList },
]

const router = createRouter({ history: createWebHistory(), routes })

// 路由守卫：未登录一律去 /login；已登录访问 /login 直接去 /dashboard
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('wwjob_token')
  if (to.path === '/login') {
    next(token ? '/dashboard' : undefined)
    return
  }
  if (!token) {
    next('/login')
  } else {
    next()
  }
})

export default router
