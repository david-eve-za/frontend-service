package com.glez.frontendservice.pdf.util;

import com.glez.frontendservice.pdf.model.PDFMetadata;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;

public class PDFUtils {

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
        if (info.getCreationDate() != null) {
            metadata.setCreationDate(info.getCreationDate().toInstant());
        }
        if (info.getModificationDate() != null) {
            metadata.setModificationDate(info.getModificationDate().toInstant());
        }
        metadata.setTotalPages(document.getNumberOfPages());

        return metadata;
    }

    public static void applyMetadata(PDDocument document, PDFMetadata metadata) {
        if (document == null) {
            throw new IllegalArgumentException("Input document cannot be null.");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Input metadata cannot be null.");
        }

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

        document.setDocumentInformation(info);
    }
}