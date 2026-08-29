import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import JobList from '../views/JobList.vue'
import JobLogList from '../views/JobLogList.vue'
import RegistryList from '../views/RegistryList.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', component: Dashboard },
  { path: '/jobs', component: JobList },
  { path: '/joblogs', component: JobLogList },
  { path: '/registries', component: RegistryList },
]

export default createRouter({ history: createWebHistory(), routes })
