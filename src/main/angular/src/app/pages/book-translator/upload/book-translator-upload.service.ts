import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface UploadResponse {
    message: string;
    bookId: string;
    status: string;
    error?: string;
    success?: boolean;
}

export interface BookStatusResponse {
    bookId: string;
    status: string;
    currentStep?: string;
    lastTraceId?: string;
    audioFilePath?: string;
}

export interface TraceEvent {
    id: string;
    traceId: string;
    step: 'EXTRACT' | 'SPLIT' | 'TRANSLATE' | 'AUDIO' | 'FINALIZE';
    status: 'STARTED' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'CANCELLED';
    attempt: number;
    startedAt: string;
    finishedAt?: string;
    errorMessage?: string;
    details?: string;
}

export interface SemanticBlock {
    type: 'prologue' | 'chapter' | 'epilogue';
    id: number;
    content: string;
}

export interface SaveBlocksPayload {
    documentId: string;
    rawText: string;
    blocks: SemanticBlock[];
}

export interface BookListItem {
    id: string;
    name: string;
    status: string;
    currentStep?: string;
    lastTraceId?: string;
    audioFilePath?: string;
    /** Total de chunks del libro (progreso de traducción, para el picker de Generar Audiolibro). */
    totalChunks?: number;
    /** Chunks ya traducidos: totalChunks > 0 && totalChunks === completedChunks = libro traducido. */
    completedChunks?: number;
}

/** Bloque semántico de un libro (GET /api/blocks/{bookId}). */
export interface BlockItem {
    id: string;
    blockType: 'PROLOGUE' | 'CHAPTER' | 'EPILOGUE';
    blockNumber: number | null;
    position: number;
    preview: string;
    contentLength: number;
}

/** Chunk de un libro con su texto original y su traducción (GET .../chunks). */
export interface Chunk {
    id: string;
    originalText: string | null;
    translatedText: string | null;
    status: 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'AWAITING';
    position: number;
    /** Causa del último fallo de traducción (null si el chunk está sano) — popup en vivo. */
    errorMessage?: string | null;
    /** Bloque semántico propietario (null para chunks legados sin bloque). */
    blockId?: string | null;
    blockType?: 'PROLOGUE' | 'CHAPTER' | 'EPILOGUE' | null;
    blockNumber?: number | null;
}

/** Página de chunks serializada por Spring Data (PaginatedResponse). */
export interface ChunkPage {
    content: Chunk[];
    pageNumber: number;
    pageSize: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
    first: boolean;
}

@Injectable({
    providedIn: 'root',
})
export class BookTranslatorUploadService {
    private readonly API_URL = '/api/files';

    constructor(private http: HttpClient) {}

    uploadFile(file: File): Observable<UploadResponse> {
        const formData = new FormData();
        formData.append('file', file);
        return this.http.post<UploadResponse>(
            `${this.API_URL}/upload`,
            formData,
        );
    }

    uploadFiles(files: File[]): Observable<UploadResponse[]> {
        // Upload files sequentially
        const uploads$ = files.map((file) => this.uploadFile(file));
        // We'll handle this in the component with forkJoin or sequential
        return new Observable((subscriber) => {
            const results: UploadResponse[] = [];
            let currentIndex = 0;

            const uploadNext = () => {
                if (currentIndex >= files.length) {
                    subscriber.next(results);
                    subscriber.complete();
                    return;
                }

                this.uploadFile(files[currentIndex]).subscribe({
                    next: (response) => {
                        results.push(response);
                        currentIndex++;
                        uploadNext();
                    },
                    error: (err) => {
                        subscriber.error(err);
                    },
                });
            };

            uploadNext();
        });
    }

    getBookStatus(bookId: string): Observable<BookStatusResponse> {
        return this.http.get<BookStatusResponse>(
            `${this.API_URL}/${bookId}/status`,
        );
    }

    getAllBooks(): Observable<BookListItem[]> {
        return this.http.get<BookListItem[]>(`${this.API_URL}/list`);
    }

    /**
     * Vista paralela de los chunks en orden de lectura, con el bloque
     * propietario de cada uno. Con blockIds se limita a los chunks de esos
     * bloques (vista del alcance de traducción marcado).
     */
    getBookChunks(
        bookId: string,
        page: number,
        size: number,
        blockIds: string[] = [],
    ): Observable<ChunkPage> {
        let params = new HttpParams().set('page', page).set('size', size);
        if (blockIds.length > 0) {
            params = params.set('blocks', blockIds.join(','));
        }
        return this.http.get<ChunkPage>(`${this.API_URL}/${bookId}/chunks`, {
            params,
        });
    }

    /**
     * Traduce UN chunk suelto sin afectar al resto del libro (202: el chunk
     * pasa a PROCESSING y termina COMPLETED/FAILED en segundo plano).
     */
    translateChunk(
        bookId: string,
        chunkId: string,
    ): Observable<{ message: string }> {
        return this.http.post<{ message: string }>(
            `${this.API_URL}/${bookId}/chunks/${chunkId}/translate`,
            {},
        );
    }

    getBookBlocks(bookId: string): Observable<BlockItem[]> {
        return this.http.get<BlockItem[]>(`/api/blocks/${bookId}`);
    }

    /**
     * Texto etiquetado (blockML) reconstruido a partir de los bloques
     * persistidos: los tags son solo delimitadores editoriales y jamás se
     * almacenan; esta vista derivada alimenta el editor de Dividir Texto.
     * Vacío cuando el documento no tiene bloques.
     */
    getRawTaggedText(bookId: string): Observable<string> {
        return this.http.get(`/api/blocks/${bookId}/raw`, {
            responseType: 'text',
        });
    }

    /**
     * Inicia la traducción de un libro. Con blockIds no vacíos el alcance se
     * limita a esos bloques: los chunks se regeneran con su contenido.
     */
    startTranslation(
        bookId: string,
        blockIds: string[],
    ): Observable<{ message: string }> {
        let params = new HttpParams().set('to', 'TRANSLATE');
        if (blockIds.length > 0) {
            params = params.set('blocks', blockIds.join(','));
        }
        return this.http.post<{ message: string }>(
            `${this.API_URL}/${bookId}/process`,
            {},
            { params },
        );
    }

    splitBookIntoChunks(bookId: string): Observable<{ message: string }> {
        return this.http.post<{ message: string }>(
            `${this.API_URL}/${bookId}/split`,
            {},
        );
    }

    /**
     * Genera el audiolibro de UN libro YA TRADUCIDO, como etapa manual e
     * independiente (checkpoint AUDIO, el mismo contrato que usa el gestor
     * de novelas): sin traducción encadenada, reanudable (los bloques con
     * audio en disco se saltan).
     */
    generateAudioBook(bookId: string): Observable<{ message: string }> {
        const params = new HttpParams().set('to', 'AUDIO');
        return this.http.post<{ message: string }>(
            `${this.API_URL}/${bookId}/process`,
            {},
            { params },
        );
    }

    /**
     * Fija la voz Edge TTS del audiolibro de UN libro (selección por libro,
     * persistida en BD): null = volver a la voz por defecto. Se aplica a
     * todas las corridas de audio del libro.
     */
    setBookTtsVoice(bookId: string, voice: string | null): Observable<{ message: string }> {
        return this.http.put<{ message: string }>(
            `${this.API_URL}/${bookId}/tts-voice`,
            { voice },
        );
    }

    getBookTrace(bookId: string): Observable<TraceEvent[]> {
        return this.http.get<TraceEvent[]>(`${this.API_URL}/${bookId}/trace`);
    }

    getBookFullText(bookId: string): Observable<string> {
        return this.http.get(`${this.API_URL}/${bookId}/full-text`, {
            responseType: 'text',
        });
    }

    saveBlocks(
        payload: SaveBlocksPayload,
    ): Observable<{ message: string; blocksSaved: number }> {
        return this.http.post<{ message: string; blocksSaved: number }>(
            '/api/blocks',
            payload,
        );
    }

    pollBookStatus(
        bookId: string,
        intervalMs: number = 5000, // 5s en lugar de 2s por defecto
    ): Observable<BookStatusResponse> {
        return new Observable((subscriber) => {
            let consecutiveErrors = 0;
            const baseIntervalMs = intervalMs;
            const maxIntervalMs = 30000;
            let isSubscribed = true;
            
            const isVisible = () => !document.hidden;
            
            const poll = () => {
                if (!isSubscribed) return;
                
                if (!isVisible()) {
                    setTimeout(poll, 5000);
                    return;
                }
                
                this.getBookStatus(bookId).subscribe({
                    next: (response) => {
                        if (!isSubscribed) return;
                        consecutiveErrors = 0;
                        subscriber.next(response);
                        if (
                            response.status === 'COMPLETED' ||
                            response.status === 'FAILED'
                        ) {
                            subscriber.complete();
                        } else {
                            setTimeout(poll, baseIntervalMs);
                        }
                    },
                    error: (err) => {
                        if (!isSubscribed) return;
                        consecutiveErrors++;
                        const backoffMs = Math.min(baseIntervalMs * Math.pow(2, consecutiveErrors - 1), maxIntervalMs);
                        console.warn(`Book status poll failed for ${bookId}, backing off to ${backoffMs}ms`);
                        setTimeout(poll, backoffMs);
                    },
                });
            };
            poll();
            
            // Cleanup al desuscribirse
            return () => {
                isSubscribed = false;
            };
        });
    }
}
