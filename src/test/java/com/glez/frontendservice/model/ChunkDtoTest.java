package com.glez.frontendservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChunkDto Model Tests")
class ChunkDtoTest {

    @Nested
    @DisplayName("Constructor and getter/setter tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create ChunkDto with no-args constructor")
        void noArgsConstructor_createsChunkDto() {
            ChunkDto dto = new ChunkDto();
            assertNotNull(dto);
            assertNull(dto.getId());
            assertNull(dto.getOriginalText());
            assertNull(dto.getTranslatedText());
            assertNull(dto.getStatus());
            assertEquals(0, dto.getPosition());
        }

        @Test
        @DisplayName("Should create ChunkDto with all-args constructor")
        void allArgsConstructor_createsChunkDtoWithAllFields() {
            UUID id = UUID.randomUUID();
            String originalText = "Original text";
            String translatedText = "Translated text";
            ChunkStatus status = ChunkStatus.COMPLETED;
            int position = 5;

            ChunkDto dto = new ChunkDto(id, originalText, translatedText, status, position);

            assertEquals(id, dto.getId());
            assertEquals(originalText, dto.getOriginalText());
            assertEquals(translatedText, dto.getTranslatedText());
            assertEquals(status, dto.getStatus());
            assertEquals(position, dto.getPosition());
        }

        @Test
        @DisplayName("Should set and get all fields")
        void settersAndGetters_workCorrectly() {
            ChunkDto dto = new ChunkDto();
            UUID id = UUID.randomUUID();
            String originalText = "Original";
            String translatedText = "Traducido";
            ChunkStatus status = ChunkStatus.PROCESSING;
            int position = 10;

            dto.setId(id);
            dto.setOriginalText(originalText);
            dto.setTranslatedText(translatedText);
            dto.setStatus(status);
            dto.setPosition(position);

            assertEquals(id, dto.getId());
            assertEquals(originalText, dto.getOriginalText());
            assertEquals(translatedText, dto.getTranslatedText());
            assertEquals(status, dto.getStatus());
            assertEquals(position, dto.getPosition());
        }
    }

    @Nested
    @DisplayName("Equals and hashCode tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when all fields are equal")
        void equals_sameFields_returnsTrue() {
            UUID id = UUID.randomUUID();
            ChunkDto dto1 = new ChunkDto(id, "Original", "Translated", ChunkStatus.COMPLETED, 5);
            ChunkDto dto2 = new ChunkDto(id, "Original", "Translated", ChunkStatus.COMPLETED, 5);

            assertEquals(dto1, dto2);
        }

        @Test
        @DisplayName("Should not be equal when IDs are different")
        void equals_differentId_returnsFalse() {
            ChunkDto dto1 = new ChunkDto(UUID.randomUUID(), "Original", "Translated", ChunkStatus.COMPLETED, 5);
            ChunkDto dto2 = new ChunkDto(UUID.randomUUID(), "Original", "Translated", ChunkStatus.COMPLETED, 5);

            assertNotEquals(dto1, dto2);
        }

        @Test
        @DisplayName("Should have consistent hashCode")
        void hashCode_consistent() {
            ChunkDto dto = new ChunkDto(UUID.randomUUID(), "Original", "Translated", ChunkStatus.COMPLETED, 5);

            int hash1 = dto.hashCode();
            int hash2 = dto.hashCode();

            assertEquals(hash1, hash2);
        }
    }

    @Nested
    @DisplayName("toString test")
    class ToStringTests {

        @Test
        @DisplayName("Should generate toString with all fields")
        void toString_containsAllFields() {
            UUID id = UUID.randomUUID();
            ChunkDto dto = new ChunkDto(id, "Original", "Translated", ChunkStatus.COMPLETED, 5);

            String toString = dto.toString();

            assertNotNull(toString);
            assertTrue(toString.contains(id.toString()));
            assertTrue(toString.contains("Original"));
            assertTrue(toString.contains("Translated"));
            assertTrue(toString.contains("COMPLETED"));
            assertTrue(toString.contains("5"));
        }
    }
}