import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, timer, switchMap, takeWhile } from 'rxjs';
import { map } from 'rxjs/operators';

export interface NovelSummary {
  id: string;
  title: string;
  category: string | null;
  volumeCount: number;
  lastSyncedAt: string | null;
  // --- Carátula nativa del crawler (para fuentes no-Elscione) ---
  coverUrl: string | null;
  // --- Campos de enriquecimiento AniList ---
  anilistId: number | null;
  anilistCoverUrl: string | null;
  anilistDescription: string | null;
  anilistGenres: string[] | null;
  anilistTags: string[] | null;
  anilistStatus: string | null;
  anilistAverageScore: number | null;
  anilistFormat: string | null;
  anilistSource: string | null;
  anilistCountryOfOrigin: string | null;
  anilistVolumes: number | null;
  anilistChapters: number | null;
  // --- Metadatos adicionales del crawler ---
  author: string | null;
  status: string | null;
  language: string | null;
  isMtl: boolean;
  isManga: boolean;
  sourceId: string | null;
}

/** Shape of a serialized Spring Data page (GET /api/novels-manager/catalog). */
export interface NovelCatalogPage {
  content: NovelSummary[];
  page: {
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
  };
  links: any[];
}

export interface TranslatedSynopsis {
  obra: string;
  sinopsis: string | null;
}

export interface VolumeFile {
  id: string;
  format: 'EPUB' | 'PDF' | 'ZIP' | 'TXT' | 'OTHER';
  sizeBytes: number | null;
  fileName: string;
  presentLocally: boolean;
}

export interface CatalogVolume {
  id: string;
  volumeNumber: number | null;
  label: string | null;
  translatorGroup: string | null;
  removedFromSource: boolean;
  bookId: string | null;
  bookStatus: string | null;
  splitDone: boolean;
  translated: boolean;
  audioCreated: boolean;
  running: boolean;
  sourceAvailable: boolean;
  canPrepareText: boolean;
  canTranslate: boolean;
  canCreateAudio: boolean;
  files: VolumeFile[];
}

export interface CatalogSyncStatus {
  state: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
  currentPath: string | null;
  novelsSeen: number;
  volumesSeen: number;
  filesSeen: number;
  newNovels: number;
  newVolumes: number;
  removedVolumes: number;
  startedAt: string | null;
  finishedAt: string | null;
  lastError: string | null;
}

export interface VolumePrepared {
  volumeId: string;
  bookId: string;
  bookName: string;
}

export interface AnilistEnrichmentResult {
  success: boolean;
  message: string;
}

export interface BatchAnilistEnrichmentResult {
  enriched: number;
  notFound: number;
  errors: number;
  errorDetails: string[];
}

@Injectable({ providedIn: 'root' })
export class NovelsManagerService {
  private readonly API_URL = '/api/novels-manager';
  private readonly SOURCES_API_URL = '/api/sources';

  constructor(private http: HttpClient) {}

  /**
   * Get catalog from the default source (elscione) or a specific source.
   * If sourceId is provided, uses the new multi-source API.
   * Otherwise, uses the legacy elscione-only endpoint for backward compatibility.
   */
  getCatalog(search: string | null, page: number, size: number, sourceId?: string): Observable<NovelCatalogPage> {
    if (sourceId) {
      // Use new multi-source API
      let params = new HttpParams().set('page', page).set('size', size);
      if (search && search.trim()) {
        params = params.set('q', search.trim());
      }
      return this.http.get<any>(`${this.SOURCES_API_URL}/${sourceId}/search`, { params }).pipe(
        map(response => {
          const rawContent = response.content || response;
          // Map NovelSearchResult[] to NovelSummary[] with crawler's cover image
          const content = (Array.isArray(rawContent) ? rawContent : []).map((item: any) => ({
            id: item.sourceNovelId || item.url,
            title: item.title,
            category: null,
            volumeCount: 0,
            lastSyncedAt: null,
            coverUrl: item.coverUrl || null, // Crawler's native cover
            // AniList fields (will be populated after enrichment)
            anilistId: null,
            anilistCoverUrl: null,
            anilistDescription: null,
            anilistGenres: null,
            anilistTags: null,
            anilistStatus: null,
            anilistAverageScore: null,
            anilistFormat: null,
            anilistSource: null,
            anilistCountryOfOrigin: null,
            anilistVolumes: null,
            anilistChapters: null,
            // Additional crawler metadata
            author: null,
            status: item.info || null,
            language: null,
            isMtl: false,
            isManga: false,
            sourceId: sourceId
          }));
          return {
            content,
            page: {
              number: response.page ?? page,
              size: response.size ?? size,
              totalElements: response.totalElements ?? 0,
              totalPages: response.totalPages ?? 1
            },
            links: response.links || []
          };
        })
      );
    }

    // Legacy elscione-only endpoint
    let params = new HttpParams().set('page', page).set('size', size);
    if (search && search.trim()) {
      params = params.set('search', search.trim());
    }
    return this.http.get<any>(`${this.API_URL}/catalog`, { params }).pipe(
      map(response => ({
        content: response.content,
        page: response.page || {
          number: response.number ?? 0,
          size: response.size ?? 0,
          totalElements: response.totalElements ?? 0,
          totalPages: response.totalPages ?? 0
        },
        links: response.links || []
      }))
    );
  }

  /** Obtiene una novela individual por ID (con campos AniList y metadatos del crawler). */
  getNovel(novelId: string, sourceId?: string): Observable<NovelSummary> {
    if (sourceId) {
      return this.http.get<any>(`${this.SOURCES_API_URL}/${sourceId}/novels/${novelId}`).pipe(
        map((detail: any) => ({
          id: detail.sourceNovelId || novelId,
          title: detail.title,
          category: null,
          volumeCount: detail.volumes?.length || 0,
          lastSyncedAt: null,
          coverUrl: detail.coverUrl || null, // Crawler's native cover
          // AniList fields
          anilistId: detail.anilistId || null,
          anilistCoverUrl: detail.anilistCoverUrl || null,
          anilistDescription: detail.anilistDescription || detail.synopsis || null,
          anilistGenres: detail.anilistGenres || detail.tags || null,
          anilistTags: detail.anilistTags || null,
          anilistStatus: detail.anilistStatus || detail.status || null,
          anilistAverageScore: detail.anilistAverageScore || null,
          anilistFormat: detail.anilistFormat || null,
          anilistSource: detail.anilistSource || null,
          anilistCountryOfOrigin: detail.anilistCountryOfOrigin || null,
          anilistVolumes: detail.anilistVolumes || null,
          anilistChapters: detail.anilistChapters || null,
          // Additional crawler metadata
          author: detail.author || null,
          status: detail.status || null,
          language: detail.language || null,
          isMtl: detail.isMtl || false,
          isManga: detail.isManga || false,
          sourceId: sourceId
        }))
      );
    }
    return this.http.get<NovelSummary>(`${this.API_URL}/novels/${novelId}`);
  }

  getVolumes(novelId: string, sourceId?: string): Observable<CatalogVolume[]> {
    if (sourceId) {
      // Convert ChapterInfo[] to CatalogVolume[] format
      return this.http.get<any[]>(`${this.SOURCES_API_URL}/${sourceId}/novels/${novelId}/chapters`).pipe(
        map(chapters => this.groupChaptersIntoVolumes(chapters))
      );
    }
    return this.http.get<CatalogVolume[]>(`${this.API_URL}/novels/${novelId}/volumes`);
  }

  startSync(sourceId?: string): Observable<CatalogSyncStatus> {
    if (sourceId) {
      // For multi-source, sync is per-source - would need new endpoint
      // For now, fall back to elscione sync
      return this.http.post<CatalogSyncStatus>(`${this.API_URL}/sync`, {});
    }
    return this.http.post<CatalogSyncStatus>(`${this.API_URL}/sync`, {});
  }

  getSyncStatus(sourceId?: string): Observable<CatalogSyncStatus> {
    if (sourceId) {
      // Would need per-source sync status endpoint
      return this.http.get<CatalogSyncStatus>(`${this.API_URL}/sync/status`);
    }
    return this.http.get<CatalogSyncStatus>(`${this.API_URL}/sync/status`);
  }

  cancelSync(sourceId?: string): Observable<{ cancelled: boolean }> {
    if (sourceId) {
      return this.http.post<{ cancelled: boolean }>(`${this.API_URL}/sync/cancel`, {});
    }
    return this.http.post<{ cancelled: boolean }>(`${this.API_URL}/sync/cancel`, {});
  }

  /**
   * Enriquece una novela buscando en AniList por su título.
   */
  enrichNovelAnilist(novelId: string, sourceId?: string): Observable<AnilistEnrichmentResult> {
    if (sourceId && sourceId !== 'elscione') {
      // AniList enrichment only available for elscione currently
      return this.http.post<AnilistEnrichmentResult>(`${this.API_URL}/novels/${novelId}/enrich-anilist`, {});
    }
    return this.http.post<AnilistEnrichmentResult>(`${this.API_URL}/novels/${novelId}/enrich-anilist`, {});
  }

  /** Enriquecimiento en lote de múltiples novelas. */
  enrichBatchAnilist(novelIds: string[], sourceId?: string): Observable<BatchAnilistEnrichmentResult> {
    return this.http.post<BatchAnilistEnrichmentResult>(`${this.API_URL}/novels/enrich-batch-anilist`, novelIds);
  }

  /*** Traduce todas las sinopsis/descripciones de AniList al español. */
  translateAllSynopses(sourceId?: string): Observable<TranslatedSynopsis[]> {
    return this.http.get<TranslatedSynopsis[]>(`${this.API_URL}/novels/translate-synopses`);
  }

  /** Obtiene todos los IDs de novelas. */
  getAllNovelIds(sourceId?: string): Observable<string[]> {
    if (sourceId) {
      return this.http.get<string[]>(`${this.SOURCES_API_URL}/${sourceId}/novels/ids`);
    }
    return this.http.get<string[]>(`${this.API_URL}/novels/ids`);
  }

  /** Enriquece todas las novelas con AniList. */
  enrichAllNovels(sourceId?: string): Observable<BatchAnilistEnrichmentResult> {
    return this.http.post<BatchAnilistEnrichmentResult>(`${this.API_URL}/novels/enrich-all-anilist`, {});
  }

  /**
   * Obtiene el texto del volumen (archivo local del crawler o descarga
   * on-demand) y crea el Book del pipeline: el split se hace después en el
   * wizard con el editor de bloques.
   */
  prepareVolumeText(volumeId: string, sourceId?: string): Observable<VolumePrepared> {
    if (sourceId && sourceId !== 'elscione') {
      // Would need new endpoint for multi-source
      // Fall back to elscione for now
    }
    return this.http.post<VolumePrepared>(`${this.API_URL}/volumes/${volumeId}/prepare-text`, {});
  }

  /** Traduce / genera audio del Book enlazado al volumen (pipeline por etapas). */
  processVolumeBook(bookId: string, target: 'TRANSLATE' | 'AUDIO', sourceId?: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/api/files/${bookId}/process?to=${target}`, {});
  }

  /** Sondea el estado de sync mientras esté RUNNING. */
  pollSyncStatus(intervalMs: number, sourceId?: string): Observable<CatalogSyncStatus> {
    return timer(0, intervalMs).pipe(
      switchMap(() => this.getSyncStatus(sourceId)),
      takeWhile(status => status.state === 'RUNNING', true)
    );
  }

  /** Helper: group flat chapter list into volumes by volumeId */
  private groupChaptersIntoVolumes(chapters: any[]): CatalogVolume[] {
    const volumeMap = new Map<number, CatalogVolume>();
    
    for (const ch of chapters) {
      const volId = ch.volumeId || 1;
      if (!volumeMap.has(volId)) {
        volumeMap.set(volId, {
          id: `vol-${volId}`,
          volumeNumber: volId,
          label: `Volume ${volId}`,
          translatorGroup: null,
          removedFromSource: false,
          bookId: null,
          bookStatus: null,
          splitDone: false,
          translated: false,
          audioCreated: false,
          running: false,
          sourceAvailable: true,
          canPrepareText: true,
          canTranslate: true,
          canCreateAudio: true,
          files: []
        });
      }
      // We don't have chapter details in the new API yet, just basic info
    }
    
    return Array.from(volumeMap.values());
  }
}