package io.github.heavyseasmc.engine.weather;

/** 天候牌的十种封闭效果。字符串 id 是数据格式，枚举名是引擎内部协议。 */
public enum WeatherEffect {
    FIGHTERS_OVERBOARD("fighters_overboard"),
    DOUBLE_WATER("double_water"),
    SKIP_NAVIGATION("skip_navigation"),
    ALL_THIRST("all_thirst"),
    RESHUFFLE_DISCARD("reshuffle_discard"),
    IGNORE_GULLS("ignore_gulls"),
    ROWERS_OVERBOARD("rowers_overboard"),
    EXTRA_NAVIGATION("extra_navigation"),
    IGNORE_THIRST("ignore_thirst"),
    EXTRA_PROVISION("extra_provision");

    private final String id;

    WeatherEffect(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static WeatherEffect fromId(String id) {
        for (WeatherEffect effect : values()) {
            if (effect.id.equals(id)) {
                return effect;
            }
        }
        throw new IllegalArgumentException("未知天候效果: " + id);
    }
}
