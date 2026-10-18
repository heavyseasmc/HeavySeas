package io.github.heavyseasmc.mod.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameplateTeamNamesTest {

    @Test
    void onlyAnExactOwnershipMarkerMakesATeamOurs() {
        String team = NameplateTeamNames.teamName("captain");
        assertTrue(NameplateTeamNames.owns(team, NameplateTeamNames.marker(team)));
        assertFalse(NameplateTeamNames.owns(team, "Captain's admin team"));
        assertFalse(NameplateTeamNames.owns("other", NameplateTeamNames.marker(team)));
        assertFalse(NameplateTeamNames.owns("hs_other", NameplateTeamNames.marker(team)));
    }

    @Test
    void anExistingExternalTeamAlwaysWinsOverOurNameplate() {
        String team = NameplateTeamNames.teamName("captain");
        assertTrue(NameplateTeamNames.mayJoin(team, null));
        assertTrue(NameplateTeamNames.mayJoin(team, team));
        assertFalse(NameplateTeamNames.mayJoin(team, "server_admins"));
        assertFalse(NameplateTeamNames.mayJoin(team, "hs_external"));
    }
}
