#!/usr/bin/env python3
"""Join worker JSON trace logs with a bench_run_id and calculate S5 timing stages."""

import argparse
import datetime as dt
import json
import math
import re
import subprocess
from pathlib import Path


DB = ["mysql", "-h127.0.0.1", "-P3307", "-uroot", "-pdash123456", "campus_dash", "-N", "-B", "-e"]


def mysql(query):
    return subprocess.check_output(DB + [query], text=True).strip()


def pct(values, p):
    index = max(0, min(math.ceil(len(values) * p / 100) - 1, len(values) - 1))
    return values[index]


def parse_trace(path):
    events = []
    for line in Path(path).read_text(errors="replace").splitlines():
        marker = line.find('{"event":"timeout_')
        if marker < 0:
            continue
        try:
            event = json.loads(line[marker:])
        except json.JSONDecodeError:
            continue
        events.append(event)
    return events


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--trace-log", required=True)
    parser.add_argument("--timeout-seconds", type=int, required=True)
    args = parser.parse_args()

    ids_text = mysql(
        f"SELECT entity_id FROM bench_run_item "
        f"WHERE run_id='{args.run_id}' AND entity_type='ERRAND'"
    )
    ids = [int(value) for value in ids_text.splitlines() if value.strip()]
    if not ids:
        raise SystemExit("run_id 没有登记任务")
    id_set = set(ids)
    ids_csv = ",".join(map(str, ids))

    trace = [event for event in parse_trace(args.trace_log)
             if int(event.get("errandId", -1)) in id_set]
    received = {}
    completed = {}
    for event in trace:
        key = (int(event["errandId"]), int(event["round"]), event["source"])
        if event["event"] == "timeout_received":
            received[key] = event
        elif event["event"] == "timeout_completed":
            completed[key] = event

    lock_rows = mysql(f"""
        SELECT errand_id, MAX(created_at)
          FROM errand_status_log
         WHERE errand_id IN ({ids_csv})
           AND from_status='PUBLISHED' AND to_status='LOCKED'
         GROUP BY errand_id
    """)
    flow_rows = mysql(f"""
        SELECT errand_id, MAX(created_at)
          FROM errand_status_log
         WHERE errand_id IN ({ids_csv})
           AND from_status='LOCKED'
         GROUP BY errand_id
    """)

    def parse_time(value):
        return dt.datetime.strptime(value, "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000

    locked_at = {int(row.split("\t")[0]): parse_time(row.split("\t")[1])
                 for row in lock_rows.splitlines() if row.strip()}
    flowed_at = {int(row.split("\t")[0]): parse_time(row.split("\t")[1])
                 for row in flow_rows.splitlines() if row.strip()}

    expiry_to_receive = []
    receive_to_complete = []
    expiry_to_persist = []
    matched = 0
    for errand_id in ids:
        if errand_id not in locked_at or errand_id not in flowed_at:
            continue
        candidates = [event for key, event in received.items()
                      if key[0] == errand_id]
        if not candidates:
            continue
        event = min(candidates, key=lambda item: item["tsEpochMs"])
        completed_event = completed.get((
            errand_id, int(event["round"]), event["source"]))
        expected_expiry = locked_at[errand_id] + args.timeout_seconds * 1000
        expiry_to_receive.append(event["tsEpochMs"] - expected_expiry)
        expiry_to_persist.append(flowed_at[errand_id] - expected_expiry)
        if completed_event:
            receive_to_complete.append(
                completed_event["tsEpochMs"] - event["tsEpochMs"])
        matched += 1

    if not expiry_to_persist:
        raise SystemExit("没有匹配到可统计的状态流转")

    locked_count = int(mysql(
        f"SELECT COUNT(*) FROM errand WHERE id IN ({ids_csv}) AND status='LOCKED'"
    ))
    repeated_count = int(mysql(f"""
        SELECT COUNT(*) FROM (
          SELECT errand_id, COUNT(*) AS c
            FROM errand_status_log
           WHERE errand_id IN ({ids_csv}) AND from_status='LOCKED'
           GROUP BY errand_id HAVING c > 1
        ) repeated
    """))

    result = {
        "runId": args.run_id,
        "tasks": len(ids),
        "matchedTraceTasks": matched,
        "locked": locked_count,
        "repeatedFlowTasks": repeated_count,
        "expiryToMqReceiveMs": {
            "p50": round(pct(sorted(expiry_to_receive), 50), 1),
            "p95": round(pct(sorted(expiry_to_receive), 95), 1),
            "p99": round(pct(sorted(expiry_to_receive), 99), 1),
            "max": round(max(expiry_to_receive), 1),
        },
        "mqReceiveToCompleteMs": {
            "p50": round(pct(sorted(receive_to_complete), 50), 1),
            "p95": round(pct(sorted(receive_to_complete), 95), 1),
            "p99": round(pct(sorted(receive_to_complete), 99), 1),
            "max": round(max(receive_to_complete), 1),
        } if receive_to_complete else None,
        "expiryToStatusPersistMs": {
            "p50": round(pct(sorted(expiry_to_persist), 50), 1),
            "p95": round(pct(sorted(expiry_to_persist), 95), 1),
            "p99": round(pct(sorted(expiry_to_persist), 99), 1),
            "max": round(max(expiry_to_persist), 1),
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
