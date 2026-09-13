package com.glez.frontendservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaginatedResponse Model Tests")
class PaginatedResponseTest {

    @Nested
    @DisplayName("Constructor tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create PaginatedResponse with no-args constructor")
        void noArgsConstructor_createsPaginatedResponse() {
            PaginatedResponse<String> response = new PaginatedResponse<>();
            assertNotNull(response);
            assertNull(response.getContent());
            assertEquals(0, response.getPageNumber());
            assertEquals(0, response.getPageSize());
            assertEquals(0, response.getTotalElements());
            assertEquals(0, response.getTotalPages());
            assertFalse(response.isLast());
            assertFalse(response.isFirst());
        }

        @Test
        @DisplayName("Should create PaginatedResponse with all-args constructor")
        void allArgsConstructor_createsPaginatedResponseWithAllFields() {
            List<String> content = List.of("item1", "item2");
            int pageNumber = 1;
            int pageSize = 10;
            long totalElements = 25;
            int totalPages = 3;
            boolean last = false;
            boolean first = false;

            PaginatedResponse<String> response = new PaginatedResponse<>(content, pageNumber, pageSize, totalElements, totalPages, last, first);

            assertEquals(content, response.getContent());
            assertEquals(pageNumber, response.getPageNumber());
            assertEquals(pageSize, response.getPageSize());
            assertEquals(totalElements, response.getTotalElements());
            assertEquals(totalPages, response.getTotalPages());
            assertEquals(last, response.isLast());
            assertEquals(first, response.isFirst());
        }

        @Test
        @DisplayName("Should create PaginatedResponse from Spring Page")
        void pageConstructor_createsFromSpringPage() {
            PageRequest pageable = PageRequest.of(1, 10);
            List<String> content = List.of("item1", "item2", "item3");
            Page<String> page = new PageImpl<>(content, pageable, 25);

            PaginatedResponse<String> response = new PaginatedResponse<>(page);

            assertEquals(content, response.getContent());
            assertEquals(1, response.getPageNumber());
            assertEquals(10, response.getPageSize());
            assertEquals(25, response.getTotalElements());
            assertEquals(3, response.getTotalPages());
            assertFalse(response.isLast());
            assertFalse(response.isFirst());
        }

        @Test
        @DisplayName("Should create PaginatedResponse from first page")
        void pageConstructor_firstPage_setsFirstTrue() {
            PageRequest pageable = PageRequest.of(0, 10);
            List<String> content = List.of("item1");
            Page<String> page = new PageImpl<>(content, pageable, 25);

            PaginatedResponse<String> response = new PaginatedResponse<>(page);

            assertTrue(response.isFirst());
            assertFalse(response.isLast());
        }

        @Test
        @DisplayName("Should create PaginatedResponse from last page")
        void pageConstructor_lastPage_setsLastTrue() {
            PageRequest pageable = PageRequest.of(2, 10);
            List<String> content = List.of("item1");
            Page<String> page = new PageImpl<>(content, pageable, 25);

            PaginatedResponse<String> response = new PaginatedResponse<>(page);

            assertTrue(response.isLast());
            assertFalse(response.isFirst());
        }
    }

    @Nested
    @DisplayName("Setter tests")
    class SetterTests {

        @Test
        @DisplayName("Should set all fields via setters")
        void setters_workCorrectly() {
            PaginatedResponse<String> response = new PaginatedResponse<>();
            
            response.setContent(List.of("item1", "item2"));
            response.setPageNumber(2);
            response.setPageSize(20);
            response.setTotalElements(100);
            response.setTotalPages(5);
            response.setLast(true);
            response.setFirst(false);

            assertEquals(List.of("item1", "item2"), response.getContent());
            assertEquals(2, response.getPageNumber());
            assertEquals(20, response.getPageSize());
            assertEquals(100, response.getTotalElements());
            assertEquals(5, response.getTotalPages());
            assertTrue(response.isLast());
            assertFalse(response.isFirst());
        }
    }

    @Nested
    @DisplayName("Equals and hashCode tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when all fields are equal")
        void equals_sameFields_returnsTrue() {
            PaginatedResponse<String> response1 = new PaginatedResponse<>(List.of("a", "b"), 1, 10, 20, 2, false, false);
            PaginatedResponse<String> response2 = new PaginatedResponse<>(List.of("a", "b"), 1, 10, 20, 2, false, false);

            assertEquals(response1, response2);
        }

        @Test
        @DisplayName("Should not be equal when content differs")
        void equals_differentContent_returnsFalse() {
            PaginatedResponse<String> response1 = new PaginatedResponse<>(List.of("a"), 1, 10, 20, 2, false, false);
            PaginatedResponse<String> response2 = new PaginatedResponse<>(List.of("b"), 1, 10, 20, 2, false, false);

            assertNotEquals(response1, response2);
        }

        @Test
        @DisplayName("Should have consistent hashCode")
        void hashCode_consistent() {
            PaginatedResponse<String> response = new PaginatedResponse<>(List.of("a", "b"), 1, 10, 20, 2, false, false);

            int hash1 = response.hashCode();
            int hash2 = response.hashCode();

            assertEquals(hash1, hash2);
        }
    }

    @Nested
    @DisplayName("toString test")
    class ToStringTests {

        @Test
        @DisplayName("Should generate toString with all fields")
        void toString_containsAllFields() {
            PaginatedResponse<String> response = new PaginatedResponse<>(List.of("item1"), 1, 10, 25, 3, false, false);

            String toString = response.toString();

            assertNotNull(toString);
            assertTrue(toString.contains("item1"));
            assertTrue(toString.contains("1"));
            assertTrue(toString.contains("10"));
            assertTrue(toString.contains("25"));
            assertTrue(toString.contains("3"));
        }
    }

    @Nested
    @DisplayName("Generic type tests")
    class GenericTypeTests {

        @Test
        @DisplayName("Should work with ChunkDto type")
        void withChunkDto_worksCorrectly() {
            ChunkDto chunk = new ChunkDto(UUID.randomUUID(), "Original", "Translated", ChunkStatus.COMPLETED, 0);
            PageRequest pageable = PageRequest.of(0, 10);
            Page<ChunkDto> page = new PageImpl<>(List.of(chunk), pageable, 1);

            PaginatedResponse<ChunkDto> response = new PaginatedResponse<>(page);

            assertEquals(1, response.getContent().size());
            assertEquals(chunk, response.getContent().get(0));
        }

        @Test
        @DisplayName("Should work with Integer type")
        void withInteger_worksCorrectly() {
            PageRequest pageable = PageRequest.of(0, 10);
            Page<Integer> page = new PageImpl<>(List.of(1, 2, 3), pageable, 3);

            PaginatedResponse<Integer> response = new PaginatedResponse<>(page);

            assertEquals(3, response.getContent().size());
            assertEquals(List.of(1, 2, 3), response.getContent());
        }
    }
}