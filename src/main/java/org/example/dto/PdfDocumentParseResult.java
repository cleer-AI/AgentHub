package org.example.dto;

import java.util.ArrayList;
import java.util.List;

public class PdfDocumentParseResult {
    private final String documentId;
    private final int pageCount;
    private final List<TextChunk> textChunks;
    private final List<ImageAsset> images;

    public PdfDocumentParseResult(String documentId, int pageCount,
                                  List<TextChunk> textChunks, List<ImageAsset> images) {
        this.documentId = documentId;
        this.pageCount = pageCount;
        this.textChunks = textChunks;
        this.images = images;
    }

    public String getDocumentId() { return documentId; }
    public int getPageCount() { return pageCount; }
    public List<TextChunk> getTextChunks() { return textChunks; }
    public List<ImageAsset> getImages() { return images; }

    public record BoundingBox(double x, double y, double width, double height) {
        public double verticalDistance(BoundingBox other) {
            double bottom = y + height;
            double otherBottom = other.y + other.height;
            if (bottom < other.y) return other.y - bottom;
            if (otherBottom < y) return y - otherBottom;
            return 0;
        }
    }

    public static class TextChunk {
        private final String chunkId;
        private final String content;
        private final int page;
        private final int sequence;
        private final String sectionId;
        private final BoundingBox boundingBox;
        private final List<String> relatedImageIds = new ArrayList<>();

        public TextChunk(String chunkId, String content, int page, int sequence,
                         String sectionId, BoundingBox boundingBox) {
            this.chunkId = chunkId;
            this.content = content;
            this.page = page;
            this.sequence = sequence;
            this.sectionId = sectionId;
            this.boundingBox = boundingBox;
        }

        public String getChunkId() { return chunkId; }
        public String getContent() { return content; }
        public int getPage() { return page; }
        public int getSequence() { return sequence; }
        public String getSectionId() { return sectionId; }
        public BoundingBox getBoundingBox() { return boundingBox; }
        public List<String> getRelatedImageIds() { return relatedImageIds; }
    }

    public static class ImageAsset {
        private final String imageId;
        private final byte[] pngData;
        private final int page;
        private final int pixelWidth;
        private final int pixelHeight;
        private final String sha256;
        private final String sectionId;
        private final BoundingBox boundingBox;
        private final List<String> relatedChunkIds = new ArrayList<>();
        private String nearbyText = "";

        public ImageAsset(String imageId, byte[] pngData, int page, int pixelWidth, int pixelHeight,
                          String sha256, String sectionId, BoundingBox boundingBox) {
            this.imageId = imageId;
            this.pngData = pngData;
            this.page = page;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.sha256 = sha256;
            this.sectionId = sectionId;
            this.boundingBox = boundingBox;
        }

        public String getImageId() { return imageId; }
        public byte[] getPngData() { return pngData; }
        public int getPage() { return page; }
        public int getPixelWidth() { return pixelWidth; }
        public int getPixelHeight() { return pixelHeight; }
        public String getSha256() { return sha256; }
        public String getSectionId() { return sectionId; }
        public BoundingBox getBoundingBox() { return boundingBox; }
        public List<String> getRelatedChunkIds() { return relatedChunkIds; }
        public String getNearbyText() { return nearbyText; }
        public void setNearbyText(String nearbyText) { this.nearbyText = nearbyText; }
    }
}
