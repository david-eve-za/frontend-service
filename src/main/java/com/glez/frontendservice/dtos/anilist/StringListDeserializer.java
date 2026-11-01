package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom deserializer for List<String> that handles both arrays and objects.
 * Accepts:
 * - Array of strings: ["Action", "Romance"]
 * - Object with string keys: {"Action": true, "Romance": false}
 * - Object with string values: {"genre1": "Action", "genre2": "Romance"}
 * - Single string: "Action"
 * - Null or missing: empty list
 */
public class StringListDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<List<String>> {
    @Override
    public List<String> deserialize(com.fasterxml.jackson.core.JsonParser p, com.fasterxml.jackson.databind.DeserializationContext ctxt) throws IOException {
        com.fasterxml.jackson.databind.JsonNode node = p.getCodec().readTree(p);
        List<String> result = new ArrayList<>();
        
        if (node == null || node.isNull()) {
            return result;
        }
        
        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText());
                } else if (item.isObject()) {
                    // Extract first string value from object
                    java.util.Iterator<String> fieldNames = item.fieldNames();
                    if (fieldNames.hasNext()) {
                        String firstKey = fieldNames.next();
                        com.fasterxml.jackson.databind.JsonNode value = item.get(firstKey);
                        if (value.isTextual()) {
                            result.add(value.asText());
                        } else {
                            result.add(firstKey); // Use key as fallback
                        }
                    }
                }
            }
        } else if (node.isObject()) {
            // Handle object with string keys/values
            java.util.Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                com.fasterxml.jackson.databind.JsonNode value = node.get(key);
                if (value.isTextual()) {
                    result.add(value.asText());
                } else if (value.isBoolean() && value.asBoolean()) {
                    // Include key if value is true
                    result.add(key);
                } else if (value.isNull()) {
                    // Skip null values
                } else {
                    // Use key as fallback
                    result.add(key);
                }
            }
        } else if (node.isTextual()) {
            result.add(node.asText());
        }
        
        return result;
    }
}