import request from './request'

export const pageLogs = (params) => request.get('/joblog/page', { params })
export const getLogDetail = (id) => request.get(`/joblog/${id}`)
