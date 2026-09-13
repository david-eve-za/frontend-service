package com.glez.frontendservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessingStatus Enum Tests")
class ProcessingStatusTest {

    @Nested
    @DisplayName("Enum values tests")
    class EnumValuesTests {

        @Test
        @DisplayName("Should have all expected values")
        void values_containsAllExpected() {
            ProcessingStatus[] values = ProcessingStatus.values();
            
            assertEquals(6, values.length);
            assertTrue(contains(values, ProcessingStatus.UPLOADED));
            assertTrue(contains(values, ProcessingStatus.PROCESSING));
            assertTrue(contains(values, ProcessingStatus.COMPLETED));
            assertTrue(contains(values, ProcessingStatus.FAILED));
            assertTrue(contains(values, ProcessingStatus.AWAITING));
            assertTrue(contains(values, ProcessingStatus.STOPPED));
        }

        @Test
        @DisplayName("Should return correct value for valueOf")
        void valueOf_returnsCorrectEnum() {
            assertEquals(ProcessingStatus.UPLOADED, ProcessingStatus.valueOf("UPLOADED"));
            assertEquals(ProcessingStatus.COMPLETED, ProcessingStatus.valueOf("COMPLETED"));
            assertEquals(ProcessingStatus.FAILED, ProcessingStatus.valueOf("FAILED"));
        }

        @Test
        @DisplayName("Should throw exception for invalid value")
        void valueOf_invalidValue_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> ProcessingStatus.valueOf("INVALID"));
        }

        @Test
        @DisplayName("Should have correct ordinal values")
        void ordinal_hasCorrectValues() {
            assertEquals(0, ProcessingStatus.UPLOADED.ordinal());
            assertEquals(1, ProcessingStatus.PROCESSING.ordinal());
            assertEquals(2, ProcessingStatus.COMPLETED.ordinal());
            assertEquals(3, ProcessingStatus.FAILED.ordinal());
            assertEquals(4, ProcessingStatus.AWAITING.ordinal());
            assertEquals(5, ProcessingStatus.STOPPED.ordinal());
        }

        @Test
        @DisplayName("Should return correct name")
        void name_returnsCorrectString() {
            assertEquals("UPLOADED", ProcessingStatus.UPLOADED.name());
            assertEquals("COMPLETED", ProcessingStatus.COMPLETED.name());
            assertEquals("FAILED", ProcessingStatus.FAILED.name());
        }
    }

    @Nested
    @DisplayName("CompareTo tests")
    class CompareToTests {

        @Test
        @DisplayName("Should compare correctly based on ordinal")
        void compareTo_ordersByOrdinal() {
            assertTrue(ProcessingStatus.UPLOADED.compareTo(ProcessingStatus.PROCESSING) < 0);
            assertTrue(ProcessingStatus.PROCESSING.compareTo(ProcessingStatus.COMPLETED) < 0);
            assertTrue(ProcessingStatus.COMPLETED.compareTo(ProcessingStatus.FAILED) < 0);
            assertEquals(0, ProcessingStatus.UPLOADED.compareTo(ProcessingStatus.UPLOADED));
        }
    }

    private boolean contains(ProcessingStatus[] array, ProcessingStatus value) {
        for (ProcessingStatus v : array) {
            if (v == value) return true;
        }
        return false;
    }
}