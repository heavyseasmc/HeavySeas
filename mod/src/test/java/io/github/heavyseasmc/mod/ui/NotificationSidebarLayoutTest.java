package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationSidebarLayoutTest {

    @Test
    void hiddenSidebarLeavesTheWholeScreenToTheGameScreen() {
        NotificationSidebarLayout layout = NotificationSidebarLayout.of(427, false);

        assertEquals(0, layout.sidebarWidth());
        assertEquals(427, layout.contentWidth());
    }

    @Test
    void visibleSidebarAlwaysHasASeparateContentRegion() {
        for (int screenWidth : new int[]{320, 427, 640, 854, 1280, 1920}) {
            NotificationSidebarLayout layout = NotificationSidebarLayout.of(screenWidth, true);

            assertTrue(layout.contentWidth() > 0, "主内容宽度必须为正: " + screenWidth);
            assertEquals(NotificationSidebarLayout.MARGIN,
                    layout.sidebarX() - layout.contentWidth(), "主内容与侧栏之间要有留白");
            assertEquals(screenWidth - NotificationSidebarLayout.MARGIN,
                    layout.sidebarX() + layout.sidebarWidth(), "侧栏右边也要有留白");
        }
    }

    @Test
    void sidebarKeepsItsReadableBoundsAtNormalGuiWidths() {
        assertEquals(140, NotificationSidebarLayout.of(320, true).sidebarWidth());
        assertEquals(142, NotificationSidebarLayout.of(427, true).sidebarWidth());
        assertEquals(240, NotificationSidebarLayout.of(854, true).sidebarWidth());
        assertEquals(240, NotificationSidebarLayout.of(1920, true).sidebarWidth());
    }

    @Test
    void rejectsNegativeScreenWidths() {
        assertThrows(IllegalArgumentException.class, () -> NotificationSidebarLayout.of(-1, true));
    }
}
