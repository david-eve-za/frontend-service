package com.glez.frontendservice.pdf.util;

import com.glez.frontendservice.pdf.model.PDFMetadata;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;

// Required for Calendar types in PDDocumentInformation


/**
 * Utility class for extracting and applying metadata to PDF documents.
 */
public class PDFUtils {

    /**
     * Extracts metadata from the given PDF document.
     *
     * @param document The PDDocument from which to extract metadata. Must not be null.
     * @return A {@link PDFMetadata} object populated with information from the document.
     *         Returns default/null values for fields not present in the document.
     */
    public static PDFMetadata extractMetadata(PDDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Input document cannot be null.");
        }
        PDDocumentInformation info = document.getDocumentInformation();
        PDFMetadata metadata = new PDFMetadata();

        metadata.setTitle(info.getTitle());
        metadata.setAuthor(info.getAuthor());
        metadata.setCreator(info.getCreator());
        metadata.setProducer(info.getProducer());
        metadata.setCreationDate(info.getCreationDate().toInstant());
        metadata.setModificationDate(info.getModificationDate().toInstant());
        metadata.setTotalPages(document.getNumberOfPages());

        return metadata;
    }

    /**
     * Applies the provided metadata to the PDF document.
     * This method updates the document's existing metadata. If a field in the provided
     * {@link PDFMetadata} object is not null, its value will be set on the document,
     * potentially overwriting an existing value. Fields that are null in the
     * {@link PDFMetadata} object will not change the corresponding fields in the document.
     * Existing metadata fields in the document not covered by {@link PDFMetadata} will be preserved.
     *
     * @param document The PDDocument to which metadata will be applied. Must not be null.
     * @param metadata The {@link PDFMetadata} object containing the metadata to apply. Must not be null.
     */
    public static void applyMetadata(PDDocument document, PDFMetadata metadata) {
        if (document == null) {
            throw new IllegalArgumentException("Input document cannot be null.");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Input metadata cannot be null.");
        }

        // Retrieve the existing document information.
        // PDDocument.getDocumentInformation() creates a new PDDocumentInformation instance
        // if one doesn't already exist in the document, so 'info' will not be null.
        PDDocumentInformation info = document.getDocumentInformation();

        if (metadata.getTitle() != null) {
            info.setTitle(metadata.getTitle());
        }
        if (metadata.getAuthor() != null) {
            info.setAuthor(metadata.getAuthor());
        }
        if (metadata.getCreator() != null) {
            info.setCreator(metadata.getCreator());
        }
        if (metadata.getProducer() != null) {
            info.setProducer(metadata.getProducer());
        }
        if (metadata.getCreationDate() != null) {
            info.setCreationDate(GregorianCalendar.from(ZonedDateTime.ofInstant(metadata.getCreationDate(), ZoneId.systemDefault())));
        }
        if (metadata.getModificationDate() != null) {
            info.setModificationDate(GregorianCalendar.from(ZonedDateTime.ofInstant(metadata.getModificationDate(), ZoneId.systemDefault())));
        }
        // Note: TotalPages is typically a derived property of the document's structure
        // and not set via PDDocumentInformation. It's correctly handled in extractMetadata.

        // Set the (potentially modified) document information back to the document.
        // This step is crucial as it updates the PDF's trailer dictionary.
        document.setDocumentInformation(info);
    }
}