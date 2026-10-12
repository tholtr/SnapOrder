-- 用户维度滑动窗口计数（10 秒 + 60 秒，一次往返）
--
-- 为什么必须用 Lua：每个窗口都要走「查重 -> 裁剪 -> 加成员 -> 计数」四步，
-- 拆成多条命令会被其他客户端插入。并发下两个相同 requestId 的请求会互相看不到，
-- 双双计数，去重当场失效；而窗口计数偏大意味着正常用户被误伤。
-- Lua 脚本在 Redis 中作为一个整体执行，中途不会被打断。
--
-- 为什么两个窗口合并到一个脚本：分两次调用就是两次网络往返，
-- 与蓝图 §3.8 的 10ms 总 deadline 直接冲突。以后加窗口应该继续往这个脚本里加 KEYS，
-- 而不是拆成多个脚本。
--
-- KEYS[1] = risk:win:{userId}:10s   ZSet  成员=requestId 分值=微秒时间戳
-- KEYS[2] = risk:win:{userId}:60s   ZSet  成员=requestId 分值=微秒时间戳
-- ARGV[1] = requestId
-- ARGV[2] = 10 秒窗口成员上限
-- ARGV[3] = 60 秒窗口成员上限
-- ARGV[4] = TTL 秒数（必须取配置里最长的窗口秒数，理由见「查重与裁剪的顺序」）
-- 返回：{10 秒计数, 60 秒计数, 是否重复}  是否重复：1=是 0=否
--
-- ⚠ 成员上限不得超过 127，**不是 128**。
--   Redis 的 ZSet 在成员数 ≤ zset-max-listpack-entries（默认 128）时用 listpack 编码，
--   超过即转 skiplist，而且**编码只升不降**——转过去就再也回不来。
--   本脚本是「先 ZADD、再裁剪」，键在调用瞬间会达到「上限 +1」个成员，
--   所以上限取 128 时瞬时到 129，当场触发转换。实测（Redis 7.4.11，真实形态成员）：
--     上限 127 -> listpack  7232 B
--     上限 128 -> skiplist 17601 B   ← 翻 2.4 倍
--   这种退化不报错、不告警，只在压测里表现为 Redis 内存异常。
--   确需更大窗口请降低窗口精度或换结构，不要直接调大上限。

local now = redis.call('TIME')
-- ⚠ 时间基准必须用微秒，绝不能用纳秒。
--   Lua number 是 IEEE-754 double，只能精确表示到 2^53 ≈ 9.007e15。
--   微秒时间戳约 1.789e15，在安全区内；纳秒是 1.789e18，超出精度后
--   不同请求会算出同一个分值，被 ZSet 当成同一个成员而互相覆盖——
--   计数会静默偏小，即漏报。微秒是能安全使用的最高精度。
local nowUs = now[1] * 1000000 + now[2]

local requestId = ARGV[1]
local cap10 = tonumber(ARGV[2])
local cap60 = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

-- ── 查重与裁剪的顺序：必须「先裁剪、后查重」（改动前务必读懂） ────────────
-- 成员在窗口内是**惰性清理**的：只在有写入时才裁剪。两次写入之间时间在走，
-- 一个已经滑出窗口的成员会一直留在集合里，直到下一次写入才被裁掉。
-- 所以「先查重」会把这种残留成员误判为重复请求。
--
-- 实测（Redis 7.4.11）：写入一个 requestId，静默 62 秒（键靠 TTL 存活），
-- 再重放同一个 id：
--
--   先查重后裁剪 -> {0, 0, 1}   ← 一边说「重复」，一边说窗口里一个成员都没有
--   先裁剪后查重 -> {1, 1, 0}   ← 正确：已滑出 60 秒窗口，应当算新请求
--
-- 那种自相矛盾的返回值不需要任何外部推理就能看出不对，所以顺序不能反。
--
-- 反过来担心的「先裁剪会把已经裁剪掉的 requestId 当成全新请求重新计数」
-- 是反的：滑出窗口的请求本来就该重新计数，这正是期望行为。窗口内的重复请求
-- 不会被裁掉（分数还在窗口里），查重照样命中，去重没有削弱。
--
-- 前提：**TTL 必须 ≥ 最长窗口的秒数**，不能更短。键若在窗口结束前整体过期，
-- 窗口内的成员会跟着一起消失，重放会被当成新请求，计数偏小（漏报）。
-- 若把 TTL 调长却不在写入时刷新，键会白占内存（刷新时机见下面的 EXPIRE）。
-- 两者都不会抛异常，只会让规则悄悄变得不准。
--
-- 查重。命中任一窗口的已有成员即视为重复请求，两个窗口都不再加计数。
--
-- 成员直接用 requestId，不额外开去重键：去重键方案会让键数随 QPS×TTL 增长
-- （S2 实测 12642 QPS 下 10 秒就是十几万个键），而 requestId 作为成员后
-- 去重只需一次 ZSCORE——零额外键、零额外 TTL，窗口裁剪天然负责成员的生命周期。
--
-- 按时间裁剪，移出窗口外的成员。放在查重之前（理由见上），
-- 同时也保证返回的计数始终等于「当前窗口内的成员数」。
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowUs - 10000000)
redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, nowUs - 60000000)

local duplicate = 0
if redis.call('ZSCORE', KEYS[1], requestId) or redis.call('ZSCORE', KEYS[2], requestId) then
    duplicate = 1
end

if duplicate == 0 then
    redis.call('ZADD', KEYS[1], nowUs, requestId)
    redis.call('ZADD', KEYS[2], nowUs, requestId)
    -- TTL 只在真正写入时刷新：重放同一个 requestId 不续命，
    -- 否则攻击者反复重放就能让键永不过期。
    redis.call('EXPIRE', KEYS[1], ttl)
    redis.call('EXPIRE', KEYS[2], ttl)
end

-- 上限裁剪：只保留最新的 N 条。
-- ZREMRANGEBYRANK key 0 -(N+1) 删除排名 ≤ -(N+1) 的成员（最旧的若干条）；
-- 当 ZCARD <= N 时该 rank 落在集合起点之外，天然不删任何东西。
redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -(cap10 + 1))
redis.call('ZREMRANGEBYRANK', KEYS[2], 0, -(cap60 + 1))

return {redis.call('ZCARD', KEYS[1]), redis.call('ZCARD', KEYS[2]), duplicate}
