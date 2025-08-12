package com.glez.frontendservice.pdf.converter;

import com.glez.frontendservice.pdf.model.PDFDocument;
import com.glez.frontendservice.pdf.model.PDFImage;
import com.glez.frontendservice.pdf.model.PDFPage;
import com.glez.frontendservice.pdf.model.StyledText;
import com.glez.frontendservice.pdf.util.PDFUtils;
import lombok.Cleanup;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Map;

@Component
public class JsonToPDFConverter implements PdfConverter {

    private static final Map<String, PDType1Font> FONT_MAP = Map.of(
            "TIMES_ROMAN", new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN),
            "TIMES_BOLD", new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD),
            "TIMES_ITALIC", new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC),
            "TIMES_BOLD_ITALIC", new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC)
    );

    @Override
    public PDFDocument convertPdfToDocumentModel(InputStream pdfInputStream) throws IOException {
        throw new UnsupportedOperationException("This converter does not support PDF to JSON conversion.");
    }

    @Override
    public void convertJsonToPdf(PDFDocument pdfDocument, OutputStream outputStream) throws IOException {
        @Cleanup PDDocument document = new PDDocument();
        PDFUtils.applyMetadata(document, pdfDocument.getMetadata());

        for (PDFPage pageData : pdfDocument.getPages()) {
            PDPage page = new PDPage(new PDRectangle(pageData.getWidth(), pageData.getHeight()));
            document.addPage(page);

            @Cleanup PDPageContentStream contentStream = new PDPageContentStream(document, page);
            for (StyledText text : pageData.getTexts()) {
                addStyledText(contentStream, text);
            }

            for (PDFImage image : pageData.getImages()) {
                addImage(document, contentStream, image);
            }
        }
        document.save(outputStream);
    }

    private void addStyledText(PDPageContentStream contentStream, StyledText text) throws IOException {
        contentStream.beginText();

        PDType1Font font = resolveFont(text);
        contentStream.setFont(font, text.getFontSize());

        if (text.getRed() >= 0 && text.getGreen() >= 0 && text.getBlue() >= 0) {
            contentStream.setNonStrokingColor(text.getRed(), text.getGreen(), text.getBlue());
        }

        contentStream.newLineAtOffset(text.getX(), text.getY());
        contentStream.showText(text.getText());
        contentStream.endText();
    }

    private PDType1Font resolveFont(StyledText text) {
        if (text.isBold() && text.isItalic()) {
            return FONT_MAP.get("TIMES_BOLD_ITALIC");
        } else if (text.isBold()) {
            return FONT_MAP.get("TIMES_BOLD");
        } else if (text.isItalic()) {
            return FONT_MAP.get("TIMES_ITALIC");
        } else {
            return FONT_MAP.get("TIMES_ROMAN");
        }
    }

    private void addImage(PDDocument document, PDPageContentStream contentStream, PDFImage image) throws IOException {
        byte[] imageData = Base64.getDecoder().decode(image.getData());
        PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                document,
                imageData,
                image.getName()
        );

        contentStream.drawImage(
                pdImage,
                image.getX(),
                image.getY(),
                image.getWidth(),
                image.getHeight()
        );
    }
}
