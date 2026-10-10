package org.example.tool.skill;

import org.example.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ReadSkillTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadSkillTool.class);
    private static final String TOOL_NAME = "read_skill";

    private final SkillService skillService;

    public ReadSkillTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(name = "read_skill", description = "读取某个 skill 的完整内容。当你需要使用某个 skill 的详细说明、步骤、约束时，优先调用此工具。")
    public String readSkill(@ToolParam(description = "skill 名称") String skillName) {
        logger.info("调用 read_skill 工具，skillName: {}", skillName);
        
        if (skillName == null || skillName.isBlank()) {
            return "请提供 skill 名称";
        }

        try {
            String content = skillService.readSkillContent(skillName.trim());
            SkillResourceListVO resourceList = skillService.listSkillResources(skillName.trim());
            String result = buildReadResult(skillName.trim(), content, resourceList);
            logger.info("read_skill 工具执行成功，skillName: {}", skillName.trim());
            return result;
        } catch (Exception e) {
            logger.error("read_skill 工具执行失败", e);
            return "读取 skill 失败：" + e.getMessage();
        }
    }

    private String buildReadResult(String skillName, String content, SkillResourceListVO resourceList) {
        return """
                skillName: %s
                mainFile: %s
                references:
                %s
                scripts:
                %s
                content:
                %s
                """.formatted(
                skillName,
                resourceList == null ? "SKILL.md" : defaultText(resourceList.getMainFile()),
                formatList(resourceList == null ? null : resourceList.getReferences()),
                formatList(resourceList == null ? null : resourceList.getScripts()),
                defaultText(content)
        );
    }

    private String formatList(java.util.List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- 无";
        }
        return items.stream()
                .map(item -> "- " + defaultText(item))
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse("- 无");
    }

    private String defaultText(String text) {
        return text == null || text.isBlank() ? "无" : text;
    }

    // SkillResourceListVO 内部类
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SkillResourceListVO {
        private String mainFile;
        private java.util.List<String> references;
        private java.util.List<String> scripts;
    }
}
