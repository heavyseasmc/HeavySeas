package io.github.heavyseasmc.mod.rulebook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 书柜里的书（ADR-0109 ③）：放的那一本在 jar 里、每一本都解析得了、只用书的写法（不从数据展开），钩子同讲台。
 * 每页放得下、页数在不在 8–16 页之间，按游戏字体量，在 {@code client/ShelfBookPagesTest}。
 */
public class ShelfBookTest {

    /** {@code books/} 底下的每一本（目录名 = id）。一本都找不到就失败 —— 「没在测」不是通过。 */
    public static List<String> allBooks() {
        URL dir = ShelfBook.class.getResource("/assets/heavyseas/books");
        assertNotNull(dir, "类路径上没有 assets/heavyseas/books —— 没在测，不是通过");
        try (Stream<Path> list = Files.list(Path.of(dir.toURI()))) {
            List<String> ids = new ArrayList<>(list.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList());
            assertFalse(ids.isEmpty(), "books/ 底下一本书都没有");
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("书柜里放的那一本在 jar 里，而且在 books/ 底下")
    void shelvedBookExists() {
        assertTrue(ShelfBook.source(ShelfBook.SHELVED, ShelfBook.BASE_LANGUAGE).isPresent(),
                "jar 里没有 books/" + ShelfBook.SHELVED + "/" + ShelfBook.BASE_LANGUAGE + ".md");
        assertTrue(allBooks().contains(ShelfBook.SHELVED), allBooks().toString());
    }

    @Test
    @DisplayName("每一本都有英文那一份、解析得了，扉页之后至少一章")
    void everyBookParses() {
        for (String id : allBooks()) {
            String src = ShelfBook.source(id, ShelfBook.BASE_LANGUAGE)
                    .orElseThrow(() -> new AssertionError(id + " 没有 " + ShelfBook.BASE_LANGUAGE + ".md"));
            List<Rulebook.Block> blocks = ShelfBook.parse(src);
            assertInstanceOf(Rulebook.TitlePage.class, blocks.getFirst(), id);
            assertTrue(blocks.stream().anyMatch(b -> b instanceof Rulebook.Chapter), id + " 一章都没有");
        }
    }

    @Test
    @DisplayName("没有那种语言的那一份就退到英文，并报出用的是哪一份；连英文都没有就抛")
    void picksLanguageThenFallsBack() {
        Map.Entry<String, String> zh = ShelfBook.pick(ShelfBook.SHELVED, "zh_cn");
        // 现在只有英文：中文客户端翻到的是英文那一份（ADR-0109 ③ · 待用户定）
        assertEquals(ShelfBook.source(ShelfBook.SHELVED, "zh_cn").isPresent() ? "zh_cn" : "en_us", zh.getKey());
        assertEquals("en_us", ShelfBook.pick(ShelfBook.SHELVED, "en_us").getKey());
        assertThrows(IllegalStateException.class, () -> ShelfBook.pick("no_such_book", "en_us"));
        assertThrows(IllegalArgumentException.class, () -> ShelfBook.source("../rulebook", "en_us"));
    }

    @Test
    @DisplayName("书柜里的书不从数据展开：用了 @roster 一类指令当场点名行号")
    void dataDirectivesAreRefused() {
        String src = "@title T\n@subtitle S\n@motto M\n# One\nA line.\n@roster captain\n";
        String msg = assertThrows(IllegalArgumentException.class, () -> ShelfBook.parse(src)).getMessage();
        assertTrue(msg.contains("第 6 行") && msg.contains("书柜里的书不从数据展开"), msg);
        String keys = "@title T\n@subtitle S\n@motto M\n# One\n@keys\n";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ShelfBook.parse(keys)).getMessage().contains("第 5 行"));
    }

    @Test
    @DisplayName("书柜的钩子：客户端没登记（专用服务端）时开不了、不抛；登记了才开，开的是放着的那一本")
    void hookDoesNothingUntilTheClientInstallsIt() {
        ShelfBook.install(null);
        assertFalse(ShelfBook.open());
        List<String> opened = new ArrayList<>();
        try {
            ShelfBook.install(opened::add);
            assertTrue(ShelfBook.open());
            assertEquals(List.of(ShelfBook.SHELVED), opened);
            // 与讲台是两个钩子：开书柜不开规则书
            RulebookView.install(() -> fail("开书柜开到了规则书"));
            assertTrue(ShelfBook.open());
        } finally {
            ShelfBook.install(null);
            RulebookView.install(null);
        }
        assertFalse(ShelfBook.open());
    }
}
