package org.example.tool.skill;

import org.example.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ReadResourceTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadResourceTool.class);
    private static final String TOOL_NAME = "read_resource";

    private final SkillService skillService;

    public ReadResourceTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(name = "read_resource", description = "读取某个 skill 下的 reference 或其他 text resource 的内容。当你需要某个具体的参考资料时，先通过 list_skill_resources 确定文件名，再通过这个工具读取。")
    public String readResource(@ToolParam(description = "skill 名称") String skillName,
                                  @ToolParam(description = "resource 文件名，例如 example.json") String resourceName) {
        logger.info("调用 read_resource 工具，skillName: {}, resourceName: {}", skillName, resourceName);
        
        if (skillName == null || skillName.isBlank()) {
            return "请提供 skill 名称";
        }
        if (resourceName == null || resourceName.isBlank()) {
            return "请提供 resource 文件名";
        }

        try {
            String content = skillService.readResource(skillName.trim(), resourceName.trim());
            logger.info("read_resource 工具执行成功，skillName: {}, resourceName: {}", skillName.trim(), resourceName.trim());
            return content;
        } catch (Exception e) {
            logger.error("read_resource 工具执行失败", e);
            return "读取 resource 失败：" + e.getMessage();
        }
    }
}
