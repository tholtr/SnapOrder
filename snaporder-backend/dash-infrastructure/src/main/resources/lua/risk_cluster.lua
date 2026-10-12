-- 设备与 IP 维度的聚集特征（一次往返）
--
-- 为什么必须用 Lua：三个维度各自都要「读计数 -> 判断是否封顶 -> 写入 -> 再读计数」，
-- 拆开调用时并发请求会读到彼此的中间状态，集合可能在超过上限后继续增长
-- （SADD 判断与写入之间被插入），成员上限就形同虚设。
-- Lua 在 Redis 中整体执行，中途不会被打断。
--
-- 为什么合成一个脚本：三个维度服务于同一次请求，拆成三个脚本就是三次往返，
-- 与蓝图 §3.8 的 10ms 总 deadline 直接冲突。
--
-- KEYS[1] = risk:dev:{deviceHash}       Set   成员=userId
-- KEYS[2] = risk:ip:{ipHash}:users      Set   成员=userId
-- KEYS[3] = risk:ip:{ipHash}:rate       ZSet  成员=微秒时间戳 分值=同一时间戳
-- ARGV[1] = userId
-- ARGV[2] = 设备集合成员上限
-- ARGV[3] = IP 账号集合成员上限
-- ARGV[4] = IP 速率 ZSet 成员上限
-- ARGV[5] = 设备集合的 TTL 秒数
-- ARGV[6] = IP 账号集合的 TTL 秒数
-- ARGV[7] = IP 速率 ZSet 的 TTL 秒数，同时用作速率窗口长度（两者取同一个配置值）
-- ARGV[8] = 是否存在设备维度：1=有 0=无（客户端没带 X-Device-Id）
-- ARGV[9] = 是否存在 IP 维度：1=有 0=无
-- 返回：{设备关联账号数, IP 关联账号数, IP 窗口内请求数}（缺失的维度恒为 0）
--
-- ⚠ 两个 Set 各有自己的 TTL，**不能合并成一个参数**。
--   Set 没有分值，成员上没有时间信息，「窗口」这个语义完全由 TTL 承载
--   （加上「只在新增成员时才续期」那条规则）。所以 TTL 不是一个无关紧要的
--   过期时间，**它就是窗口长度本身**。
--   若两个 Set 共用一个 TTL，把设备窗口从 600 调到 300 就会**顺手把 IP 账号
--   窗口也改成 300**，而配置里 dash.risk.ip-account-window.seconds 看起来
--   还在生效、实际读都没读过——改配置没有任何效果，也不报错。
--
-- ⚠ 缺失维度：调用方仍会传满三个 KEYS，缺失的那一维传一个**占位键**
--   （Java 侧的 RiskKeys.ABSENT = "risk:_absent_"），本脚本用 ARGV[7]/[8] 跳过它的全部分支，
--   绝不访问。这样做的原因是 Redis 的 EVAL 要求 key 位置固定：
--   若按维度数量增减 KEYS，同一个脚本就会有两套下标含义，
--   改动时极易把 userId 写进错误的键。
--   代价是「占位键一旦被访问就会真的建出来」——所以两个 if 是唯一的防线，
--   它们不是可选的优化。占位键起名为 risk:_absent_ 就是为了万一泄漏时一眼能认出来。
--
-- ⚠ ZSet 成员上限不得超过 127，**不是 128**。
--   Redis 的 ZSet 在成员数 ≤ zset-max-listpack-entries（默认 128）时用 listpack 编码，
--   超过即转 skiplist，而且**编码只升不降**。本脚本是「先 ZADD、再裁剪」，
--   键在调用瞬间会达到「上限 +1」个成员，所以上限取 128 时瞬时到 129，
--   当场触发转换。实测（Redis 7.4.11）：上限 127 -> listpack 7232 B，
--   上限 128 -> skiplist 17601 B。
--   当前上限 101 留了 26 的余量，是安全的。**不要为了「更精确」把它调过 127。**
--
-- ⚠ 上面两个 Set 也有编码悬崖，只是位置不同——**不要以为 Set 就没有这个问题**。
--   Set 的成员是整数 userId，走 intset 而非 listpack，悬崖由 set-max-intset-entries
--   （默认 512）决定，超过即转 hashtable，同样只升不降。
--   实测（走本脚本真实写入路径）：上限 512 -> intset 1336 B，
--   上限 513 -> hashtable 24720 B，**跳涨 18.5 倍**，比 ZSet 那条还陡。
--   Set 没有 ZSet 的「先写后裁」瞬时问题（本脚本是 SCARD < cap 才 SADD，
--   最多正好到 cap），所以上界是干净的 512。
--   当前上限 9 和 11 都是几十字节，离悬崖很远。两处守卫都在 RiskProperties 里。

local now = redis.call('TIME')
-- ⚠ 时间基准必须用微秒，绝不能用纳秒。理由与 risk_user_window.lua 相同：
--   Lua number 是 IEEE-754 double，只精确到 2^53 ≈ 9.007e15；纳秒时间戳
--   约 1.789e18 超出精度，不同请求会算出同一分值而被当成同一个成员。
local nowUs = now[1] * 1000000 + now[2]

local userId = ARGV[1]
local deviceCap = tonumber(ARGV[2])
local ipAccountCap = tonumber(ARGV[3])
local rateCap = tonumber(ARGV[4])
local deviceSetTtl = tonumber(ARGV[5])
local ipSetTtl = tonumber(ARGV[6])
local rateTtl = tonumber(ARGV[7])
local hasDevice = ARGV[8] == '1'
local hasIp = ARGV[9] == '1'

-- ── 设备维度：这个设备关联过多少个账号 ─────────────────────────────────
-- 上限只需要取「阈值 +1」：规则是「关联账号 > 8 则拦截」，集合封顶在 9
-- 就足以表达「已越线」，9 和 900 对决策没有任何区别。
-- 这样集合大小有硬上界，直接满足蓝图 §3.8「成员上限和内存预算」的要求。
--
-- 封顶方式是**拒绝继续 SADD**，不是随机剔除：集合达到上限后会一直保持不变，
-- 这正是期望行为——该设备在窗口内确实关联了超限账号，把它「稀释」掉反而是漏报。
local deviceCount = 0
if hasDevice then
    deviceCount = redis.call('SCARD', KEYS[1])
    if deviceCount < deviceCap then
        -- 只有真的写入了新成员才刷新 TTL。达到上限后不再续期，
        -- 集合会在最后一次新增的 TTL 秒后自然过期，从而自愈；
        -- 若每请求都续期，被判定超限的设备会永久停留在超限状态。
        if redis.call('SADD', KEYS[1], userId) == 1 then
            redis.call('EXPIRE', KEYS[1], deviceSetTtl)
        end
        deviceCount = redis.call('SCARD', KEYS[1])
    end
end

-- ── IP 维度其一：这个 IP 关联过多少个账号 ──────────────────────────────
-- 处理方式与设备集合完全一致，上限同样取「阈值 +1」。
--
-- 键名前缀用的是 ipHash 而不是设备哈希，两个维度的键天然隔离——
-- 同一个用户先换设备、再换 IP，会在两个不同的键上各自累积，不会互相污染。
--
-- 两个 IP 维度的开关是同一个 hasIp：它们同为「这个 IP」的属性，
-- 分开开关只会多一种「users 键写了但 rate 键没写」的错配。
local ipAccountCount = 0
local ipRequestCount = 0
if hasIp then
    ipAccountCount = redis.call('SCARD', KEYS[2])
    if ipAccountCount < ipAccountCap then
        if redis.call('SADD', KEYS[2], userId) == 1 then
            redis.call('EXPIRE', KEYS[2], ipSetTtl)
        end
        ipAccountCount = redis.call('SCARD', KEYS[2])
    end

    -- ── IP 维度其二：这个 IP 在窗口内发了多少请求 ──────────────────────
    -- 成员用微秒时间戳，**不用 requestId**——这是与用户窗口唯一不同的处理，
    -- 因为两条规则的语义不同：
    --
    --   用户窗口识别「脚本刷同一个人的抢单」，需要去重（重试幂等），成员用 requestId；
    --   IP 速率识别「单个 IP 的异常请求量」，要计原始请求数，去重会让规则直接失效。
    --
    -- 若这里也用 requestId 去重，攻击者只要反复使用同一个 requestId
    -- 就能把计数压到 1，这条规则形同不存在。
    --
    -- 代价：同一微秒内的并发请求会合并成一条（低估）。在单 IP 的实际速率下可忽略；
    -- 若某个 IP 真能到百万 QPS 量级，这条规则本身也已经没有意义了。
    redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, nowUs - rateTtl * 1000000)
    redis.call('ZADD', KEYS[3], nowUs, nowUs)
    redis.call('ZREMRANGEBYRANK', KEYS[3], 0, -(rateCap + 1))
    redis.call('EXPIRE', KEYS[3], rateTtl)
    ipRequestCount = redis.call('ZCARD', KEYS[3])
end

return {deviceCount, ipAccountCount, ipRequestCount}
