import request from './request'

export const listGroups = () => request.get('/jobgroup/list')
