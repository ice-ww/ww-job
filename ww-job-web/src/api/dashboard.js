import request from './request'

export const getStats = () => request.get('/dashboard/stats')
