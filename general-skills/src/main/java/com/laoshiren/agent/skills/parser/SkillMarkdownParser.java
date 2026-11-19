package com.laoshiren.agent.skills.parser;

import com.laoshiren.agent.skills.param.CreateSkillDraft;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;

/** 严格解析 SKILL.md：格式或字段错误直接返回原因，正文保留原有缩进、空行与换行格式。 */
@Component
public class SkillMarkdownParser {
    private static final Pattern DELIMITER = Pattern.compile("(?m)^---[ \\t]*(?:\\r\\n|\\n|\\r|\\z)");

    public CreateSkillDraft parse(String markdown) {
        Assert.hasText(markdown, "SKILL.md 文件不能为空");
        Assert.isTrue(markdown.indexOf('\0') < 0, "SKILL.md 不能包含空字符，请上传 UTF-8 文本文件");
        String text = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        var delimiter = DELIMITER.matcher(text);
        if (!delimiter.lookingAt()) {
            throw new IllegalArgumentException("SKILL.md 必须以独立一行 --- 开始 YAML 文件头");
        }
        int headerStart = delimiter.end();
        if (!delimiter.find()) {
            throw new IllegalArgumentException("YAML 文件头缺少结束分隔符 ---");
        }
        String header = text.substring(headerStart, delimiter.start());
        String body = text.substring(delimiter.end());
        Map<?, ?> metadata = parseHeader(header);
        List<String> errors = new ArrayList<>();
        String name = requiredText(metadata.get("name"), "名称（name）", errors);
        String description = requiredText(metadata.get("description"), "描述（description）", errors);
        if (body.isBlank()) {
            errors.add("缺少 Markdown 正文（instructionsMd）或正文为空白");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }

        Map<String, Object> extra = jsonObject(metadata);
        extra.remove("name");
        extra.remove("description");
        return new CreateSkillDraft(name, description, body, extra);
    }

    private Map<?, ?> parseHeader(String header) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(32);
        options.setCodePointLimit(1024 * 1024);
        // Yaml 实例带有解析状态，每次创建，避免并发导入共享状态。
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded;
        try {
            // 同时拒绝标量别名，防止小文件在 JSONB 序列化时展开为大量重复内容。
            for (var event : yaml.parse(new StringReader(header))) {
                if (event instanceof AliasEvent) {
                    throw new IllegalArgumentException("YAML 文件头不支持别名引用，请直接填写属性值");
                }
            }
            loaded = yaml.load(header);
        } catch (org.yaml.snakeyaml.constructor.DuplicateKeyException exception) {
            throw new IllegalArgumentException("YAML 文件头包含重复字段" + location(exception), exception);
        } catch (YAMLException exception) {
            String location = exception instanceof MarkedYAMLException marked ? location(marked) : "";
            throw new IllegalArgumentException("YAML 文件头格式错误或超出解析限制" + location, exception);
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("YAML 文件头必须是键值对象");
        }
        return map;
    }

    private static String requiredText(Object value, String field, List<String> errors) {
        if (value == null || value instanceof String text && text.isBlank()) {
            errors.add("缺少" + field + "或内容为空白");
            return null;
        }
        if (!(value instanceof String text)) {
            errors.add(field + "必须是字符串；数字、布尔值或日期文字请使用引号");
            return null;
        }
        return text;
    }

    private static Map<String, Object> jsonObject(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("YAML 文件头的属性名必须是字符串");
            }
            Assert.isTrue(key.indexOf('\0') < 0, "YAML 文件头的属性名不能包含空字符");
            result.put(key, jsonValue(entry.getValue()));
        }
        return result;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof String text) {
            Assert.isTrue(text.indexOf('\0') < 0, "YAML 文件头字符串不能包含空字符");
            return text;
        }
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Number number) {
            if (number instanceof Double d && !Double.isFinite(d)
                    || number instanceof Float f && !Float.isFinite(f)) {
                throw new IllegalArgumentException("YAML 文件头的数字不能是 NaN 或 Infinity");
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) return jsonObject(map);
        if (value instanceof List<?> list) return list.stream().map(SkillMarkdownParser::jsonValue).toList();
        // YAML 日期按 ISO-8601 文本保存，避免被 JSON 序列化成毫秒时间戳。
        if (value instanceof Date date) return date.toInstant().toString();
        throw new IllegalArgumentException("YAML 文件头只支持字符串、数字、布尔值、对象、数组、日期或 null");
    }

    private static String location(MarkedYAMLException exception) {
        if (exception.getProblemMark() == null) return "";
        return "（文件第 " + (exception.getProblemMark().getLine() + 2)
                + " 行，第 " + (exception.getProblemMark().getColumn() + 1) + " 列）";
    }
}
