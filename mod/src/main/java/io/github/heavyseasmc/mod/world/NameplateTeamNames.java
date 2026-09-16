package io.github.heavyseasmc.mod.world;

import java.util.Objects;

/** 计分板队伍的短名字与持久所有权标记；标记让启服清理不会误删别人的队伍。 */
final class NameplateTeamNames {

    private static final String PREFIX = "hs_";
    private static final String OWNER = "heavyseas:nameplate:";

    private NameplateTeamNames() {
    }

    static String teamName(String characterId) {
        return PREFIX + Objects.requireNonNull(characterId, "characterId");
    }

    static String marker(String teamName) {
        return OWNER + Objects.requireNonNull(teamName, "teamName");
    }

    static boolean owns(String teamName, String displayName) {
        return teamName != null && teamName.startsWith(PREFIX) && marker(teamName).equals(displayName);
    }

    /** 未入队或已经在目标队时才能挂名牌；绝不把玩家从外部队伍挪走。 */
    static boolean mayJoin(String desiredTeam, String currentTeam) {
        return currentTeam == null || Objects.requireNonNull(desiredTeam, "desiredTeam").equals(currentTeam);
    }
}
