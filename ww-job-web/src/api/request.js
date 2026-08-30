import axios from 'axios'

const request = axios.create({ baseURL: '/' })

// 请求拦截器：统一携带 JWT
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('wwjob_token')
    if (token) config.headers.Authorization = 'Bearer ' + token
    return config
  },
  (err) => Promise.reject(err)
)

// 响应拦截器：401 清登录态跳登录页
request.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('wwjob_token')
      localStorage.removeItem('wwjob_username')
      window.location.href = '/login'
    }
    console.error('请求失败', err)
    return Promise.reject(err)
  }
)

export default request
