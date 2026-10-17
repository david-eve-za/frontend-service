import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

/** Proveedores válidos por caso de uso. */
export type AiProvider = 'nvidia' | 'ollama';

/** Estado del selector: proveedor activo por caso de uso + pools disponibles. */
export interface AiProviderState {
    translationProvider: AiProvider;
    storyProvider: AiProvider;
    nvidiaModels: string[];
    ollamaModels: string[];
}

/**
 * Cliente del selector de proveedor de IA (/api/ai-providers): traducción
 * de libros y Asistente de Historias pueden usar el pool de NVIDIA (cloud
 * con fallback local) o el pool 100% local de Ollama. El cambio aplica en
 * caliente y persiste entre reinicios.
 */
@Injectable({ providedIn: 'root' })
export class AiProviderClientService {
    private readonly API_URL = '/api/ai-providers';

    constructor(private http: HttpClient) {}

    /** Selección actual + modelos de cada pool. */
    load(): Observable<AiProviderState> {
        return this.http.get<AiProviderState>(this.API_URL);
    }

    /** Actualiza la selección (parcial: omite lo que no quieras cambiar). */
    update(payload: {
        translationProvider?: AiProvider;
        storyProvider?: AiProvider;
    }): Observable<AiProviderState> {
        return this.http.put<AiProviderState>(this.API_URL, payload);
    }
}
