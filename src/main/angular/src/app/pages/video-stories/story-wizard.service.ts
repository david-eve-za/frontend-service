import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

/** Proyecto del Asistente: la cadena completa 1 historia → 2 diseños → 3 assets → 4 escenas → 5 video. */
export interface VideoStoryProject {
    id: string;
    title: string;
    story?: string | null;
    /** PASO 1 — Duración objetivo del video en segundos (dirige historia y escenas). */
    videoDurationSeconds?: number | null;
    designStyle?: string | null;
    designCharacterCount?: number | null;
    /** PASO 2 — Diseños de personajes generados por la IA (uno por bloque). */
    designs?: string | null;
    /** PASO 3 — Relación de aspecto elegida para la biblioteca de assets (16:9/9:16/1:1). */
    assetsAspectRatio?: string | null;
    /** PASO 3 — Biblioteca de assets generada por la IA (entornos, props, tecnología, documentos…). */
    assets?: string | null;
    /** PASO 4 — Duración media por escena en segundos (el nº se deriva de la duración total). */
    sceneAvgSeconds?: number | null;
    sceneSpeaker1?: string | null;
    sceneSpeaker2?: string | null;
    sceneLanguage?: string | null;
    scenes?: string | null;
    videoPrompt?: string | null;
    createdAt: string;
    updatedAt?: string | null;
}

/** Resumen para la lista (sin outputs pesados). */
export interface VideoStoryProjectSummary {
    id: string;
    title: string;
    hasStory: boolean;
    hasDesigns: boolean;
    hasAssets: boolean;
    hasScenes: boolean;
    hasVideoPrompt: boolean;
    createdAt: string;
}

/** Payload del paso 1: el mismo formulario de historia del Creador de Historias. */
export interface StoryPromptPayload {
    title: string;
    genre: string;
    subgenre?: string | null;
    targetAudience: string;
    countryCulture?: string | null;
    location?: string | null;
    familyFriendly?: string | null;
    visualStyle?: string | null;
    tone: string;
    realismLevel?: string | null;
    overallFeeling?: string | null;
    themes?: string | null;
    characterCount?: number | null;
    character1Requirements?: string | null;
    character2Requirements?: string | null;
    otherCharacterRequirements?: string | null;
    endingType?: string | null;
    mainEmotionalConflict: string;
    unresolvedElement: string;
    dialogueLanguage: string;
    videoDurationSeconds: number;
    pacingFirstPercent?: number | null;
    pacingMiddlePercent?: number | null;
    pacingFinalPercent?: number | null;
    platform: string;
}

/** Paso 2: parámetros de los diseños de personajes. */
export interface DesignStepPayload {
    animationStyle: string;
    characterCount: number;
}

/** Paso 3: parámetros de la biblioteca de assets — todos opcionales. */
export interface AssetsStepPayload {
    animationStyle?: string | null;
    targetAudience?: string | null;
    aspectRatio?: string | null;
    colorPalette?: string | null;
}

/** Paso 4: parámetros de las escenas. */
export interface ScenesStepPayload {
    averageSceneSeconds: number;
    speakerFirst?: string | null;
    speakerSecond?: string | null;
    language: string;
}

/** Paso 4 (opcional): la escena que se transporta a la plantilla de video. */
export interface VideoStepPayload {
    scene: string;
    animationStyle?: string | null;
}

/**
 * Cliente del Asistente de Historias en Video (/api/video-story-projects):
 * la timeline encadenada por la IA — cada llamada genera un paso con la
 * salida del anterior y puede tardar varios minutos.
 */
@Injectable({ providedIn: 'root' })
export class StoryWizardClientService {
    private readonly API_URL = '/api/video-story-projects';

    constructor(private http: HttpClient) {}

    /** Proyectos del asistente (resúmenes), más recientes primero. */
    list(): Observable<VideoStoryProjectSummary[]> {
        return this.http.get<VideoStoryProjectSummary[]>(this.API_URL);
    }

    /** Proyecto completo con todos los outputs de la cadena. */
    get(id: string): Observable<VideoStoryProject> {
        return this.http.get<VideoStoryProject>(`${this.API_URL}/${id}`);
    }

    /** PASO 1: crea el proyecto y genera la historia con la IA. */
    createWithStory(payload: StoryPromptPayload): Observable<VideoStoryProject> {
        return this.aiCall(this.http.post<VideoStoryProject>(this.API_URL, payload));
    }

    /** PASO 2: genera los diseños de personajes sobre la historia. */
    generateDesigns(id: string, payload: DesignStepPayload): Observable<VideoStoryProject> {
        return this.aiCall(
            this.http.post<VideoStoryProject>(`${this.API_URL}/${id}/designs`, payload));
    }

    /** PASO 3: genera la biblioteca de assets sobre historia y diseños. */
    generateAssets(id: string, payload: AssetsStepPayload): Observable<VideoStoryProject> {
        return this.aiCall(
            this.http.post<VideoStoryProject>(`${this.API_URL}/${id}/assets`, payload));
    }

    /** PASO 4: genera las escenas sobre historia y diseños. */
    generateScenes(id: string, payload: ScenesStepPayload): Observable<VideoStoryProject> {
        return this.aiCall(
            this.http.post<VideoStoryProject>(`${this.API_URL}/${id}/scenes`, payload));
    }

    /** PASO 5 (opcional): transporta una escena a la plantilla de video. */
    generateVideo(id: string, payload: VideoStepPayload): Observable<VideoStoryProject> {
        return this.aiCall(
            this.http.post<VideoStoryProject>(`${this.API_URL}/${id}/video`, payload));
    }

    delete(id: string): Observable<void> {
        return this.http.delete<void>(`${this.API_URL}/${id}`);
    }

    /**
     * Las llamadas de generación devuelven el error de la IA en
     * {message} (502/409/400): lo propagamos como texto para el toast.
     */
    private aiCall<T>(call: Observable<T>): Observable<T> {
        return call.pipe(
            catchError((err: HttpErrorResponse) =>
                throwError(() =>
                    err.error?.message
                        ? err.error.message
                        : 'La generación con la IA falló (¿proveedor caído?). Vuelve a intentarlo.',
                ),
            ),
        );
    }
}
