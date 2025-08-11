package com.glez.frontendservice.controlers; // O el paquete que prefieras para tus controladores

import com.glez.frontendservice.pdf.converter.PDFToJsonConverter;
import com.glez.frontendservice.pdf.model.PDFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
// Considera añadir un logger para un mejor manejo de logs en producción
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/pdf") // Ruta base para las APIs relacionadas con PDF
public class PdfProcessingController {

    // private static final Logger logger = LoggerFactory.getLogger(PdfProcessingController.class);

    @PostMapping(value = "/extract-content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @CrossOrigin(origins = "*")
    public ResponseEntity<?> extractContentFromPdf(@RequestParam("file") MultipartFile pdfFile) {
        if (pdfFile.isEmpty()) {
            // logger.warn("Se intentó subir un archivo PDF vacío.");
            return ResponseEntity.badRequest().body("El archivo PDF no puede estar vacío.");
        }

        String contentType = pdfFile.getContentType();
        // Es una buena práctica validar el tipo de contenido, aunque PDFBox podría manejar algunos errores.
        if (contentType == null || !contentType.equals("application/pdf")) {
            // logger.warn("Se recibió un archivo con un tipo de contenido no esperado: {}. Se intentará procesar.", contentType);
            System.err.println("Advertencia: El archivo recibido no parece ser un PDF (Content-Type: " + contentType + "). Se intentará procesar de todas formas.");
            // Podrías optar por rechazar el archivo aquí si eres estricto:
            // return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body("El archivo debe ser de tipo application/pdf.");
        }

        try (InputStream inputStream = pdfFile.getInputStream()) {
            // Llama al método público que hemos añadido en PDFToJsonConverter
            PDFDocument pdfDocument = PDFToJsonConverter.convertPdfToDocumentModel(inputStream);
            return ResponseEntity.ok(pdfDocument);
        } catch (IOException e) {
            // logger.error("Error al procesar el archivo PDF: {}", e.getMessage(), e);
            System.err.println("Error al procesar el archivo PDF: " + e.getMessage());
            // Proporciona un mensaje de error más genérico al cliente por seguridad
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error al procesar el archivo PDF. Detalles: " + e.getMessage());
        } catch (Exception e) { // Captura genérica para otros errores inesperados durante la conversión
            // logger.error("Error inesperado durante la extracción de contenido del PDF: {}", e.getMessage(), e);
            System.err.println("Error inesperado durante la extracción de contenido del PDF: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ocurrió un error inesperado al procesar el PDF.");
        }
    }
}