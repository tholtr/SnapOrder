#!/usr/bin/env bash
# RocketMQ 初始化：创建 DELAY 类型 topic 与消费组。
#
# 为什么要显式建 topic：5.x 的定时消息要求 topic 的 message.type=DELAY，
# 自动创建的普通 topic 会让 broker 拒收定时消息，报错信息还不太直观。
set -euo pipefail

BROKER_CONTAINER="${BROKER_CONTAINER:-dash-rmqbroker}"
NAMESRV="${NAMESRV:-rmqnamesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
TOPIC="${TOPIC:-errand-confirm-timeout}"
# P3：送达后 24h 自动结算（DELAY 类型，与超时流转同类）
TOPIC_AUTO_SETTLE="${TOPIC_AUTO_SETTLE:-errand-auto-settle}"
# P3：资金事件通知（普通类型，走事务消息——事务消息不支持延迟，所以必须是普通 topic）
TOPIC_FUND_EVENT="${TOPIC_FUND_EVENT:-errand-fund-event}"
# P5：延迟双删（DELAY 类型）
TOPIC_CACHE_EVICT="${TOPIC_CACHE_EVICT:-errand-cache-evict}"
# P9.4：风险事件（普通类型，不带 -a）
TOPIC_RISK_EVENT="${TOPIC_RISK_EVENT:-errand-risk-event}"
# P9.4：风险事件消费组。这个名字同时写在 RiskEventConsumer 的 @Value 默认值里，
# 两处必须一致——否则消费照样跑，只是下面那个 -r 3 落在一个没人订阅的组上。
GROUP_RISK_EVENT="${GROUP_RISK_EVENT:-dash-risk-event-consumer}"
# P9.4：IT 专用消费组（RiskEventIT 的**观察者**消费者在测试 JVM 里手建，用它收一份载荷）。
# 它与 worker 的组必须不同：同组会抢消息，组里两个消费者各拿一半，「worker 收到了吗」
# 就成了一条概率性断言。它写在这里而不是只活在测试代码里，是为了不成为「在别人机器上跑才失败」的隐藏依赖。
GROUP_RISK_EVENT_IT="${GROUP_RISK_EVENT_IT:-dash-risk-event-it}"
GROUP="${GROUP:-dash-timeout-consumer}"

admin() {
  docker exec "$BROKER_CONTAINER" sh -c "cd /home/rocketmq/rocketmq-*/bin && sh mqadmin $*"
}

echo "创建 DELAY topic: $TOPIC"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC -a +message.type=DELAY" | tail -2

echo "创建 DELAY topic: $TOPIC_AUTO_SETTLE"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_AUTO_SETTLE -a +message.type=DELAY" | tail -1

echo "创建 TRANSACTION topic（事务消息用）: $TOPIC_FUND_EVENT"
# 关键：事务消息要求 topic 是 TRANSACTION 类型。建成 NORMAL 会在发送时报
# "Current message type not match with topic accept message types"（实测踩过）。
# 类型一旦建错无法原地修改，必须 deleteTopic 后重建。
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_FUND_EVENT -a +message.type=TRANSACTION" | tail -1

echo "创建 DELAY topic（延迟双删）: $TOPIC_CACHE_EVICT"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_CACHE_EVICT -a +message.type=DELAY" | tail -1

echo "创建 topic（普通类型）: $TOPIC_RISK_EVENT"
# 不带 -a：DELAY/TRANSACTION 才需要声明 message.type，风险事件二者都不是。
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_RISK_EVENT" | tail -1

echo "创建消费组: $GROUP"
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g $GROUP" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g dash-autosettle-consumer" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g dash-fund-event-consumer" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g dash-cache-evict-consumer" | tail -1

echo "创建风险事件消费组（含重试窗口）: $GROUP_RISK_EVENT"
# -r 3：D11 的取值理由——风险消费端只写日志，一条失败 3 次的消息几乎必然是格式非法
# （格式非法按 D10 根本不重投）或 worker 整体不可用。默认的 16 次会在 worker 停机时
# 堆出一条要十几分钟才排空的重试队列，让 P9.5 的「无长期堆积」多出一个无关的观察对象。
# 1 秒起、倍增、上限 10 秒：三次重投在 10 秒内走完。
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g $GROUP_RISK_EVENT -r 3 \
       -p '{\"type\":\"EXPONENTIAL\",\"exponentialRetryPolicy\":{\"initial\":1000,\"max\":10000,\"multiplier\":2}}'" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g $GROUP_RISK_EVENT_IT" | tail -1

echo "集群状态:"
admin "clusterList -n $NAMESRV" | tail -3
