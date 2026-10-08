package io.github.heavyseasmc.mod.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 只认光秃秃的一个整数（外面可以包空白与标点）。判据取严：认错比认不出代价大。
 * 「认得的」与「认不出的」各列一批，两边都要有 —— 只列一边，宽松的实现与严格的实现都能过。
 */
class ChoiceParserTest {

    @ParameterizedTest(name = "「{0}」→ 3")
    @ValueSource(strings = {"3", " 3 ", "3\n", "3.", "3。", "(3)", "（3）", "[3]", "【3】", "「3」", "\"3\"", "'3'", "`3`",
            "**3**", "#3", "３", "03", "<think>选 1 还是 2？</think>3", "<think>\n长长的思考\n</think>\n\n3",
            // 审查 2026-10-07 U12：有的推理模型模板只吐收尾标签（开标签在提示词模板里），思考段在前、答案在 </think> 之后
            "选 1 还是 2？想想\n</think>\n\n3", "思考里有 4 和 5</think>3"})
    @DisplayName("认得：数字外面只包了空白、括号引号、句末标点、markdown 的星号；全角数字；去掉思考之后（含只有收尾标签的）")
    void accepts(String answer) {
        ChoiceParser.Result r = ChoiceParser.parse(answer, 5);
        assertEquals(ChoiceParser.Result.Kind.OK, r.kind(), answer);
        assertEquals(3, r.number(), answer);
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"", "   ", "三", "three", "Option 3", "选项3", "选 3", "我选 3", "3 或 4", "3,4", "3.5", "1 2",
            "3/5", "3. 抢", "I choose 3", "<think>3</think>", "<think>3", "答：3", "想好了选 3</think>", "3</think>"})
    @DisplayName("认不出：带了别的字、两个数、小数、只在思考里有数字")
    void rejects(String answer) {
        assertEquals(ChoiceParser.Result.Kind.UNPARSEABLE, ChoiceParser.parse(answer, 5).kind(), answer);
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"0", "6", "-1", "-3", "99999999999999", "１０"})
    @DisplayName("越界：0、大于选项数、负数、长得离谱的数")
    void outOfRange(String answer) {
        assertEquals(ChoiceParser.Result.Kind.OUT_OF_RANGE, ChoiceParser.parse(answer, 5).kind(), answer);
    }

    @Test
    @DisplayName("两头的编号都认：1 与选项数")
    void bounds() {
        assertEquals(1, ChoiceParser.parse("1", 5).number());
        assertEquals(5, ChoiceParser.parse("5", 5).number());
        assertEquals(ChoiceParser.Result.Kind.OK, ChoiceParser.parse("1", 1).kind());
    }

    @Test
    @DisplayName("visible：去掉思考与首尾空白；null 当空串")
    void visible() {
        assertEquals("2", ChoiceParser.visible("<think>x</think> 2 "));
        assertEquals("", ChoiceParser.visible("<think>没写完"));
        assertEquals("", ChoiceParser.visible(null));
    }
}
