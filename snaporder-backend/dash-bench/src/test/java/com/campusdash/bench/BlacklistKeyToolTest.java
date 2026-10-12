package com.campusdash.bench;

import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖重点不是「输出好不好看」，而是**工具算出的键与应用的是否同一个**——
 * 这条不一致会让黑名单静默失效，是本模块反复出现的故障类型。
 */
@DisplayName("黑名单键计算工具")
class BlacklistKeyToolTest {

    private static final DeviceHasher HASHER = new DeviceHasher("local-dev-secret");

    /** 报告格式与端点无关，测非默认端点的那两条会显式换一个。 */
    private static final String ENDPOINT = BlacklistKeyTool.DEFAULT_HOST + ":" + BlacklistKeyTool.DEFAULT_PORT;

    @Test
    @DisplayName("设备键与 RiskKeys 算出的一致——即与应用查询侧同源")
    void deviceKeyMatchesRiskKeys() {
        // 这是本工具存在的全部意义所在。工具自己拼一套字符串也能「跑通」，
        // 但那种跑通毫无价值：键对不上，黑名单就是死的。
        String report = BlacklistKeyTool.buildReport("device", "device-abc", HASHER);

        String expectedKey = RiskKeys.blacklistDevice(HASHER.hash("device-abc"));
        assertTrue(report.contains(expectedKey), "报告里没有出现应用实际查询的键：" + expectedKey);
    }

    @Test
    @DisplayName("IP 键与 RiskKeys 算出的一致")
    void ipKeyMatchesRiskKeys() {
        String report = BlacklistKeyTool.buildReport("ip", "1.2.3.4", HASHER);

        assertTrue(report.contains(RiskKeys.blacklistIp(HASHER.hash("1.2.3.4"))));
    }

    @Test
    @DisplayName("用户维度不做哈希，直接是 userId")
    void userKeyIsNotHashed() {
        String report = BlacklistKeyTool.buildReport("user", "1001", HASHER);

        assertEquals(RiskKeys.blacklistUser(1001), extractKeyLine(report));
    }

    @Test
    @DisplayName("报告里绝不能出现密钥——它的输出常被贴进工单和聊天记录")
    void doesNotLeakSecret() {
        // 报告会回显「原始值」供运维核对（那是他们自己敲进去的，不构成新泄露），
        // 但密钥不行：它来自环境变量，一旦被打出来就会顺着复制粘贴扩散出去。
        // 这条守的是一个很容易在加调试输出时被破坏的不变量。
        String report = BlacklistKeyTool.buildReport("ip", "203.0.113.77", HASHER);

        assertFalse(report.contains("local-dev-secret"), "报告里出现了密钥");
    }

    @Test
    @DisplayName("输出可直接粘贴：SADD 与 SISMEMBER 用的是同一个键")
    void emitsRunnableCommands() {
        String report = BlacklistKeyTool.buildReport("device", "device-abc", HASHER);
        String key = RiskKeys.blacklistDevice(HASHER.hash("device-abc"));

        assertTrue(report.contains("SADD " + key + " 1"));
        assertTrue(report.contains("SISMEMBER " + key + " 1"));
    }

    @Test
    @DisplayName("未知维度给出可执行的提示，而不是 NPE 或静默产出错键")
    void rejectsUnknownDimension() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BlacklistKeyTool.buildReport("devcie", "x", HASHER));

        assertTrue(ex.getMessage().contains("user / device / ip"));
    }

    @Test
    @DisplayName("用户维度传入非数字直接报错")
    void rejectsNonNumericUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> BlacklistKeyTool.buildReport("user", "device-abc", HASHER));
    }

    @Test
    @DisplayName("维度大小写不敏感，避免运维手敲大小写踩坑")
    void dimensionIsCaseInsensitive() {
        assertEquals(BlacklistKeyTool.buildReport("ip", "1.2.3.4", HASHER),
                BlacklistKeyTool.buildReport("IP", "1.2.3.4", HASHER));
    }

    // ── 写入路径（P9.2 任务 12）────────────────────────────────────────

    @Test
    @DisplayName("两参数的旧写法永远解析成只算键，不会写")
    void twoArgFormIsNeverAWrite() {
        // 本工具最危险的行为是「写」：黑名单是生产数据，「以为只是算个键、结果写进去了」
        // 比「以为写进去了、结果只算了个键」危险得多——后者再跑一次就好，
        // 前者可能把一个正常用户永久封禁。所以这条性质不能只写在注释里。
        assertEquals(BlacklistKeyTool.ACTION_KEY,
                BlacklistKeyTool.parse(new String[]{"user", "1001"}).action());
        assertEquals(BlacklistKeyTool.ACTION_KEY,
                BlacklistKeyTool.parse(new String[]{"device", "device-abc"}).action());
    }

    @Test
    @DisplayName("三参数时首个参数是动作，且大小写不敏感")
    void actionIsTheFirstArgument() {
        BlacklistKeyTool.Command command =
                BlacklistKeyTool.parse(new String[]{"ADD", "device", "device-abc"});

        assertEquals(BlacklistKeyTool.ACTION_ADD, command.action());
        assertEquals("device", command.dimension());
        assertEquals("device-abc", command.rawIdentifier());
    }

    @Test
    @DisplayName("动作写错时报错，而不是被当成维度去算键")
    void rejectsUnknownAction() {
        // 若把 "ad" 当成维度，会走到「未知维度」那条错误——提示指向 user/device/ip，
        // 而真正写错的是动作。两种拼错必须给出各自的提示，否则运维会照着一个
        // 正确维度的提示去改一个没错的维度。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BlacklistKeyTool.parse(new String[]{"ad", "device", "device-abc"}));

        assertTrue(ex.getMessage().contains("add"), ex.getMessage());
        assertTrue(ex.getMessage().contains("remove"), ex.getMessage());
    }

    @Test
    @DisplayName("参数个数不对时打印用法，而不是数组越界")
    void rejectsWrongArity() {
        assertThrows(IllegalArgumentException.class, () -> BlacklistKeyTool.parse(new String[]{"user"}));
        assertThrows(IllegalArgumentException.class,
                () -> BlacklistKeyTool.parse(new String[]{"add", "user", "1001", "extra"}));
    }

    @Test
    @DisplayName("写入报告里的键与 RiskKeys 算出的一致——写的和查的必须是同一个键")
    void addReportUsesTheSameKeyAsTheApplication() {
        String report = BlacklistKeyTool.buildAddReport("device", "device-abc", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, true, 1, -1));

        assertEquals(RiskKeys.blacklistDevice(HASHER.hash("device-abc")), extractKeyLine(report));
    }

    @Test
    @DisplayName("解除报告里的键与 RiskKeys 算出的一致")
    void removeReportUsesTheSameKeyAsTheApplication() {
        // 解除时键算错，危害不比写入时小：运维以为放行了，用户还在被拦。
        String report = BlacklistKeyTool.buildRemoveReport("ip", "1.2.3.4", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, true, false, 0, -2));

        assertEquals(RiskKeys.blacklistIp(HASHER.hash("1.2.3.4")), extractKeyLine(report));
    }

    @Test
    @DisplayName("用户维度在写入报告里同样不做哈希")
    void addReportDoesNotHashUserId() {
        String report = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, true, 1, -1));

        assertEquals(RiskKeys.blacklistUser(1001), extractKeyLine(report));
    }

    @Test
    @DisplayName("写入报告里出现的是实际连的那个端点")
    void writeReportShowsTheEndpointItWroteTo() {
        // 报告里的数字必须说得出它来自哪个库。写死默认端点的话，
        // 一份照着复制的命令在生产上会打到本机 Redis——不报错，只是黑名单没生效。
        String report = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, "10.0.0.5:6379",
                new BlacklistKeyTool.Outcome("10.0.0.5:6379", false, true, 1, -1));

        assertTrue(report.contains("10.0.0.5:6379"), report);
        assertFalse(report.contains(BlacklistKeyTool.DEFAULT_HOST + ":" + BlacklistKeyTool.DEFAULT_PORT),
                "不该同时出现默认端点，否则运维分不清写到了哪个库：" + report);
    }

    @Test
    @DisplayName("只算键的报告也用传入的端点，不写死默认端口")
    void keyReportUsesTheGivenEndpoint() {
        // P9.1 的报告写死了 -p 6380。那在生产上是一条会打错库的命令：
        // 语法合法、执行成功、只是黑名单没生效。
        String report = BlacklistKeyTool.buildReport("user", "1001", HASHER, "10.0.0.5:6379");

        assertTrue(report.contains("redis://10.0.0.5:6379"), report);
        assertFalse(report.contains("6380"), "不该残留默认端口：" + report);
    }

    @Test
    @DisplayName("带 TTL 的黑名单必须被显眼地标出来")
    void writeReportWarnsAboutADeadline() {
        // 手写键时带了 TTL，黑名单会自己消失——不报错、不告警，只是某天开始不再拦截。
        // 这正是本模块反复出现的那类静默失效，所以它不能只显示成一个数字。
        String report = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, true, true, 1, 3600));

        assertTrue(report.contains("3600"), report);
        assertTrue(report.contains("会自己失效"), report);
    }

    @Test
    @DisplayName("永久、只剩一段、键已不在，三种 TTL 读数各有各的说法")
    void ttlReadingsAreDistinguished() {
        // -1 是「永久」，-2 是「键不存在」，正数是「会自己失效」。
        // 把 -2 渲染成「永久」是最坏的一种：写入根本没生效，报告却说黑名单已经立住了。
        String permanent = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, true, 1, -1));
        String expiring = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, true, 1, 3600));
        String gone = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, false, 0, -2));

        assertTrue(permanent.contains("永久"), permanent);
        assertFalse(permanent.contains("会自己失效"), "永久不该报警：" + permanent);
        assertTrue(expiring.contains("会自己失效"), expiring);
        assertTrue(gone.contains("键不存在"), gone);
    }

    @Test
    @DisplayName("核验结果如实渲染，不美化")
    void verificationIsReportedAsIs() {
        // 核验失败时报告必须说「0」。把 0 渲染成对勾等于把一次没生效的写入
        // 报告成成功——运维不会再查第二次。
        String failed = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, false, 0, -2));

        assertTrue(failed.contains("EXISTS 判定 → 0"), failed);
    }

    @Test
    @DisplayName("写入前已在库里与刚写进去，两种情形分开说")
    void alreadyBlacklistedIsReportedDifferently() {
        String fresh = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, true, 1, -1));
        String existing = BlacklistKeyTool.buildAddReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, true, true, 1, -1));

        assertTrue(fresh.contains("此前不存在"), fresh);
        assertTrue(existing.contains("已存在"), existing);
    }

    @Test
    @DisplayName("解除时键本来就不存在，如实说是「无事发生」而不是「已解除」")
    void removingSomethingThatWasNotThereSaysSo() {
        // 「已解除」会让运维以为刚才那次封禁生效过。这两件事在复盘时意义完全不同。
        String report = BlacklistKeyTool.buildRemoveReport("user", "1001", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, false, 0, -2));

        assertTrue(report.contains("无事发生"), report);
    }

    @Test
    @DisplayName("两份写入报告里都不能出现密钥")
    void writeReportsDoNotLeakSecret() {
        // 报告常被整段贴进工单。密钥一旦被打出来就会顺着复制粘贴扩散出去。
        String add = BlacklistKeyTool.buildAddReport("ip", "203.0.113.77", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, false, true, 1, -1));
        String remove = BlacklistKeyTool.buildRemoveReport("ip", "203.0.113.77", HASHER, ENDPOINT,
                new BlacklistKeyTool.Outcome(ENDPOINT, true, false, 0, -2));

        assertFalse(add.contains("local-dev-secret"), "写入报告里出现了密钥");
        assertFalse(remove.contains("local-dev-secret"), "解除报告里出现了密钥");
    }

    @Test
    @DisplayName("写入路径上的维度校验与只算键完全一致")
    void writeReportsRejectUnknownDimensionToo() {
        // 三条路径共用 keyOf，所以「未知维度」和「userId 不是数字」的提示只能有一份。
        // 若哪天有人在写入路径上手搓一份键计算，这两条会先红。
        assertThrows(IllegalArgumentException.class,
                () -> BlacklistKeyTool.buildAddReport("devcie", "x", HASHER, ENDPOINT,
                        new BlacklistKeyTool.Outcome(ENDPOINT, false, true, 1, -1)));
        assertThrows(IllegalArgumentException.class,
                () -> BlacklistKeyTool.buildRemoveReport("user", "device-abc", HASHER, ENDPOINT,
                        new BlacklistKeyTool.Outcome(ENDPOINT, true, false, 0, -2)));
    }

    private static String extractKeyLine(String report) {
        return report.lines()
                .filter(line -> line.startsWith("Redis 键 : "))
                .map(line -> line.substring("Redis 键 : ".length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有 Redis 键 一行：\n" + report));
    }
}
