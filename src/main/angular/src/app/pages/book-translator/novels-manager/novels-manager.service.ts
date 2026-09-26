import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, timer, switchMap, takeWhile } from 'rxjs';

export interface NovelSummary {
  id: string;
  title: string;
  category: string | null;
  volumeCount: number;
  lastSyncedAt: string | null;
}

/** Shape of a serialized Spring Data page (GET /api/novels-manager/catalog). */
export interface NovelCatalogPage {
  content: NovelSummary[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
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

@Injectable({ providedIn: 'root' })
export class NovelsManagerService {
  private readonly API_URL = '/api/novels-manager';

  constructor(private http: HttpClient) {}

  getCatalog(search: string | null, page: number, size: number): Observable<NovelCatalogPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search && search.trim()) {
      params = params.set('search', search.trim());
    }
    return this.http.get<NovelCatalogPage>(`${this.API_URL}/catalog`, { params });
  }

  getVolumes(novelId: string): Observable<CatalogVolume[]> {
    return this.http.get<CatalogVolume[]>(`${this.API_URL}/novels/${novelId}/volumes`);
  }

  startSync(): Observable<CatalogSyncStatus> {
    return this.http.post<CatalogSyncStatus>(`${this.API_URL}/sync`, {});
  }

  getSyncStatus(): Observable<CatalogSyncStatus> {
    return this.http.get<CatalogSyncStatus>(`${this.API_URL}/sync/status`);
  }

  cancelSync(): Observable<{ cancelled: boolean }> {
    return this.http.post<{ cancelled: boolean }>(`${this.API_URL}/sync/cancel`, {});
  }

  /**
   * Obtiene el texto del volumen (archivo local del crawler o descarga
   * on-demand) y crea el Book del pipeline: el split se hace después en el
   * wizard con el editor de bloques.
   */
  prepareVolumeText(volumeId: string): Observable<VolumePrepared> {
    return this.http.post<VolumePrepared>(`${this.API_URL}/volumes/${volumeId}/prepare-text`, {});
  }

  /** Traduce / genera audio del Book enlazado al volumen (pipeline por etapas). */
  processVolumeBook(bookId: string, target: 'TRANSLATE' | 'AUDIO'): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/api/files/${bookId}/process?to=${target}`, {});
  }

  /** Sondea el estado de sync mientras esté RUNNING. */
  pollSyncStatus(intervalMs: number): Observable<CatalogSyncStatus> {
    return timer(0, intervalMs).pipe(
      switchMap(() => this.getSyncStatus()),
      takeWhile(status => status.state === 'RUNNING', true)
    );
  }
}
