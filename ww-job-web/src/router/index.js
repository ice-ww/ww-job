import { createRouter, createWebHistory } from 'vue-router'
import JobList from '../views/JobList.vue'
import JobLogList from '../views/JobLogList.vue'

const routes = [
  { path: '/', redirect: '/jobs' },
  { path: '/jobs', component: JobList },
  { path: '/joblogs', component: JobLogList },
]

export default createRouter({ history: createWebHistory(), routes })
