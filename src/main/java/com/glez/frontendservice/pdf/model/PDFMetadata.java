package com.glez.frontendservice.pdf.model;

import lombok.Getter;
import lombok.NoArgsConstructor; // Added for clarity
import lombok.Setter;
import org.json.JSONObject;

// import java.util.Calendar; // Replaced by java.time.Instant
import java.time.Instant;    // Using modern Java Date/Time API

@Getter
@Setter
@NoArgsConstructor // Explicitly declare the no-argument constructor used by fromJson
public class PDFMetadata {
    private String title;
    private String author;
    private String creator;
    private String producer;
    private Instant creationDate;     // Changed from Calendar to Instant
    private Instant modificationDate; // Changed from Calendar to Instant
    private int totalPages;

    // A sentinel value to represent a non-existent or null timestamp from JSON.
    // Assumes valid timestamps are non-negative.
    private static final long NULL_TIMESTAMP_SENTINEL = -1L;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("title", title);
        json.put("author", author);
        json.put("creator", creator);
        json.put("producer", producer);

        // Convert Instant to epoch milliseconds.
        // If date is null, org.json.JSONObject.put(key, null) will remove the key,
        // so it won't be present in the JSON, which is a clean way to handle optional fields.
        json.put("creationDate", creationDate != null ? creationDate.toEpochMilli() : null);
        json.put("modificationDate", modificationDate != null ? modificationDate.toEpochMilli() : null);
        json.put("totalPages", totalPages);
        return json;
    }

    public static PDFMetadata fromJson(JSONObject json) {
        PDFMetadata metadata = new PDFMetadata(); // Relies on NoArgsConstructor

        metadata.setTitle(json.optString("title", null));
        metadata.setAuthor(json.optString("author", null));
        metadata.setCreator(json.optString("creator", null));
        metadata.setProducer(json.optString("producer", null));

        // Use optInt for totalPages for robustness; defaults to 0 if key is missing or not an int.
        metadata.setTotalPages(json.optInt("totalPages"));

        // Handle optional Instant fields using optLong with a sentinel.
        // If optLong returns the sentinel, it means the key was absent,
        // not a number, or JSONObject.NULL. In these cases, the Instant field remains null.
        long creationTimestamp = json.optLong("creationDate", NULL_TIMESTAMP_SENTINEL);
        if (creationTimestamp != NULL_TIMESTAMP_SENTINEL) {
            metadata.setCreationDate(Instant.ofEpochMilli(creationTimestamp));
        } else {
            metadata.setCreationDate(null); // Ensure it's null if sentinel was returned
        }

        long modificationTimestamp = json.optLong("modificationDate", NULL_TIMESTAMP_SENTINEL);
        if (modificationTimestamp != NULL_TIMESTAMP_SENTINEL) {
            metadata.setModificationDate(Instant.ofEpochMilli(modificationTimestamp));
        } else {
            metadata.setModificationDate(null); // Ensure it's null if sentinel was returned
        }

        return metadata;
    }
}