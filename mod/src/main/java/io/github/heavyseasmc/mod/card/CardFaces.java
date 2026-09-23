package io.github.heavyseasmc.mod.card;

import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
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

    /** 角标与口渴排的图标名 —— 与 {@code textures/gui/cards/icon/<名>.png} 一一对应，管线按这张表烘。 */
    public static final String STRENGTH = "strength";
    public static final String SURVIVAL = "life_ring";
    public static final String POINTS = "treasure";
    public static final String GULL_PLUS = "gull_plus";
    public static final String GULL_MINUS = "gull_minus";
    public static final String ROWERS = "oar";
    public static final String FIGHTERS = "fight";
    public static final String EVERYONE = "everyone";

    private CardFaces() {
    }

    // ---------------------------------------------------------------- 物资

    /**
     * 物资的角标：打架时的体型加值（武器 · 朗姆酒）或上岸的分数。珠宝按套计分，一个数说不清，不印。
     */
    public static List<CardFace.Badge> provisionBadges(Provision p) {
        int power = p.weaponPower();
        if (power > 0) {
            return List.of(new CardFace.Badge(STRENGTH, Integer.toString(power)));
        }
        if (p.effect() instanceof ProvisionEffect.BuffSize buff) {
            return List.of(new CardFace.Badge(STRENGTH, Integer.toString(buff.amount())));
        }
        if (p.effect() instanceof ProvisionEffect.ScoreFlat score) {
            return List.of(new CardFace.Badge(POINTS, Integer.toString(score.points())));
        }
        return List.of();
    }

    public static CardFace provision(String id, List<CardFace.Badge> badges) {
        return new CardFace("provision", id, CardFace.Title.of("heavyseas.provision." + id), badges, List.of());
    }

    // ---------------------------------------------------------------- 角色

    /** 角色的两枚角标：左体力、右生存（L1 / L2 不画图标，靠位置认 —— ADR-0039 §7.3）。 */
    public static List<CardFace.Badge> characterBadges(int size, int survival) {
        return List.of(new CardFace.Badge(STRENGTH, Integer.toString(size)),
                new CardFace.Badge(SURVIVAL, Integer.toString(survival)));
    }

    /** 牌名是<b>身份</b>（船长），不是人名 —— 人名是冷信息。 */
    public static CardFace character(String id, List<CardFace.Badge> badges) {
        return new CardFace("character", id, CardFace.Title.of("heavyseas.character." + id), badges, List.of());
    }

    // ---------------------------------------------------------------- 天候

    public static CardFace weather(String id) {
        return new CardFace("weather", id, CardFace.Title.of("heavyseas.weather." + id), List.of(), List.of());
    }

    // ---------------------------------------------------------------- 航海

    public static CardFace nav(NavCardView card) {
        List<CardFace.Badge> badges = switch (card.gull()) {
            case 1 -> List.of(new CardFace.Badge(GULL_PLUS, "+1"));
            case -1 -> List.of(new CardFace.Badge(GULL_MINUS, "-1"));
            default -> List.of();
        };
        return new CardFace("nav", card.id(), navTitle(card.overboard()), badges, roll(card));
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
