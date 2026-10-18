package io.github.heavyseasmc.mod.client;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.text.TranslatableTextContent;

import java.util.Optional;

/**
 * 航海日志里一条播报的每个字归哪一类：人名 · 物资牌名 · 天候名 · 服务端标了色的 · 普通字。
 *
 * <p>用户 2026-10-07：「日志看起来很费劲，行动或者牌的字应该有主题色」。认法是<b>参数自己的 lang 键前缀</b>
 * （{@code heavyseas.character.*} · {@code heavyseas.provision.*} · {@code heavyseas.weather.*}）——
 * 服务端发的本来就是可翻译文本、名字是嵌在里面的一个参数，所以服务端一行不用改，已经在投影里的旧播报也照样着色。
 *
 * <h2>怎么认出参数</h2>
 * 一个字一个字地走一遍文本时（{@link Text#visit}）参数与格式串里的字长得一样。所以先复制一份：把认得出的参数套上一个
 * {@link Style#withInsertion 插入串}记号，再走 —— 记号随样式一路继承到那个参数的每一个字上。
 * 插入串只在聊天栏里 Shift+点击才有用，这一份复制只在这里读、从不显示，不会漏到别处。
 *
 * <h2>动词</h2>
 * 播报里「发生了什么」的那几个字（拿走了 · 加入了 · 划了船……）同一种墨色加粗（用户 2026-10-13 选样张 B）。
 * 哪几个字是动词写在 lang 的格式串里：{@code §l} 起、{@code §r} 止（Minecraft 自己的格式记号 ——
 * 聊天栏里照样显示成粗体，别处经 {@link GuiText} 画时剥掉）。这里认出记号、剥掉，记下哪几个字是动词；
 * Java 里不按任何文案去认。哪些键要有记号由 {@code LogVerbsTest} 核对。
 */
final class LogInk {

    /** 一个字归哪一类。{@link #STYLED} 是服务端整条标了色（战斗结算的红）。 */
    enum Kind { PLAIN, NAME, CARD, WEATHER, STYLED }

    /**
     * 排好的一条：字（与 {@code note.getString()} 去掉格式记号之后一个字都不差）· 每个字的类别 ·
     * 服务端标的颜色（没标为 -1）· 是不是动词（加粗）。
     */
    record Runs(String text, Kind[] kinds, int[] styledRgb, boolean[] verbs) {
    }

    /** Minecraft 的格式记号起头那个字；后面跟一个字说是哪种格式。 */
    private static final char SECTION = '§';
    private static final char BOLD_ON = 'l';
    private static final char RESET = 'r';

    private static final String MARK = "heavyseas:log/";
    private static final String CHARACTER = "heavyseas.character.";
    private static final String PROVISION = "heavyseas.provision.";
    private static final String WEATHER = "heavyseas.weather.";
    private static final String WEATHER_EFFECT = "heavyseas.weather.effect.";

    private LogInk() {
    }

    static Runs runs(Text note) {
        StringBuilder text = new StringBuilder();
        java.util.List<Kind> kinds = new java.util.ArrayList<>();
        java.util.List<Integer> rgb = new java.util.ArrayList<>();
        java.util.List<Boolean> verbs = new java.util.ArrayList<>();
        // 0 = 普通字 · 1 = 刚读到记号起头那个字、下一个字说是哪种格式；动词与否跨参数一直带着（记号不会落在参数里）
        boolean[] state = {false, false};
        marked(note).visit((style, piece) -> {
            Kind kind = kindOf(style);
            TextColor color = style.getColor();
            if (kind == Kind.PLAIN && color != null) {
                kind = Kind.STYLED;
            }
            for (int i = 0; i < piece.length(); i++) {
                char ch = piece.charAt(i);
                if (state[1]) {
                    state[1] = false;
                    if (ch == BOLD_ON) {
                        state[0] = true;
                    } else if (ch == RESET) {
                        state[0] = false;
                    }
                    continue;                    // 其余格式记号不认，也不上屏
                }
                if (ch == SECTION) {
                    state[1] = true;
                    continue;
                }
                text.append(ch);
                kinds.add(kind);
                rgb.add(color == null ? -1 : color.getRgb());
                verbs.add(state[0]);
            }
            return Optional.empty();
        }, Style.EMPTY);
        int[] colors = new int[rgb.size()];
        boolean[] verb = new boolean[verbs.size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = rgb.get(i);
            verb[i] = verbs.get(i);
        }
        return new Runs(text.toString(), kinds.toArray(Kind[]::new), colors, verb);
    }

    /**
     * 哪几个字缝不许断：人名、牌名、天候名是一个整体（2026-10-13 第二轮验收 50：「船/长」被拆到两行）。
     * {@code glue[i]} 为真 = 第 {@code i} 个字与前一个字同属一个名字。同一类的两个名字紧挨着（中间没有别的字）不会出现 ——
     * 格式串里名字之间总隔着字或标点。
     */
    static boolean[] glue(Kind[] kinds) {
        boolean[] out = new boolean[kinds.length];
        for (int i = 1; i < kinds.length; i++) {
            Kind k = kinds[i];
            out[i] = k == kinds[i - 1] && (k == Kind.NAME || k == Kind.CARD || k == Kind.WEATHER);
        }
        return out;
    }

    private static Kind kindOf(Style style) {
        String mark = style.getInsertion();
        if (mark == null || !mark.startsWith(MARK)) {
            return Kind.PLAIN;
        }
        return Kind.valueOf(mark.substring(MARK.length()));
    }

    /** 认得出的参数套上记号；其余原样（参数里再嵌参数的，一层层往下认）。 */
    static Text marked(Text text) {
        if (!(text.getContent() instanceof TranslatableTextContent content)) {
            if (text.getSiblings().isEmpty()) {
                return text;
            }
            MutableText out = MutableText.of(text.getContent()).setStyle(text.getStyle());
            text.getSiblings().forEach(sibling -> out.append(marked(sibling)));
            return out;
        }
        Kind kind = kindOfKey(content.getKey());
        if (kind != null) {
            return text.copy().setStyle(text.getStyle().withInsertion(MARK + kind.name()));
        }
        Object[] args = content.getArgs().clone();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Text arg) {
                args[i] = marked(arg);
            }
        }
        MutableText out = content.getFallback() == null
                ? Text.translatable(content.getKey(), args)
                : Text.translatableWithFallback(content.getKey(), content.getFallback(), args);
        out.setStyle(text.getStyle());
        text.getSiblings().forEach(sibling -> out.append(marked(sibling)));
        return out;
    }

    private static Kind kindOfKey(String key) {
        if (key.startsWith(CHARACTER)) {
            return Kind.NAME;
        }
        if (key.startsWith(PROVISION)) {
            return Kind.CARD;
        }
        if (key.startsWith(WEATHER) && !key.startsWith(WEATHER_EFFECT)) {
            return Kind.WEATHER;
        }
        return null;
    }
}
