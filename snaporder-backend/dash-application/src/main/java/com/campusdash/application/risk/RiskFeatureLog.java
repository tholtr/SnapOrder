package com.campusdash.application.risk;

import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.UserWindow;
import com.campusdash.shared.LogField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 特征采集完成后那一行的格式（计划 §6）。字段 camelCase；离线分析脚本负责映射到
 * snake_case 指标，所以<b>字段名与顺序是跨系统的契约，改动等于改协议</b>。
 *
 * <pre>
 * risk_feature userId=... errandId=... requestId=... count10s=... count60s=...
 *              deviceAccounts=... ipAccounts=... ipRequests=... degraded=false
 * </pre>
 *
 * <p><b>为什么这一行不属于适配器</b>：{@code errandId} 与 {@code degraded} 两个字段
 * 适配器都拿不到——端口签名里没有 errandId，而「本次采集是否降级」是调用方
 * catch 住异常才知道的事。硬塞进适配器意味着给端口加一个只为打日志存在的参数。
 *
 * <p><b>为什么本类只收 {@link Cluster} 这种聚合值、不收原始 deviceId/clientIp</b>：
 * 让「日志泄露原始设备号/IP」在类型上不可能发生，而不是靠每次改代码时记得住（D1）。
 * 端口返回的记录里只有计数，没有原始值，这里也就无从打印。
 */
public final class RiskFeatureLog {

    private static final Logger log = LoggerFactory.getLogger(RiskFeatureLog.class);

    private RiskFeatureLog() {
    }

    /**
     * 拼出日志行。单独拆出来是为了能在单测里断言整行文本——
     * 本仓库没有日志捕获依赖，把格式做成纯函数是唯一不引依赖的测法。
     *
     * <p>{@code requestId} 是本行里唯一来自客户端的字段，过 {@link LogField#safe} 净化；
     * 净化逻辑与 {@code RiskDecisionLog} 共用一处，理由见 {@link LogField}。
     */
    public static String format(long userId, long errandId, String requestId,
                                UserWindow window, Cluster cluster, boolean degraded) {
        return "risk_feature userId=" + userId
                + " errandId=" + errandId
                + " requestId=" + LogField.safe(requestId)
                + " count10s=" + window.count10s()
                + " count60s=" + window.count60s()
                + " deviceAccounts=" + cluster.deviceAccounts()
                + " ipAccounts=" + cluster.ipAccounts()
                + " ipRequests=" + cluster.ipRequests()
                + " degraded=" + degraded;
    }

    /** 采集完成后调用。 */
    public static void emit(long userId, long errandId, String requestId,
                            UserWindow window, Cluster cluster, boolean degraded) {
        log.info(format(userId, errandId, requestId, window, cluster, degraded));
    }

}
