package io.github.heavyseasmc.mod.config;

/**
 * 设置菜单左栏的分组签（ADR-0099 版式 A）。每一项设置在 {@link SettingDef#category()} 里说自己归哪一组；
 * 签的先后就是这里的先后，同一组里的先后就是设置表里的先后 —— 加一项设置不用碰界面。
 *
 * <p>一组里一项都没有就不画那张签。
 * 签上的名字是 {@code heavyseas.settings.category.<id>}（界面里按 switch 取，构建期的 checkLangKeys 看得见）。
 */
public enum SettingsCategory {

    /** 本机：只属于这台客户端的偏好（主题 · Minecraft 状态栏 · 图例），改了当场生效、当场存。 */
    LOCAL("local", false),
    /** 对局时限：各扇等人窗口（{@code windows.*}）。 */
    WINDOWS("windows", true),
    /** 节奏：有真人在座时几段停顿（{@code pacing.*}）。 */
    PACING("pacing", true),
    /** 替身：起服默认值（自动推进 · 怎么拿主意）· 补位 · 演示局不限时。 */
    STAND_INS("stand_ins", true),
    /** 动脑替身：引擎席位策略的每一项旋钮（{@code smart.*}，开局时取一份）。 */
    SMART("smart", true),
    /** 大模型替身：接入层的每一项（{@code llm.*}，存了就生效；密钥只写不读）。 */
    LLM("llm", true);

    private final String id;
    private final boolean server;

    SettingsCategory(String id, boolean server) {
        this.id = id;
        this.server = server;
    }

    public String id() {
        return id;
    }

    /** 这一组是不是服务端那一份（要进存档、要权限、改了攒着按 S 存）。 */
    public boolean server() {
        return server;
    }
}
