-- 一次性迁移（存量库手动执行，勿放入 schema.sql）：job_registry 加唯一键 (job_group_id, registry_value)。
-- 执行顺序必须：① 先清重（同 group+value 保留 id 最小行）② 再加唯一键。顺序反了会因脏数据建键失败。
-- 回归阶段由 Claude 先 SELECT COUNT(*) 数出将删行数留档，再整段执行（与 ice-ww 确认后）。
DELETE r1 FROM job_registry r1
  INNER JOIN job_registry r2
    ON r1.job_group_id = r2.job_group_id
   AND r1.registry_value = r2.registry_value
   AND r1.id > r2.id;
ALTER TABLE job_registry ADD UNIQUE KEY uk_group_value (job_group_id, registry_value);