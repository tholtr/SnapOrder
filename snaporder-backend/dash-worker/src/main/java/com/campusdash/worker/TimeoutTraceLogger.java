package com.campusdash.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 可开关的超时链路结构化日志。
 *
 * run_id 不写入业务消息：测试结束后由 bench_run_item 按 errand_id 关联，
 * 避免测试字段侵入在线业务模型和 MQ payload。
 */
@Component
public class TimeoutTraceLogger {

    private static final Logger log = LoggerFactory.getLogger(TimeoutTraceLogger.class);

    private final boolean enabled;

    public TimeoutTraceLogger(
            @Value("${dash.timeout.trace.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void received(long errandId, int round, String source, String msgKey) {
        if (!enabled) {
            return;
        }
        write("timeout_received", errandId, round, source, msgKey, null, null);
    }

    public void completed(long errandId, int round, String source, String msgKey,
                          String outcome, long elapsedMs) {
        if (!enabled) {
            return;
        }
        write("timeout_completed", errandId, round, source, msgKey, outcome, elapsedMs);
    }

    private void write(String event, long errandId, int round, String source,
                       String msgKey, String outcome, Long elapsedMs) {
        StringBuilder json = new StringBuilder(192)
                .append("{\"event\":\"").append(event)
                .append("\",\"tsEpochMs\":").append(System.currentTimeMillis())
                .append(",\"errandId\":").append(errandId)
                .append(",\"round\":").append(round)
                .append(",\"source\":\"").append(escape(source)).append("\"");
        if (msgKey != null) {
            json.append(",\"msgKey\":\"").append(escape(msgKey)).append("\"");
        }
        if (outcome != null) {
            json.append(",\"outcome\":\"").append(escape(outcome)).append("\"");
        }
        if (elapsedMs != null) {
            json.append(",\"elapsedMs\":").append(elapsedMs);
        }
        json.append('}');
        log.info(json.toString());
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
