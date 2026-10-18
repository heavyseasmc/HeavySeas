package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.rulebook.ShelfBook;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 书柜里那一本书（ADR-0109 ③）：Minecraft 自带的 {@link BookScreen} 书页，排法与讲台上的规则书同一套
 * （{@link RulebookBook#plainPages}：按<b>当前字体</b>折行切页、章另起一页），只是不插目录、底下只有一枚「收起」。
 *
 * <p>❗声音：{@code BookScreen} 的公开构造器翻页时放游戏自带的翻书声，这里用的是不带声音的那一个（{@code super()}，
 * 书页随后在 {@link #init} 里换上）。翻书声归音频会话（ADR-0106 §2.6：新声音不用游戏自带的），接在 {@link #pageTurned}。
 */
public final class ShelfBookScreen extends BookScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private final BookScreen.Contents contents;
    private boolean loaded;

    private ShelfBookScreen(List<Text> pages) {
        super();
        this.contents = new BookScreen.Contents(pages);
    }

    /** 书柜右键（{@link ShelfBook#open} 的客户端那一半）。打不开就记下原因、在聊天栏说一句，不为一本书崩游戏。 */
    public static void open(MinecraftClient client, String id) {
        try {
            String language = client.getLanguageManager().getLanguage();
            Map.Entry<String, String> picked = ShelfBook.pick(id, language);
            List<Text> pages = RulebookBook.plainPages(ShelfBook.parse(picked.getValue()), client.textRenderer);
            // 报出用的是哪一本、哪一份：退回英文与没退回，在书页上只差在字是什么语言
            LOGGER.info("书柜：{} · 客户端语言 {} · 用的是 {} 那一份 · {} 页", id, language, picked.getKey(), pages.size());
            client.setScreen(new ShelfBookScreen(pages));
            opened();
        } catch (RuntimeException e) {
            // 书柜右键在渲染线程上：抛出去整个客户端就崩了
            LOGGER.error("书柜里的书打不开：{}", id, e);
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("heavyseas.shelf_book.failed", String.valueOf(e.getMessage())), false);
            }
        }
    }

    @Override
    protected void init() {
        super.init();                 // 先让 BookScreen 建好翻页按钮：setPageProvider 要动它们，建之前调会空指针
        if (!loaded) {
            setPageProvider(contents);
            loaded = true;
        }
    }

    @Override
    protected void addCloseButton() {
        // 同规则书底下那一排的画法（2026-10-13 验收：灰色按钮与纸板不搭），一枚 200 宽，位置照 BookScreen 自己的「完成」
        addDrawableChild(new RulebookBook.BookButton(width / 2 - 100, 196, 200, 20, Text.translatable("heavyseas.keys.close"),
                b -> close()));
    }

    @Override
    protected void goToPreviousPage() {
        super.goToPreviousPage();
        pageTurned();
    }

    @Override
    protected void goToNextPage() {
        super.goToNextPage();
        pageTurned();
    }

    /** 音频会话的接口点：翻开这本书时一声。现在什么都不做。 */
    private static void opened() {
    }

    /** 音频会话的接口点：翻一页一声（翻页按钮与 PgUp / PgDn 都经过这里；翻到头了也会调到，接声音时判一下页码动没动）。现在什么都不做。 */
    private static void pageTurned() {
    }
}
