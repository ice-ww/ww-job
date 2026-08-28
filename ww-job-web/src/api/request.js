import axios from 'axios'

const request = axios.create({ baseURL: '/' })

request.interceptors.response.use(
  (res) => res,
  (err) => {
    console.error('请求失败', err)
    return Promise.reject(err)
  }
)

export default request
