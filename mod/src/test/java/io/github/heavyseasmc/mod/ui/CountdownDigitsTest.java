package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 倒计时整数秒 · 最后 5 秒朱砂（用户 2026-10-13）。 */
class CountdownDigitsTest {

    @Test
    @DisplayName("整数秒往上取整：剩 0.3 秒写 1，到点才写 0，不会出现小数")
    void wholeSecondsRoundUp() {
        assertEquals("12", Long.toString(CountdownDigits.seconds(11_001)));
        assertEquals(12, CountdownDigits.seconds(12_000));
        assertEquals(1, CountdownDigits.seconds(300));
        assertEquals(0, CountdownDigits.seconds(0));
        assertEquals(0, CountdownDigits.seconds(-50));
        assertEquals("20", CountdownDigits.widest(20_000));
    }

    @Test
    @DisplayName("朱砂与写出来的数同一帧变：写 6 时墨色，写 5 时朱砂，与总长无关")
    void urgentFollowsTheShownDigit() {
        assertFalse(CountdownDigits.urgent(5_001), "写出来是 6，不该已经是朱砂");
        assertTrue(CountdownDigits.urgent(5_000), "写出来是 5，该是朱砂");
        assertTrue(CountdownDigits.urgent(4_200));
        assertTrue(CountdownDigits.urgent(1));
        // 原先按总长 35 % 算：60 秒的窗口剩 4 秒时仍是墨色、4 秒的窗口剩 3.9 秒就红了。现在只看剩几秒。
        assertFalse(CountdownDigits.urgent(9_000));
        assertTrue(CountdownDigits.urgent(3_000));
    }

    @Test
    @DisplayName("客户端里没有哪一处还在用一位小数写倒计时（剩余毫秒 / 1000 再 %.1f）")
    void noDecimalCountdownLeftInClient() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client");
        java.util.regex.Pattern decimal = java.util.regex.Pattern.compile("\"%\\.1f\"\\s*,\\s*\\w+\\s*/\\s*1000");
        java.util.List<String> hits = new java.util.ArrayList<>();
        int scanned = 0;
        try (var files = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                scanned++;
                String src = java.nio.file.Files.readString(p);
                if (decimal.matcher(src).find()) {
                    hits.add(p.getFileName().toString());
                }
            }
        }
        assertTrue(scanned > 50, "没扫到客户端源码（扫了 " + scanned + " 个）：判据在空转");
        assertTrue(hits.isEmpty(), "还在写小数秒：" + hits);
    }
}
