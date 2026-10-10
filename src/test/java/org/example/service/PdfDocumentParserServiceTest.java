package org.example.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.example.config.DocumentChunkConfig;
import org.example.config.PdfImageProperties;
import org.example.dto.PdfDocumentParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDocumentParserServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsImageAndNormalizedCoordinates() throws Exception {
        Path pdf = temporaryDirectory.resolve("sample.pdf");
        createPdf(pdf);

        DocumentChunkConfig chunks = new DocumentChunkConfig();
        chunks.setMaxSize(800);
        chunks.setOverlap(100);
        PdfImageProperties images = new PdfImageProperties();
        images.setMinWidth(20);
        images.setMinHeight(20);
        images.setMinPageAreaRatio(0.001);

        PdfDocumentParseResult result = new PdfDocumentParserService(chunks, images)
                .parse(pdf.toFile(), "uploads/sample.pdf");

        assertThat(result.getPageCount()).isEqualTo(1);
        assertThat(result.getTextChunks()).isEmpty();
        assertThat(result.getImages()).hasSize(1);
        assertThat(result.getImages().get(0).getBoundingBox().x()).isBetween(0.0, 1.0);
        assertThat(result.getImages().get(0).getBoundingBox().y()).isBetween(0.0, 1.0);
        assertThat(result.getImages().get(0).getPngData()).isNotEmpty();
    }

    @Test
    void associatesNearestTextAndImageBidirectionallyWithoutRecursion() {
        DocumentChunkConfig chunks = new DocumentChunkConfig();
        PdfImageProperties images = new PdfImageProperties();
        images.setRelatedTextLimit(1);
        PdfDocumentParserService parser = new PdfDocumentParserService(chunks, images);
        PdfDocumentParseResult.TextChunk nearest = new PdfDocumentParseResult.TextChunk(
                "text-1", "nearest caption", 1, 0, "section-1",
                new PdfDocumentParseResult.BoundingBox(0.1, 0.5, 0.8, 0.1));
        PdfDocumentParseResult.TextChunk distant = new PdfDocumentParseResult.TextChunk(
                "text-2", "distant text", 1, 1, "section-1",
                new PdfDocumentParseResult.BoundingBox(0.1, 0.05, 0.8, 0.1));
        PdfDocumentParseResult.ImageAsset image = new PdfDocumentParseResult.ImageAsset(
                "image-1", new byte[]{1}, 1, 100, 100, "hash", "section-1",
                new PdfDocumentParseResult.BoundingBox(0.1, 0.3, 0.8, 0.15));

        parser.associate(List.of(nearest, distant), new ArrayList<>(List.of(image)));

        assertThat(image.getRelatedChunkIds()).containsExactly("text-1");
        assertThat(nearest.getRelatedImageIds()).containsExactly("image-1");
        assertThat(distant.getRelatedImageIds()).isEmpty();
        assertThat(image.getNearbyText()).isEqualTo("nearest caption");
    }

    private void createPdf(Path target) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            BufferedImage image = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLUE);
            graphics.fillRect(20, 20, 200, 120);
            graphics.dispose();

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, image), 72, 420, 360, 240);
            }
            document.save(target.toFile());
        }
    }
}
