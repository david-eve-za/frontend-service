package com.glez.frontendservice.pdf.model;

import lombok.Data;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Data
public class PDFPage {
    private int pageNumber;
    private float width;
    private float height;
    private List<StyledText> texts = new ArrayList<>();
    private List<PDFImage> images = new ArrayList<>();

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("pageNumber", pageNumber);
        json.put("width", width);
        json.put("height", height);

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
        PDFPage page = new PDFPage();
        page.setPageNumber(json.getInt("pageNumber"));
        page.setWidth(json.getFloat("width"));
        page.setHeight(json.getFloat("height"));

        JSONArray textsArray = json.getJSONArray("texts");
        IntStream.range(0, textsArray.length())
                .mapToObj(textsArray::getJSONObject)
                .map(StyledText::fromJson)
                .forEach(page.getTexts()::add);

        JSONArray imagesArray = json.getJSONArray("images");
        IntStream.range(0, imagesArray.length())
                .mapToObj(imagesArray::getJSONObject)
                .map(PDFImage::fromJson)
                .forEach(page.getImages()::add);

        return page;
    }
}