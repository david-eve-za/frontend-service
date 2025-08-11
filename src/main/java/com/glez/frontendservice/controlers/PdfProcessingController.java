package com.glez.frontendservice.controlers;

import com.glez.frontendservice.pdf.converter.PDFToJsonConverter;
import com.glez.frontendservice.pdf.model.PDFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/pdf")
public class PdfProcessingController {

    private static final Logger logger = LoggerFactory.getLogger(PdfProcessingController.class);

    @PostMapping(value = "/extract-content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @CrossOrigin(origins = "*")
    public ResponseEntity<?> extractContentFromPdf(@RequestParam("file") MultipartFile pdfFile) {
        if (pdfFile.isEmpty()) {
            logger.warn("Empty PDF file upload attempt.");
            return ResponseEntity.badRequest().body("PDF file cannot be empty.");
        }

        String contentType = pdfFile.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            logger.warn("Received file with unexpected content type: {}. Attempting to process.", contentType);
        }

        try (InputStream inputStream = pdfFile.getInputStream()) {
            PDFDocument pdfDocument = PDFToJsonConverter.convertPdfToDocumentModel(inputStream);
            return ResponseEntity.ok(pdfDocument);
        } catch (IOException e) {
            logger.error("Error processing PDF file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing PDF file. Details: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error during PDF content extraction: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred while processing the PDF.");
        }
    }
}