package com.glez.frontendservice.pdf.converter;

import com.glez.frontendservice.pdf.model.PDFDocument;
import com.glez.frontendservice.pdf.model.PDFImage;
import com.glez.frontendservice.pdf.model.PDFPage;
import com.glez.frontendservice.pdf.model.StyledText;
import com.glez.frontendservice.pdf.util.PDFUtils;
import com.glez.frontendservice.pdf.util.StyleUtils;
import lombok.Cleanup;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;

public class PDFToJsonConverter {

    private static final Logger logger = LoggerFactory.getLogger(PDFToJsonConverter.class);
    private static final int JSON_INDENT_FACTOR = 4;

    public static void convert(String pdfPath, String jsonPath) throws IOException {
        @Cleanup PDDocument document = Loader.loadPDF(new File(pdfPath));
        PDFDocument pdfDocument = extractPDFContent(document);
        saveAsJson(pdfDocument, jsonPath);
    }

    public static PDFDocument convertPdfToDocumentModel(InputStream pdfInputStream) throws IOException {
        @Cleanup PDDocument document = Loader.loadPDF(pdfInputStream.readAllBytes());
        return extractPDFContent(document);
    }

    private static PDFDocument extractPDFContent(PDDocument document) throws IOException {
        PDFDocument pdfDocument = new PDFDocument();
        pdfDocument.setMetadata(PDFUtils.extractMetadata(document));

        List<PDFPage> pages = new ArrayList<>();
        for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
            PDPage pdPage = document.getPage(pageNum);
            PDFPage customPage = new PDFPage();

            customPage.setPageNumber(pageNum + 1);

            PDRectangle mediaBox = pdPage.getMediaBox();
            customPage.setWidth(mediaBox.getWidth());
            customPage.setHeight(mediaBox.getHeight());

            StyleAwareTextStripper stripper = new StyleAwareTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageNum + 1);
            stripper.setEndPage(pageNum + 1);

            stripper.writeText(document, new StringWriter());
            customPage.setTexts(stripper.getStyledTexts());

            customPage.setImages(extractImagesFromPage(pdPage, pageNum));
            pages.add(customPage);
        }

        pdfDocument.setPages(pages);
        return pdfDocument;
    }

    private static List<PDFImage> extractImagesFromPage(PDPage pdPage, int pageNum) throws IOException {
        List<PDFImage> images = new ArrayList<>();
        PDResources resources = pdPage.getResources();
        int imageCount = 1;

        for (var name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) {
                PDImageXObject imageXObject = (PDImageXObject) resources.getXObject(name);

                PDFImage pdfImage = new PDFImage();
                pdfImage.setName(String.format("image_%d_%d.%s", pageNum + 1, imageCount, imageXObject.getSuffix()));
                pdfImage.setWidth(imageXObject.getWidth());
                pdfImage.setHeight(imageXObject.getHeight());
                pdfImage.setFormat(imageXObject.getSuffix());

                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    BufferedImage bufferedImage = imageXObject.getImage();
                    ImageIO.write(bufferedImage, imageXObject.getSuffix(), baos);
                    pdfImage.setData(Base64.getEncoder().encodeToString(baos.toByteArray()));
                } catch (IOException e) {
                    logger.error("Error converting image to Base64", e);
                }

                images.add(pdfImage);
                imageCount++;
            }
        }
        return images;
    }

    private static void saveAsJson(PDFDocument pdfDocument, String jsonPath) throws IOException {
        JSONObject json = pdfDocument.toJson();
        try (FileWriter fileWriter = new FileWriter(jsonPath)) {
            fileWriter.write(json.toString(JSON_INDENT_FACTOR));
        }
    }

    private static class StyleAwareTextStripper extends PDFTextStripper {
        private final List<StyledText> styledTextsInternal = new ArrayList<>();

        public StyleAwareTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            for (TextPosition textPosition : textPositions) {
                if (textPosition == null) continue;

                PDFont font = textPosition.getFont();
                String fontName = (font != null && font.getName() != null) ? font.getName() : "Unknown";

                StyledText styledText = new StyledText();
                styledText.setText(textPosition.getUnicode());
                styledText.setFontName(fontName);
                styledText.setFontSize(textPosition.getFontSizeInPt());

                PDGraphicsState gs = getGraphicsState();

                styledText.setBold(font != null && StyleUtils.isBold(font));
                styledText.setItalic(font != null && StyleUtils.isItalic(font));

                styledText.setX(textPosition.getX());
                styledText.setY(textPosition.getY());
                styledText.setWidth(textPosition.getWidthDirAdj());
                styledText.setHeight(textPosition.getHeightDir());

                PDColor pdColor = gs.getNonStrokingColor();

                if (pdColor != null) {
                    try {
                        PDColorSpace cs = pdColor.getColorSpace();
                        float[] components = pdColor.getComponents();

                        if (cs instanceof PDDeviceRGB) {
                            if (components != null && components.length >= 3) {
                                styledText.setRed(components[0]);
                                styledText.setGreen(components[1]);
                                styledText.setBlue(components[2]);
                            } else {
                                setDefaultColor(styledText);
                            }
                        } else if (cs instanceof PDDeviceGray) {
                            if (components != null && components.length >= 1) {
                                styledText.setRed(components[0]);
                                styledText.setGreen(components[0]);
                                styledText.setBlue(components[0]);
                            } else {
                                setDefaultColor(styledText);
                            }
                        } else {
                            int rgb = pdColor.toRGB();
                            styledText.setRed(((rgb >> 16) & 0xFF) / 255.0f);
                            styledText.setGreen(((rgb >> 8) & 0xFF) / 255.0f);
                            styledText.setBlue((rgb & 0xFF) / 255.0f);
                        }
                    } catch (IOException e) {
                        logger.warn("Could not process color for text '{}': {}", textPosition.getUnicode(), e.getMessage());
                        setDefaultColor(styledText);
                    }
                } else {
                    setDefaultColor(styledText);
                }

                styledTextsInternal.add(styledText);
            }
        }

        private void setDefaultColor(StyledText styledText) {
            styledText.setRed(0.0f);
            styledText.setGreen(0.0f);
            styledText.setBlue(0.0f);
        }

        public List<StyledText> getStyledTexts() {
            return new ArrayList<>(styledTextsInternal);
        }
    }
}