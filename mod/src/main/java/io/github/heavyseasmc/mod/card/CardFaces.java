package io.github.heavyseasmc.mod.card;

import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.weather.WeatherEffect;
import io.github.heavyseasmc.mod.state.NavCardView;
import java.util.ArrayList;
import java.util.List;

/**
 * 从数据拼出 {@link CardFace}。服务端（目录包）· 客户端（画牌）· 闸门（分得开）三处都走这里 ——
 * 同一张牌在三处各拼一遍的话，迟早一处写「体力 3」一处写「分数 3」，而且谁都不报错。
 *
 * <p>「哪个规则数值印在角标上」是<b>呈现</b>的决定，不是规则：引擎不知道也不该知道角标。
 */
public final class CardFaces {

    /**
     * 角标与口渴排的图标名 —— 与 {@code textures/gui/cards/icon/<名>.png} 一一对应，管线按这张表烘。
     * 角标那四枚（体型秤砣 · 分数钱币 · 生存救生圈 · 海鸥）是木刻，与插画同一套画法（ADR-0090）。
     */
    public static final String SIZE = "size";
    public static final String SURVIVAL = "life_ring";
    public static final String POINTS = "treasure";
    public static final String GULL = "gull";
    public static final String ROWERS = "oar";
    public static final String FIGHTERS = "fight";
    public static final String EVERYONE = "everyone";
    // 天候的效果图示（ADR-0040）。海鸥借航海卡角标上那一枚。
    public static final String OVERBOARD = "overboard";
    public static final String THIRST = "thirst";
    public static final String WATER = "water";
    public static final String NAV_CARD = "nav_card";
    public static final String CRATE = "crate";
    public static final String RESHUFFLE = "reshuffle";
    public static final String ARROW = "arrow";

    private CardFaces() {
    }

    // ---------------------------------------------------------------- 物资

    /**
     * 物资的角标：打架时的体型加值（武器 · 朗姆酒）或上岸的分数。珠宝按套计分，一个数说不清，不印。
     *
     * <p>加值写成 {@code +3}：「加」是这个数的一部分 —— 角色卡上的体型 {@code 8} 是本身多大，武器的 {@code +3}
     * 是在那之上再加多少，两者印成同一个样子就分不开（用户 2026-10-05：「看起来不明确，不知道数字想表达什么」）。
     * 符号画成实心箭头（{@code CardPainter}），字串里仍是 {@code +}：闸门的指纹与语言无关。
     */
    public static List<CardFace.Badge> provisionBadges(Provision p) {
        int power = p.weaponPower();
        if (power > 0) {
            return List.of(new CardFace.Badge(SIZE, "+" + power));
        }
        if (p.effect() instanceof ProvisionEffect.BuffSize buff) {
            return List.of(new CardFace.Badge(SIZE, "+" + buff.amount()));
        }
        if (p.effect() instanceof ProvisionEffect.ScoreFlat score) {
            return List.of(new CardFace.Badge(POINTS, Integer.toString(score.points())));
        }
        return List.of();
    }

    public static CardFace provision(String id, List<CardFace.Badge> badges) {
        return new CardFace("provision", id, CardFace.Title.of("heavyseas.provision." + id), badges, List.of());
    }

    // ---------------------------------------------------------------- 行动 · 表态 · 站队（ADR-0050）

    /**
     * 选一件事的那几张牌：只有插画与牌名，没有角标（它们不是物资，没有体力或分数可印）。
     * 牌名的字色照原来按钮上的：抢夺 · 战斗朱砂（会伤人），什么也不做 · 旁观次墨（什么都不按就是它）。
     */
    public static CardFace action(ActionCard card) {
        CardFace.Title.Tone tone = switch (card) {
            case STEAL, FIGHT -> CardFace.Title.Tone.HARM;
            case PASS, WATCH -> CardFace.Title.Tone.QUIET;
            default -> CardFace.Title.Tone.INK;
        };
        return new CardFace("action", card.id(), CardFace.Title.of(card.titleKey(), tone), List.of(), List.of());
    }

    // ---------------------------------------------------------------- 角色

    /** 角色的两枚角标：左体型、右生存分。三档都画图标（ADR-0090 取代 ADR-0039 §7.3「L1 / L2 靠位置认」）。 */
    public static List<CardFace.Badge> characterBadges(int size, int survival) {
        return List.of(new CardFace.Badge(SIZE, Integer.toString(size)),
                new CardFace.Badge(SURVIVAL, Integer.toString(survival)));
    }

    /** 牌名是<b>身份</b>（船长），不是人名 —— 人名是冷信息。 */
    public static CardFace character(String id, List<CardFace.Badge> badges) {
        return new CardFace("character", id, CardFace.Title.of("heavyseas.character." + id), badges, List.of());
    }

    // ---------------------------------------------------------------- 天候

    /** 只有牌名与插画的天候牌（效果图示还不知道时 —— 目录没到就是这样，信息带空着，不编）。 */
    public static CardFace weather(String id) {
        return weather(id, List.of());
    }

    /** 天候牌：牌名 · 插画 · 信息带里的效果图示（{@link #weatherGlyph}）。 */
    public static CardFace weather(String id, List<CardFace.Chip> glyph) {
        return new CardFace("weather", id, CardFace.Title.of("heavyseas.weather." + id), List.of(), glyph);
    }

    /**
     * 一种天候效果画成信息带里的哪一排（ADR-0040 §5 的表）：「因 → 果」，元素只有四种。
     *
     * <p>这是<b>呈现</b>的决定，不是规则 —— 与 {@link #provisionBadges} 同一类，所以放在这里而不在引擎。
     * ❗{@code switch} 不带 {@code default}：加一种效果而忘了画它，编译就过不去。
     */
    public static List<CardFace.Chip> weatherGlyph(WeatherEffect effect) {
        return switch (effect) {
            case FIGHTERS_OVERBOARD -> List.of(icon(FIGHTERS), arrow(), icon(OVERBOARD));
            case ROWERS_OVERBOARD -> List.of(icon(ROWERS), arrow(), icon(OVERBOARD));
            // 两枚水而不是「×2」：乘号不一定在 GUI 字体的子集里，两枚水也不必认字
            case DOUBLE_WATER -> List.of(icon(THIRST), arrow(), icon(WATER), icon(WATER));
            case ALL_THIRST -> List.of(icon(EVERYONE), arrow(), icon(THIRST));
            case IGNORE_THIRST -> List.of(struck(THIRST));
            case IGNORE_GULLS -> List.of(struck(GULL));
            case SKIP_NAVIGATION -> List.of(struck(NAV_CARD));
            case EXTRA_NAVIGATION -> List.of(count("+1"), icon(NAV_CARD));
            case EXTRA_PROVISION -> List.of(count("+1"), icon(CRATE));
            case RESHUFFLE_DISCARD -> List.of(icon(RESHUFFLE));
        };
    }

    private static CardFace.Chip icon(String ref) {
        return new CardFace.Chip(CardFace.Chip.Type.ICON, ref);
    }

    private static CardFace.Chip struck(String ref) {
        return new CardFace.Chip(CardFace.Chip.Type.STRUCK, ref);
    }

    private static CardFace.Chip arrow() {
        return new CardFace.Chip(CardFace.Chip.Type.ARROW, "");
    }

    private static CardFace.Chip count(String text) {
        return new CardFace.Chip(CardFace.Chip.Type.COUNT, text);
    }

    // ---------------------------------------------------------------- 航海

    public static CardFace nav(NavCardView card) {
        return new CardFace("nav", card.id(), navTitle(card.overboard()), navBadges(card.gull()), roll(card));
    }

    /** 航海卡的海鸥角标：一只海鸥 + 带符号的数（符号画成上 / 下箭头，所以不必再有一枚划掉的海鸥）。 */
    static List<CardFace.Badge> navBadges(int gull) {
        return gull == 0 ? List.of() : List.of(new CardFace.Badge(GULL, (gull > 0 ? "+" : "-") + Math.abs(gull)));
    }

    /**
     * 这种牌角上<b>最宽</b>的那一组角标长什么样：牌名据此给角标让位（{@code CardNameFit}）。
     * 只看形状不看数据 —— 目录没到之前就要选档，而一位数里最宽的是 8。两位数的值（换了数据包）
     * 画的时候牌名照样让开（{@code CardPainter} 按实际宽度让），只是可能缩一级字。
     */
    public static List<CardFace.Badge> widestBadges(String kind) {
        return switch (kind) {
            case "provision" -> List.of(new CardFace.Badge(SIZE, "+8"));
            case "character" -> characterBadges(8, 8);
            case "nav" -> navBadges(-1);
            default -> List.of();
        };
    }

    /**
     * 牌名 = 落海的那一位（们）。lang 键全是字面量 —— 构建期的 checkLangKeys 才扫得到；
     * 不认识的条件露出一个缺键（屏幕上看得见的错），不是安静地空着。
     */
    static CardFace.Title navTitle(NavCardView.Names overboard) {
        return switch (overboard.mode()) {
            case ONLY -> new CardFace.Title("heavyseas.nav.title.only", overboard.characters());
            // 「除某某外落海」在两种语言里都放不进标题带（L1 字号下中文七个字、英文 All but … 都越界），
            // 而数据里没有一张牌这样落海。真出现时先定它的版式 —— 不许悄悄截断（ADR-0039 §7.4）。
            case EXCEPT -> throw new IllegalArgumentException(
                    "落海点名是「除…之外」，牌名还没有它的版式（放不进标题带）");
            case EVERYONE -> CardFace.Title.of("heavyseas.nav.title.everyone");
            case NOBODY -> CardFace.Title.of("heavyseas.nav.title.nobody");
            case CONDITIONAL -> switch (overboard.condition()) {
                case "used_rum" -> CardFace.Title.of("heavyseas.nav.title.used_rum");
                default -> CardFace.Title.of("heavyseas.nav.title.condition." + overboard.condition());
            };
        };
    }

    /**
     * 口渴排：牌面点名 + 划船与打架两个图示。「所有人」是<b>一枚</b>图示，不是八张脸 ——
     * 牌上原来印的就是「所有人」，它本来就是一个整体（ADR-0039 §7.4）。
     */
    static List<CardFace.Chip> roll(NavCardView card) {
        List<CardFace.Chip> out = new ArrayList<>();
        NavCardView.Names t = card.thirst();
        switch (t.mode()) {
            case ONLY -> t.characters().forEach(c -> out.add(new CardFace.Chip(CardFace.Chip.Type.WHO, c)));
            case EXCEPT -> t.characters().forEach(c -> out.add(new CardFace.Chip(CardFace.Chip.Type.NOT, c)));
            case EVERYONE -> out.add(new CardFace.Chip(CardFace.Chip.Type.ICON, EVERYONE));
            case NOBODY -> {
            }
            // 条件点名在口渴那一栏目前没有牌用到；真有一天出现，要先画一枚它的图示再来
            case CONDITIONAL -> throw new IllegalArgumentException(
                    card.id() + " 的口渴是条件点名（" + t.condition() + "），牌面还没有它的图示");
        }
        if (card.thirstRowers()) {
            out.add(new CardFace.Chip(CardFace.Chip.Type.ICON, ROWERS));
        }
        if (card.thirstFighters()) {
            out.add(new CardFace.Chip(CardFace.Chip.Type.ICON, FIGHTERS));
        }
        return out;
    }
}
