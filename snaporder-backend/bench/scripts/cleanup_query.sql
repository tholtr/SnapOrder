-- S2 任务查询性能测试清理。
-- 使用方式与 seed_query.sql 相同；默认清理预留的 1,000,000 条 ID 区间。

SET @query_base = IFNULL(@query_base, 940000000000);
SET @query_capacity = 1000000;

DELETE FROM errand
 WHERE id BETWEEN @query_base + 1 AND @query_base + @query_capacity;

SELECT CONCAT('已清理任务数=', ROW_COUNT(),
              '，ID 段=[', @query_base + 1, ', ', @query_base + @query_capacity, ']') AS cleaned;
