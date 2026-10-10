package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.SkillMetadata;
import org.example.tool.skill.ReadSkillTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SkillService {

    private static final Logger logger = LoggerFactory.getLogger(SkillService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SkillRegistry skillRegistry;

    @Value("${skill.base-path:src/main/resources/skills}")
    private String skillsBasePath;

    public SkillService(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public String listAllSkillMetadata() {
        List<SkillMetadata> metadataList = skillRegistry.listAllSkillsMetadata();
        if (metadataList.isEmpty()) {
            return "没有可用的 skill";
        }

        StringBuilder sb = new StringBuilder();
        for (SkillMetadata metadata : metadataList) {
            sb.append("name: ").append(metadata.getName()).append("\n");
            sb.append("description: ").append(metadata.getDescription()).append("\n\n");
        }
        return sb.toString();
    }

    public ReadSkillTool.SkillResourceListVO listSkillResources(String skillName) {
        return skillRegistry.listSkillResources(skillName);
    }

    public String readSkillContent(String skillName) {
        SkillRegistry.SkillDefinition definition = skillRegistry.getSkill(skillName);
        if (definition == null) {
            throw new RuntimeException("找不到 skill: " + skillName);
        }
        return definition.getContent();
    }

    public String readResource(String skillName, String resourceName) {
        String resourcePath = skillsBasePath + "/" + skillName + "/references/" + resourceName;
        File resourceFile = new File(resourcePath);
        if (!resourceFile.exists()) {
            resourcePath = skillsBasePath + "/" + skillName + "/" + resourceName;
            resourceFile = new File(resourcePath);
        }

        if (!resourceFile.exists()) {
            throw new RuntimeException("找不到资源文件: " + resourceName);
        }

        try {
            return Files.readString(resourceFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    public String executeScript(String skillName, String scriptName, String paramsJson) {
        String scriptPath = skillsBasePath + "/" + skillName + "/scripts/" + scriptName;
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            throw new RuntimeException("找不到脚本: " + scriptName);
        }

        try {
            String scriptContent = Files.readString(scriptFile.toPath(), StandardCharsets.UTF_8);
            
            Map<String, Object> params;
            if (paramsJson != null && !paramsJson.isBlank()) {
                try {
                    params = OBJECT_MAPPER.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    params = new HashMap<>();
                }
            } else {
                params = new HashMap<>();
            }

            if (scriptName.endsWith(".js")) {
                return executeJavaScript(scriptContent, params);
            } else if (scriptName.endsWith(".py")) {
                return executePythonScript(scriptPath, params);
            } else {
                return "暂不支持的脚本类型";
            }
        } catch (IOException e) {
            throw new RuntimeException("执行脚本失败: " + e.getMessage(), e);
        }
    }

    private String executeJavaScript(String scriptContent, Map<String, Object> params) {
        try {
            javax.script.ScriptEngineManager manager = new javax.script.ScriptEngineManager();
            javax.script.ScriptEngine engine = manager.getEngineByName("js");
            
            if (engine == null) {
                return "找不到 JavaScript 引擎";
            }

            for (Map.Entry<String, Object> entry : params.entrySet()) {
                engine.put(entry.getKey(), entry.getValue());
            }

            Object result = engine.eval(scriptContent);
            return result == null ? "null" : result.toString();
        } catch (Exception e) {
            logger.error("执行 JavaScript 失败", e);
            return "执行失败: " + e.getMessage();
        }
    }

    private String executePythonScript(String scriptPath, Map<String, Object> params) {
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add("python");
            cmd.add(scriptPath);
            
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                cmd.add("--" + entry.getKey());
                cmd.add(entry.getValue().toString());
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return "脚本执行失败，退出码: " + exitCode + "\n" + output;
            }

            return output.toString();
        } catch (Exception e) {
            logger.error("执行 Python 失败", e);
            return "执行失败: " + e.getMessage();
        }
    }
}
