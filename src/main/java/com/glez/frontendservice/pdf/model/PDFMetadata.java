package com.glez.frontendservice.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.json.JSONObject;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PDFMetadata {
    private String title;
    private String author;
    private String creator;
    private String producer;
    private Instant creationDate;
    private Instant modificationDate;
    private int totalPages;

    private static final long NULL_TIMESTAMP_SENTINEL = -1L;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("title", title);
        json.put("author", author);
        json.put("creator", creator);
        json.put("producer", producer);
        json.put("creationDate", creationDate != null ? creationDate.toEpochMilli() : null);
        json.put("modificationDate", modificationDate != null ? modificationDate.toEpochMilli() : null);
        json.put("totalPages", totalPages);
        return json;
    }

    public static PDFMetadata fromJson(JSONObject json) {
        long creationTimestamp = json.optLong("creationDate", NULL_TIMESTAMP_SENTINEL);
        long modificationTimestamp = json.optLong("modificationDate", NULL_TIMESTAMP_SENTINEL);

        return PDFMetadata.builder()
                .title(json.optString("title", null))
                .author(json.optString("author", null))
                .creator(json.optString("creator", null))
                .producer(json.optString("producer", null))
                .totalPages(json.optInt("totalPages"))
                .creationDate(creationTimestamp != NULL_TIMESTAMP_SENTINEL ? Instant.ofEpochMilli(creationTimestamp) : null)
                .modificationDate(modificationTimestamp != NULL_TIMESTAMP_SENTINEL ? Instant.ofEpochMilli(modificationTimestamp) : null)
                .build();
    }
}