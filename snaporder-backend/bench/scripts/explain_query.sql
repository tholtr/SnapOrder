-- S2 任务查询 EXPLAIN ANALYZE 模板。
-- 默认按 pageSize=50 展示第 1、1000、10000 页，与应用层 pageSize 上限一致。
--
-- 精准路由与广播查询的两组 SQL 只在真实多数据源 ShardingSphere 环境中
-- 才能证明实际访问分片数量；当前单库环境只能观察索引与扫描计划。

SET @query_campus_id = 1;
SET @query_status = 'PUBLISHED';

-- ---------- OFFSET：第 1 页 ----------
EXPLAIN ANALYZE
SELECT id, campus_id, publisher_id, type, title, reward_amount,
       slot_total, slot_taken, status, round, version, created_at
  FROM errand
 WHERE campus_id = @query_campus_id AND status = @query_status
 ORDER BY created_at DESC, id DESC
 LIMIT 50 OFFSET 0;

-- ---------- OFFSET：第 1000 页 ----------
EXPLAIN ANALYZE
SELECT id, campus_id, publisher_id, type, title, reward_amount,
       slot_total, slot_taken, status, round, version, created_at
  FROM errand
 WHERE campus_id = @query_campus_id AND status = @query_status
 ORDER BY created_at DESC, id DESC
 LIMIT 50 OFFSET 49950;

-- ---------- OFFSET：第 10000 页 ----------
EXPLAIN ANALYZE
SELECT id, campus_id, publisher_id, type, title, reward_amount,
       slot_total, slot_taken, status, round, version, created_at
  FROM errand
 WHERE campus_id = @query_campus_id AND status = @query_status
 ORDER BY created_at DESC, id DESC
 LIMIT 50 OFFSET 499950;

-- ---------- 游标：第 1000 页 ----------
SELECT created_at, id
  INTO @query_cursor_created_at_1000, @query_cursor_id_1000
  FROM errand
 WHERE campus_id = @query_campus_id AND status = @query_status
 ORDER BY created_at DESC, id DESC
 LIMIT 1 OFFSET 49949;

EXPLAIN ANALYZE
SELECT e.id, e.campus_id, e.publisher_id, e.type, e.title, e.reward_amount,
       e.slot_total, e.slot_taken, e.status, e.round, e.version, e.created_at
  FROM errand e
 WHERE e.campus_id = @query_campus_id AND e.status = @query_status
   AND (e.created_at < @query_cursor_created_at_1000
        OR (e.created_at = @query_cursor_created_at_1000 AND e.id < @query_cursor_id_1000))
 ORDER BY e.created_at DESC, e.id DESC
 LIMIT 50;

-- ---------- 游标：第 10000 页 ----------
SELECT created_at, id
  INTO @query_cursor_created_at_10000, @query_cursor_id_10000
  FROM errand
 WHERE campus_id = @query_campus_id AND status = @query_status
 ORDER BY created_at DESC, id DESC
 LIMIT 1 OFFSET 499949;

EXPLAIN ANALYZE
SELECT e.id, e.campus_id, e.publisher_id, e.type, e.title, e.reward_amount,
       e.slot_total, e.slot_taken, e.status, e.round, e.version, e.created_at
  FROM errand e
 WHERE e.campus_id = @query_campus_id AND e.status = @query_status
   AND (e.created_at < @query_cursor_created_at_10000
        OR (e.created_at = @query_cursor_created_at_10000 AND e.id < @query_cursor_id_10000))
 ORDER BY e.created_at DESC, e.id DESC
 LIMIT 50;

-- ---------- 路由对比模板 ----------
-- 精准路由：带 campus_id，真实分片环境应只访问一个任务分片。
EXPLAIN ANALYZE
SELECT id, created_at
  FROM errand
 WHERE campus_id = @query_campus_id AND status = @query_status
 ORDER BY created_at DESC, id DESC
 LIMIT 50;

-- 广播模拟：缺少 campus_id，真实分片环境可能访问全部任务分片。
-- 当前单库环境下该 SQL 不能代表真实广播成本，只用于记录基线计划。
EXPLAIN ANALYZE
SELECT id, campus_id, created_at
  FROM errand
 WHERE status = @query_status
 ORDER BY created_at DESC, id DESC
 LIMIT 50;
