package com.glez.frontendservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Chunks Model Tests")
class ChunksTest {

    @Nested
    @DisplayName("Constructor and getter/setter tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create chunk with no-args constructor")
        void noArgsConstructor_createsChunk() {
            Chunks chunk = new Chunks();
            assertNotNull(chunk);
            assertNull(chunk.getId());
            assertNull(chunk.getBook());
            assertNull(chunk.getOriginalText());
            assertNull(chunk.getTranslatedText());
            assertNull(chunk.getStatus());
            assertNull(chunk.getPosition());
        }

        @Test
        @DisplayName("Should create chunk with all-args constructor")
        void allArgsConstructor_createsChunkWithAllFields() {
            UUID id = UUID.randomUUID();
            Book book = new Book();
            book.setId(UUID.randomUUID());
            String originalText = "Original text";
            String translatedText = "Translated text";
            ChunkStatus status = ChunkStatus.COMPLETED;
            Integer position = 5;

            Chunks chunk = new Chunks(id, book, originalText, translatedText, status, position, 2);

            assertEquals(id, chunk.getId());
            assertEquals(book, chunk.getBook());
            assertEquals(originalText, chunk.getOriginalText());
            assertEquals(translatedText, chunk.getTranslatedText());
            assertEquals(status, chunk.getStatus());
            assertEquals(position, chunk.getPosition());
            assertEquals(2, chunk.getAttempts());
        }

        @Test
        @DisplayName("Should set and get all fields")
        void settersAndGetters_workCorrectly() {
            Chunks chunk = new Chunks();
            UUID id = UUID.randomUUID();
            Book book = new Book();
            book.setId(UUID.randomUUID());
            String originalText = "Original";
            String translatedText = "Traducido";
            ChunkStatus status = ChunkStatus.PROCESSING;
            Integer position = 10;

            chunk.setId(id);
            chunk.setBook(book);
            chunk.setOriginalText(originalText);
            chunk.setTranslatedText(translatedText);
            chunk.setStatus(status);
            chunk.setPosition(position);

            assertEquals(id, chunk.getId());
            assertEquals(book, chunk.getBook());
            assertEquals(originalText, chunk.getOriginalText());
            assertEquals(translatedText, chunk.getTranslatedText());
            assertEquals(status, chunk.getStatus());
            assertEquals(position, chunk.getPosition());
        }
    }

    @Nested
    @DisplayName("Equals and hashCode tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when all fields are equal")
        void equals_sameFields_returnsTrue() {
            UUID id = UUID.randomUUID();
            Book book = new Book();
            book.setId(UUID.randomUUID());
            
            Chunks chunk1 = new Chunks();
            chunk1.setId(id);
            chunk1.setBook(book);
            chunk1.setPosition(1);

            Chunks chunk2 = new Chunks();
            chunk2.setId(id);
            chunk2.setBook(book);
            chunk2.setPosition(1);

            assertEquals(chunk1, chunk2);
        }

        @Test
        @DisplayName("Should not be equal when positions are different")
        void equals_differentPosition_returnsFalse() {
            UUID id = UUID.randomUUID();
            Book book = new Book();
            book.setId(UUID.randomUUID());
            
            Chunks chunk1 = new Chunks();
            chunk1.setId(id);
            chunk1.setBook(book);
            chunk1.setPosition(1);

            Chunks chunk2 = new Chunks();
            chunk2.setId(id);
            chunk2.setBook(book);
            chunk2.setPosition(2);

            assertNotEquals(chunk1, chunk2);
        }

        @Test
        @DisplayName("Should have consistent hashCode")
        void hashCode_consistent() {
            Chunks chunk = new Chunks();
            chunk.setId(UUID.randomUUID());

            int hash1 = chunk.hashCode();
            int hash2 = chunk.hashCode();

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
            Book book = new Book();
            book.setId(UUID.randomUUID());
            
            Chunks chunk = new Chunks();
            chunk.setId(id);
            chunk.setBook(book);
            chunk.setOriginalText("Original");
            chunk.setTranslatedText("Translated");
            chunk.setStatus(ChunkStatus.COMPLETED);
            chunk.setPosition(5);

            String toString = chunk.toString();

            assertNotNull(toString);
            assertTrue(toString.contains(id.toString()));
            assertTrue(toString.contains("Original"));
            assertTrue(toString.contains("Translated"));
            assertTrue(toString.contains("COMPLETED"));
            assertTrue(toString.contains("5"));
        }
    }
}