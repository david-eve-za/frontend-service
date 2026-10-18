import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SelectModule } from 'primeng/select';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import {
    BookTranslatorUploadService,
    BookListItem,
} from './book-translator-upload.service';

export interface ProcessingConfig {
    targetLanguage: string;
}

/** Opción del combo de voces REALES de Edge TTS (short names del servicio). */
export interface EdgeVoiceOption {
    label: string;
    value: string;
}

/** Clave de persistencia local de la configuración de generación de audio. */
const AUDIOBOOK_CONFIG_KEY = 'audiobook-config';

/** Opción del combo: un libro ya traducido, listo para generar su audiolibro. */
export interface TranslatedBookOption {
    label: string;
    value: string;
    /** El libro ya tiene audiolibro generado (re-ejecutar lo reanuda/completa). */
    hasAudio: boolean;
}

/**
 * Página Generar Audiolibro del pipeline: SELECCIÓN MANUAL del libro ya
 * traducido (combo) y configuración de la voz. El audio NUNCA se encadena
 * tras la traducción: la traducción termina en su checkpoint (to=TRANSLATE)
 * y esta página es la única entrada manual de la etapa de audio
 * (to=AUDIO — el mismo contrato de etapa independiente que usa el gestor
 * de novelas; los bloques con audio en disco se saltan, de modo que
 * re-ejecutar reanuda donde se quedó).
 *
 * La configuración de voz se guarda en localStorage hasta que el backend
 * exponga un endpoint de configuración.
 */
@Component({
    selector: 'app-upload-step2-config',
    standalone: true,
    imports: [
        CommonModule,
        RouterLink,
        FormsModule,
        SelectModule,
        ButtonModule,
        ToastModule,
    ],
    template: `
        <p-toast [life]="12000"></p-toast>

        <div class="card">
            <div class="font-semibold text-xl mb-4">Generar Audiolibro</div>
            <p class="text-gray-600 mb-6">
                Selecciona el libro traducido al que quieres generar el
                audiolibro y configura la voz. La traducción ya no lanza el
                audio automáticamente.
            </p>

            <!-- Libro ya traducido -->
            <div class="mb-6">
                <label class="block text-sm font-medium mb-2"
                    >Libro traducido *</label
                >
                @if (loadingBooks) {
                    <div class="text-gray-500 flex items-center gap-2">
                        <i class="pi pi-spin pi-spinner"></i>
                        Cargando libros…
                    </div>
                } @else if (translatedOptions.length === 0) {
                    <div
                        class="p-4 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-600"
                    >
                        Aún no hay libros traducidos: traduce un documento
                        desde Traducir Libro y aparecerá aquí.
                    </div>
                } @else {
                    <p-select
                        [options]="translatedOptions"
                        [(ngModel)]="selectedBookId"
                        optionLabel="label"
                        optionValue="value"
                        placeholder="Selecciona un libro traducido"
                        styleClass="w-full"
                        [showClear]="true"
                    >
                    </p-select>
                    <small class="text-gray-500"
                        >Solo se listan los libros con todos sus chunks
                        traducidos. Los marcados con ✔ ya tienen audiolibro:
                        volver a generarlos reanuda lo que falte.</small
                    >
                }
            </div>

            <!-- Voz Edge TTS del audiolibro (se persiste por libro en la BD) -->
            <div class="mb-6">
                <label class="block text-sm font-medium mb-2"
                    >Voz del audiolibro *</label
                >
                <p-select
                    [options]="edgeVoiceOptions"
                    [(ngModel)]="selectedVoice"
                    optionLabel="label"
                    optionValue="value"
                    styleClass="w-full"
                    [showClear]="false"
                >
                </p-select>
                <small class="text-gray-500"
                    >Voces REALES de Edge TTS (Microsoft) — se guarda en el libro
                    seleccionado y se aplica a todas sus corridas de audio,
                    incluido el botón «Crear Audio» del gestor de novelas.</small
                >
            </div>

            <!-- Target Language -->
            <div class="mb-6">
                <label class="block text-sm font-medium mb-2"
                    >Idioma de Destino *</label
                >
                <p-select
                    [options]="languages"
                    [(ngModel)]="config.targetLanguage"
                    optionLabel="label"
                    optionValue="value"
                    placeholder="Seleccionar idioma"
                    styleClass="w-full"
                    [showClear]="false"
                >
                </p-select>
                <small class="text-gray-500"
                    >Idioma en el que se traducirá el texto antes de generar el
                    audio</small
                >
            </div>

            <!-- Automatic cleaning note -->
            <div
                class="mb-6 p-4 bg-blue-50 border border-blue-200 rounded-lg text-sm text-gray-600 flex items-start gap-3"
            >
                <i class="pi pi-info-circle text-blue-500 mt-0.5"></i>
                <span>
                    La limpieza del texto no se configura aquí: al subir un
                    archivo se aplican automáticamente todos los patrones de
                    limpieza habilitados en la
                    <a
                        routerLink="/book-translator/settings"
                        class="text-blue-600 hover:underline"
                        >Configuración</a
                    >
                    antes de almacenar el texto en la base de datos (Extraer →
                    Limpiar → Almacenar).
                </span>
            </div>

            <!-- Action Buttons -->
            <div class="flex justify-content-end pt-4 border-t gap-3">
                <p-button
                    label="Guardar Configuración"
                    icon="pi pi-save"
                    (click)="saveConfig()"
                    styleClass="p-button-secondary p-button-outlined"
                    iconPos="right"
                    [disabled]="!isValid()"
                >
                </p-button>
                <p-button
                    label="Generar Audiolibro"
                    icon="pi pi-volume-up"
                    (click)="generateAudioBook()"
                    styleClass="p-button-primary"
                    iconPos="right"
                    [loading]="generating"
                    [disabled]="!canGenerate"
                >
                </p-button>
            </div>
        </div>
    `,
    providers: [MessageService],
})
export class UploadStep2ConfigComponent implements OnInit {
    config: ProcessingConfig = {
        targetLanguage: 'es',
    };

    /** Libros ya traducidos (todos los chunks COMPLETED), para el combo. */
    translatedOptions: TranslatedBookOption[] = [];
    selectedBookId?: string;
    loadingBooks = false;
    generating = false;

    languages = [
        { label: 'Español (es)', value: 'es' },
        { label: 'Inglés (en)', value: 'en' },
        { label: 'Francés (fr)', value: 'fr' },
        { label: 'Alemán (de)', value: 'de' },
        { label: 'Italiano (it)', value: 'it' },
        { label: 'Portugués (pt)', value: 'pt' },
        { label: 'Chino (zh)', value: 'zh' },
        { label: 'Japonés (ja)', value: 'ja' },
        { label: 'Coreano (ko)', value: 'ko' },
    ];

    // Voces REALES de Edge TTS (nombres cortos del servicio — la síntesis
    // nunca usó las voces de macOS 'say' que ofrecía esta página antes).
    // Por defecto: es-MX-DaliaNeural (app.audio.voice del backend).
    edgeVoices: EdgeVoiceOption[] = [
        { label: 'Dalia — español México (por defecto)', value: 'es-MX-DaliaNeural' },
        { label: 'Jorge — español México', value: 'es-MX-JorgeNeural' },
        { label: 'Beatriz — español México', value: 'es-MX-BeatrizNeural' },
        { label: 'Catalina — español México', value: 'es-MX-CatalinaNeural' },
        { label: 'Emilio — español México', value: 'es-MX-EmilioNeural' },
        { label: 'Elvira — español España', value: 'es-ES-ElviraNeural' },
        { label: 'Álvaro — español España', value: 'es-ES-AlvaroNeural' },
        { label: 'Laia — español España', value: 'es-ES-LaiaNeural' },
        { label: 'Pablo — español España', value: 'es-ES-PabloNeural' },
        { label: 'Abrela — español España', value: 'es-ES-AbrelaNeural' },
        { label: 'Emma — inglés, multilingüe (habla español)', value: 'en-US-EmmaMultilingualNeural' },
        { label: 'Aria — inglés EE. UU.', value: 'en-US-AriaNeural' },
        { label: 'Guy — inglés EE. UU.', value: 'en-US-GuyNeural' },
    ];

    /** Voz elegida para el libro seleccionado; 'default' = la del backend. */
    selectedVoice = 'default';

    constructor(
        private messageService: MessageService,
        private cdr: ChangeDetectorRef,
        private uploadService: BookTranslatorUploadService,
    ) {}

    ngOnInit() {
        this.loadSavedConfig();
        this.loadTranslatedBooks();
    }

    /** Recupera la configuración guardada en localStorage, si existe. */
    private loadSavedConfig() {
        try {
            const raw = localStorage.getItem(AUDIOBOOK_CONFIG_KEY);
            if (raw) {
                const saved = JSON.parse(raw) as Partial<ProcessingConfig>;
                this.config = { ...this.config, ...saved };
            }
        } catch {
            // Configuración corrupta: se usan los valores por defecto.
        }
    }

    /**
     * Carga los libros y deja en el combo SOLO los totalmente traducidos
     * (totalChunks > 0 && completedChunks === totalChunks). El progreso de
     * chunks llega en la misma llamada del listado (una query agrupada),
     * así que el filtro no genera peticiones extra.
     */
    loadTranslatedBooks() {
        this.loadingBooks = true;
        this.uploadService.getAllBooks().subscribe({
            next: (books) => {
                const translated = books
                    .filter(
                        (book) =>
                            (book.totalChunks ?? 0) > 0 &&
                            (book.completedChunks ?? 0) ===
                                (book.totalChunks ?? 0),
                    )
                    .map((book) => this.toOption(book))
                    // Sin audiolibro primero: son los pendientes reales.
                    .sort((a, b) =>
                        a.hasAudio === b.hasAudio
                            ? a.label.localeCompare(b.label)
                            : a.hasAudio
                              ? 1
                              : -1,
                    );
                this.translatedOptions = translated;
                // La selección previa puede haber dejado de existir.
                if (
                    this.selectedBookId &&
                    !translated.some((o) => o.value === this.selectedBookId)
                ) {
                    this.selectedBookId = undefined;
                }
                this.loadingBooks = false;
                this.cdr.markForCheck();
            },
            error: () => {
                this.loadingBooks = false;
                this.messageService.add({
                    sticky: true,
                    severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo cargar la lista de libros.',
                });
            },
        });
    }

    private toOption(book: BookListItem): TranslatedBookOption {
        const hasAudio = !!book.audioFilePath;
        return {
            value: book.id,
            hasAudio,
            label:
                (book.name ?? book.id) +
                (hasAudio ? '  ✔ audiolibro' : ''),
        };
    }

    isValid(): boolean {
        return !!this.config.targetLanguage;
    }

    get canGenerate(): boolean {
        return !!this.selectedBookId && !this.generating && !this.loadingBooks;
    }

    /** Opciones del combo: la de por defecto + las voces reales de Edge. */
    get edgeVoiceOptions(): EdgeVoiceOption[] {
        return this.edgeVoices;
    }

    saveConfig() {
        if (!this.isValid()) return;
        try {
            localStorage.setItem(
                AUDIOBOOK_CONFIG_KEY,
                JSON.stringify(this.config),
            );
            this.messageService.add({
                severity: 'success',
                summary: 'Configuración guardada',
                detail: 'Las opciones de generación de audio se guardaron correctamente.',
            });
        } catch {
            this.messageService.add({
                sticky: true, severity: 'error',
                summary: 'Error',
                detail: 'No se pudo guardar la configuración.',
            });
        }
    }

    /**
     * Lanza la etapa de AUDIO del libro seleccionado (to=AUDIO): etapa
     * manual e independiente, reanudable — los bloques que ya tienen su
     * mp3 en disco se saltan y solo se generan los pendientes.
     *
     * Primero PERSISTE la voz elegida en el libro (PUT tts-voice — update
     * en bloque, sin cargar el CLOB) y luego dispara: la voz queda en la
     * BD y TODAS las corridas futuras del libro la usan (incluido el
     * botón Crear Audio del gestor de novelas, que comparte el Book).
     */
    generateAudioBook() {
        if (!this.canGenerate || !this.selectedBookId) return;
        this.generating = true;
        const bookId = this.selectedBookId;
        const voice = this.selectedVoice === 'default' ? null : this.selectedVoice;
        this.uploadService.setBookTtsVoice(bookId, voice).subscribe({
            next: () => {
                this.uploadService.generateAudioBook(bookId).subscribe({
                    next: () => {
                        this.generating = false;
                        const name = this.translatedOptions.find(
                            (o) => o.value === bookId,
                        )?.label;
                        const voiceLabel = voice
                            ? ` con la voz ${voice}`
                            : ' con la voz por defecto';
                        this.messageService.add({
                            severity: 'success',
                            summary: 'Audiolibro en marcha',
                            detail:
                                `Generación iniciada para ${name ?? 'el libro'}${voiceLabel}. ` +
                                'Sigue el avance en el Panel de estado → Procesos (o desde su botón «Ver logs»).',
                        });
                        this.cdr.markForCheck();
                    },
                    error: (err) => {
                        this.generating = false;
                        this.messageService.add({
                            sticky: true,
                            severity: 'error',
                            summary: 'No se pudo iniciar la generación',
                            detail: err?.error?.error ?? err?.message ?? 'error',
                        });
                        this.cdr.markForCheck();
                    },
                });
            },
            error: (err) => {
                this.generating = false;
                this.messageService.add({
                    sticky: true,
                    severity: 'error',
                    summary: 'No se pudo fijar la voz del audiolibro',
                    detail: err?.error?.error ?? err?.message ?? 'error',
                });
                this.cdr.markForCheck();
            },
        });
    }
}
