import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface AiProcessRequest {
    text: string;
    parameters?: Record<string, string>;
}

export interface AiProcessResponse {
    originalText: string;
    processedResult: string;
    modelUsed: string | null;
}

/**
 * Service for communicating with the AI Processing backend endpoint.
 */
@Injectable({ providedIn: 'root' })
export class AiProcessorService {
    private readonly API_URL = '/api/process-data';

    constructor(private http: HttpClient) {}

    /**
     * Send text for AI processing.
     */
    processData(request: AiProcessRequest): Observable<AiProcessResponse> {
        return this.http.post<AiProcessResponse>(this.API_URL, request);
    }
}