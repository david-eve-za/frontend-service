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

/** Libro con ciclo de vida activo (todo estado salvo COMPLETED). */
export interface BookProcess {
    bookId: string;
    name: string | null;
    status: string;
    currentStep: string | null;
    /** true si hay una corrida real de esta instancia ahora mismo. */
    running: boolean;
}

/**
 * Proyecto del Asistente de Historias con la cadena incompleta (panel de
 * procesos): estado derivado, paso siguiente y progreso n/5.
 */
export interface WizardProcess {
    projectId: string;
    title: string;
    /** RUNNING | FAILED | IN_PROGRESS | NEW */
    state: string;
    currentStep: string;
    stepsDone: number;
    stepsTotal: number;
    running: boolean;
    updatedAt: string;
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
    books: BookProcess[];
    wizardProcesses: WizardProcess[];
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

/** Descubrimientos del catálogo de elscione en un día (del sync). */
export interface CatalogActivityPoint {
    day: string;
    novels: number;
    volumes: number;
}

export interface CatalogStats {
    novels: number;
    volumes: number;
    activity: CatalogActivityPoint[];
}

/** Actividad del asistente en un día (para el gráfico del panel). */
export interface WizardActivityPoint {
    day: string;
    createdProjects: number;
    completedSteps: number;
    failedSteps: number;
}

/** Métricas del Asistente de Historias (gráfico del panel de estado). */
export interface WizardStats {
    totalProjects: number;
    completedChains: number;
    stepsCompleted: number;
    stepsFailed: number;
    activity: WizardActivityPoint[];
}

/** Mirror of DashboardStatsDto (GET /api/dashboard/stats). */
export interface DashboardStats {
    database: DatabaseStatus;
    processes: ProcessStatus;
    books: BookStats;
    chunks: ChunkStats;
    catalog: CatalogStats;
    wizard: WizardStats;
}

@Injectable({ providedIn: 'root' })
export class DashboardService {
    private readonly API_URL = '/api/dashboard';
    private readonly FILES_URL = '/api/files';
    private readonly NOVELS_URL = '/api/novels-manager';

    constructor(private http: HttpClient) {}

    getStats(): Observable<DashboardStats> {
        return this.http.get<DashboardStats>(`${this.API_URL}/stats`);
    }

    /** Arranca/reanuda el pipeline de un libro (despierta también STOPPED). */
    startBook(bookId: string): Observable<object> {
        return this.http.post(`${this.FILES_URL}/${bookId}/process`, {});
    }

    /** Pausa cooperativa: el paso en curso termina y se detiene en el checkpoint. */
    pauseBook(bookId: string): Observable<object> {
        return this.http.post(`${this.FILES_URL}/${bookId}/pause`, {});
    }

    /** Detención inmediata: cancela el trabajo en vuelo y deja el libro STOPPED. */
    stopBook(bookId: string): Observable<object> {
        return this.http.post(`${this.FILES_URL}/${bookId}/stop`, {});
    }

    /** Lanza la sincronización del catálogo de elscione. */
    startCatalogSync(): Observable<object> {
        return this.http.post(`${this.NOVELS_URL}/sync`, {});
    }

    /** Cancela la sincronización del catálogo en curso (retomable). */
    cancelCatalogSync(): Observable<object> {
        return this.http.post(`${this.NOVELS_URL}/sync/cancel`, {});
    }
}
