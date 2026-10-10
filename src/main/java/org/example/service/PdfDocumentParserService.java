package org.example.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.example.config.DocumentChunkConfig;
import org.example.config.PdfImageProperties;
import org.example.dto.PdfDocumentParseResult;
import org.example.dto.PdfDocumentParseResult.BoundingBox;
import org.example.dto.PdfDocumentParseResult.ImageAsset;
import org.example.dto.PdfDocumentParseResult.TextChunk;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PdfDocumentParserService {
    private final DocumentChunkConfig chunkConfig;
    private final PdfImageProperties imageProperties;

    public PdfDocumentParserService(DocumentChunkConfig chunkConfig, PdfImageProperties imageProperties) {
        this.chunkConfig = chunkConfig;
        this.imageProperties = imageProperties;
    }

    public PdfDocumentParseResult parse(File file, String normalizedSource) throws IOException {
        String documentId = UUID.nameUUIDFromBytes(normalizedSource.getBytes(StandardCharsets.UTF_8)).toString();
        List<TextChunk> textChunks = new ArrayList<>();
        List<ImageAsset> images = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            int sequence = 0;
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                PDPage page = document.getPage(pageIndex);
                String sectionId = documentId + "-page-" + pageNumber;
                List<TextLine> lines = extractTextLines(document, pageIndex);
                List<TextChunk> pageChunks = buildTextChunks(documentId, pageNumber, sectionId, sequence, lines);
                textChunks.addAll(pageChunks);
                sequence += pageChunks.size();

                if (imageProperties.isEnabled() && images.size() < imageProperties.getMaxImagesPerDocument()) {
                    ImageCollector collector = new ImageCollector(page, documentId, pageNumber, sectionId,
                            imageProperties, imageProperties.getMaxImagesPerDocument() - images.size());
                    collector.processPage(page);
                    images.addAll(collector.images());
                }
            }
            associate(textChunks, images);
            return new PdfDocumentParseResult(documentId, document.getNumberOfPages(), textChunks, images);
        }
    }

    private List<TextLine> extractTextLines(PDDocument document, int pageIndex) throws IOException {
        PositionedTextStripper stripper = new PositionedTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(document);
        return stripper.lines();
    }

    private List<TextChunk> buildTextChunks(String documentId, int page, String sectionId,
                                            int sequenceStart, List<TextLine> lines) {
        List<TextChunk> result = new ArrayList<>();
        List<TextLine> current = new ArrayList<>();
        int length = 0;
        int sequence = sequenceStart;

        for (TextLine line : lines) {
            if (!current.isEmpty() && length + line.text().length() + 1 > chunkConfig.getMaxSize()) {
                result.add(toChunk(documentId, page, sectionId, sequence++, current));
                current = overlapLines(current);
                length = current.stream().mapToInt(item -> item.text().length() + 1).sum();
            }
            current.add(line);
            length += line.text().length() + 1;
        }
        if (!current.isEmpty()) result.add(toChunk(documentId, page, sectionId, sequence, current));
        return result;
    }

    private List<TextLine> overlapLines(List<TextLine> lines) {
        List<TextLine> overlap = new ArrayList<>();
        int length = 0;
        for (int i = lines.size() - 1; i >= 0 && length < chunkConfig.getOverlap(); i--) {
            overlap.add(0, lines.get(i));
            length += lines.get(i).text().length();
        }
        return overlap;
    }

    private TextChunk toChunk(String documentId, int page, String sectionId,
                              int sequence, List<TextLine> lines) {
        String content = String.join("\n", lines.stream().map(TextLine::text).toList()).trim();
        BoundingBox box = union(lines.stream().map(TextLine::box).toList());
        String chunkId = documentId + "-text-" + String.format("%05d", sequence);
        return new TextChunk(chunkId, content, page, sequence, sectionId, box);
    }

    void associate(List<TextChunk> chunks, List<ImageAsset> images) {
        for (ImageAsset image : images) {
            List<TextChunk> related = chunks.stream()
                    .filter(chunk -> chunk.getPage() == image.getPage())
                    .sorted(Comparator.comparingDouble(chunk ->
                            chunk.getBoundingBox().verticalDistance(image.getBoundingBox())))
                    .limit(imageProperties.getRelatedTextLimit())
                    .toList();
            Set<String> nearbyText = new LinkedHashSet<>();
            for (TextChunk chunk : related) {
                image.getRelatedChunkIds().add(chunk.getChunkId());
                chunk.getRelatedImageIds().add(image.getImageId());
                nearbyText.add(chunk.getContent());
            }
            image.setNearbyText(String.join("\n", nearbyText));
        }
    }

    private static BoundingBox union(List<BoundingBox> boxes) {
        if (boxes.isEmpty()) return new BoundingBox(0, 0, 1, 1);
        double minX = boxes.stream().mapToDouble(BoundingBox::x).min().orElse(0);
        double minY = boxes.stream().mapToDouble(BoundingBox::y).min().orElse(0);
        double maxX = boxes.stream().mapToDouble(box -> box.x() + box.width()).max().orElse(1);
        double maxY = boxes.stream().mapToDouble(box -> box.y() + box.height()).max().orElse(1);
        return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }

    private record TextLine(String text, BoundingBox box) {}

    private static final class PositionedTextStripper extends PDFTextStripper {
        private final List<TextLine> lines = new ArrayList<>();

        private PositionedTextStripper() throws IOException {}

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            String clean = text == null ? "" : text.trim();
            if (clean.isEmpty() || positions.isEmpty()) return;
            float pageWidth = positions.get(0).getPageWidth();
            float pageHeight = positions.get(0).getPageHeight();
            double minX = positions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0);
            double minY = positions.stream().mapToDouble(TextPosition::getYDirAdj).min().orElse(0);
            double maxX = positions.stream().mapToDouble(p -> p.getXDirAdj() + p.getWidthDirAdj()).max().orElse(pageWidth);
            double maxY = positions.stream().mapToDouble(p -> p.getYDirAdj() + p.getHeightDir()).max().orElse(pageHeight);
            lines.add(new TextLine(clean, normalized(minX, minY, maxX, maxY, pageWidth, pageHeight)));
        }

        private List<TextLine> lines() { return lines; }
    }

    private static final class ImageCollector extends PDFGraphicsStreamEngine {
        private final String documentId;
        private final int pageNumber;
        private final String sectionId;
        private final PdfImageProperties properties;
        private final int limit;
        private final List<ImageAsset> images = new ArrayList<>();
        private final Path2D path = new Path2D.Float();

        private ImageCollector(PDPage page, String documentId, int pageNumber, String sectionId,
                               PdfImageProperties properties, int limit) {
            super(page);
            this.documentId = documentId;
            this.pageNumber = pageNumber;
            this.sectionId = sectionId;
            this.properties = properties;
            this.limit = limit;
        }

        @Override
        public void drawImage(PDImage pdImage) throws IOException {
            if (images.size() >= limit || pdImage.getWidth() < properties.getMinWidth()
                    || pdImage.getHeight() < properties.getMinHeight()) return;
            PDRectangle pageBox = getPage().getCropBox();
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Point2D p0 = ctm.transformPoint(0, 0);
            Point2D p1 = ctm.transformPoint(1, 0);
            Point2D p2 = ctm.transformPoint(0, 1);
            Point2D p3 = ctm.transformPoint(1, 1);
            double minX = Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            double maxX = Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            double minY = Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            double maxY = Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            BoundingBox box = normalized(minX - pageBox.getLowerLeftX(),
                    pageBox.getHeight() - (maxY - pageBox.getLowerLeftY()),
                    maxX - pageBox.getLowerLeftX(),
                    pageBox.getHeight() - (minY - pageBox.getLowerLeftY()),
                    pageBox.getWidth(), pageBox.getHeight());
            if (box.width() * box.height() < properties.getMinPageAreaRatio()) return;

            BufferedImage image = pdImage.getImage();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            byte[] png = output.toByteArray();
            String hash = sha256(png);
            String imageId = documentId + "-image-" + String.format("%03d-%03d", pageNumber, images.size() + 1);
            images.add(new ImageAsset(imageId, png, pageNumber, image.getWidth(), image.getHeight(),
                    hash, sectionId, box));
        }

        private List<ImageAsset> images() { return images; }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { path.moveTo(p0.getX(), p0.getY()); path.lineTo(p1.getX(), p1.getY()); path.lineTo(p2.getX(), p2.getY()); path.lineTo(p3.getX(), p3.getY()); path.closePath(); }
        @Override public void clip(int windingRule) { path.setWindingRule(windingRule); }
        @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
        @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { path.curveTo(x1, y1, x2, y2, x3, y3); }
        @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
        @Override public void closePath() { path.closePath(); }
        @Override public void endPath() { path.reset(); }
        @Override public void strokePath() { path.reset(); }
        @Override public void fillPath(int windingRule) { path.reset(); }
        @Override public void fillAndStrokePath(int windingRule) { path.reset(); }
        @Override public void shadingFill(COSName shadingName) {}
    }

    private static BoundingBox normalized(double minX, double minY, double maxX, double maxY,
                                          double pageWidth, double pageHeight) {
        double x = clamp(minX / pageWidth);
        double y = clamp(minY / pageHeight);
        double width = clamp(maxX / pageWidth) - x;
        double height = clamp(maxY / pageHeight) - y;
        return new BoundingBox(x, y, Math.max(0, width), Math.max(0, height));
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash extracted image", error);
        }
    }
}
