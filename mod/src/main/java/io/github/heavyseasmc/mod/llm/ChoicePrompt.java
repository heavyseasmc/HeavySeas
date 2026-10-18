package io.github.heavyseasmc.mod.llm;

import java.util.List;

/**
 * 一次决定的提示词：一条系统消息 + 一条用户消息，<b>不带任何历史</b>。
 *
 * <p>形状照 TLM 的「先决定」那一步（ADR-0096 §1.2）：不带人设、不带工具，只给一张编了号的单子，要求只回一个编号。
 * 输出要求在系统消息里说一遍，在用户消息<b>最后一句</b>再说一遍 —— 紧挨着生成位置的那句话分量最重（TLM 同一处的实测）。
 *
 * <p>规则只来自 {@link RulebookExcerpts}（我们自己的规则书）；局面由调用方给，这里原样放进去。
 */
public final class ChoicePrompt {

    private ChoicePrompt() {
    }

    public record Messages(String system, String user) {
    }

    private static final String SYSTEM_ZH = """
            你在一局多人桌游里替一个座位做决定。
            故事：1912 年 4 月 14 日夜，北辰号沉了，几个陌生人挤在一条救生艇上，水只够几天。
            每次你会收到三样东西：规则摘录、这一座此刻看得到的局面、一张编了号的选项单。
            替这一座挑对它最有利的一项：让它最后的总分尽量高（分怎么算，规则摘录里写着）。
            只回答一个选项编号，只写阿拉伯数字，不要别的任何字：不解释，不复述选项，不加标点。""";

    private static final String SYSTEM_EN = """
            You are making one decision for one seat in a multiplayer board game.
            The story: on the night of 14 April 1912 the Northern Star has gone down, and a few strangers are crowded \
            into a small boat with water for only a few days.
            Each time you receive three things: an excerpt of the rules, what this seat can see right now, and a numbered \
            list of options.
            Pick the option that is best for this seat: make its final total score as high as possible (the rules excerpt \
            explains the scoring).
            Answer with the option number only, in Arabic digits, and nothing else: no explanation, no restating the \
            option, no punctuation.""";

    public static Messages build(ChoiceRequest request, String excerpt, String language) {
        return build(request, excerpt, language, false);
    }

    /**
     * @param reminder 再问一遍时用：末尾再加一句输出要求。<b>不带上一次的回答</b> —— 仍旧不带历史（带了就回到 TLM 实测里弱档模型
     *                 不肯照单子答的那个条件）
     */
    public static Messages build(ChoiceRequest request, String excerpt, String language, boolean reminder) {
        boolean en = language.equals("en_us");
        List<String> options = request.options();
        StringBuilder user = new StringBuilder();
        user.append(en ? "[Rules excerpt]\n" : "【规则摘录】\n").append(excerpt.strip()).append("\n\n");
        user.append(en ? "[What this seat can see right now]\n" : "【这一座此刻看得到的局面】\n");
        user.append(en ? "You are: " : "你是：").append(request.seat().strip()).append('\n');
        if (!request.situation().isBlank()) {
            user.append(request.situation().strip()).append('\n');
        }
        user.append('\n').append(en ? "[The decision]\n" : "【这一次要决定的事】\n");
        user.append(request.kind().question(language)).append('\n');
        for (int i = 0; i < options.size(); i++) {
            user.append(i + 1).append(". ").append(options.get(i).strip()).append('\n');
        }
        user.append('\n').append(en
                ? "Reply with a single number: one option number from 1 to " + options.size() + "."
                : "只回一个数字：1 到 " + options.size() + " 之间的一个选项编号。");
        if (reminder) {
            user.append('\n').append(en
                    ? "(A previous reply to this could not be read. Reply with the number only, from 1 to " + options.size()
                    + ", and nothing else.)"
                    : "（先前那一次的回答没认出来。只写一个 1 到 " + options.size() + " 之间的数字，别的字一个也不要。）");
        }
        return new Messages(en ? SYSTEM_EN : SYSTEM_ZH, user.toString());
    }
}
