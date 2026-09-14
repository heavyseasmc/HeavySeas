package io.github.heavyseasmc.engine.data;

import java.nio.file.Path;

/**
 * 数值数据不合 schema。
 *
 * <p>消息里<b>必须带上文件与出错位置</b>：这些文件由导出器生成，出错时人要去改的是导出器，
 * 而「哪张牌的哪个字段」是唯一能把两边接起来的信息。只说「解析失败」等于没说。
 */
public class DataFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataFormatException(String message) {
        super(message);
    }

    public DataFormatException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @param file  出问题的文件
     * @param where 文件内的位置，如 {@code cards[3].overboard}
     * @param what  出了什么问题
     */
    public static DataFormatException at(Path file, String where, String what) {
        return new DataFormatException("%s 的 %s：%s".formatted(file, where, what));
    }
}
