import request from './request'

export const pageJobs = (params) => request.get('/job/page', { params })
export const createJob = (data) => request.post('/job', data)
export const updateJob = (data) => request.put('/job', data)
export const triggerJob = (id) => request.post(`/job/${id}/trigger`)
export const startJob = (id) => request.post(`/job/${id}/start`)
export const stopJob = (id) => request.post(`/job/${id}/stop`)
export const deleteJob = (id) => request.delete(`/job/${id}`)
