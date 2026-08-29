import request from './request'

export const listRegistries = () => request.get('/registry/list')
