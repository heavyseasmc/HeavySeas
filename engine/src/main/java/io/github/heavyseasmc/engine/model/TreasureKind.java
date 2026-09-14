package io.github.heavyseasmc.engine.model;

/** 三类财宝。各自对应一个角色的加倍技能。 */
public enum TreasureKind {
    CASH,
    JEWELRY,
    FINE_ART;

    public static TreasureKind fromId(String id) {
        return switch (id) {
            case "cash" -> CASH;
            case "jewelry" -> JEWELRY;
            case "fine_art" -> FINE_ART;
            default -> throw new IllegalArgumentException("未知财宝类别: " + id);
        };
    }
}
