import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

/** Término del glosario de traducción (entidad TranslationTerm). */
export interface GlossaryTerm {
    id: string;
    sourceTerm: string;
    targetTerm: string;
    createdAt: string;
    updatedAt?: string | null;
}

/** Payload de creación/actualización de un término. */
export interface GlossaryTermPayload {
    novelId?: string | null;
    bookId?: string | null;
    sourceTerm: string;
    targetTerm: string;
}

/**
 * Cliente del glosario de traducción (/api/glossary): los términos con su
 * traducción establecida, por alcance (global, serie o libro).
 */
@Injectable({ providedIn: 'root' })
export class GlossaryClientService {
    private readonly API_URL = '/api/glossary';

    constructor(private http: HttpClient) {}

    /** Términos globales (sin novelId ni bookId). */
    listGlobal(): Observable<GlossaryTerm[]> {
        return this.http.get<GlossaryTerm[]>(this.API_URL);
    }

    /** Términos de una serie. */
    listByNovel(novelId: string): Observable<GlossaryTerm[]> {
        return this.http.get<GlossaryTerm[]>(this.API_URL, {
            params: { novelId },
        });
    }

    /** Términos acotados a un libro. */
    listByBook(bookId: string): Observable<GlossaryTerm[]> {
        return this.http.get<GlossaryTerm[]>(this.API_URL, {
            params: { bookId },
        });
    }

    /** Crea un término o actualiza su traducción si ya existe en el alcance. */
    save(payload: GlossaryTermPayload): Observable<GlossaryTerm> {
        return this.http.post<GlossaryTerm>(this.API_URL, payload);
    }

    delete(id: string): Observable<void> {
        return this.http.delete<void>(`${this.API_URL}/${id}`);
    }
}
