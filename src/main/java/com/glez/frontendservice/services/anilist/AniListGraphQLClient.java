package com.glez.frontendservice.services.anilist;

import com.glez.frontendservice.dtos.anilist.*;
import com.glez.frontendservice.dtos.anilist.AniListGraphQLResponse;
import com.glez.frontendservice.dtos.anilist.AniListMedia;
import com.glez.frontendservice.dtos.anilist.AniListGraphQLResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AniListGraphQLClient {

    private static final String ANILIST_GRAPHQL_URL = "https://graphql.anilist.co";

    private final RestTemplate restTemplate;

    private static final String SEARCH_MEDIA_QUERY = """
            query ($search: String!, $page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                pageInfo {
                  total
                  currentPage
                  lastPage
                  hasNextPage
                  perPage
                }
                media(search: $search, type: MANGA) {
                  id
                  idMal
                  title {
                    romaji
                    english
                    native
                    userPreferred
                  }
                  description
                  format
                  status
                  episodes
                  chapters
                  volumes
                  countryOfOrigin
                  genres
                  tags {
                    name
                    description
                    category
                    rank
                    isGeneralSpoiler
                    isMediaSpoiler
                    isAdult
                  }
                  coverImage {
                    large
                    medium
                    extraLarge
                    color
                  }
                  averageScore
                  meanScore
                  popularity
                  isAdult
                  source
                  startDate {
                    year
                    month
                    day
                  }
                  endDate {
                    year
                    month
                    day
                  }
                  season
                  seasonYear
                }
              }
            }
            """;

    private static final String GET_MEDIA_BY_ID_QUERY = """
            query ($id: Int!) {
              Media(id: $id, type: MANGA) {
                id
                idMal
                title {
                  romaji
                  english
                  native
                  userPreferred
                }
                description
                format
                status
                episodes
                chapters
                volumes
                countryOfOrigin
                genres
                tags {
                  name
                  description
                  category
                  rank
                  isGeneralSpoiler
                  isMediaSpoiler
                  isAdult
                }
                coverImage {
                  large
                  medium
                  extraLarge
                  color
                }
                averageScore
                meanScore
                popularity
                isAdult
                source
                startDate {
                  year
                  month
                  day
                }
                endDate {
                    year
                    month
                    day
                }
                season
                seasonYear
              }
            }
            """;

    public AniListGraphQLClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(java.time.Duration.ofSeconds(10))
                .setReadTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /**
     * Busca manga/novela en AniList por título (tipo MANGA para light novels).
     * Devuelve la primera página de resultados.
     */
    public List<AniListMedia> searchManga(String title) {
        return searchManga(title, 1, 10);
    }

    /**
     * Busca manga/novela en AniList por título con paginación.
     */
    public List<AniListMedia> searchManga(String title, int page, int perPage) {
        Map<String, Object> variables = Map.of(
                "search", title,
                "page", page,
                "perPage", perPage
        );

        AniListGraphQLResponse response = executeQuery(SEARCH_MEDIA_QUERY, variables);

        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            log.warn("Errores GraphQL AniList al buscar '{}': {}", title, response.getErrors());
            return Collections.emptyList();
        }

        if (response.getData() == null || response.getData().getPage() == null) {
            log.warn("Respuesta vacía de AniList para búsqueda '{}'", title);
            return Collections.emptyList();
        }

        return response.getData().getPage().getMedia();
    }

    /**
     * Obtiene un media por su ID de AniList.
     */
    public Optional<AniListMedia> getMediaById(int anilistId) {
        Map<String, Object> variables = Map.of("id", anilistId);
        AniListGraphQLResponse response = executeQuery(GET_MEDIA_BY_ID_QUERY, variables);

        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            log.warn("Errores GraphQL AniList al obtener ID {}: {}", anilistId, response.getErrors());
            return Optional.empty();
        }

        if (response.getData() == null) {
            return Optional.empty();
        }

        // El query GET_MEDIA_BY_ID_QUERY devuelve directamente Media, no Page
        // Necesitamos parsearlo manualmente ya que la estructura es diferente
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> mediaMap = (Map<String, Object>) response.getData();
            // Convertir el mapa a AniListMedia usando Jackson
            // Por simplicidad, reutilizamos la deserialización
            return Optional.empty(); // TODO: implementar deserialización directa
        } catch (Exception e) {
            log.warn("Error parseando Media por ID {}", anilistId, e);
            return Optional.empty();
        }
    }

    private AniListGraphQLResponse executeQuery(String query, Map<String, Object> variables) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "FrontendService/1.0 (contact: admin@frontend-service)");

        Map<String, Object> body = Map.of(
                "query", query,
                "variables", variables
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<AniListGraphQLResponse> response = restTemplate.postForEntity(
                    ANILIST_GRAPHQL_URL, request, AniListGraphQLResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                log.warn("Respuesta no exitosa de AniList: {}", response.getStatusCode());
                return new AniListGraphQLResponse(null, List.of());
            }
        } catch (Exception e) {
            log.error("Error llamando a AniList GraphQL API", e);
            return new AniListGraphQLResponse(null, List.of(new AniListError(e.getMessage(), Collections.emptyList(), null)));
        }
    }
}