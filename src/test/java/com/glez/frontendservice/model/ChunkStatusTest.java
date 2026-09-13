package com.glez.frontendservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChunkStatus Enum Tests")
class ChunkStatusTest {

    @Nested
    @DisplayName("Enum values tests")
    class EnumValuesTests {

        @Test
        @DisplayName("Should have all expected values")
        void values_containsAllExpected() {
            ChunkStatus[] values = ChunkStatus.values();
            
            assertEquals(4, values.length);
            assertTrue(contains(values, ChunkStatus.PROCESSING));
            assertTrue(contains(values, ChunkStatus.COMPLETED));
            assertTrue(contains(values, ChunkStatus.FAILED));
            assertTrue(contains(values, ChunkStatus.AWAITING));
        }

        @Test
        @DisplayName("Should return correct value for valueOf")
        void valueOf_returnsCorrectEnum() {
            assertEquals(ChunkStatus.PROCESSING, ChunkStatus.valueOf("PROCESSING"));
            assertEquals(ChunkStatus.COMPLETED, ChunkStatus.valueOf("COMPLETED"));
            assertEquals(ChunkStatus.FAILED, ChunkStatus.valueOf("FAILED"));
            assertEquals(ChunkStatus.AWAITING, ChunkStatus.valueOf("AWAITING"));
        }

        @Test
        @DisplayName("Should throw exception for invalid value")
        void valueOf_invalidValue_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> ChunkStatus.valueOf("INVALID"));
        }

        @Test
        @DisplayName("Should have correct ordinal values")
        void ordinal_hasCorrectValues() {
            assertEquals(0, ChunkStatus.PROCESSING.ordinal());
            assertEquals(1, ChunkStatus.COMPLETED.ordinal());
            assertEquals(2, ChunkStatus.FAILED.ordinal());
            assertEquals(3, ChunkStatus.AWAITING.ordinal());
        }

        @Test
        @DisplayName("Should return correct name")
        void name_returnsCorrectString() {
            assertEquals("PROCESSING", ChunkStatus.PROCESSING.name());
            assertEquals("COMPLETED", ChunkStatus.COMPLETED.name());
            assertEquals("FAILED", ChunkStatus.FAILED.name());
            assertEquals("AWAITING", ChunkStatus.AWAITING.name());
        }
    }

    @Nested
    @DisplayName("CompareTo tests")
    class CompareToTests {

        @Test
        @DisplayName("Should compare correctly based on ordinal")
        void compareTo_ordersByOrdinal() {
            assertTrue(ChunkStatus.PROCESSING.compareTo(ChunkStatus.COMPLETED) < 0);
            assertTrue(ChunkStatus.COMPLETED.compareTo(ChunkStatus.FAILED) < 0);
            assertTrue(ChunkStatus.FAILED.compareTo(ChunkStatus.AWAITING) < 0);
            assertEquals(0, ChunkStatus.PROCESSING.compareTo(ChunkStatus.PROCESSING));
        }
    }

    private boolean contains(ChunkStatus[] array, ChunkStatus value) {
        for (ChunkStatus v : array) {
            if (v == value) return true;
        }
        return false;
    }
}