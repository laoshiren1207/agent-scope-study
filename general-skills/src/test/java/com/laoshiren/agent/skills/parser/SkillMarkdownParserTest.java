package com.laoshiren.agent.skills.parser;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillMarkdownParserTest {
    private final SkillMarkdownParser parser = new SkillMarkdownParser();

    @Test
    void parsesMultilineDescriptionAndNestedMetadataWithoutIncludingHeaderInBody() {
        String body = "\n# 审查\n\n    保留缩进\n\n---\n正文中的分隔线\n";
        var draft = parser.parse("""
                ---
                name: spring-code-review
                description: >-
                  检查 Spring 代码。
                  适用于代码审查。
                tags: [java, spring]
                metadata:
                  priority: 2
                  enabled: true
                  note: null
                created: 2026-11-18
                ---
                """ + body);

        assertThat(draft.name()).isEqualTo("spring-code-review");
        assertThat(draft.description()).isEqualTo("检查 Spring 代码。 适用于代码审查。");
        assertThat(draft.instructionsMd()).isEqualTo(body);
        assertThat(draft.extraFrontmatter()).containsEntry("tags", List.of("java", "spring"))
                .containsEntry("created", "2026-11-18T00:00:00Z")
                .doesNotContainKeys("name", "description");
        var metadata = (Map<?, ?>) draft.extraFrontmatter().get("metadata");
        assertThat(metadata.containsKey("note")).isTrue();
        assertThat(metadata.get("note")).isNull();
        assertThat(metadata.get("priority")).isEqualTo(2);
        assertThat(metadata.get("enabled")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void supportsBomAndLineEndingsWhilePreservingBody(String newline) {
        String body = newline + "# 正文" + newline + "    缩进" + newline;
        var draft = parser.parse("\uFEFF---  " + newline + "name: review" + newline
                + "description: 描述" + newline + "---\t" + newline + body);

        assertThat(draft.name()).isEqualTo("review");
        assertThat(draft.instructionsMd()).isEqualTo(body);
        assertThat(draft.extraFrontmatter()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("missingContent")
    void explainsWhichRequiredContentIsMissing(String markdown, String expected) {
        assertThatThrownBy(() -> parser.parse(markdown))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(expected);
    }

    static Stream<Arguments> missingContent() {
        return Stream.of(
                Arguments.of("---\ndescription: 描述\n---\n正文", "缺少名称（name）"),
                Arguments.of("---\nname: ' '\ndescription: 描述\n---\n正文", "名称（name）"),
                Arguments.of("---\nname: review\n---\n正文", "缺少描述（description）"),
                Arguments.of("---\nname: review\ndescription: null\n---\n正文", "描述（description）"),
                Arguments.of("---\nname: review\ndescription: 描述\n---", "缺少 Markdown 正文"),
                Arguments.of("---\nname: review\ndescription: 描述\n---\n \t\n", "正文为空白"));
    }

    @Test
    void reportsAllMissingFieldsTogether() {
        assertThatThrownBy(() -> parser.parse("---\n---\n"))
                .hasMessageContaining("名称（name）").hasMessageContaining("描述（description）")
                .hasMessageContaining("Markdown 正文");
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void rejectsMalformedHeadersAndUnsupportedMetadata(String markdown, String expected) {
        assertThatThrownBy(() -> parser.parse(markdown))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(expected);
    }

    static Stream<Arguments> invalidDocuments() {
        String fields = "name: review\ndescription: 描述\n";
        return Stream.of(
                Arguments.of("# 正文", "必须以独立一行 ---"),
                Arguments.of("---\n" + fields, "缺少结束分隔符"),
                Arguments.of("---\n[a, b]\n---\n正文", "必须是键值对象"),
                Arguments.of("---\nname: [review]\ndescription: 描述\n---\n正文", "名称（name）必须是字符串"),
                Arguments.of("---\nname: review\ndescription: true\n---\n正文", "描述（description）必须是字符串"),
                Arguments.of("---\nname: review\ndescription: 2026-11-18\n---\n正文", "必须是字符串"),
                Arguments.of("---\n" + fields + "name: another\n---\n正文", "重复字段"),
                Arguments.of("---\n" + fields + "metadata: {key: 1, key: 2}\n---\n正文", "重复字段"),
                Arguments.of("---\n" + fields + "tags: [unclosed\n---\n正文", "YAML 文件头格式错误"),
                Arguments.of("---\n" + fields + "1: value\n---\n正文", "属性名必须是字符串"),
                Arguments.of("---\n" + fields + "metadata: {1: value}\n---\n正文", "属性名必须是字符串"),
                Arguments.of("---\n" + fields + "score: .nan\n---\n正文", "NaN 或 Infinity"),
                Arguments.of("---\n" + fields + "score: .inf\n---\n正文", "NaN 或 Infinity"),
                Arguments.of("---\n" + fields + "metadata: !!set {a: null}\n---\n正文", "只支持"),
                Arguments.of("---\n" + fields + "object: !!java.lang.Object {}\n---\n正文", "YAML 文件头格式错误"),
                Arguments.of("---\n" + fields + "a: &a [*a]\n---\n正文", "不支持别名引用"),
                Arguments.of("---\n" + fields + "a: &a text\nb: *a\n---\n正文", "不支持别名引用"),
                Arguments.of("---\n" + fields + "metadata: \"\\0\"\n---\n正文", "不能包含空字符"),
                Arguments.of("---\n" + fields + "\"\\0\": value\n---\n正文", "属性名不能包含空字符"),
                Arguments.of("---\n" + fields + "---\n正文\0", "不能包含空字符"));
    }

    @Test
    void givesFileLineAndColumnForMalformedYaml() {
        assertThatThrownBy(() -> parser.parse("---\nname: review\nname: again\n---\n正文"))
                .hasMessageContaining("文件第 3 行").hasMessageContaining("第 1 列");
    }

    @Test
    void boundsYamlNesting() {
        String deeplyNested = "[".repeat(40) + "1" + "]".repeat(40);
        assertThatThrownBy(() -> parser.parse("---\nname: review\ndescription: 描述\na: "
                + deeplyNested + "\n---\n正文"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("超出解析限制");
    }
}
