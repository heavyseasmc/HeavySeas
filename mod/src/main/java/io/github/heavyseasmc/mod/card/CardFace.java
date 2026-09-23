package io.github.heavyseasmc.mod.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 拼一张牌要的全部<b>内容</b>（ADR-0039 §7.1 的「内容」那一层）：牌名 · 角标 · 口渴排。
 * 边框与插画是贴图，按 {@code kind} / {@code id} 去取，不在这里。
 *
 * <p>❗与语言无关：牌名只存 lang 键与参数，排字时才翻。于是闸门能在不起客户端的情况下
 * 给每张牌算「这一档看得见什么」（{@link #visible}），而同一个对象在两种语言下各拼一次。
 *
 * @param kind   牌型（{@code provision} · {@code character} · {@code weather} · {@code nav}），版面描述里的键
 * @param id     牌 id；插画贴图 {@code cards/art/<kind>/<id>.png}
 * @param title  牌名
 * @param badges 角标，从左到右
 * @param roll   口渴排（只有航海卡有），从左到右
 */
public record CardFace(String kind, String id, Title title, List<Badge> badges, List<Chip> roll) {

    public CardFace {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        badges = List.copyOf(badges);
        roll = List.copyOf(roll);
    }

    /**
     * 牌名：一个 lang 键，外加要填进 {@code %s} 的角色（按 {@code heavyseas.nav.name_sep} 连起来）。
     * 物资 · 角色 · 天候的 {@code names} 为空。
     */
    public record Title(String key, List<String> names) {

        public Title {
            Objects.requireNonNull(key, "key");
            names = List.copyOf(names);
        }

        public static Title of(String key) {
            return new Title(key, List.of());
        }
    }

    /**
     * 一枚角标：图标（{@code cards/icon/<icon>.png}）+ 印在上面的数字。
     * 数字用字符串：海鸥要印成「+1」，符号是它的一部分。
     */
    public record Badge(String icon, String text) {

        public Badge {
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(text, "text");
        }
    }

    /** 口渴排里一枚：一个人（{@code WHO}）· 除他之外（{@code NOT}）· 一个图示（{@code ICON}）。 */
    public record Chip(Type type, String ref) {

        public enum Type { WHO, NOT, ICON }

        public Chip {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(ref, "ref");
        }
    }

    /**
     * 这张牌在 {@code tier} 这一档上<b>看得见的内容</b>（插画另算，见闸门）。
     * 「分得开」由它决定：拿掉的槽位不进来，所以 L2 上没有牌名。
     */
    public List<String> visible(CardLayout layout, String tier) {
        CardLayout.Shape shape = layout.shapeOf(kind);
        List<String> out = new ArrayList<>();
        if (shape.title().tiers().contains(tier)) {
            out.add("title=" + title.key() + title.names());
        }
        if (shape.badges().tiers().contains(tier)) {
            for (Badge b : badges) {
                // 图标只在 icon_tiers 里画；别的档靠位置认，所以位置（下标）要算进来
                out.add("badge=" + (shape.badges().iconTiers().contains(tier) ? b.icon() : "") + ":" + b.text());
            }
        }
        CardLayout.Roll r = layout.kind(kind).roll();
        if (r != null && r.tiers().contains(tier)) {
            for (Chip c : roll) {
                out.add("chip=" + c.type() + ":" + c.ref());
            }
        }
        return out;
    }
}
