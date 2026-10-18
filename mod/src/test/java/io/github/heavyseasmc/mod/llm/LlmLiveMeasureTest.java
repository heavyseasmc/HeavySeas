package io.github.heavyseasmc.mod.llm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 实测：真连一个 OpenAI 兼容的服务，量「快不快、答不答得对格式、花多少 token」（ADR-0096 §6）。<b>平时不跑</b>。
 *
 * <p>要跑就设这几个环境变量（缺一个就跳过，并把缺的是哪个打出来 —— 跳过与跑过在日志上长得一样，证伪表）：
 * <ul>
 *   <li>{@code HEAVYSEAS_LLM_LIVE=1}</li>
 *   <li>{@code HEAVYSEAS_LLM_BASE_URL}：接口的根，写到 {@code /v1}</li>
 *   <li>{@code HEAVYSEAS_LLM_MODELS}：逗号分隔，{@code 模型[:reasoning_effort][@attemptShare]}（例 {@code gpt-x:low@1.0}）</li>
 *   <li>{@code HEAVYSEAS_LLM_API_KEY}：密钥（可省）。<b>只经环境变量</b>，测试里不出现、不打印</li>
 * </ul>
 * 每个模型：10 种决定各跑两轮（顺序发，截止 20 秒），再 5 个站队决定同时发（截止 8 秒，余量 1 秒 —— 站队窗口的样子）。
 * 结果打印出来，并写一份到 {@code mod/build/llm-live/}（不进仓库）。局面是编的，用的是规则书里的名字与说法。
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LlmLiveMeasureTest {

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    record Row(String model, String round, DecisionKind kind, String seat, int options, ChoiceOutcome outcome) {
    }

    @Test
    @DisplayName("实测（要设环境变量才跑）：各模型的耗时 p50 / p95、尝试次数、认得出的比例、5 个同时发")
    void measure() throws Exception {
        List<String> missing = new ArrayList<>();
        if (!"1".equals(env("HEAVYSEAS_LLM_LIVE"))) {
            missing.add("HEAVYSEAS_LLM_LIVE=1");
        }
        if (env("HEAVYSEAS_LLM_BASE_URL") == null) {
            missing.add("HEAVYSEAS_LLM_BASE_URL");
        }
        if (env("HEAVYSEAS_LLM_MODELS") == null) {
            missing.add("HEAVYSEAS_LLM_MODELS");
        }
        if (!missing.isEmpty()) {
            System.out.println("大模型实测跳过：没设 " + String.join("、", missing) + "（这一条平时不跑，不是通过）");
            Assumptions.abort("大模型实测跳过：没设 " + String.join("、", missing));
        }
        String baseUrl = env("HEAVYSEAS_LLM_BASE_URL");
        List<Row> rows = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("# 大模型实测 ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");
        report.append("接口：").append(java.net.URI.create(baseUrl).getHost()).append(":")
                .append(java.net.URI.create(baseUrl).getPort()).append(" · 密钥 ")
                .append(env(LlmConfig.KEY_ENV) == null ? "无" : "有（环境变量）").append("\n\n");

        for (String spec : env("HEAVYSEAS_LLM_MODELS").split(",")) {
            String model = spec.strip();
            Double share = null;
            int at = model.indexOf('@');
            if (at > 0) {
                share = Double.parseDouble(model.substring(at + 1));
                model = model.substring(0, at);
            }
            String effort = null;
            int colon = model.indexOf(':');
            if (colon > 0) {
                effort = model.substring(colon + 1);
                model = model.substring(0, colon);
            }
            String label = model + (effort == null ? "" : "（effort " + effort + "）") + (share == null ? "" : "（share " + share + "）");

            // 顺序：10 种决定 × 2 轮，截止 20 秒
            try (LlmService service = LlmService.start(config(baseUrl, model, effort, share, 4))) {
                assertEnabled(service);
                for (int round = 1; round <= 2; round++) {
                    for (ChoiceRequest template : decisions(20_000)) {
                        // 每一次的截止从发出那一刻起算（20 秒，与游戏里大多数窗口一样）
                        ChoiceRequest r = new ChoiceRequest(template.seat(), template.kind(), template.options(),
                                template.situation(), Instant.now().plusMillis(20_000));
                        ChoiceOutcome o = service.choose(r).get(60, TimeUnit.SECONDS);
                        rows.add(new Row(label, "顺序", r.kind(), r.seat(), r.options().size(), o));
                        System.out.println(line(label, "顺序" + round, r, o));
                    }
                }
            }
            // 同时发：5 个站队决定，截止 8 秒、余量 1 秒；名额给 5 个（看服务端自己扛不扛得住）
            try (LlmService service = LlmService.start(config(baseUrl, model, effort, share, 5))) {
                assertEnabled(service);
                List<ChoiceRequest> batch = contestBatch(8_000);
                List<CompletableFuture<ChoiceOutcome>> futures = batch.stream().map(service::choose).toList();
                for (int i = 0; i < batch.size(); i++) {
                    ChoiceOutcome o = futures.get(i).get(60, TimeUnit.SECONDS);
                    rows.add(new Row(label, "同时5", batch.get(i).kind(), batch.get(i).seat(), batch.get(i).options().size(), o));
                    System.out.println(line(label, "同时5", batch.get(i), o));
                }
            }
        }

        report.append(table(rows));
        report.append("\n## 每一次\n\n| 模型 | 轮 | 窗 | 座位 | 选项 | 结果 | 经过 | 耗时 ms | token 入/出（思考） | 回答 |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            ChoiceOutcome o = r.outcome();
            report.append("| ").append(r.model()).append(" | ").append(r.round()).append(" | ").append(r.kind())
                    .append(" | ").append(r.seat()).append(" | ").append(r.options())
                    .append(" | ").append(o.chosen() ? "选 " + (o.index() + 1) : "退路 " + o.fallback())
                    .append(" | ").append(o.trailText()).append(" | ").append(o.latencyMs())
                    .append(" | ").append(o.usage()).append(" | ").append(o.answer() == null ? "" : o.answer().replace("|", "/"))
                    .append(" |\n");
        }
        Path dir = Path.of("build", "llm-live");
        Files.createDirectories(dir);
        Path out = dir.resolve("llm-live-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(table(rows));
        System.out.println("大模型实测：写到 " + out.toAbsolutePath());
    }

    private static void assertEnabled(LlmService service) {
        if (!service.enabled()) {
            throw new AssertionError("服务没开起来：" + service.disabledWhy());
        }
    }

    private static LlmConfig config(String baseUrl, String model, String effort, Double share, int concurrent) {
        LlmConfig.Draft draft = new LlmConfig.Draft(true, baseUrl, null, model, null, null, effort, null, share, null,
                null, null, null, concurrent, null, null, 0, null, "zh_cn", false);
        LlmConfig.Loaded loaded = LlmConfig.from(draft, List.of(), System::getenv);
        if (loaded.broken()) {
            throw new AssertionError("设置写坏了：" + loaded.problem());
        }
        return loaded.config();
    }

    private static String line(String model, String round, ChoiceRequest r, ChoiceOutcome o) {
        return "大模型实测 " + model + " " + round + " " + r.kind() + " " + r.seat() + " → "
                + (o.chosen() ? "选 " + (o.index() + 1) + "「" + r.options().get(o.index()) + "」" : "退路 " + o.fallback() + "（" + o.detail() + "）")
                + " 经过=" + o.trailText() + " 耗时=" + o.latencyMs() + "ms token=" + o.usage()
                + (o.answer() == null ? "" : " 回答「" + o.answer() + "」");
    }

    /** 按模型 × 轮次汇总：次数、认得出的比例、p50 / p95 / 最大耗时、尝试次数分布、平均 token。 */
    private static String table(List<Row> rows) {
        StringBuilder sb = new StringBuilder("## 汇总\n\n| 模型 | 轮 | 次数 | 选中 | p50 ms | p95 ms | 最大 ms | 尝试次数分布 | 平均 token 入/出 | 退路 |\n|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, List<Row>> groups = rows.stream().collect(Collectors.groupingBy(r -> r.model() + " · " + r.round(),
                TreeMap::new, Collectors.toList()));
        groups.forEach((key, list) -> {
            List<Long> latency = list.stream().map(r -> r.outcome().latencyMs()).sorted().toList();
            long chosen = list.stream().filter(r -> r.outcome().chosen()).count();
            Map<Integer, Long> attempts = list.stream().collect(Collectors.groupingBy(r -> r.outcome().attempts(),
                    TreeMap::new, Collectors.counting()));
            double in = list.stream().mapToInt(r -> Math.max(0, r.outcome().usage().prompt())).average().orElse(0);
            double out = list.stream().mapToInt(r -> Math.max(0, r.outcome().usage().completion())).average().orElse(0);
            String fallbacks = list.stream().filter(r -> !r.outcome().chosen())
                    .collect(Collectors.groupingBy(r -> r.outcome().fallback().name(), TreeMap::new, Collectors.counting()))
                    .toString();
            String[] parts = key.split(" · ");
            sb.append("| ").append(parts[0]).append(" | ").append(parts[1]).append(" | ").append(list.size())
                    .append(" | ").append(chosen).append("/").append(list.size())
                    .append(" | ").append(percentile(latency, 0.50)).append(" | ").append(percentile(latency, 0.95))
                    .append(" | ").append(latency.getLast())
                    .append(" | ").append(attempts.entrySet().stream().map(e -> e.getKey() + "次×" + e.getValue())
                            .collect(Collectors.joining(" ")))
                    .append(" | ").append(Math.round(in)).append("/").append(Math.round(out))
                    .append(" | ").append(fallbacks.equals("{}") ? "-" : fallbacks).append(" |\n");
        });
        return sb.toString();
    }

    /** 最近秩：第 ⌈p·n⌉ 个（从 1 数）。 */
    private static long percentile(List<Long> sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    // ---- 编的局面：规则书里的名字与说法，不来自任何一局 ----

    private static List<ChoiceRequest> decisions(long deadlineMs) {
        Instant deadline = Instant.now().plusMillis(deadlineMs);
        List<ChoiceRequest> out = new ArrayList<>();
        out.add(ProbeDecision.request("zh_cn", deadline));
        out.add(new ChoiceRequest("大副", DecisionKind.PROVISION, List.of("水", "医疗箱", "名画", "救生圈"), """
                第 2 天 · 天候：晴空
                海鸥：1 只（凑满 4 只就靠岸）
                你：大副，体力 6（体型 6），清醒；从船头数第 3 个座位
                你手里：短刀；你面前：没有
                你心里：爱 小孩；恨 收藏家
                补给箱传到你手上：箱里还剩 4 张，你后面还有 3 个人等着拿""", deadline));
        out.add(new ChoiceRequest("水手", DecisionKind.ROW, List.of(
                "海鸥 +1；落海：没人；口渴：大副、医生；印着船桨图示",
                "海鸥 无；落海：珠宝商；口渴：所有人",
                "海鸥 −1；落海：医生；口渴：水手；印着打架图示"), """
                第 4 天 · 天候：闷热（每次口渴要 2 张水才能化解）
                海鸥：3 只（再来 1 只就靠岸）
                你：水手，体力 4（体型 4），清醒
                你手里：水；你面前：船桨
                你心里：爱 珠宝商；恨 医生
                你在划船：摸了 3 张航海牌（面前的船桨让你多摸 1 张），挑 1 张扣进划船堆""", deadline));
        out.add(new ChoiceRequest("陪酒女", DecisionKind.HELM, List.of(
                "海鸥 +1；落海：所有人；口渴：没人",
                "海鸥 无；落海：小孩；口渴：陪酒女、船长；印着船桨图示",
                "海鸥 +1；落海：船长；口渴：所有人"), """
                第 5 天 · 天候：晴空
                海鸥：3 只（再来 1 只就靠岸，这张牌后面的落海与口渴就不结算了）
                你：陪酒女，体力 2（体型 2），清醒；你坐在最靠船尾，是舵手
                你手里：水；你面前：没有
                你心里：爱 船长；恨 小孩
                今天划过船的有：船长、你""", deadline));
        out.add(new ChoiceRequest("小孩", DecisionKind.TARGET, List.of(
                "船长（手里 3 张；面前：指南针）", "大副（手里 0 张；面前：短刀）", "陪酒女（手里 2 张）",
                "收藏家（手里 1 张；面前：名画）"), """
                第 3 天 · 天候：晴空
                你：小孩，体力 1（体型 1），清醒
                你手里：没有；你面前：没有
                你心里：爱 陪酒女；恨 收藏家
                你按下了「抢」：小孩只能从对方手里随机拿一张，对方不能拒绝""", deadline));
        out.add(new ChoiceRequest("收藏家", DecisionKind.CONTEST_ANSWER, List.of("同意（让他拿一张）", "不同意（打起来）"), """
                第 3 天 · 天候：晴空
                你：收藏家，体力 3（体型 3），清醒
                你手里：名画、水；你面前：没有
                你心里：爱 医生；恨 大副
                大副（体型 6）要抢你；船上还没人站队""", deadline));
        out.add(new ChoiceRequest("医生", DecisionKind.CONTEST_JOIN, List.of("不加入", "加入发起方（船长）", "加入被指方（大副）"), """
                第 4 天 · 天候：晴空
                你：医生，体力 4（体型 4），清醒
                你心里：爱 船长；恨 陪酒女
                船长要和大副换座位，大副不同意，打起来了
                现在：发起方 船长（体型 5），合计 5；被指方 大副（体型 6），合计 6；平手算被指方赢""", deadline));
        out.add(new ChoiceRequest("大副", DecisionKind.CONTEST_WEAPON, List.of("不押", "押短刀", "押信号枪"), """
                第 4 天 · 天候：晴空
                你：大副，体力 6（体型 6），清醒；你是被指方
                你手里：短刀、信号枪；你面前：船桨
                现在：发起方 船长 + 医生，合计体型 9；被指方 只有你，体型 6；平手算被指方赢
                输的一边每人受 1 点伤害""", deadline));
        out.add(new ChoiceRequest("珠宝商", DecisionKind.THIRST, List.of("喝 0 张（受 2 点伤害）", "喝 1 张（受 1 点伤害）",
                "喝 2 张（不受伤）"), """
                第 5 天 · 天候：炎热
                你：珠宝商，体力 2（体型 3），清醒
                你手里：水、水；你面前：没有
                你心里：爱 船长；恨 大副
                你今天口渴 2 次（划过船、这张牌印着船桨图示；今天天候炎热）。每次喝 1 张水化解，不喝就受 1 点伤害""", deadline));
        out.add(new ChoiceRequest("船长", DecisionKind.OVERBOARD, List.of("什么也不出", "把救生圈扔给陪酒女",
                "打出血饵（这一批落海的每人再受 1 点伤害）", "把救生圈扔给大副"), """
                第 6 天 · 天候：晴空
                你：船长，体力 4（体型 5），清醒
                你手里：救生圈、血饵；你面前：没有
                你心里：爱 陪酒女；恨 大副
                这张航海牌把 陪酒女（体力 1，清醒，面前没有救生圈）和 大副（体力 6）送下了水""", deadline));
        return out;
    }

    private static List<ChoiceRequest> contestBatch(long deadlineMs) {
        Instant deadline = Instant.now().plusMillis(deadlineMs);
        String[][] seats = {{"医生", "船长", "陪酒女"}, {"水手", "大副", "小孩"}, {"珠宝商", "船长", "大副"},
                {"小孩", "陪酒女", "医生"}, {"陪酒女", "小孩", "水手"}};
        List<ChoiceRequest> out = new ArrayList<>();
        for (String[] s : seats) {
            out.add(new ChoiceRequest(s[0], DecisionKind.CONTEST_JOIN, List.of("不加入", "加入发起方（船长）", "加入被指方（大副）"),
                    "第 4 天 · 天候：晴空\n你：" + s[0] + "，清醒\n你心里：爱 " + s[1] + "；恨 " + s[2]
                            + "\n船长要和大副换座位，大副不同意，打起来了\n现在：发起方 船长（体型 5）；被指方 大副（体型 6）；平手算被指方赢",
                    deadline));
        }
        return out;
    }
}
