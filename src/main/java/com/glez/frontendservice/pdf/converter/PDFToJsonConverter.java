package com.glez.frontendservice.pdf.converter;

import com.glez.frontendservice.pdf.model.PDFDocument;
import com.glez.frontendservice.pdf.model.PDFImage;
import com.glez.frontendservice.pdf.model.PDFPage;
import com.glez.frontendservice.pdf.model.StyledText;
import com.glez.frontendservice.pdf.util.PDFUtils;
import com.glez.frontendservice.pdf.util.StyleUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
// Consider adding PDDeviceCMYK if specific CMYK handling beyond toRGB() is needed
// import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
// Consider adding a logger, e.g., SLF4J
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

public class PDFToJsonConverter {

    // private static final Logger logger = LoggerFactory.getLogger(PDFToJsonConverter.class); // Example logger
    private static final int JSON_INDENT_FACTOR = 4; // For pretty-printing JSON

    /**
     * Converts a PDF file to a JSON representation.
     *
     * @param pdfPath  Path to the input PDF file.
     * @param jsonPath Path to save the output JSON file.
     * @throws IOException If an I/O error occurs during reading the PDF or writing the JSON.
     */
    public static void convert(String pdfPath, String jsonPath) throws IOException {
        // Using try-with-resources to ensure the PDDocument is closed automatically.
        // Updated to use Loader.loadPDF for PDFBox 3.x
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            PDFDocument pdfDocument = extractPDFContent(document);
            saveAsJson(pdfDocument, jsonPath);
        }
    }

    /**
     * NUEVO MÉTODO PÚBLICO
     * Convierte un PDF desde un InputStream a un modelo PDFDocument.
     * Este método es adecuado para usar en un endpoint de API donde el PDF se recibe como un stream.
     *
     * @param pdfInputStream El InputStream del archivo PDF.
     * @return Un objeto PDFDocument poblado con los datos extraídos.
     * @throws IOException Si ocurre un error de E/S durante la lectura o procesamiento del PDF.
     */
    public static PDFDocument convertPdfToDocumentModel(InputStream pdfInputStream) throws IOException {
        // Usar try-with-resources para asegurar que PDDocument se cierre automáticamente.
        try (PDDocument document = Loader.loadPDF(pdfInputStream.readAllBytes())) {
            return extractPDFContent(document); // Reutiliza la lógica de extracción privada existente
        }
    }

    /**
     * Extracts content (metadata, pages, text, images) from the PDDocument.
     *
     * @param document The loaded PDFBox PDDocument.
     * @return A PDFDocument model object populated with extracted data.
     * @throws IOException If an I/O error occurs during content extraction.
     */
    private static PDFDocument extractPDFContent(PDDocument document) throws IOException {
        PDFDocument pdfDocument = new PDFDocument();
        pdfDocument.setMetadata(PDFUtils.extractMetadata(document));

        List<PDFPage> pages = new ArrayList<>();
        for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
            PDPage pdPage = document.getPage(pageNum);
            PDFPage customPage = new PDFPage(); // Page numbers are 1-based

            // Pagenumber
            customPage.setPageNumber(pageNum + 1);

            // Extract page dimensions
            PDRectangle mediaBox = pdPage.getMediaBox();
            customPage.setWidth(mediaBox.getWidth());
            customPage.setHeight(mediaBox.getHeight());

            // Extract text with styling information for the current page
            // A new stripper instance is created for each page to ensure text styles
            // are isolated per page.
            StyleAwareTextStripper stripper = new StyleAwareTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageNum + 1); // Process only the current page
            stripper.setEndPage(pageNum + 1);

            // The StringWriter is a dummy writer; PDFTextStripper's overridden methods
            // will capture the text and styling information.
            stripper.writeText(document, new StringWriter());
            customPage.setTexts(stripper.getStyledTexts()); // getStyledTexts() returns a copy

            // Extract images from the current page
            customPage.setImages(extractImagesFromPage(pdPage, pageNum));
            pages.add(customPage);
        }

        pdfDocument.setPages(pages);
        return pdfDocument;
    }

    /**
     * Extracts images from a specific page of the PDF.
     *
     * @param pdPage  The PDFBox PDPage object.
     * @param pageNum The 0-based page number, used for naming images.
     * @return A list of PDFImage objects found on the page.
     * @throws IOException If an I/O error occurs during image extraction.
     */
    private static List<PDFImage> extractImagesFromPage(PDPage pdPage, int pageNum) throws IOException {
        List<PDFImage> images = new ArrayList<>();
        PDResources resources = pdPage.getResources();
        int imageCount = 1; // Counter for naming images uniquely per page

        for (var name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) {
                PDImageXObject imageXObject = (PDImageXObject) resources.getXObject(name);

                PDFImage pdfImage = new PDFImage();
                // Consistent image naming: image_pageNumber_imageIndex.format
                pdfImage.setName(String.format("image_%d_%d.%s", pageNum + 1, imageCount, imageXObject.getSuffix()));
                pdfImage.setWidth(imageXObject.getWidth());
                pdfImage.setHeight(imageXObject.getHeight());
                pdfImage.setFormat(imageXObject.getSuffix());

                // Convert image data to Base64 string
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    BufferedImage bufferedImage = imageXObject.getImage();
                    ImageIO.write(bufferedImage, imageXObject.getSuffix(), baos);
                    pdfImage.setData(Base64.getEncoder().encodeToString(baos.toByteArray()));
                }

                images.add(pdfImage);
                imageCount++;
            }
        }
        return images;
    }

    /**
     * Saves the structured PDFDocument object as a JSON file.
     *
     * @param pdfDocument The PDFDocument model to serialize.
     * @param jsonPath    The path where the JSON file will be saved.
     * @throws IOException If an I/O error occurs while writing the file.
     */
    private static void saveAsJson(PDFDocument pdfDocument, String jsonPath) throws IOException {
        JSONObject json = pdfDocument.toJson(); // Assumes PDFDocument has a toJson() method
        try (FileWriter fileWriter = new FileWriter(jsonPath)) {
            fileWriter.write(json.toString(JSON_INDENT_FACTOR)); // Pretty print JSON
        }
    }

    /**
     * A custom PDFTextStripper that captures text along with its styling information.
     */
    private static class StyleAwareTextStripper extends PDFTextStripper {
        private final List<StyledText> styledTextsInternal = new ArrayList<>();

        public StyleAwareTextStripper() throws IOException {
            super();
            // Additional setup for the stripper can be done here if needed.
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

                // --- Corrected Style Extraction for PDFBox 3.x ---
                PDGraphicsState gs = getGraphicsState(); // Method from PDFTextStripper
                PDTextState textState = gs.getTextState();

                // Style detection (bold, italic) relies on utility methods.
                // Ensure StyleUtils methods are null-safe or check font != null.
                styledText.setBold(font != null && StyleUtils.isBold(font));
                styledText.setItalic(font != null && StyleUtils.isItalic(font));

                // Positional information
                styledText.setX(textPosition.getX());
                styledText.setY(textPosition.getY());
                styledText.setWidth(textPosition.getWidthDirAdj());
                styledText.setHeight(textPosition.getHeightDir());

                // --- Corrected Color Extraction ---
                PDColor pdColor = gs.getNonStrokingColor(); // Get fill color

                if (pdColor != null) {
                    try {
                        PDColorSpace cs = pdColor.getColorSpace();
                        float[] components = pdColor.getComponents(); // Components are typically 0-1f

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
                                styledText.setRed(components[0]);   // Gray is R=G=B
                                styledText.setGreen(components[0]);
                                styledText.setBlue(components[0]);
                            } else {
                                setDefaultColor(styledText);
                            }
                        } else {
                            // For other color spaces (CMYK, Indexed, Separation, etc.),
                            // attempt to convert to RGB.
                            // PDColor.toRGB() returns an int: (r << 16) | (g << 8) | b;
                            int rgb = pdColor.toRGB();
                            styledText.setRed(((rgb >> 16) & 0xFF) / 255.0f);
                            styledText.setGreen(((rgb >> 8) & 0xFF) / 255.0f);
                            styledText.setBlue((rgb & 0xFF) / 255.0f);
                        }
                    } catch (IOException e) {
                        // logger.warn("Could not process color for text '{}': {}", textPosition.getUnicode(), e.getMessage());
                        System.err.println("Error processing color for text '" + textPosition.getUnicode() + "': " + e.getMessage());
                        setDefaultColor(styledText);
                    }
                } else {
                    setDefaultColor(styledText); // Default to black if no color info
                }

                styledTextsInternal.add(styledText);
            }
        }

        /**
         * Sets a default color (black) for the StyledText object.
         *
         * @param styledText The StyledText object to modify.
         */
        private void setDefaultColor(StyledText styledText) {
            styledText.setRed(0.0f);
            styledText.setGreen(0.0f);
            styledText.setBlue(0.0f);
        }

        /**
         * Returns a copy of the styled texts extracted by this stripper.
         *
         * @return A new list containing the StyledText objects.
         */
        public List<StyledText> getStyledTexts() {
            // Return a copy to prevent external modification of the internal list.
            return new ArrayList<>(styledTextsInternal);
        }
    }
}