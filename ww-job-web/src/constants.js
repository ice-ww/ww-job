export const ROUTE_STRATEGIES = [
  { value: 'round_robin', label: '轮询' },
  { value: 'random', label: '随机' },
  { value: 'failover', label: '故障转移' },
  { value: 'sharding', label: '分片广播' },
]

export const BLOCK_STRATEGIES = [
  { value: 'serial', label: '串行' },
  { value: 'SINGLE', label: '单机互斥' },
]

export const LOG_STATUS = [
  { value: 0, label: '运行中', tag: 'primary' },
  { value: 1, label: '成功', tag: 'success' },
  { value: 2, label: '失败', tag: 'danger' },
  { value: 3, label: '未知', tag: 'warning' },
  { value: 4, label: '被阻塞', tag: 'info' },
]

export const fmtTime = (ts) => {
  if (!ts) return '-'
  const d = new Date(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
