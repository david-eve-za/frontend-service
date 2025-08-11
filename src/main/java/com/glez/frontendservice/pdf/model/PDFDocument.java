package com.glez.frontendservice.pdf.model;

import lombok.Getter;
import lombok.Setter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects; // For Objects.nonNull
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Getter
@Setter
public class PDFDocument {
    private PDFMetadata metadata;
    private List<PDFPage> pages = new ArrayList<>(); // Initialized, so 'pages' itself won't be null

    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        // Handle potential null metadata
        if (this.metadata != null) {
            json.put("metadata", this.metadata.toJson());
        }
        // If metadata is null, the "metadata" key will be absent in the output JSON.
        // Alternatively, you could put JSONObject.NULL if the key must always be present:
        // else { json.put("metadata", JSONObject.NULL); }


        // Convert pages to JSONArray using streams for conciseness.
        // this.pages is guaranteed non-null due to initialization.
        // An empty pages list will correctly result in an empty JSONArray.
        List<JSONObject> pageJsonObjects = this.pages.stream()
                .filter(Objects::nonNull) // Filter out any null PDFPage objects within the list
                .map(PDFPage::toJson)     // Convert each non-null PDFPage to its JSON representation
                .collect(Collectors.toList());
        json.put("pages", new JSONArray(pageJsonObjects));

        return json;
    }

    public static PDFDocument fromJson(JSONObject json) {
        PDFDocument document = new PDFDocument();

        // Safely extract metadata
        if (json.has("metadata") && !json.isNull("metadata")) {
            JSONObject metadataJson = json.getJSONObject("metadata");
            document.setMetadata(PDFMetadata.fromJson(metadataJson));
        }
        // If "metadata" is missing or JSON null, document.metadata will remain null.

        // Safely extract pages
        if (json.has("pages") && !json.isNull("pages")) {
            JSONArray pagesArray = json.getJSONArray("pages");

            List<PDFPage> parsedPages = IntStream.range(0, pagesArray.length())
                    .filter(i -> !pagesArray.isNull(i)) // Ensure the JSON object at index i is not JSONObject.NULL
                    .mapToObj(pagesArray::getJSONObject) // Get JSONObject for each valid index
                    .map(PDFPage::fromJson)              // Convert JSONObject to PDFPage
                    .collect(Collectors.toList());
            document.setPages(parsedPages);
        }
        // If "pages" key is absent, JSON null, or an empty array,
        // document.pages will be an empty list (either the initial one or a new empty one).

        return document;
    }
}