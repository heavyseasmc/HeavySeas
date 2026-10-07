package io.github.heavyseasmc.mod.llm;

import java.time.Instant;
import java.util.List;

/**
 * {@code /seasllm probe} 发的那一次假决定：固定的局面、五个行动选项。只用来量「通不通、多快、花多少 token」，
 * 局面是编的（规则书里的名字与说法），不来自任何一局。
 */
public final class ProbeDecision {

    private ProbeDecision() {
    }

    private static final String SITUATION_ZH = """
            第 3 天 · 天候：炎热（每个还活着的人今天多一个口渴来源）
            海鸥：2 只（凑满 4 只就靠岸）
            你：珠宝商，体力 2（体型 3），清醒；从船头数第 2 个座位
            你手里：水、珠宝、医疗箱；你面前：船桨
            你心里：爱 船长；恨 大副
            艇上从船头到船尾：船长（体力 4，面前亮着指南针）· 你 · 大副（体力 5，手里 3 张）· 小孩（体力 0，昏迷）· 陪酒女（体力 2，手里 1 张）
            今天还没行动的：你、大副、陪酒女
            发生过：第 2 天大副抢走了你一张水""";

    private static final String SITUATION_EN = """
            Day 3 · Weather: Scorching Heat (everyone still alive has one extra source of thirst today)
            Gulls: 2 (the boat reaches land at 4)
            You: Jeweller, health 2 (Size 3), conscious; second seat from the bow
            In your hand: Water, Jewellery, Medical Kit; in front of you: Oar
            In your heart: you love the Captain; you hate the First Mate
            In the boat, bow to stern: Captain (health 4, Compass in front) · you · First Mate (health 5, 3 cards in hand) \
            · Child (health 0, unconscious) · Hostess (health 2, 1 card in hand)
            Still to act today: you, First Mate, Hostess
            Earlier: on day 2 the First Mate stole a Water from you""";

    public static ChoiceRequest request(String language, Instant deadline) {
        boolean en = language.equals("en_us");
        List<String> options = en
                ? List.of("Row", "Swap seats", "Steal", "Use a provision (Medical Kit)", "Do nothing")
                : List.of("划船", "换座位", "抢", "用物资（医疗箱）", "什么也不做");
        return new ChoiceRequest(en ? "Jeweller" : "珠宝商", DecisionKind.ACTION, options,
                en ? SITUATION_EN : SITUATION_ZH, deadline);
    }
}
