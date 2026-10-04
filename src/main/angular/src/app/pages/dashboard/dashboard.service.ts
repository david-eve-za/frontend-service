import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface DatabaseStatus {
    status: 'UP' | 'DOWN';
    product: string | null;
    version: string | null;
    url: string | null;
    fileSizeBytes: number | null;
    error: string | null;
}

export interface RunningProcess {
    bookId: string;
    name: string | null;
    status: string | null;
    currentStep: string | null;
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
    pauseUntil: string | null;
}

export interface ProcessStatus {
    running: RunningProcess[];
    catalogSync: CatalogSyncStatus;
}

export interface BookStats {
    total: number;
    split: number;
    translated: number;
    audioCreated: number;
    readyForAudio: number;
    byStatus: Record<string, number>;
}

export interface ChunkStats {
    total: number;
    completed: number;
}

export interface CatalogStats {
    novels: number;
    volumes: number;
}

/** Mirror of DashboardStatsDto (GET /api/dashboard/stats). */
export interface DashboardStats {
    database: DatabaseStatus;
    processes: ProcessStatus;
    books: BookStats;
    chunks: ChunkStats;
    catalog: CatalogStats;
}

@Injectable({ providedIn: 'root' })
export class DashboardService {
    private readonly API_URL = '/api/dashboard';

    constructor(private http: HttpClient) {}

    getStats(): Observable<DashboardStats> {
        return this.http.get<DashboardStats>(`${this.API_URL}/stats`);
    }
}
