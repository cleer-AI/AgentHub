package org.example.tool.skill;

import org.example.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ListSkillsTool {

    private static final Logger logger = LoggerFactory.getLogger(ListSkillsTool.class);
    private static final String TOOL_NAME = "list_skills";

    private final SkillService skillService;

    public ListSkillsTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(name = "list_skills", description = "列出所有可用的 agent skill 的名称和描述。在解决问题前，建议先调用此工具来了解是否有合适的 skill。")
    public String listSkills() {
        logger.info("调用 list_skills 工具");
        try {
            String result = skillService.listAllSkillMetadata();
            logger.info("list_skills 工具执行成功，返回 skill 数量");
            return result;
        } catch (Exception e) {
            logger.error("list_skills 工具执行失败", e);
            return "列出 skill 失败：" + e.getMessage();
        }
    }
}
