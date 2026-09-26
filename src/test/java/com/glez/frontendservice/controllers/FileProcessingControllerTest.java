package com.glez.frontendservice.controllers;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ChunkDto;
import com.glez.frontendservice.model.PaginatedResponse;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.services.BookProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileProcessingController Tests")
class FileProcessingControllerTest {

    @Mock
    private BookProcessingService bookProcessingService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private com.glez.frontendservice.services.BookPipelineOrchestrator bookPipelineOrchestrator;

    @Mock
    private com.glez.frontendservice.services.ProcessingTraceService processingTraceService;

    private FileProcessingController controller;
    private UUID bookId;
    private Book book;

    @BeforeEach
    void setUp() {
        controller = new FileProcessingController(bookProcessingService, bookRepository, bookPipelineOrchestrator, processingTraceService);
        bookId = UUID.randomUUID();
        book = new Book();
        book.setId(bookId);
        book.setName("test.pdf");
        book.setStatus(ProcessingStatus.UPLOADED);
    }

    @Nested
    @DisplayName("uploadFile method tests")
    class UploadFileTests {

        @Test
        @DisplayName("Should return 400 for empty file")
        void uploadFile_withEmptyFile_returnsBadRequest() {
            MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);

            ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Please select a file to upload.", response.getBody().get("error"));
        }

        @Test
        @DisplayName("Should return 200 with book info for successful upload")
        void uploadFile_withValidFile_returnsOkWithBookInfo() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "test.pdf", MediaType.APPLICATION_PDF_VALUE, "PDF content".getBytes());
            
            Book savedBook = new Book();
            savedBook.setId(bookId);
            savedBook.setName("test.pdf");
            savedBook.setStatus(ProcessingStatus.UPLOADED);

            when(bookProcessingService.storeBook(any(), eq("test.pdf"))).thenReturn(savedBook);

            ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("File uploaded successfully. Processing started.", response.getBody().get("message"));
            assertEquals(bookId, response.getBody().get("bookId"));
            assertEquals(ProcessingStatus.UPLOADED, response.getBody().get("status"));
        }

        @Test
        @DisplayName("Should return 500 for RuntimeException from service")
        void uploadFile_withRuntimeException_returnsInternalServerError() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "test.pdf", MediaType.APPLICATION_PDF_VALUE, "PDF content".getBytes());
            
            when(bookProcessingService.storeBook(any(), eq("test.pdf"))).thenThrow(new RuntimeException("Read failed"));

            ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().get("error").toString().contains("Error initiating processing"));
        }

        @Test
        @DisplayName("Should return 500 for general exception during processing")
        void uploadFile_withGeneralException_returnsInternalServerError() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "test.pdf", MediaType.APPLICATION_PDF_VALUE, "PDF content".getBytes());
            
            when(bookProcessingService.storeBook(any(), eq("test.pdf"))).thenThrow(new RuntimeException("Processing failed"));

            ResponseEntity<Map<String, Object>> response = controller.uploadFile(file);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().get("error").toString().contains("Error initiating processing"));
        }
    }

    @Nested
    @DisplayName("getAllBooks method tests")
    class GetAllBooksTests {

        @Test
        @DisplayName("Should return list of all books")
        void getAllBooks_returnsAllBooks() {
            Book book1 = new Book();
            book1.setId(UUID.randomUUID());
            book1.setName("book1.pdf");
            book1.setStatus(ProcessingStatus.COMPLETED);

            Book book2 = new Book();
            book2.setId(UUID.randomUUID());
            book2.setName("book2.pdf");
            book2.setStatus(ProcessingStatus.PROCESSING);

            when(bookRepository.findAll()).thenReturn(List.of(book1, book2));

            ResponseEntity<List<Map<String, Object>>> response = controller.getAllBooks();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(2, response.getBody().size());
            assertEquals(book1.getId(), response.getBody().get(0).get("id"));
            assertEquals(book1.getName(), response.getBody().get(0).get("name"));
            assertEquals(book1.getStatus(), response.getBody().get(0).get("status"));
        }

        @Test
        @DisplayName("Should return empty list when no books exist")
        void getAllBooks_noBooks_returnsEmptyList() {
            when(bookRepository.findAll()).thenReturn(List.of());

            ResponseEntity<List<Map<String, Object>>> response = controller.getAllBooks();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().isEmpty());
        }
    }

    @Nested
    @DisplayName("getBookStatus method tests")
    class GetBookStatusTests {

        @Test
        @DisplayName("Should return 200 with status for existing book")
        void getBookStatus_existingBook_returnsStatus() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

            ResponseEntity<Map<String, Object>> response = controller.getBookStatus(bookId);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(bookId, response.getBody().get("bookId"));
            assertEquals(ProcessingStatus.UPLOADED, response.getBody().get("status"));
        }

        @Test
        @DisplayName("Should return 404 for non-existent book")
        void getBookStatus_nonExistentBook_returnsNotFound() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.getBookStatus(bookId);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Nested
    @DisplayName("deleteBook method tests")
    class DeleteBookTests {

        @Test
        @DisplayName("Should return 204 for successful deletion")
        void deleteBook_existingBook_returnsNoContent() {
            doNothing().when(bookProcessingService).deleteBook(bookId);

            ResponseEntity<Void> response = controller.deleteBook(bookId);

            assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
            verify(bookProcessingService).deleteBook(bookId);
        }

        @Test
        @DisplayName("Should return 404 when book not found")
        void deleteBook_bookNotFound_returnsNotFound() {
            doThrow(new IllegalArgumentException("Book not found")).when(bookProcessingService).deleteBook(bookId);

            ResponseEntity<Void> response = controller.deleteBook(bookId);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }

        @Test
        @DisplayName("Should return 500 for general exception")
        void deleteBook_generalException_returnsInternalServerError() {
            doThrow(new RuntimeException("Unexpected error")).when(bookProcessingService).deleteBook(bookId);

            ResponseEntity<Void> response = controller.deleteBook(bookId);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("getBookFullText method tests")
    class GetBookFullTextTests {

        @Test
        @DisplayName("Should return 200 with full text for existing book")
        void getBookFullText_existingBook_returnsFullText() {
            String fullText = "Full book text content";
            when(bookProcessingService.getFullText(bookId)).thenReturn(fullText);

            ResponseEntity<String> response = controller.getBookFullText(bookId);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(fullText, response.getBody());
        }

        @Test
        @DisplayName("Should return 404 when book not found")
        void getBookFullText_bookNotFound_returnsNotFound() {
            when(bookProcessingService.getFullText(bookId)).thenThrow(new IllegalArgumentException("Book not found"));

            ResponseEntity<String> response = controller.getBookFullText(bookId);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }

        @Test
        @DisplayName("Should return 500 for general exception")
        void getBookFullText_generalException_returnsInternalServerError() {
            when(bookProcessingService.getFullText(bookId)).thenThrow(new RuntimeException("Unexpected error"));

            ResponseEntity<String> response = controller.getBookFullText(bookId);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("updateBookFullText method tests")
    class UpdateBookFullTextTests {

        @Test
        @DisplayName("Should return 200 for successful update")
        void updateBookFullText_validRequest_returnsOk() {
            String newText = "Updated full text";
            doNothing().when(bookProcessingService).updateFullText(bookId, newText);

            ResponseEntity<Void> response = controller.updateBookFullText(bookId, newText);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(bookProcessingService).updateFullText(bookId, newText);
        }

        @Test
        @DisplayName("Should return 404 when book not found")
        void updateBookFullText_bookNotFound_returnsNotFound() {
            doThrow(new IllegalArgumentException("Book not found")).when(bookProcessingService).updateFullText(bookId, "text");

            ResponseEntity<Void> response = controller.updateBookFullText(bookId, "text");

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }

        @Test
        @DisplayName("Should return 500 for general exception")
        void updateBookFullText_generalException_returnsInternalServerError() {
            doThrow(new RuntimeException("Unexpected error")).when(bookProcessingService).updateFullText(bookId, "text");

            ResponseEntity<Void> response = controller.updateBookFullText(bookId, "text");

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("splitBookIntoChunks method tests")
    class SplitBookIntoChunksTests {

        @Test
        @DisplayName("Should return 200 for successful split")
        void splitBookIntoChunks_validBook_returnsOk() {
            doNothing().when(bookProcessingService).splitBookIntoChunks(bookId);

            ResponseEntity<Map<String, Object>> response = controller.splitBookIntoChunks(bookId);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Book " + bookId + " successfully split into chunks.", response.getBody().get("message"));
            verify(bookProcessingService).splitBookIntoChunks(bookId);
        }

        @Test
        @DisplayName("Should return 400 when book has no full text")
        void splitBookIntoChunks_noFullText_returnsBadRequest() {
            doThrow(new IllegalArgumentException("Book has no full text to chunk")).when(bookProcessingService).splitBookIntoChunks(bookId);

            ResponseEntity<Map<String, Object>> response = controller.splitBookIntoChunks(bookId);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Book has no full text to chunk", response.getBody().get("error"));
        }

        @Test
        @DisplayName("Should return 500 for general exception")
        void splitBookIntoChunks_generalException_returnsInternalServerError() {
            doThrow(new RuntimeException("Unexpected error")).when(bookProcessingService).splitBookIntoChunks(bookId);

            ResponseEntity<Map<String, Object>> response = controller.splitBookIntoChunks(bookId);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().get("error").toString().contains("Error splitting book into chunks"));
        }
    }

    @Nested
    @DisplayName("getBookChunks method tests")
    class GetBookChunksTests {

        @Test
        @DisplayName("Should return paginated chunks for existing book")
        void getBookChunks_existingBook_returnsPaginatedChunks() {
            Pageable pageable = PageRequest.of(0, 10);
            ChunkDto chunk1 = new ChunkDto(UUID.randomUUID(), "Original 1", "Translated 1", null, 0);
            ChunkDto chunk2 = new ChunkDto(UUID.randomUUID(), "Original 2", "Translated 2", null, 1);
            Page<ChunkDto> chunkPage = new PageImpl<>(List.of(chunk1, chunk2), pageable, 2);
            PaginatedResponse<ChunkDto> paginatedResponse = new PaginatedResponse<>(chunkPage);

            when(bookProcessingService.getBookChunks(bookId, pageable)).thenReturn(paginatedResponse);

            ResponseEntity<PaginatedResponse<ChunkDto>> response = controller.getBookChunks(bookId, pageable);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(2, response.getBody().getContent().size());
            assertEquals(0, response.getBody().getPageNumber());
            assertEquals(10, response.getBody().getPageSize());
        }

        @Test
        @DisplayName("Should return 404 when book not found")
        void getBookChunks_bookNotFound_returnsNotFound() {
            Pageable pageable = PageRequest.of(0, 10);
            when(bookProcessingService.getBookChunks(bookId, pageable)).thenThrow(new IllegalArgumentException("Book not found"));

            ResponseEntity<PaginatedResponse<ChunkDto>> response = controller.getBookChunks(bookId, pageable);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }

        @Test
        @DisplayName("Should return 500 for general exception")
        void getBookChunks_generalException_returnsInternalServerError() {
            Pageable pageable = PageRequest.of(0, 10);
            when(bookProcessingService.getBookChunks(bookId, pageable)).thenThrow(new RuntimeException("Unexpected error"));

            ResponseEntity<PaginatedResponse<ChunkDto>> response = controller.getBookChunks(bookId, pageable);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }
}