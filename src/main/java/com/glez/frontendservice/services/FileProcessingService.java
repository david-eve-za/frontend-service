package com.glez.frontendservice.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FileProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingService.class);

    private static final String UPLOAD_DIR_NAME = "upload-dir";
    private final Path fileStorageLocation;

    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif"
    );

    public FileProcessingService() {
        this.fileStorageLocation = Paths.get(UPLOAD_DIR_NAME).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
            logger.info("Storage directory initialized at: {}", this.fileStorageLocation);
        } catch (IOException ex) {
            logger.error("Could not create the upload directory: {}. Service might not function correctly.", this.fileStorageLocation, ex);
            throw new RuntimeException("Failed to create storage directory: " + this.fileStorageLocation, ex);
        }
    }

    public String processFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            logger.warn("Attempted to process an empty file.");
            return "Error: The file is empty.";
        }

        String originalFileName = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        if (originalFileName.contains("..")) {
            logger.error("Invalid filename (contains '..'): {}", originalFileName);
            return String.format("Error: The filename '%s' contains an invalid path sequence.", originalFileName);
        }

        String contentType = file.getContentType();
        long size = file.getSize();

        logger.info("Processing file: Name='{}', Type='{}', Size='{} bytes'", originalFileName, contentType, size);

        byte[] fileBytes;
        try (InputStream inputStream = file.getInputStream()) {
            fileBytes = inputStream.readAllBytes();
            logger.info("Successfully read {} bytes from file '{}'.", fileBytes.length, originalFileName);
        } catch (IOException e) {
            logger.error("Error reading content from file: {}", originalFileName, e);
            throw new IOException(String.format("Error reading content from file: %s", originalFileName), e);
        }

        return String.format("File '%s' (Size: %d bytes) processed. Content read successfully.", originalFileName, size);
    }

    private Path storeFileOnServer(MultipartFile file, String originalFileName) throws IOException {
        String extension = StringUtils.getFilenameExtension(originalFileName);
        String uniqueFileName = UUID.randomUUID().toString() + (extension != null && !extension.isEmpty() ? "." + extension : "");

        Path targetLocation = this.fileStorageLocation.resolve(uniqueFileName);

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetLocation, StandardCopyOption.REPLACE_EXISTING);
            logger.info("File stored successfully at: {}", targetLocation);
            return targetLocation;
        } catch (IOException ex) {
            logger.error("Could not store file {} at {}. Original filename: {}. Error: {}",
                    uniqueFileName, targetLocation, originalFileName, ex.getMessage(), ex);
            throw new IOException(String.format("Could not store file %s (original: %s)", uniqueFileName, originalFileName), ex);
        }
    }

    public boolean isValidImageType(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType != null && SUPPORTED_IMAGE_TYPES.contains(contentType);
    }
}