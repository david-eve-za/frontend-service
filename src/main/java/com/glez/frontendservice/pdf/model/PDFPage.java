package com.glez.frontendservice.pdf.model;

import lombok.Data; // Changed from Getter, Setter, RequiredArgsConstructor
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors; // Added for Collectors.toList()
import java.util.stream.IntStream;  // Added for IntStream

@Data // Consolidates Getter, Setter, RequiredArgsConstructor, ToString, EqualsAndHashCode
public class PDFPage {
    private int pageNumber;
    private float width;
    private float height;
    private List<StyledText> texts = new ArrayList<>();
    private List<PDFImage> images = new ArrayList<>();

    // No-arg constructor is implicitly provided by @Data (via @RequiredArgsConstructor with no final fields)
    // or you could add @NoArgsConstructor explicitly if preferred.

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("pageNumber", pageNumber);
        json.put("width", width);
        json.put("height", height);

        // Use streams for cleaner list transformation
        json.put("texts", new JSONArray(
                texts.stream()
                        .map(StyledText::toJson)
                        .collect(Collectors.toList())
        ));

        json.put("images", new JSONArray(
                images.stream()
                        .map(PDFImage::toJson)
                        .collect(Collectors.toList())
        ));

        return json;
    }

    public static PDFPage fromJson(JSONObject json) {
        PDFPage page = new PDFPage(); // Relies on the no-arg constructor
        page.setPageNumber(json.getInt("pageNumber"));
        // Use getFloat for direct float parsing
        page.setWidth(json.getFloat("width"));
        page.setHeight(json.getFloat("height"));

        JSONArray textsArray = json.getJSONArray("texts");
        // Use IntStream for iterating JSONArray and collecting results
        IntStream.range(0, textsArray.length())
                .mapToObj(textsArray::getJSONObject) // Method reference for conciseness
                .map(StyledText::fromJson)           // Method reference
                .forEach(page.getTexts()::add);      // Add to the existing list

        JSONArray imagesArray = json.getJSONArray("images");
        IntStream.range(0, imagesArray.length())
                .mapToObj(imagesArray::getJSONObject)
                .map(PDFImage::fromJson)
                .forEach(page.getImages()::add);     // Add to the existing list

        return page;
    }
}