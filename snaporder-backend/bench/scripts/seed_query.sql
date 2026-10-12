-- S2 任务查询性能测试造数。
--
-- 默认生成 100,000 条任务；正式深分页测试建议传入 1,000,000。
-- 通过 mysql --init-command 覆盖变量，例如：
--   --init-command="SET @query_count=1000000; SET @query_base=940000000000; SET @query_campus_id=1"
--
-- 该脚本使用数字笛卡尔积，不受 cte_max_recursion_depth=1000 限制。
-- 数据使用独立 ID 段，不登记 bench_run_item；清理请使用 cleanup_query.sql。

SET @query_base = IFNULL(@query_base, 940000000000);
SET @query_count = IFNULL(@query_count, 100000);
SET @query_campus_id = IFNULL(@query_campus_id, 1);
SET @query_capacity = 1000000;

-- 先清理整个预留区间，保证从 100,000 切换到 1,000,000 时不会残留旧数据。
DELETE FROM errand
 WHERE id BETWEEN @query_base + 1 AND @query_base + @query_capacity;

INSERT INTO errand (id, campus_id, publisher_id, type, title, reward_amount,
                    slot_total, slot_taken, status, round, version, created_at)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), seq AS (
    SELECT 1 + d0.n + d1.n * 10 + d2.n * 100 + d3.n * 1000
             + d4.n * 10000 + d5.n * 100000 AS n
      FROM digits d0
      CROSS JOIN digits d1
      CROSS JOIN digits d2
      CROSS JOIN digits d3
      CROSS JOIN digits d4
      CROSS JOIN digits d5
)
SELECT @query_base + n,
       @query_campus_id,
       1001,
       'DELIVERY',
       CONCAT('bench_query_', n),
       1000,
       1,
       0,
       'PUBLISHED',
       0,
       1,
       TIMESTAMPADD(MICROSECOND, -((n - 1) * 1000),
                    CAST('2026-09-04 00:00:00.000' AS DATETIME(3)))
  FROM seq
 WHERE n <= @query_count;

SELECT CONCAT('已造任务数=', COUNT(*),
              '，ID 段=[', @query_base + 1, ', ', @query_base + @query_count,
              ']，campus_id=', @query_campus_id) AS seeded
  FROM errand
 WHERE id BETWEEN @query_base + 1 AND @query_base + @query_count;
