package org.example.service;

import org.example.entity.SkillMetadata;
import org.example.tool.skill.ReadSkillTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    @Value("${skill.base-path:src/main/resources/skills}")
    private String skillsBasePath;
    @PostConstruct
    public void init() throws IOException {
        loadSkillsFromDirectory(skillsBasePath);
    }

    public void loadSkillsFromDirectory(String directoryPath) throws IOException {
        File dir = new File(directoryPath);
        if (!dir.exists() || !dir.isDirectory()) {
            logger.warn("目录不存在：{}", directoryPath);
            return;
        }

        File[] skillDirs = dir.listFiles(File::isDirectory);
        if (skillDirs == null) {
            return;
        }

        for (File skillDir : skillDirs) {
            try {
                loadSkill(skillDir);
            } catch (Exception e) {
                logger.error("加载 skill 失败: {}", skillDir.getName(), e);
            }
        }
    }

    private void loadSkill(File skillDir) throws IOException {
        String skillName = skillDir.getName();
        File skillMdFile = new File(skillDir, "SKILL.md");

        if (!skillMdFile.exists()) {
            logger.warn("找不到 SKILL.md: {}", skillName);
            return;
        }

        String content = Files.readString(skillMdFile.toPath(), StandardCharsets.UTF_8);
        SkillDefinition definition = parseSkillDefinition(skillName, content);
        skills.put(skillName, definition);

        logger.info("成功加载 Skill: {}", skillName);
    }

    private SkillDefinition parseSkillDefinition(String skillName, String content) {
        SkillMetadata metadata = parseMetadata(content);
        List<String> references = listResources(skillName, "references");
        List<String> scripts = listResources(skillName, "scripts");

        return new SkillDefinition(skillName, metadata, content, references, scripts);
    }

    private SkillMetadata parseMetadata(String content) {
        String name = null;
        String description = null;

        String[] lines = content.split("\\r?\\n");
        int maxLinesToCheck = Math.min(lines.length, 50);

        for (int i = 0; i < maxLinesToCheck; i++) {
            String line = lines[i].trim();
            if (line.startsWith("name:")) {
                name = line.substring("name:".length()).trim();
            } else if (line.startsWith("description:")) {
                description = line.substring("description:".length()).trim();
            }
        }

        if (name == null) {
            name = "未命名";
        }
        if (description == null) {
            description = "无描述";
        }

        return new SkillMetadata(name, description);
    }

    private List<String> listResources(String skillName, String subDir) {
        List<String> result = new ArrayList<>();
        File dir = new File(skillsBasePath + "/" + skillName + "/" + subDir);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        result.add(file.getName());
                    }
                }
            }
        }
        return result;
    }

    public SkillDefinition getSkill(String name) {
        return skills.get(name);
    }

    public List<SkillMetadata> listAllSkillsMetadata() {
        List<SkillMetadata> metadataList = new ArrayList<>();
        for (SkillDefinition definition : skills.values()) {
            metadataList.add(definition.getMetadata());
        }
        return metadataList;
    }

    public ReadSkillTool.SkillResourceListVO listSkillResources(String skillName) {
        SkillDefinition definition = skills.get(skillName);
        if (definition == null) {
            return null;
        }
        return new ReadSkillTool.SkillResourceListVO(
                "SKILL.md",
                definition.getReferences(),
                definition.getScripts()
        );
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SkillDefinition {
        private String skillName;
        private SkillMetadata metadata;
        private String content;
        private List<String> references;
        private List<String> scripts;
    }
}
