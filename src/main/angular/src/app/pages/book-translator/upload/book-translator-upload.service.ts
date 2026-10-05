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
    status: 'STARTED' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
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

    getBookChunks(
        bookId: string,
        page: number,
        size: number,
    ): Observable<ChunkPage> {
        const params = new HttpParams().set('page', page).set('size', size);
        return this.http.get<ChunkPage>(`${this.API_URL}/${bookId}/chunks`, {
            params,
        });
    }

    getBookBlocks(bookId: string): Observable<BlockItem[]> {
        return this.http.get<BlockItem[]>(`/api/blocks/${bookId}`);
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

    processBook(bookId: string): Observable<{ message: string }> {
        return this.http.post<{ message: string }>(
            `${this.API_URL}/${bookId}/process`,
            {},
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
        intervalMs: number = 2000,
    ): Observable<BookStatusResponse> {
        return new Observable((subscriber) => {
            const poll = () => {
                this.getBookStatus(bookId).subscribe({
                    next: (response) => {
                        subscriber.next(response);
                        if (
                            response.status === 'COMPLETED' ||
                            response.status === 'FAILED'
                        ) {
                            subscriber.complete();
                        } else {
                            setTimeout(poll, intervalMs);
                        }
                    },
                    error: (err) => subscriber.error(err),
                });
            };
            poll();
        });
    }
}
