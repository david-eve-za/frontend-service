package com.glez.frontendservice.pdf.model;

import lombok.Data;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Data
public class PDFDocument {
    private PDFMetadata metadata;
    private List<PDFPage> pages = new ArrayList<>();

    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        if (this.metadata != null) {
            json.put("metadata", this.metadata.toJson());
        }

        List<JSONObject> pageJsonObjects = this.pages.stream()
                .filter(Objects::nonNull)
                .map(PDFPage::toJson)
                .collect(Collectors.toList());
        json.put("pages", new JSONArray(pageJsonObjects));

        return json;
    }

    public static PDFDocument fromJson(JSONObject json) {
        PDFDocument document = new PDFDocument();

        if (json.has("metadata") && !json.isNull("metadata")) {
            JSONObject metadataJson = json.getJSONObject("metadata");
            document.setMetadata(PDFMetadata.fromJson(metadataJson));
        }

        if (json.has("pages") && !json.isNull("pages")) {
            JSONArray pagesArray = json.getJSONArray("pages");

            List<PDFPage> parsedPages = IntStream.range(0, pagesArray.length())
                    .filter(i -> !pagesArray.isNull(i))
                    .mapToObj(pagesArray::getJSONObject)
                    .map(PDFPage::fromJson)
                    .collect(Collectors.toList());
            document.setPages(parsedPages);
        }

        return document;
    }
}