package com.glez.frontendservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Book Model Tests")
class BookTest {

    @Nested
    @DisplayName("Constructor and getter/setter tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create book with no-args constructor")
        void noArgsConstructor_createsBook() {
            Book book = new Book();
            assertNotNull(book);
            assertNull(book.getId());
            assertNull(book.getName());
            assertNull(book.getStatus());
            assertNull(book.getFullText());
            assertNull(book.getChunks());
        }

        @Test
        @DisplayName("Should create book with all-args constructor")
        void allArgsConstructor_createsBookWithAllFields() {
            UUID id = UUID.randomUUID();
            String name = "test.pdf";
            ProcessingStatus status = ProcessingStatus.UPLOADED;
            String fullText = "Full text content";

            Book book = new Book(id, name, status, fullText, null);

            assertEquals(id, book.getId());
            assertEquals(name, book.getName());
            assertEquals(status, book.getStatus());
            assertEquals(fullText, book.getFullText());
        }

        @Test
        @DisplayName("Should set and get all fields")
        void settersAndGetters_workCorrectly() {
            Book book = new Book();
            UUID id = UUID.randomUUID();
            String name = "new-book.pdf";
            ProcessingStatus status = ProcessingStatus.COMPLETED;
            String fullText = "New full text";

            book.setId(id);
            book.setName(name);
            book.setStatus(status);
            book.setFullText(fullText);

            assertEquals(id, book.getId());
            assertEquals(name, book.getName());
            assertEquals(status, book.getStatus());
            assertEquals(fullText, book.getFullText());
        }
    }

    @Nested
    @DisplayName("Equals and hashCode tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when all fields are equal")
        void equals_sameFields_returnsTrue() {
            UUID id = UUID.randomUUID();
            Book book1 = new Book();
            book1.setId(id);
            book1.setName("book1.pdf");
            book1.setStatus(ProcessingStatus.UPLOADED);

            Book book2 = new Book();
            book2.setId(id);
            book2.setName("book1.pdf");
            book2.setStatus(ProcessingStatus.UPLOADED);

            assertEquals(book1, book2);
        }

        @Test
        @DisplayName("Should not be equal when names are different")
        void equals_differentName_returnsFalse() {
            UUID id = UUID.randomUUID();
            Book book1 = new Book();
            book1.setId(id);
            book1.setName("book1.pdf");

            Book book2 = new Book();
            book2.setId(id);
            book2.setName("book2.pdf");

            assertNotEquals(book1, book2);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void equals_null_returnsFalse() {
            Book book = new Book();
            book.setId(UUID.randomUUID());

            assertNotEquals(null, book);
        }

        @Test
        @DisplayName("Should not be equal to different class")
        void equals_differentClass_returnsFalse() {
            Book book = new Book();
            book.setId(UUID.randomUUID());

            assertNotEquals("not a book", book);
        }

        @Test
        @DisplayName("Should have consistent hashCode")
        void hashCode_consistent() {
            Book book = new Book();
            book.setId(UUID.randomUUID());

            int hash1 = book.hashCode();
            int hash2 = book.hashCode();

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
            book.setId(id);
            book.setName("test.pdf");
            book.setStatus(ProcessingStatus.UPLOADED);
            book.setFullText("Full text");

            String toString = book.toString();

            assertNotNull(toString);
            assertTrue(toString.contains(id.toString()));
            assertTrue(toString.contains("test.pdf"));
            assertTrue(toString.contains("UPLOADED"));
            assertTrue(toString.contains("Full text"));
        }
    }
}