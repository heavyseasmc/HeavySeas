package io.github.heavyseasmc.mod.llm;

/**
 * 替身要做的是哪一种决定（ADR-0096 §1.1 的八处决策点，拆细到各自要读的规则不同为止）。
 *
 * <p>它只管两件事：提示词里「这一次要决定什么」那一句，以及规则摘录取规则书的哪几节（{@link RulebookExcerpts#SECTIONS}）。
 * 合法选项由调用方算好、编好号再给进来 —— 这里不知道、也不判断哪些选项合法。
 */
public enum DecisionKind {

    /** 补给箱传到手上：留一张。 */
    PROVISION("补给箱传到你手上：从里面留下一张。",
            "The supply crate has reached you: keep one card from it."),
    /** 行动阶段轮到这一座：五件事挑一件。 */
    ACTION("轮到你行动：挑一件事。",
            "It is your turn to act: choose one thing to do."),
    /** 占掉行动打一张物资（医疗箱给谁 · 撑阳伞 · 分食 · 信号枪）。 */
    USE_PROVISION("你要用一张物资：挑用哪一张、怎么用。",
            "You are using a provision: choose which one and how."),
    /** 划船：摸来的航海牌里挑一张扣进划船堆。 */
    ROW("你在划船：挑一张航海牌扣进划船堆，其余的塞回牌堆底。",
            "You are rowing: choose one navigation card to put into the rowing pile; the rest go to the bottom of the deck."),
    /** 换座位或抢：指哪一个人。 */
    TARGET("挑你要找的那个人。",
            "Choose the person you are going after."),
    /** 被指的人表态：同不同意（不同意就打）。 */
    CONTEST_ANSWER("有人指了你：同不同意？不同意就打起来。",
            "Someone has picked you: do you agree? If you refuse, there is a fight."),
    /** 站队：加不加入、加哪边。 */
    CONTEST_JOIN("船上要打起来了：站不站队、站哪一边？",
            "A fight is about to start: do you take a side, and which one?"),
    /** 押武器：押不押、押哪一张。 */
    CONTEST_WEAPON("你在场上：押不押武器、押哪一张？",
            "You are in the fight: do you commit a weapon, and which one?"),
    /** 抢成了：挑一张拿走（面前指名一张，或从手里随机摸一张）。 */
    STEAL_PICK("你抢成了：挑一张拿走。",
            "Your steal succeeded: choose what to take."),
    /** 舵手：划船堆里挑一张执行。 */
    HELM("你是舵手：挑一张航海牌执行。",
            "You are the helmsman: choose the navigation card to carry out."),
    /** 落海那一刻：扔救生圈 · 打血饵 · 什么也不做。 */
    OVERBOARD("有人落海了：要不要出牌？",
            "Someone has gone overboard: do you play a card?"),
    /** 自己口渴：喝几张水。 */
    THIRST("你口渴了：喝几张水？",
            "You are thirsty: how many waters do you drink?"),
    /** 别人在结算口渴：递不递水给他。 */
    GIVE_WATER("有人在结算口渴：要不要递水给他？",
            "Someone is settling thirst: do you hand them water?"),
    /** 不占行动的那几件：亮出 · 喝朗姆酒 · 送牌。 */
    FREE_ACTION("不占行动的事：要不要亮出、喝酒或送牌？",
            "Things that do not use your action: lay a card in front, drink rum, or give a card?"),
    /** 别的：只给共通的规则。 */
    OTHER("做一个决定。",
            "Make a decision.");

    private final String zh;
    private final String en;

    DecisionKind(String zh, String en) {
        this.zh = zh;
        this.en = en;
    }

    /** 提示词里「这一次要决定什么」那一句。 */
    public String question(String language) {
        return language.equals("en_us") ? en : zh;
    }
}
