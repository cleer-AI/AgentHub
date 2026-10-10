package org.example.tool.skill;

import org.example.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ExecuteScriptTool {

    private static final Logger logger = LoggerFactory.getLogger(ExecuteScriptTool.class);
    private static final String TOOL_NAME = "execute_script";

    private final SkillService skillService;

    public ExecuteScriptTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(name = "execute_script", description = "执行某个 skill 下的 script。参数必须通过 JSON 格式提供，执行完脚本后返回结果。")
    public String executeScript(@ToolParam(description = "skill 名称") String skillName,
                                @ToolParam(description = "script 文件名，例如 generate_income_tax.js") String scriptName,
                                @ToolParam(description = "script 参数，JSON 格式的字符串，键值对") String paramsJson) {
        logger.info("调用 execute_script 工具，skillName: {}, scriptName: {}, paramsLength: {}",
                skillName, scriptName, paramsJson == null ? 0 : paramsJson.length());
        
        if (skillName == null || skillName.isBlank()) {
            return "请提供 skill 名称";
        }
        if (scriptName == null || scriptName.isBlank()) {
            return "请提供 script 文件名";
        }

        try {
            String result = skillService.executeScript(skillName.trim(), scriptName.trim(), paramsJson);
            logger.info("execute_script 工具执行成功，skillName: {}, scriptName: {}", skillName.trim(), scriptName.trim());
            return result;
        } catch (Exception e) {
            logger.error("execute_script 工具执行失败", e);
            return "执行 script 失败：" + e.getMessage();
        }
    }
}
