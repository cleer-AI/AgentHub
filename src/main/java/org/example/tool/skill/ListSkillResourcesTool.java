package org.example.tool.skill;

import org.example.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ListSkillResourcesTool {

    private static final Logger logger = LoggerFactory.getLogger(ListSkillResourcesTool.class);
    private static final String TOOL_NAME = "list_skill_resources";

    private final SkillService skillService;

    public ListSkillResourcesTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(name = "list_skill_resources", description = "列出某个 skill 的 resources 和 scripts 的文件名，用于确认可用的资源或脚本")
    public String listSkillResources(@ToolParam(description = "skill 名称") String skillName) {
        logger.info("调用 list_skill_resources 工具，skillName: {}", skillName);
        
        if (skillName == null || skillName.isBlank()) {
            return "请提供 skill 名称";
        }

        try {
            ReadSkillTool.SkillResourceListVO resourceList = skillService.listSkillResources(skillName.trim());
            String result = buildListResult(skillName.trim(), resourceList);
            logger.info("list_skill_resources 工具执行成功，skillName: {}", skillName.trim());
            return result;
        } catch (Exception e) {
            logger.error("list_skill_resources 工具执行失败", e);
            return "列出 skill 资源失败：" + e.getMessage();
        }
    }

    private String buildListResult(String skillName, ReadSkillTool.SkillResourceListVO resourceList) {
        return """
                skillName: %s
                mainFile: %s
                references:
                %s
                scripts:
                %s
                """.formatted(
                skillName,
                resourceList == null ? "SKILL.md" : defaultText(resourceList.getMainFile()),
                formatList(resourceList == null ? null : resourceList.getReferences()),
                formatList(resourceList == null ? null : resourceList.getScripts())
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
}
