package com.glez.frontendservice.pdf.model;

import lombok.Getter;
import lombok.Setter;
import org.json.JSONObject;

/**
 * Represents an image to be embedded in a PDF.
 * Contains metadata such as name, format, dimensions, position,
 * and the image data itself.
 */
@Getter
@Setter
public class PDFImage {

    // Define constants for JSON keys to improve maintainability and readability
    private static final String KEY_NAME = "name";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_DATA = "data";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";

    /**
     * The name of the image file (e.g., "logo.png").
     */
    private String name;

    /**
     * The format of the image (e.g., "png", "jpeg").
     */
    private String format;

    /**
     * The width of the image in pixels.
     */
    private int width;

    /**
     * The height of the image in pixels.
     */
    private int height;

    /**
     * The image data, Base64 encoded string.
     */
    private String data; // Base64

    /**
     * The x-coordinate for the bottom-left corner of the image in the PDF.
     */
    private float x;

    /**
     * The y-coordinate for the bottom-left corner of the image in the PDF.
     */
    private float y;

    // Consider adding constructors if useful, e.g., using Lombok's
    // @NoArgsConstructor (which is implicitly present if no other constructors are defined)
    // and @AllArgsConstructor.
    // public PDFImage() {} // Default constructor, useful for fromJson or frameworks

    // public PDFImage(String name, String format, int width, int height, String data, float x, float y) {
    //     this.name = name;
    //     this.format = format;
    //     this.width = width;
    //     this.height = height;
    //     this.data = data;
    //     this.x = x;
    //     this.y = y;
    // }

    /**
     * Converts this PDFImage instance to a {@link JSONObject}.
     *
     * @return A JSONObject representation of this image.
     */
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

    /**
     * Creates a PDFImage instance from a {@link JSONObject}.
     *
     * @param json The JSONObject containing the image data.
     * @return A new PDFImage instance populated from the JSONObject.
     * @throws org.json.JSONException if a key is missing or a value is of the wrong type.
     */
    public static PDFImage fromJson(JSONObject json) {
        PDFImage image = new PDFImage();
        image.setName(json.getString(KEY_NAME));
        image.setFormat(json.getString(KEY_FORMAT));
        image.setWidth(json.getInt(KEY_WIDTH));
        image.setHeight(json.getInt(KEY_HEIGHT));
        image.setData(json.getString(KEY_DATA));
        // JSONObject doesn't have a direct getFloat method.
        // getDouble is used and then cast to float.
        image.setX((float) json.getDouble(KEY_X));
        image.setY((float) json.getDouble(KEY_Y));
        return image;
    }
}