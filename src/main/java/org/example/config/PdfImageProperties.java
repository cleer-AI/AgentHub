package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "document.pdf.image")
public class PdfImageProperties {
    private boolean enabled = true;
    private int minWidth = 120;
    private int minHeight = 120;
    private double minPageAreaRatio = 0.01;
    private int maxImagesPerDocument = 50;
    private int relatedTextLimit = 2;
    private String summaryModel = "qwen-vl-max";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMinWidth() { return minWidth; }
    public void setMinWidth(int minWidth) { this.minWidth = minWidth; }
    public int getMinHeight() { return minHeight; }
    public void setMinHeight(int minHeight) { this.minHeight = minHeight; }
    public double getMinPageAreaRatio() { return minPageAreaRatio; }
    public void setMinPageAreaRatio(double minPageAreaRatio) { this.minPageAreaRatio = minPageAreaRatio; }
    public int getMaxImagesPerDocument() { return maxImagesPerDocument; }
    public void setMaxImagesPerDocument(int maxImagesPerDocument) { this.maxImagesPerDocument = maxImagesPerDocument; }
    public int getRelatedTextLimit() { return relatedTextLimit; }
    public void setRelatedTextLimit(int relatedTextLimit) { this.relatedTextLimit = relatedTextLimit; }
    public String getSummaryModel() { return summaryModel; }
    public void setSummaryModel(String summaryModel) { this.summaryModel = summaryModel; }
}
