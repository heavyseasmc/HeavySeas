package io.github.heavyseasmc.engine.data;

/**
 * 数值数据不合 schema。
 *
 * <p>消息里<b>必须带上来源与出错位置</b>：这些数据由导出器生成，出错时人要去改的是导出器，
 * 而「哪份数据的哪张牌的哪个字段」是唯一能把两边接起来的信息。只说「解析失败」等于没说。
 * 来源可以是文件路径，也可以是数据包里的资源标识。
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
     * @param source 出问题的数据来自哪里：文件路径或资源标识
     * @param where  数据内的位置，如 {@code cards[3].overboard}
     * @param what   出了什么问题
     */
    public static DataFormatException at(String source, String where, String what) {
        return new DataFormatException("%s 的 %s：%s".formatted(source, where, what));
    }
}
