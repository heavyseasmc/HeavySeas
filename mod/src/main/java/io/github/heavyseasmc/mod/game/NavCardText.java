package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
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

    /** 一行说完：「海鸥 +1 · 落海：船长 · 口渴：水手、划过船的人」。聊天播报与界面的说明行用它。 */
    public static Text describe(NavCardView card, List<String> seatOrder) {
        MutableText out = Text.empty();
        Text gull = gull(card);
        if (gull != null) {
            out.append(gull).append(SECTION_SEP);
        }
        out.append(Text.translatable("heavyseas.nav.overboard_line", names(card.overboard(), seatOrder)));
        out.append(SECTION_SEP);
        out.append(Text.translatable("heavyseas.nav.thirst_line", thirst(card, seatOrder)));
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
        List<Text> parts = new ArrayList<>();
        boolean icons = card.thirstRowers() || card.thirstFighters();
        if (card.thirst().mode() != NavCardView.Mode.NOBODY || !icons) {
            parts.add(names(card.thirst(), seatOrder));
        }
        if (card.thirstRowers()) {
            parts.add(Text.translatable("heavyseas.nav.rowers"));
        }
        if (card.thirstFighters()) {
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
