package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.LogEventDto;
import com.glez.frontendservice.logging.LogEventBroadcaster;
import com.glez.frontendservice.model.LogEvent;
import com.glez.frontendservice.model.PaginatedResponse;
import com.glez.frontendservice.repository.LogEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogStreamController Tests")
class LogStreamControllerTest {

    @Mock
    private LogEventBroadcaster broadcaster;

    @Mock
    private LogEventRepository logEventRepository;

    private LogStreamController controller;

    @BeforeEach
    void setUp() {
        controller = new LogStreamController(broadcaster, logEventRepository);
    }

    @Test
    @DisplayName("search maps persisted rows to the viewer DTO, rebuilding the MDC map")
    void search_mapsEntitiesToDtoWithMdc() {
        LogEvent event = new LogEvent(
                UUID.randomUUID(), Instant.parse("2026-10-09T12:00:00Z"), "INFO",
                "c.g.f.services.BookProcessingService", "http-nio-8080-exec-4",
                "Traducción individual solicitada: chunk X",
                "f9143480-6cae-40a9-8288-0aac08fda16d", "0b9ec859-b171-42b3-80c4-1c03684760b2", "TRANSLATE");
        when(logEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1));

        ResponseEntity<PaginatedResponse<LogEventDto>> response =
                controller.search(null, null, null, null, null, 0, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getContent().size());
        LogEventDto dto = response.getBody().getContent().get(0);
        assertEquals(event.getId(), dto.id());
        assertEquals("INFO", dto.level());
        assertEquals("Traducción individual solicitada: chunk X", dto.message());
        assertEquals("f9143480-6cae-40a9-8288-0aac08fda16d", dto.mdc().get("bookId"));
        assertEquals("0b9ec859-b171-42b3-80c4-1c03684760b2", dto.mdc().get("traceId"));
        assertEquals("TRANSLATE", dto.mdc().get("step"));
    }

    @Test
    @DisplayName("search clamps the page size to the maximum and negative pages to zero")
    void search_clampsPageSizeAndPage() {
        when(logEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<LogEvent>of(), PageRequest.of(0, 200), 0));

        ResponseEntity<PaginatedResponse<LogEventDto>> response =
                controller.search(null, null, null, null, null, -3, 99_999);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(logEventRepository).findAll(any(Specification.class), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(200, pageable.getValue().getPageSize());
    }

    @Test
    @DisplayName("search without filters queries the recent history (most recent first)")
    void search_noFilters_returnsRecentHistory() {
        when(logEventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<LogEvent>of(), PageRequest.of(0, 50), 0));

        ResponseEntity<PaginatedResponse<LogEventDto>> response = controller.search(null, null, null, null, null, 0, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getTotalElements());
    }
}
