-- 一次性迁移：历史「被阻塞丢弃」行从 status=3 迁到独立 status=4。
-- 背景：2026-09-03 item5 状态模型拆分——status=3 原混记「被阻塞丢弃」(JobDecisionService.decide，
--       handle_msg='任务上一次执行尚未结束，本次触发被阻塞丢弃'，handle_time=null)与「超时未知」(dispatch 超时)。
-- 用 decide() 固定句柄等值匹配，绝不误伤超时未知(JobTriggerServiceImpl dispatch 超时)行。
-- 执行时机：可随时执行；幂等(UPDATE 后不再有 status=3+该句柄的行)。建议先 SELECT 计数留档：
--   SELECT COUNT(*) FROM job_log WHERE status = 3 AND handle_msg = '任务上一次执行尚未结束，本次触发被阻塞丢弃';
UPDATE job_log SET status = 4
 WHERE status = 3 AND handle_msg = '任务上一次执行尚未结束，本次触发被阻塞丢弃';
