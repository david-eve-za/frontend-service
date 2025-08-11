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

    // Configuration for file storage
    private static final String UPLOAD_DIR_NAME = "upload-dir"; // Made it a constant
    private final Path fileStorageLocation;

    // Supported image types for validation
    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif"
    );

    public FileProcessingService() {
        // In a real application, UPLOAD_DIR_NAME should be configurable (e.g., via application.properties)
        this.fileStorageLocation = Paths.get(UPLOAD_DIR_NAME).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
            logger.info("Storage directory initialized at: {}", this.fileStorageLocation);
        } catch (IOException ex) { // More specific exception
            // If directory creation is critical, a runtime exception stops the service from starting in a bad state.
            logger.error("Could not create the upload directory: {}. Service might not function correctly.", this.fileStorageLocation, ex);
            throw new RuntimeException("Failed to create storage directory: " + this.fileStorageLocation, ex);
            // Consider a custom exception like:
            // throw new FileStorageInitializationException("Failed to create storage directory: " + this.fileStorageLocation, ex);
        }
    }

    /**
     * Processes the uploaded MultipartFile.
     *
     * @param file The MultipartFile to process.
     * @return A message indicating the result of the processing.
     * @throws IOException If an error occurs while reading the file.
     */
    public String processFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            logger.warn("Attempted to process an empty file.");
            return "Error: The file is empty.";
        }

        // Clean and validate the original filename
        String originalFileName = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        if (originalFileName.contains("..")) {
            logger.error("Invalid filename (contains '..'): {}", originalFileName);
            return String.format("Error: The filename '%s' contains an invalid path sequence.", originalFileName);
        }

        String contentType = file.getContentType();
        long size = file.getSize();

        logger.info("Processing file: Name='{}', Type='{}', Size='{} bytes'", originalFileName, contentType, size);

        // Example: Read the content of the file as bytes
        byte[] fileBytes;
        try (InputStream inputStream = file.getInputStream()) {
            fileBytes = inputStream.readAllBytes();
            logger.info("Successfully read {} bytes from file '{}'.", fileBytes.length, originalFileName);
            // Here you would typically process these bytes:
            // - Analyze a CSV
            // - Validate image content
            // - Send to another microservice or message queue
            // - etc.
        } catch (IOException e) {
            logger.error("Error reading content from file: {}", originalFileName, e);
            throw new IOException(String.format("Error reading content from file: %s", originalFileName), e);
        }

        // Optional: Save the file to the server.
        // This demonstrates how the saving logic can be cleanly separated.
        // You can uncomment the following block if you need to store the file.
        /*
        try {
            Path storedFilePath = storeFileOnServer(file, originalFileName);
            logger.info("File '{}' was optionally saved to: {}", originalFileName, storedFilePath);
            // You might want to include storedFilePath in the return message or use it otherwise.
        } catch (IOException e) {
            // Log the error, but decide if this should make the whole processFile operation fail.
            // For now, we'll just log a warning if optional saving fails.
            logger.warn("Optional: Could not save file '{}' to server. Processing of content continues. Error: {}", originalFileName, e.getMessage());
        }
        */

        // Further business logic can be added here, e.g.:
        // - Saving metadata to a database.
        // - Triggering other processes based on the file content.

        return String.format("File '%s' (Size: %d bytes) processed. Content read successfully.", originalFileName, size);
    }

    /**
     * Stores the given MultipartFile on the server in the configured storage location.
     * Generates a unique filename to avoid collisions and security issues.
     *
     * @param file           The MultipartFile to store.
     * @param originalFileName The cleaned original filename (used for extension).
     * @return The Path where the file was stored.
     * @throws IOException If an error occurs during file storage.
     */
    private Path storeFileOnServer(MultipartFile file, String originalFileName) throws IOException {
        // Generate a unique filename to prevent overwrites and issues with special characters.
        // Using originalFileName (already cleaned) primarily for its extension.
        String extension = StringUtils.getFilenameExtension(originalFileName);
        String uniqueFileName = UUID.randomUUID().toString() + (extension != null && !extension.isEmpty() ? "." + extension : "");

        Path targetLocation = this.fileStorageLocation.resolve(uniqueFileName);

        try (InputStream inputStream = file.getInputStream()) { // Get a new stream for saving
            Files.copy(inputStream, targetLocation, StandardCopyOption.REPLACE_EXISTING);
            logger.info("File stored successfully at: {}", targetLocation);
            return targetLocation;
        } catch (IOException ex) {
            logger.error("Could not store file {} at {}. Original filename: {}. Error: {}",
                    uniqueFileName, targetLocation, originalFileName, ex.getMessage(), ex);
            // Wrap and re-throw to provide context
            throw new IOException(String.format("Could not store file %s (original: %s)", uniqueFileName, originalFileName), ex);
        }
    }

    /**
     * Validates if the MultipartFile is a supported image type.
     *
     * @param file The MultipartFile to validate.
     * @return true if the file is a supported image type, false otherwise.
     */
    public boolean isValidImageType(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType != null && SUPPORTED_IMAGE_TYPES.contains(contentType);
    }
}