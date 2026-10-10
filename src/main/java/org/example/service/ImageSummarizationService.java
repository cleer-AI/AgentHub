package org.example.service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemBase;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemImage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import org.example.config.PdfImageProperties;
import org.example.dto.PdfDocumentParseResult.ImageAsset;
import org.example.observability.AgentTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class ImageSummarizationService {
    private final PdfImageProperties properties;
    private final AgentTelemetry telemetry;
    private final String apiKey;
    private final MultiModalConversation conversation = new MultiModalConversation();

    public ImageSummarizationService(PdfImageProperties properties, AgentTelemetry telemetry,
                                     @Value("${dashscope.api.key}") String apiKey) {
        this.properties = properties;
        this.telemetry = telemetry;
        this.apiKey = apiKey;
    }

    public String summarize(ImageAsset image) {
        return telemetry.observe("vision.summarize", () -> summarizeOnce(image));
    }

    private String summarizeOnce(ImageAsset image) throws Exception {
        Path temporary = Files.createTempFile("pdf-image-", ".png");
        try {
            Files.write(temporary, image.getPngData());
            String context = image.getNearbyText();
            if (context.length() > 1500) context = context.substring(0, 1500);
            String prompt = "请分析这张来自PDF第" + image.getPage() + "页的图片。"
                    + "输出适合知识库向量检索的中文摘要，包含图片类型、主要内容、可见文字、关键实体和关系。"
                    + "不要猜测看不清的信息，不要输出JSON。附近正文：" + context;
            List<MultiModalMessageItemBase> content = List.of(
                    new MultiModalMessageItemImage(temporary.toUri().toString()),
                    new MultiModalMessageItemText(prompt));
            MultiModalConversationMessage message = MultiModalConversationMessage.builder()
                    .role("user").content(content).build();
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model(properties.getSummaryModel())
                    .message(message)
                    .build();
            MultiModalConversationResult result = conversation.call(param);
            if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null
                    || result.getOutput().getChoices().isEmpty()) {
                throw new IllegalStateException("视觉模型返回空结果");
            }
            List<Map<String, Object>> responseContent = result.getOutput().getChoices().get(0)
                    .getMessage().getContent();
            return responseContent.stream()
                    .map(item -> item.get("text"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .findFirst()
                    .filter(text -> !text.isBlank())
                    .orElseThrow(() -> new IllegalStateException("视觉模型未返回文本摘要"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
