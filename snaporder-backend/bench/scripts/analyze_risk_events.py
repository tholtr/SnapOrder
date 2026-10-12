#!/usr/bin/env python3
"""风控事件日志离线统计（P9.4 设计 §8.3）。

读一个或多个日志文件，把风控那几行 `key=value` 结构化日志统计成一份 JSON。
发布行（`risk_event_publish*`）在应用的日志里，消费行（`risk_event_processed`）
在 worker 的日志里，所以参数是**多个文件**而不是一个目录。

    python3 analyze_risk_events.py app.log worker.log

与 `analyze_s5_trace.py` 的关键差异：那个脚本读的是 JSON 行（`{"event":"timeout_`），
风控的日志行是 `key=value`，所以这里有一个小解析器。它与 `LogField.safe`（D13）
是**同一条通路的另一端**：`safe` 保证值里没有空格与换行，本脚本才敢按空格切分字段。
两处必须一起看——只改一边，切分立刻出错。

统计口径（设计 §8.3）：

* `risk_event_publish_total` / `risk_event_suppressed_total` / `risk_event_dropped_total`：
  投递行按 `result` 分组；
* `risk_event_duplicate_total`：消费行按 **`requestId + operationType`** 去重后，
  「总行数 − 去重后条数」（蓝图 §5.3 的第三条验收）。**消费侧的所有指标都先按这个键去重**
  （实施计划 §9.4），否则一次 MQ 重投递会被算成两次风险动作；
* `risk_event_consume_lag_ms`：`lagMs` 的 P50/P95/P99/max，取 `analyze_s5_trace.py` 的
  **非插值**算法（`math.ceil(n*p/100)-1` 取下标），两个脚本的百分位必须可互相校验；
* 风险动作覆盖率：聚合开启后风险动作还能被统计到的比例，以及去重后的动作分布；
* `risk_review_pending_total`。

两处与设计字面不同、但更严的地方（写在归档里）：

1. 动作分布/去重键用 `requestId + operationType` 而不是只 `requestId`。实施计划 §8.2 说
   「两者共同标识一次业务操作」，所以按对去重只会把两种操作分开、不会把它们合并；
   只按 `requestId` 则会把「同一 requestId 的两种操作」错并成一条。
2. 多输出两个「分母完整性」计数：`risk_event_publish_failed_total`（best-effort 发送
   失败的行）与 `risk_event_processed_unidentified_total`（缺 `requestId` 或
   `operationType`、无法参与去重的消费行）。少了它们，日志里全是发送失败或全是残缺行时，
   上面那些指标会一片 0 而看不出原因。

`--selftest` 喂一段合成日志（含成对的重复消费行、含空格污染行的反例）并断言输出，
退出码非 0 即失败。仓库对脚本没有测试设施，而「能按 requestId + operationType 去重」
是本阶段的验收项之一：一个从没被验证过的去重函数与一个写错的，在报告里的表现完全一样
——都给出一个数字。
"""

import argparse
import json
import math
import sys
from collections import Counter
from pathlib import Path

PUBLISH = "risk_event_publish"
PUBLISH_FAILED = "risk_event_publish_failed"
PROCESSED = "risk_event_processed"
REVIEW_PENDING = "risk_review_pending"
INVALID = "risk_event_invalid"


def pct(values, p):
    """非插值百分位，与 analyze_s5_trace.py 逐字一致（含 0.5 向上取整的边界）。"""
    index = max(0, min(math.ceil(len(values) * p / 100) - 1, len(values) - 1))
    return values[index]


def fields_of(line, marker):
    """行里若出现 `marker` 这个**完整词**，返回它后面的 `key=value` 字段；否则 None。

    两处判断都不能省：
    * marker 前面必须是行首或空白——日志行前有「时间 级别 线程 logger」前缀，
      但值里也可能碰巧出现同样的字面量，只认词边界才能分清；
    * marker 后面必须是空格或行尾——`risk_event_publish_failed` 以
      `risk_event_publish` 打头，少了这一条，发送失败的行会被算成投递行。
    """
    at = line.find(marker)
    if at < 0:
        return None
    if at > 0 and not line[at - 1].isspace():
        return None
    after = at + len(marker)
    if after < len(line) and not line[after].isspace():
        return None

    fields = {}
    for token in line[after:].split(" "):
        name, sep, value = token.partition("=")
        if not sep or not name:
            # 没有 = 的碎片：值里混进空格的症状（safe 失效）。
            # 丢掉它而不是让整行解析失败——一行坏数据不该带走整份报告的其余部分。
            continue
        fields.setdefault(name, value)
    return fields


def scan(paths):
    """把每个文件里那五类行分别收集起来。返回 dict[str, list[dict]]。"""
    buckets = {name: [] for name in (PUBLISH, PUBLISH_FAILED, PROCESSED, REVIEW_PENDING, INVALID)}
    for path in paths:
        for line in Path(path).read_text(errors="replace").splitlines():
            for name in buckets:
                fields = fields_of(line, name)
                if fields is not None:
                    fields["__file"] = str(path)
                    buckets[name].append(fields)
                    break
    return buckets


def percentiles(values):
    if not values:
        return None
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "p50": round(pct(ordered, 50), 1),
        "p95": round(pct(ordered, 95), 1),
        "p99": round(pct(ordered, 99), 1),
        "max": round(max(ordered), 1),
    }


def dedupe(rows, key_fields):
    """按 key_fields 去重，保留**第一次**出现的那条（日志按时间追加，首次即最早）。"""
    seen = {}
    for row in rows:
        key = tuple(row.get(name) for name in key_fields)
        seen.setdefault(key, row)
    return list(seen.values())


def analyze(paths):
    bucket = scan(paths)
    publish = bucket[PUBLISH]
    processed = bucket[PROCESSED]

    by_result = Counter(row.get("result", "?") for row in publish)

    # 去重的分母要先筛干净：缺 requestId/operationType 的行拼不出身份，
    # 让它们参与去重会把一堆不相干的行并成一条，反而凭空造出「重复」。
    identified = [row for row in processed
                  if row.get("requestId") and row.get("operationType")]
    deduped_processed = dedupe(identified, ("requestId", "operationType"))

    lags = []
    for row in deduped_processed:
        try:
            lags.append(int(row.get("lagMs", "")))
        except ValueError:
            continue

    by_action = Counter(row.get("decisionAction", "?") for row in deduped_processed)
    produced_actions = (by_result.get("SENT", 0) + by_result.get("SUPPRESSED", 0)
                        + by_result.get("DROPPED", 0))
    observable = len(deduped_processed)

    result = {
        "logs": [str(path) for path in paths],
        "risk_event_publish_total": by_result.get("SENT", 0),
        "risk_event_suppressed_total": by_result.get("SUPPRESSED", 0),
        "risk_event_dropped_total": by_result.get("DROPPED", 0),
        "risk_event_publish_failed_total": len(bucket[PUBLISH_FAILED]),
        "risk_event_invalid_total": len(bucket[INVALID]),
        "risk_event_processed_lines": len(processed),
        "risk_event_processed_unidentified_total": len(processed) - len(identified),
        "risk_event_duplicate_total": len(identified) - len(deduped_processed),
        "risk_event_consume_lag_ms": percentiles(lags),
        "risk_action_coverage": {
            "observable_actions": observable,
            "produced_actions": produced_actions,
            "coverage_pct": (round(100.0 * observable / produced_actions, 2)
                             if produced_actions else None),
            "by_action": dict(sorted(by_action.items())),
        },
        "risk_review_pending_total": len(
            dedupe(bucket[REVIEW_PENDING], ("eventId",))),
    }
    return result


SELFTEST_LOG = """\
2026-09-12T18:09:22.838+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish eventId=e1 requestId=it-1 operationType=GRAB errandId=1 runnerId=950001 decisionAction=LIMIT degraded=false gateType=LIMIT result=SENT
2026-09-12T18:09:22.839+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish eventId=e2 requestId=it-2 operationType=GRAB errandId=1 runnerId=950001 decisionAction=LIMIT degraded=false gateType=LIMIT result=SUPPRESSED
2026-09-12T18:09:22.840+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish eventId=e3 requestId=it-3 operationType=GRAB errandId=1 runnerId=950001 decisionAction=REVIEW degraded=false gateType=REVIEW result=DROPPED
2026-09-12T18:09:22.841+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish_failed eventId=e9 topic=errand-risk-event error=IllegalStateException
2026-09-12T18:09:22.842+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish eventId=e4 requestId=it-4 operationType=GRAB errandId=1 runnerId=950001 decisionAction=LIMIT degraded=false gateType=LIMIT result=SENT
2026-09-12T18:09:22.843+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish eventId=e6 requestId=it-6 operationType=GRAB errandId=1 runnerId=950001 decisionAction=REVIEW degraded=false gateType=REVIEW result=SENT
2026-09-12T18:09:22.844+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish eventId=e7 requestId=it-7 operationType=GRAB errandId=1 runnerId=950001 decisionAction=LIMIT degraded=false gateType=LIMIT result=SENT
2026-09-12T18:09:22.845+08:00  INFO 1 --- [risk-event-pub] c.c.i.r.RiskEventPublishLog : risk_event_publish eventId=e8 requestId=it-8 operationType=GRAB errandId=1 runnerId=950001 decisionAction=LIMIT degraded=false gateType=LIMIT result=SENT
2026-09-12T18:09:22.900+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_event_processed eventId=e1 requestId=it-1 operationType=GRAB errandId=1 runnerId=950001 outcome=DENIED decisionAction=LIMIT degraded=false degradeReasons=- occurredAt=1 lagMs=10 deliveryAttempt=1
2026-09-12T18:09:22.950+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_event_processed eventId=e1 requestId=it-1 operationType=GRAB errandId=1 runnerId=950001 outcome=DENIED decisionAction=LIMIT degraded=false degradeReasons=- occurredAt=1 lagMs=999 deliveryAttempt=2
2026-09-12T18:09:23.000+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_event_processed eventId=e4 requestId=it-4 op operationType=GRAB errandId=1 runnerId=950001 outcome=DENIED decisionAction=LIMIT degraded=false degradeReasons=- occurredAt=1 lagMs=20 deliveryAttempt=1
2026-09-12T18:09:23.020+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_event_processed eventId=e8 requestId=it-8 operationType=GRAB errandId=1 runnerId=950001 outcome=DENIED decisionAction=LIMIT degraded=false degradeReasons=- occurredAt=1 lagMs=30 deliveryAttempt=1
2026-09-12T18:09:23.050+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_event_processed eventId=e6 requestId=it-6 operationType=GRAB errandId=1 runnerId=950001 outcome=PENDING decisionAction=REVIEW degraded=false degradeReasons=- occurredAt=1 lagMs=40 deliveryAttempt=1
2026-09-12T18:09:23.055+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_review_pending eventId=e6 errandId=1
2026-09-12T18:09:23.060+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_review_pending eventId=e6 errandId=1
2026-09-12T18:09:23.070+08:00  WARN 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_event_invalid reason=BAD_JSON deliveryAttempt=1 error=JsonSyntaxException
2026-09-12T18:09:23.080+08:00  INFO 1 --- [worker] c.c.i.r.RiskEventConsumeLog : risk_event_processed eventId=e7 operationType=GRAB errandId=1 runnerId=950001 outcome=DENIED decisionAction=LIMIT degraded=false degradeReasons=- occurredAt=1 lagMs=50 deliveryAttempt=1
"""


def selftest():
    """喂合成日志并断言输出。退出码非 0 即失败。

    合成数据的每一行都压着一个分支，数字是**手算的期望**而不是跑一遍抄回来的：
    投递 5 SENT + 1 SUPPRESSED + 1 DROPPED；消费 6 行（it-1 重投递一次、
    it-4 的值被空格污染、it-7 缺 requestId）；去重后 4 条、lag 分别是 10/20/30/40。
    """
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "synthetic.log"
        path.write_text(SELFTEST_LOG)
        got = analyze([path])

    def expect(name, actual, wanted):
        if actual != wanted:
            raise SystemExit(f"[selftest] {name}: 期望 {wanted!r}，实际 {actual!r}")

    # 按 result 分组。`risk_event_publish_failed` 那行不能算进投递——它是
    # risk_event_publish 的超串，靠「marker 后面必须是空白」那条守卫挡住。
    expect("risk_event_publish_total", got["risk_event_publish_total"], 5)
    expect("risk_event_suppressed_total", got["risk_event_suppressed_total"], 1)
    expect("risk_event_dropped_total", got["risk_event_dropped_total"], 1)
    expect("risk_event_publish_failed_total", got["risk_event_publish_failed_total"], 1)
    expect("risk_event_invalid_total", got["risk_event_invalid_total"], 1)

    # 去重：6 条消费行里 5 条有完整身份（it-1 两条、it-4、it-6、it-8），
    # 去重后 4 条 → 重复 1 条；缺 requestId 的那条单独计数，不进分母。
    expect("risk_event_processed_lines", got["risk_event_processed_lines"], 6)
    expect("risk_event_processed_unidentified_total",
           got["risk_event_processed_unidentified_total"], 1)
    expect("risk_event_duplicate_total", got["risk_event_duplicate_total"], 1)

    # 去重保留**首次**那条：it-1 记的是 lagMs=10，不是重投递那次的 999。
    # n=4 时刻意与插值算法分道扬镳：下标算法给 20，插值会给 25。
    lag = got["risk_event_consume_lag_ms"]
    expect("lag.n", lag["n"], 4)
    expect("lag.p50", lag["p50"], 20.0)
    expect("lag.p95", lag["p95"], 40.0)
    expect("lag.p99", lag["p99"], 40.0)
    expect("lag.max", lag["max"], 40.0)

    # 覆盖率：7 次动作产生了投递（5 SENT + 1 抑制 + 1 丢弃），消费侧只还能看到 4 次
    # ——1 次被聚合抑制、1 次被丢弃、1 次是重投递（去重后不算新的）。
    coverage = got["risk_action_coverage"]
    expect("coverage.observable", coverage["observable_actions"], 4)
    expect("coverage.produced", coverage["produced_actions"], 7)
    expect("coverage.coverage_pct", coverage["coverage_pct"], 57.14)
    expect("coverage.by_action", coverage["by_action"], {"LIMIT": 3, "REVIEW": 1})

    # 待审行按 eventId 去重：两行 e6 只算一条。
    expect("risk_review_pending_total", got["risk_review_pending_total"], 1)

    # 污染行本身也要钉住行为：值里的空格让 requestId 被**截断**成 it-4，
    # 解析不崩、后面的 operationType 照样读到——这正是「与 LogField.safe 同通路」的意思：
    # safe 一旦失效，本脚本不会报错，只会安静地统计一个错的字符串。
    # 挑行也要挑对：`it-4 op` 这种前缀会同时命中投递行（`requestId=it-4 operationType=`），
    # 两行都在日志里时先命中的是投递行——所以带上尾随空格，再限定在消费行里找。
    polluted = [line for line in SELFTEST_LOG.splitlines()
                if PROCESSED in line and "it-4 op " in line][0]
    polluted_fields = fields_of(polluted, PROCESSED)
    expect("polluted.requestId", polluted_fields["requestId"], "it-4")
    expect("polluted.operationType", polluted_fields["operationType"], "GRAB")
    expect("polluted.lagMs", polluted_fields["lagMs"], "20")

    print("[selftest] 全部断言通过（投递分组 / 去重 / 百分位 / 覆盖率 / 污染行）")


def main():
    parser = argparse.ArgumentParser(description="风控事件日志离线统计")
    parser.add_argument("logs", nargs="*", help="日志文件（应用日志、worker 日志，可多个）")
    parser.add_argument("--selftest", action="store_true", help="跑合成日志自检后退出")
    args = parser.parse_args()

    if args.selftest:
        selftest()
        return

    if not args.logs:
        parser.error("至少要给一个日志文件（或用 --selftest）")
    missing = [path for path in args.logs if not Path(path).is_file()]
    if missing:
        print(f"日志文件不存在：{', '.join(missing)}", file=sys.stderr)
        raise SystemExit(2)

    print(json.dumps(analyze(args.logs), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
