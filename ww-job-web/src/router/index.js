import { createRouter, createWebHistory } from 'vue-router'
import JobList from '../views/JobList.vue'
import JobLogList from '../views/JobLogList.vue'
import RegistryList from '../views/RegistryList.vue'

const routes = [
  { path: '/', redirect: '/jobs' },
  { path: '/jobs', component: JobList },
  { path: '/joblogs', component: JobLogList },
  { path: '/registries', component: RegistryList },
]

export default createRouter({ history: createWebHistory(), routes })
