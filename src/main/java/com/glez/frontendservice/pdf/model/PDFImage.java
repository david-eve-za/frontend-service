package com.glez.frontendservice.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.json.JSONObject;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PDFImage {

    private static final String KEY_NAME = "name";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_DATA = "data";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";

    private String name;
    private String format;
    private int width;
    private int height;
    private String data;
    private float x;
    private float y;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put(KEY_NAME, name);
        json.put(KEY_FORMAT, format);
        json.put(KEY_WIDTH, width);
        json.put(KEY_HEIGHT, height);
        json.put(KEY_DATA, data);
        json.put(KEY_X, x);
        json.put(KEY_Y, y);
        return json;
    }

    public static PDFImage fromJson(JSONObject json) {
        return PDFImage.builder()
                .name(json.getString(KEY_NAME))
                .format(json.getString(KEY_FORMAT))
                .width(json.getInt(KEY_WIDTH))
                .height(json.getInt(KEY_HEIGHT))
                .data(json.getString(KEY_DATA))
                .x((float) json.getDouble(KEY_X))
                .y((float) json.getDouble(KEY_Y))
                .build();
    }
}