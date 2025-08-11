package com.glez.frontendservice.pdf.model;

import lombok.*;
import org.json.JSONObject;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyledText {

    private static final String KEY_TEXT = "text";
    private static final String KEY_FONT_NAME = "fontName";
    private static final String KEY_FONT_SIZE = "fontSize";
    private static final String KEY_BOLD = "bold";
    private static final String KEY_ITALIC = "italic";
    private static final String KEY_UNDERLINED = "underlined";
    private static final String KEY_STRIKETHROUGH = "strikethrough";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_RED = "red";
    private static final String KEY_GREEN = "green";
    private static final String KEY_BLUE = "blue";

    private String text;
    private String fontName;
    private float fontSize;
    private boolean bold;
    private boolean italic;
    @Builder.Default
    private boolean underlined = false;
    @Builder.Default
    private boolean strikethrough = false;
    private float x;
    private float y;
    private float width;
    private float height;
    @Builder.Default
    private float red = -1f;
    @Builder.Default
    private float green = -1f;
    @Builder.Default
    private float blue = -1f;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put(KEY_TEXT, text);
        json.put(KEY_FONT_NAME, fontName);
        json.put(KEY_FONT_SIZE, fontSize);
        json.put(KEY_BOLD, bold);
        json.put(KEY_ITALIC, italic);
        json.put(KEY_UNDERLINED, underlined);
        json.put(KEY_STRIKETHROUGH, strikethrough);
        json.put(KEY_X, x);
        json.put(KEY_Y, y);
        json.put(KEY_WIDTH, width);
        json.put(KEY_HEIGHT, height);
        json.put(KEY_RED, red);
        json.put(KEY_GREEN, green);
        json.put(KEY_BLUE, blue);
        return json;
    }

    public static StyledText fromJson(JSONObject json) {
        return StyledText.builder()
                .text(json.getString(KEY_TEXT))
                .fontName(json.getString(KEY_FONT_NAME))
                .fontSize(json.getFloat(KEY_FONT_SIZE))
                .bold(json.getBoolean(KEY_BOLD))
                .italic(json.getBoolean(KEY_ITALIC))
                .underlined(json.optBoolean(KEY_UNDERLINED, false))
                .strikethrough(json.optBoolean(KEY_STRIKETHROUGH, false))
                .x(json.getFloat(KEY_X))
                .y(json.getFloat(KEY_Y))
                .width(json.getFloat(KEY_WIDTH))
                .height(json.getFloat(KEY_HEIGHT))
                .red(json.optFloat(KEY_RED, -1f))
                .green(json.optFloat(KEY_GREEN, -1f))
                .blue(json.optFloat(KEY_BLUE, -1f))
                .build();
    }
}