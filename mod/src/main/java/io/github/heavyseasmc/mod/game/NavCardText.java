package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.weather.WeatherEffect;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一张航海牌上印着什么，写成人读得懂的话。服务端播报与客户端界面共用这一份。
 *
 * <h2>为什么要有它</h2>
 * 航海牌没有卡面贴图（{@code art/cards} 里只有物资、角色与天候），牌 id 形如 {@code nav_07}，不是给人读的。
 * 结算后公开的那一张（决策 ⑭）、划船者与舵手手里看的那几张，都只能靠这几行字读懂 ——
 * 两处各拼一遍的话，迟早一处写「除大副外」、一处写「大副以外」，而且谁都不报错。
 *
 * <h2>lang 键全是字面量</h2>
 * 条件名来自数据（{@code used_rum}），但键不拼：认识的条件写进 switch，构建期的 checkLangKeys 才扫得到。
 * 不认识的条件原样露出条件名 —— 露出来是看得见的错，不是安静的错。
 */
public final class NavCardText {

    private static final Text SECTION_SEP = Text.literal(" · ");

    private NavCardText() {
    }

    /** 一行说完：「海鸥 +1 · 落海：船长 · 口渴：水手、划过船的人」。不看天候（没有天候、或天候不改航海牌时用）。 */
    public static Text describe(NavCardView card, List<String> seatOrder) {
        return describe(card, seatOrder, "");
    }

    /**
     * 同上，按今天的天候改写成<b>这张牌今天实际会怎样</b>（ADR-0095 A5）。
     *
     * <h2>为什么要它</h2>
     * 原先只照牌面写：暴风雨那天带桨的牌会把划过船的人送下水，划船与舵手两面上却写「口渴：划过船的人」；
     * 浓雾天照样写「海鸥 +1」。舵手与划船的人照着错的预告做决定 —— 这是<b>信息错</b>，不只是信息少。
     * 改写的每一条与引擎 {@code Session#beginNavigation} · {@code prepareThirst} · {@code effectiveThirst} 一一对应：
     * <ul>
     *   <li>浓雾（{@code ignore_gulls}）：海鸥那一栏不写；</li>
     *   <li>暴风雨（{@code rowers_overboard}）/ 巨浪（{@code fighters_overboard}）：牌上有桨 / 打架图示时，那些人从「口渴」挪到「落海」；</li>
     *   <li>无渴（{@code ignore_thirst}）：口渴那一栏写「无人」；全渴（{@code all_thirst}）：口渴那一栏先写「全员」。</li>
     * </ul>
     *
     * @param weatherEffect 今天天候的效果 id（引擎 {@code WeatherEffect#id}）；空串 = 没有天候
     */
    public static Text describe(NavCardView card, List<String> seatOrder, String weatherEffect) {
        String effect = weatherEffect == null ? "" : weatherEffect;
        boolean noGulls = WeatherEffect.IGNORE_GULLS.id().equals(effect);
        boolean rowersOverboard = WeatherEffect.ROWERS_OVERBOARD.id().equals(effect) && card.thirstRowers();
        boolean fightersOverboard = WeatherEffect.FIGHTERS_OVERBOARD.id().equals(effect) && card.thirstFighters();
        boolean noThirst = WeatherEffect.IGNORE_THIRST.id().equals(effect);
        boolean allThirst = WeatherEffect.ALL_THIRST.id().equals(effect);

        MutableText out = Text.empty();
        Text gull = noGulls ? null : gull(card);
        if (gull != null) {
            out.append(gull).append(SECTION_SEP);
        }
        List<Text> overboard = new ArrayList<>();
        if (card.overboard().mode() != NavCardView.Mode.NOBODY || !(rowersOverboard || fightersOverboard)) {
            overboard.add(names(card.overboard(), seatOrder));
        }
        if (rowersOverboard) {
            overboard.add(Text.translatable("heavyseas.nav.rowers"));
        }
        if (fightersOverboard) {
            overboard.add(Text.translatable("heavyseas.nav.fighters"));
        }
        out.append(Text.translatable("heavyseas.nav.overboard_line", join(overboard)));
        out.append(SECTION_SEP);
        Text thirst = noThirst ? Text.translatable("heavyseas.nav.nobody")
                : thirst(card, seatOrder, !rowersOverboard, !fightersOverboard, allThirst);
        out.append(Text.translatable("heavyseas.nav.thirst_line", thirst));
        return out;
    }

    /** 海鸥那一栏；这张牌不动海鸥时为 {@code null}。 */
    public static Text gull(NavCardView card) {
        return switch (card.gull()) {
            case 1 -> Text.translatable("heavyseas.nav.gull_plus");
            case -1 -> Text.translatable("heavyseas.nav.gull_minus");
            default -> null;
        };
    }

    /** 一栏点名。名单按座位从船头到船尾排 —— 与座位轨同一个次序，找人不用来回扫。 */
    public static Text names(NavCardView.Names names, List<String> seatOrder) {
        return switch (names.mode()) {
            case EVERYONE -> Text.translatable("heavyseas.nav.everyone");
            case NOBODY -> Text.translatable("heavyseas.nav.nobody");
            case ONLY -> join(characterNames(names.characters(), seatOrder));
            case EXCEPT -> Text.translatable("heavyseas.nav.except",
                    join(characterNames(names.characters(), seatOrder)));
            case CONDITIONAL -> condition(names.condition());
        };
    }

    /**
     * 口渴那一栏：牌面点名，外加划船与战斗两个图示 —— 它们也是口渴的来源（规则基线 §9.5）。
     * 牌面一个人都没点、只有图示时，只列图示，不写「无人」。
     */
    public static Text thirst(NavCardView card, List<String> seatOrder) {
        return thirst(card, seatOrder, true, true, false);
    }

    /**
     * 口渴那一栏的通用版：天候把划船 / 打架图示挪去落海时，这一栏就不再列它们；全渴天先写「全员」。
     */
    private static Text thirst(NavCardView card, List<String> seatOrder, boolean rowers, boolean fighters,
                               boolean everyone) {
        List<Text> parts = new ArrayList<>();
        if (everyone) {
            parts.add(Text.translatable("heavyseas.nav.everyone"));
        }
        boolean showRowers = rowers && card.thirstRowers();
        boolean showFighters = fighters && card.thirstFighters();
        boolean icons = showRowers || showFighters;
        if (card.thirst().mode() != NavCardView.Mode.NOBODY || (!icons && !everyone)) {
            parts.add(names(card.thirst(), seatOrder));
        }
        if (showRowers) {
            parts.add(Text.translatable("heavyseas.nav.rowers"));
        }
        if (showFighters) {
            parts.add(Text.translatable("heavyseas.nav.fighters"));
        }
        return join(parts);
    }

    private static Text condition(String condition) {
        return switch (condition) {
            case "used_rum" -> Text.translatable("heavyseas.nav.condition.used_rum");
            default -> Text.literal(condition);
        };
    }

    private static List<Text> characterNames(List<String> ids, List<String> seatOrder) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingInt((String id) -> {
            int seat = seatOrder.indexOf(id);
            return seat < 0 ? Integer.MAX_VALUE : seat;
        }).thenComparing(Comparator.naturalOrder()));
        return sorted.stream().map(id -> GameFlow.characterName(CharacterId.of(id))).toList();
    }

    private static Text join(List<Text> parts) {
        MutableText out = Text.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Text.translatable("heavyseas.nav.name_sep"));
            }
            out.append(parts.get(i));
        }
        return out;
    }
}
