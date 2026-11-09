package com.glez.frontendservice.model.source.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import com.glez.frontendservice.model.source.SourceChapterContent.ChapterImageInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Convierte List<ChapterImageInfo> a JSON string para almacenamiento en CLOB/TEXT.
 */
@Converter(autoApply = true)
public class ChapterImageInfoListToStringConverter implements AttributeConverter<List<ChapterImageInfo>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeFactory typeFactory = objectMapper.getTypeFactory();

    @Override
    public String convertToDatabaseColumn(List<ChapterImageInfo> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Error serializando List<ChapterImageInfo> a JSON", e);
        }
    }

    @Override
    public List<ChapterImageInfo> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<ChapterImageInfo> result = objectMapper.readValue(dbData, 
                typeFactory.constructCollectionType(List.class, ChapterImageInfo.class));
            return result != null ? result : new ArrayList<>();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Error deserializando JSON a List<ChapterImageInfo>", e);
        }
    }
}