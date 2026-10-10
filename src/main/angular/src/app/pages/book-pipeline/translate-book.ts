import {
    Component,
    OnInit,
    OnDestroy,
    ChangeDetectorRef,
    signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { CheckboxModule } from 'primeng/checkbox';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { PaginatorModule } from 'primeng/paginator';
import { ProgressBarModule } from 'primeng/progressbar';
import { MessageService } from 'primeng/api';
import { HttpErrorResponse } from '@angular/common/http';
import {
    BookTranslatorUploadService,
    BookListItem,
    BlockItem,
    Chunk,
    ChunkPage,
} from '../book-translator/upload/book-translator-upload.service';

/** Tamaño de página de chunks para la vista paralela. */
const PAGE_SIZE = 10;

/** Alcance de la traducción. */
type TranslationScope = 'full' | 'blocks';

/**
 * Página Traducir Libro del pipeline: selección del documento, del alcance
 * (libro completo o bloques concretos creados en el split: Prólogo,
 * Capítulo, Epílogo) e inicio de la traducción, con la comparación en
 * paralelo del texto original y el traducido por chunk.
 */
@Component({
    selector: 'app-translate-book',
    standalone: true,
    imports: [
        CommonModule,
        RouterLink,
        FormsModule,
        SelectModule,
        SelectButtonModule,
        CheckboxModule,
        ButtonModule,
        TagModule,
        ToastModule,
        PaginatorModule,
        ProgressBarModule,
    ],
    template: `
        <p-toast></p-toast>

        <div class="card mb-4">
            <div
                class="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-4"
            >
                <div class="flex-1 min-w-0">
                    <div class="font-semibold text-xl mb-2">Traducir Libro</div>
                    <label class="block text-sm font-medium mb-2"
                        >Documento</label
                    >
                    <p-select
                        [options]="bookOptions"
                        [(ngModel)]="selectedBookId"
                        (ngModelChange)="onBookChange()"
                        optionLabel="label"
                        optionValue="value"
                        placeholder="Selecciona un documento"
                        styleClass="w-full"
                        [showClear]="false"
                    >
                    </p-select>
                </div>
                <div class="flex items-center gap-3">
                    <p-button
                        icon="pi pi-refresh"
                        label="Actualizar"
                        severity="secondary"
                        [outlined]="true"
                        [loading]="loading"
                        (click)="refresh()"
                        [disabled]="!selectedBookId"
                    />
                </div>
            </div>

            @if (selectedBook(); as book) {
                <div class="mt-4 flex flex-wrap items-center gap-3 text-sm">
                    <p-tag
                        [value]="book.status"
                        [severity]="statusSeverity(book.status)"
                    />
                    @if (book.currentStep) {
                        <span class="text-gray-500"
                            >Paso actual:
                            {{ stepLabel(book.currentStep) }}</span
                        >
                    }
                    <span class="text-gray-500">·</span>
                    <span class="text-gray-500"
                        >{{ completedChunks() }} de
                        {{ page?.totalElements ?? 0 }} chunks traducidos</span
                    >
                </div>
            }

            @if (translationRunning()) {
                <!-- Barra de progreso en tiempo real: aparece mientras la
                     traducción está activa y se actualiza cada 3 s. -->
                <div
                    class="mt-4 p-4 border rounded-lg bg-blue-50 dark:bg-blue-900/20"
                >
                    <div
                        class="flex flex-wrap items-center justify-between gap-2 mb-3"
                    >
                        <div class="flex items-center gap-2">
                            <i
                                class="pi pi-spin pi-spinner text-blue-500"
                            ></i>
                            <span class="font-medium text-blue-700 dark:text-blue-300"
                                >Traducción en progreso</span
                            >
                        </div>
                        <span class="text-sm text-blue-600 dark:text-blue-400">
                            {{ translationProgress().completed }} de
                            {{ translationProgress().total }} chunks
                            traducidos
                        </span>
                    </div>
                    <p-progressbar
                        [value]="translationProgress().percent"
                        [showValue]="true"
                        styleClass="w-full"
                        [style]="{ height: '14px' }"
                    />
                    @if (translationProgress().processing > 0) {
                        <div
                            class="mt-2 text-xs text-blue-600 dark:text-blue-400"
                        >
                            <i
                                class="pi pi-spin pi-spinner"
                                style="font-size: 0.7rem"
                            ></i>
                            Traduciendo
                            {{ translationProgress().processing }}
                            chunk(s)...
                        </div>
                    }
                    <div
                        class="mt-1 text-xs text-blue-500 dark:text-blue-400"
                    >
                        Se actualiza automáticamente cada 3 segundos ·
                        Actualizado:
                        {{ translationProgress().lastUpdate }}
                    </div>
                </div>
            }

            @if (selectedBookId) {
                <!-- Alcance de la traducción -->
                <div class="mt-6 pt-4 border-t">
                    <label class="block text-sm font-medium mb-3"
                        >Alcance de la traducción</label
                    >
                    <p-selectbutton
                        [options]="scopeOptions"
                        [(ngModel)]="scope"
                        (ngModelChange)="onScopeChange()"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                    />

                    @if (scope === 'blocks') {
                        @if (loadingBlocks) {
                            <div class="mt-4 p-4 text-center text-gray-500">
                                <i class="pi pi-spin pi-spinner"></i> Cargando
                                bloques...
                            </div>
                        } @else if (blocks.length === 0) {
                            <div class="mt-4 p-4 text-center text-gray-500">
                                Este documento no tiene bloques definidos.
                                Márcalos en
                                <a
                                    routerLink="/pipeline/split-text"
                                    class="text-blue-600 hover:underline"
                                    >Dividir Texto</a
                                >.
                            </div>
                        } @else {
                            <div class="flex flex-wrap gap-2 mt-4 mb-2">
                                <p-button
                                    label="Todos"
                                    icon="pi pi-check-square"
                                    (click)="selectAllBlocks()"
                                    styleClass="p-button-sm p-button-outlined p-button-secondary"
                                    [disabled]="allBlocksSelected"
                                />
                                <p-button
                                    label="Ninguno"
                                    icon="pi pi-minus-square"
                                    (click)="deselectAllBlocks()"
                                    styleClass="p-button-sm p-button-outlined p-button-secondary"
                                    [disabled]="!anyBlockSelected"
                                />
                            </div>
                            <div
                                class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 max-h-60 overflow-y-auto border rounded-lg p-3 bg-gray-50"
                            >
                                @for (
                                    block of blocks;
                                    track block.id;
                                    let i = $index
                                ) {
                                    <label
                                        [attr.for]="'block-' + i"
                                        class="flex items-start gap-2 cursor-pointer"
                                    >
                                        <p-checkbox
                                            [(ngModel)]="selectedBlockIds"
                                            [value]="block.id"
                                            [inputId]="'block-' + i"
                                            (ngModelChange)="onBlockSelectionChange()"
                                        />
                                        <div class="flex-1 min-w-0">
                                            <div class="font-medium text-sm">
                                                {{ blockLabel(block) }}
                                                <span
                                                    class="text-xs text-gray-500"
                                                    >({{
                                                        formatLength(
                                                            block.contentLength
                                                        )
                                                    }})</span
                                                >
                                            </div>
                                            <div
                                                class="text-xs text-gray-500 truncate"
                                            >
                                                {{ block.preview }}
                                            </div>
                                        </div>
                                    </label>
                                }
                            </div>
                            @if (markedBlocks.length > 0) {
                                <div
                                    class="mt-4 flex flex-wrap items-center gap-2"
                                >
                                    <span
                                        class="text-sm font-medium text-gray-600"
                                        >Bloques marcados ({{
                                            markedBlocks.length
                                        }}):</span
                                    >
                                    @for (
                                        block of markedBlocks;
                                        track block.id
                                    ) {
                                        <p-tag
                                            [value]="blockLabel(block)"
                                            severity="info"
                                            [rounded]="true"
                                        />
                                    }
                                </div>
                            }
                            <div class="mt-2 text-xs text-gray-500">
                                Los chunks se generan por bloque: solo los
                                bloques seleccionados se vuelven a trocear (el
                                resto conserva sus chunks y traducciones), y el
                                audio final se crea por bloque.
                            </div>
                        }
                    }
                </div>

                <!-- Iniciar traducción -->
                <div
                    class="mt-6 pt-4 border-t flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3"
                >
                    <span class="text-sm text-gray-500">
                        @if (scope === 'full') {
                            Se traducirán todos los chunks pendientes del
                            documento.
                        } @else {
                            Se traducirán
                            {{ selectedBlockIds.length }} bloque(s)
                            seleccionado(s).
                        }
                    </span>
                    <p-button
                        label="Iniciar Traducción"
                        icon="pi pi-play"
                        styleClass="p-button-primary"
                        iconPos="right"
                        [loading]="starting"
                        [disabled]="!canStart"
                        (click)="startTranslation()"
                    />
                </div>
            }
        </div>

        <div class="card">
            <div
                class="flex flex-wrap items-center justify-between gap-2 mb-3"
            >
                <div class="font-medium">
                    @if (scope === 'blocks') {
                        Chunks de los bloques marcados
                    } @else {
                        Chunks del documento (original y traducción)
                    }
                </div>
                <span class="text-sm text-gray-500"
                    >{{ completedChunks() }} de
                    {{ page?.totalElements ?? 0 }} chunks
                    traducidos</span
                >
            </div>
            @if (loading) {
                <div class="p-4 text-center text-gray-500">
                    <i class="pi pi-spin pi-spinner"></i> Cargando chunks...
                </div>
            } @else if (!selectedBookId) {
                <div class="p-4 text-center text-gray-500">
                    Selecciona un documento para ver el texto original y su
                    traducción en paralelo.
                </div>
            } @else if (scope === 'blocks' && selectedBlockIds.length === 0) {
                <div class="p-4 text-center text-gray-500">
                    Marca bloques en el selector de alcance para ver aquí sus
                    chunks (original y traducción) y traducirlos de uno en
                    uno.
                </div>
            } @else if (page && page.totalElements === 0) {
                <div class="p-4 text-center text-gray-500">
                    Este documento aún no está dividido en chunks. Marca los
                    bloques semánticos en
                    <a
                        routerLink="/pipeline/split-text"
                        class="text-blue-600 hover:underline"
                        >Dividir Texto</a
                    >.
                </div>
            } @else if (page; as p) {
                @for (chunk of p.content; track chunk.id) {
                    <div class="mb-4 p-3 border rounded-lg">
                        <div
                            class="flex flex-wrap items-center justify-between gap-2 mb-3"
                        >
                            <div class="flex flex-wrap items-center gap-2">
                                <div class="font-medium">
                                    Chunk #{{ chunk.position + 1 }}
                                </div>
                                @if (chunkBlockLabel(chunk)) {
                                    <p-tag
                                        [value]="chunkBlockLabel(chunk)"
                                        severity="info"
                                        [rounded]="true"
                                    />
                                }
                            </div>
                            <div class="flex items-center gap-2">
                                <p-tag
                                    [value]="chunkStatusLabel(chunk.status)"
                                    [severity]="chunkStatusSeverity(chunk.status)"
                                />
                                <p-button
                                    label="Traducir chunk"
                                    icon="pi pi-bolt"
                                    styleClass="p-button-sm p-button-outlined p-button-secondary"
                                    [loading]="translatingChunkId === chunk.id"
                                    [disabled]="translatingChunkId !== null || chunk.status === 'PROCESSING'"
                                    (click)="translateSingleChunk(chunk)"
                                />
                            </div>
                        </div>
                        <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
                            <div>
                                <div
                                    class="text-xs uppercase text-gray-500 mb-1"
                                >
                                    Texto Original
                                </div>
                                <div
                                    class="p-3 bg-gray-50 border rounded-lg text-sm whitespace-pre-wrap max-h-72 overflow-y-auto"
                                >
                                    {{ chunk.originalText }}
                                </div>
                            </div>
                            <div>
                                <div
                                    class="text-xs uppercase text-gray-500 mb-1"
                                >
                                    Texto Traducido
                                </div>
                                @if (chunk.translatedText) {
                                    <div
                                        class="p-3 bg-blue-50 border border-blue-100 rounded-lg text-sm whitespace-pre-wrap max-h-72 overflow-y-auto"
                                    >
                                        {{ chunk.translatedText }}
                                    </div>
                                } @else {
                                    <div
                                        class="p-3 bg-gray-50 border rounded-lg text-sm text-gray-400 italic max-h-72 overflow-y-auto"
                                    >
                                        {{
                                            chunkStatusLabel(chunk.status) ===
                                            'Traducido'
                                                ? 'Traducción en curso...'
                                                : 'Pendiente de traducción'
                                        }}
                                    </div>
                                }
                            </div>
                        </div>
                    </div>
                }

                <p-paginator
                    [first]="p.pageNumber * p.pageSize"
                    [rows]="p.pageSize"
                    [totalRecords]="p.totalElements"
                    [rowsPerPageOptions]="[10, 25, 50]"
                    (onPageChange)="onPageChange($event)"
                >
                </p-paginator>
            }
        </div>
    `,
    providers: [MessageService],
})
export class TranslateBook implements OnInit, OnDestroy {
    books: BookListItem[] = [];
    selectedBookId: string | null = null;
    page: ChunkPage | null = null;
    loading = false;

    scope: TranslationScope = 'full';
    scopeOptions = [
        { label: 'Libro completo', value: 'full' },
        { label: 'Bloques específicos', value: 'blocks' },
    ];
    blocks: BlockItem[] = [];
    selectedBlockIds: string[] = [];
    loadingBlocks = false;
    starting = false;

    /** Chunk con la traducción individual en curso (spinner por chunk). */
    translatingChunkId: string | null = null;
    private chunkPollTarget: { chunkId: string; attempts: number } | null = null;
    private chunkPollTimer: ReturnType<typeof setTimeout> | null = null;

    // -----------------------------------------------------------------
    // Barra de progreso de la corrida de traducción (poll cada 3 s).
    // -----------------------------------------------------------------
    /** true mientras una corrida de traducción está activa. */
    translationRunning = signal(false);
    /** Progreso en vivo: completados, procesando, total, porcentaje. */
    translationProgress = signal({
        completed: 0,
        processing: 0,
        total: 0,
        percent: 0,
        lastUpdate: '--:--:--',
    });
    private translationTimer: ReturnType<typeof setInterval> | null = null;

    constructor(
        private uploadService: BookTranslatorUploadService,
        private messageService: MessageService,
        private cdr: ChangeDetectorRef,
    ) {}

    ngOnInit() {
        this.loadBooks();
    }

    loadBooks() {
        this.uploadService.getAllBooks().subscribe({
            next: (books) => {
                this.books = books;
                this.cdr.markForCheck();
            },
            error: () => {
                this.messageService.add({
                    severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo cargar la lista de documentos.',
                });
            },
        });
    }

    get bookOptions() {
        return this.books.map((book) => ({
            label: book.name,
            value: book.id,
        }));
    }

    selectedBook(): BookListItem | null {
        return (
            this.books.find((book) => book.id === this.selectedBookId) ?? null
        );
    }

    completedChunks(): number {
        return (
            this.page?.content.filter((chunk) => chunk.status === 'COMPLETED')
                .length ?? 0
        );
    }

    onBookChange() {
        this.stopChunkPolling();
        this.stopTranslationPolling();
        this.translatingChunkId = null;
        this.scope = 'full';
        this.blocks = [];
        this.selectedBlockIds = [];
        this.loadChunks(0, PAGE_SIZE);
        this.loadBlocks();
        this.checkTranslationStatus();
    }

    /**
     * Si el libro seleccionado ya está PROCESSING (corrida arrancada
     * antes, reinicio, etc.), retoma la barra de progreso.
     */
    private checkTranslationStatus() {
        const book = this.selectedBook();
        if (book && book.status === 'PROCESSING') {
            this.startTranslationPolling();
        }
    }

    /** Inicia el polling de progreso cada 3 s mientras la corrida está activa. */
    startTranslationPolling() {
        this.stopTranslationPolling();
        this.translationRunning.set(true);
        this.pollTranslationProgress();
        this.translationTimer = setInterval(
            () => this.pollTranslationProgress(),
            3000,
        );
    }

    /** Detiene el polling de progreso y oculta la barra. */
    stopTranslationPolling() {
        if (this.translationTimer) {
            clearInterval(this.translationTimer);
            this.translationTimer = null;
        }
        this.translationRunning.set(false);
    }

    /**
     * Carga el estado de TODOS los chunks en una sola llamada (size: 200,
     * el máximo del endpoint) y actualiza la barra. Si ya no hay chunks
     * PROCESSING ni el libro está PROCESSING, detiene el polling y da un
     * toast final.
     */
    private pollTranslationProgress() {
        if (!this.selectedBookId) return;
        this.uploadService
            .getBookChunks(this.selectedBookId, 0, 200)
            .subscribe({
                next: (fullPage: ChunkPage) => {
                    const total = fullPage.totalElements;
                    const completed = fullPage.content.filter(
                        (c) => c.status === 'COMPLETED',
                    ).length;
                    const processing = fullPage.content.filter(
                        (c) => c.status === 'PROCESSING',
                    ).length;
                    const percent =
                        total > 0 ? Math.round((completed / total) * 100) : 0;
                    const now = new Date();
                    const time =
                        now.getHours().toString().padStart(2, '0') +
                        ':' +
                        now.getMinutes().toString().padStart(2, '0') +
                        ':' +
                        now.getSeconds().toString().padStart(2, '0');
                    this.translationProgress.set({
                        completed,
                        processing,
                        total,
                        percent,
                        lastUpdate: time,
                    });
                    this.cdr.markForCheck();

                    // Si ya no hay trabajo en curso, la corrida terminó.
                    const book = this.selectedBook();
                    if (
                        processing === 0 &&
                        book &&
                        book.status !== 'PROCESSING'
                    ) {
                        this.stopTranslationPolling();
                        this.loadChunks(0, PAGE_SIZE);
                        this.loadBooks();
                        this.cdr.markForCheck();
                        this.messageService.add({
                            severity: percent === 100 ? 'success' : 'warn',
                            summary:
                                percent === 100
                                    ? 'Traducción completada'
                                    : 'Corrida detenida',
                            detail:
                                percent === 100
                                    ? `Todos los ${total} chunks están traducidos.`
                                    : `La corrida terminó con ${completed}/${total} chunks traducidos.`,
                        });
                    }
                },
                error: () => {
                    this.stopTranslationPolling();
                },
            });
    }

    /** El alcance cambia la vista de chunks: completo = todo el documento. */
    onScopeChange() {
        this.stopChunkPolling();
        this.translatingChunkId = null;
        this.page = null;
        this.loadChunks(0, PAGE_SIZE);
    }

    /**
     * Al marcar/desmarcar bloques la vista paralela sigue a la selección
     * (los bloques marcados se van mostrando con sus chunks).
     */
    onBlockSelectionChange() {
        this.page = null;
        this.loadChunks(0, PAGE_SIZE);
    }

    /** Bloques marcados en orden de lectura, para el panel de selección. */
    get markedBlocks(): BlockItem[] {
        return this.blocks.filter((block) =>
            this.selectedBlockIds.includes(block.id),
        );
    }

    refresh() {
        this.loadChunks(
            this.page?.pageNumber ?? 0,
            this.page?.pageSize ?? PAGE_SIZE,
        );
        if (this.scope === 'blocks') {
            this.loadBlocks();
        }
    }

    onPageChange(event: { first?: number; rows?: number }) {
        const first = event.first ?? 0;
        const rows = event.rows ?? PAGE_SIZE;
        const pageNumber = Math.floor(first / rows);
        this.loadChunks(pageNumber, rows);
    }

    get allBlocksSelected(): boolean {
        return (
            this.blocks.length > 0 &&
            this.selectedBlockIds.length === this.blocks.length
        );
    }

    get anyBlockSelected(): boolean {
        return this.selectedBlockIds.length > 0;
    }

    get canStart(): boolean {
        if (!this.selectedBookId || this.starting) return false;
        if (this.scope === 'blocks') {
            return this.blocks.length > 0 && this.anyBlockSelected;
        }
        return true;
    }

    selectAllBlocks() {
        this.selectedBlockIds = this.blocks.map((block) => block.id);
    }

    deselectAllBlocks() {
        this.selectedBlockIds = [];
    }

    blockLabel(block: BlockItem): string {
        switch (block.blockType) {
            case 'PROLOGUE':
                return 'Prólogo';
            case 'CHAPTER':
                return (
                    'Capítulo' +
                    (block.blockNumber ? ' ' + block.blockNumber : '')
                );
            case 'EPILOGUE':
                return 'Epílogo';
            default:
                return block.blockType;
        }
    }

    /** Badge del bloque propietario del chunk ('' para chunks sin bloque). */
    chunkBlockLabel(chunk: Chunk): string {
        switch (chunk.blockType) {
            case 'PROLOGUE':
                return 'Prólogo';
            case 'CHAPTER':
                return (
                    'Capítulo' +
                    (chunk.blockNumber ? ' ' + chunk.blockNumber : '')
                );
            case 'EPILOGUE':
                return 'Epílogo';
            default:
                return '';
        }
    }

    formatLength(chars: number): string {
        return chars >= 1000
            ? (chars / 1000).toFixed(1) + 'K chars'
            : chars + ' chars';
    }

    startTranslation() {
        if (!this.canStart || !this.selectedBookId) return;
        this.starting = true;
        const blockIds =
            this.scope === 'blocks' ? [...this.selectedBlockIds] : [];
        this.uploadService
            .startTranslation(this.selectedBookId, blockIds)
            .subscribe({
                next: (response) => {
                    this.starting = false;
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Traducción iniciada',
                        detail:
                            response.message ||
                            'La traducción se está ejecutando en segundo plano.',
                    });
                    // Arranca la barra de progreso en tiempo real.
                    this.startTranslationPolling();
                    // Los chunks pueden haberse regenerado (modo bloques).
                    this.loadChunks(0, this.page?.pageSize ?? PAGE_SIZE);
                    this.cdr.markForCheck();
                },
                error: (err: HttpErrorResponse) => {
                    this.starting = false;
                    this.cdr.markForCheck();
                    if (err.status === 409) {
                        this.messageService.add({
                            severity: 'warn',
                            summary: 'Ya en proceso',
                            detail: 'El documento ya se está procesando. Espera a que termine la corrida actual.',
                        });
                    } else {
                        this.messageService.add({
                            severity: 'error',
                            summary: 'Error',
                            detail:
                                err.error?.error ||
                                'No se pudo iniciar la traducción.',
                        });
                    }
                },
            });
    }

    /**
     * Traduce UN chunk suelto (sin regenerar el resto ni tocar sus
     * traducciones) y sigue su progreso en la vista paralela hasta que
     * termina COMPLETED/FAILED. Un chunk ya traducido puede re-traducirse.
     */
    translateSingleChunk(chunk: Chunk) {
        if (
            !this.selectedBookId ||
            this.translatingChunkId !== null ||
            chunk.status === 'PROCESSING'
        ) {
            return;
        }
        this.translatingChunkId = chunk.id;
        this.uploadService
            .translateChunk(this.selectedBookId, chunk.id)
            .subscribe({
                next: () => {
                    this.messageService.add({
                        severity: 'info',
                        summary: 'Traducción iniciada',
                        detail: 'Traduciendo el chunk seleccionado...',
                    });
                    this.startChunkPolling(chunk.id);
                    this.cdr.markForCheck();
                },
                error: (err: HttpErrorResponse) => {
                    this.translatingChunkId = null;
                    if (err.status === 409) {
                        this.messageService.add({
                            severity: 'warn',
                            summary: 'Pipeline en marcha',
                            detail:
                                'El documento está en una corrida del ' +
                                'pipeline. Espera a que termine para ' +
                                'traducir chunks sueltos.',
                        });
                    } else {
                        this.messageService.add({
                            severity: 'error',
                            summary: 'Error',
                            detail:
                                err.error?.error ||
                                'No se pudo traducir el chunk.',
                        });
                    }
                    this.cdr.markForCheck();
                },
            });
    }

    private startChunkPolling(chunkId: string) {
        this.chunkPollTarget = { chunkId, attempts: 0 };
        this.scheduleChunkPoll();
    }

    private scheduleChunkPoll() {
        this.stopChunkPollTimer();
        this.chunkPollTimer = setTimeout(() => this.checkPolledChunk(), 2500);
    }

    /** Refresca la página de chunks y comprueba el chunk en curso. */
    private checkPolledChunk() {
        if (!this.chunkPollTarget || !this.selectedBookId) {
            this.stopChunkPolling();
            this.translatingChunkId = null;
            return;
        }
        this.chunkPollTarget.attempts++;
        const target = this.chunkPollTarget;
        this.fetchChunksPage(
            this.page?.pageNumber ?? 0,
            this.page?.pageSize ?? PAGE_SIZE,
            (page: ChunkPage) => {
                this.page = page;
                const chunk = page.content.find((c) => c.id === target.chunkId);
                if (!chunk || chunk.status === 'COMPLETED' || chunk.status === 'FAILED') {
                    const ok = chunk?.status === 'COMPLETED';
                    this.stopChunkPolling();
                    this.translatingChunkId = null;
                    this.messageService.add({
                        severity: ok ? 'success' : 'error',
                        summary: ok ? 'Chunk traducido' : 'Traducción fallida',
                        detail: ok
                            ? 'El chunk seleccionado se tradujo correctamente.'
                            : 'La traducción del chunk falló. Revisa el estado del documento y reintenta.',
                    });
                } else if (target.attempts >= 24) {
                    this.stopChunkPolling();
                    this.translatingChunkId = null;
                    this.messageService.add({
                        severity: 'warn',
                        summary: 'Traducción en curso',
                        detail:
                            'El chunk sigue traduciéndose en segundo plano. ' +
                            'Usa Actualizar para ver el resultado.',
                    });
                } else {
                    this.scheduleChunkPoll();
                }
                this.cdr.markForCheck();
            },
            () => {
                this.stopChunkPolling();
                this.translatingChunkId = null;
                this.cdr.markForCheck();
            },
        );
    }

    private stopChunkPolling() {
        this.chunkPollTarget = null;
        this.stopChunkPollTimer();
    }

    private stopChunkPollTimer() {
        if (this.chunkPollTimer) {
            clearTimeout(this.chunkPollTimer);
            this.chunkPollTimer = null;
        }
    }

    ngOnDestroy() {
        this.stopChunkPolling();
        this.stopTranslationPolling();
    }

    private loadBlocks() {
        if (!this.selectedBookId) return;
        this.loadingBlocks = true;
        this.uploadService.getBookBlocks(this.selectedBookId).subscribe({
            next: (blocks) => {
                this.blocks = blocks;
                this.selectedBlockIds = this.selectedBlockIds.filter((id) =>
                    blocks.some((b) => b.id === id),
                );
                this.loadingBlocks = false;
                this.cdr.markForCheck();
            },
            error: () => {
                this.loadingBlocks = false;
                this.blocks = [];
                this.cdr.markForCheck();
            },
        });
    }

    /**
     * Carga la página de chunks según el alcance: en modo bloques solo los
     * chunks de los bloques marcados (sin selección no hay nada que ver);
     * en modo libro completo, todos los del documento en orden de lectura.
     */
    private loadChunks(pageNumber: number, pageSize: number) {
        if (!this.selectedBookId) return;
        if (this.scope === 'blocks' && this.selectedBlockIds.length === 0) {
            this.page = null;
            this.loading = false;
            this.cdr.markForCheck();
            return;
        }
        this.loading = true;
        this.fetchChunksPage(
            pageNumber,
            pageSize,
            (page: ChunkPage) => {
                this.page = page;
                this.loading = false;
                this.cdr.markForCheck();
            },
            () => {
                this.loading = false;
                this.messageService.add({
                    severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudieron cargar los chunks del documento.',
                });
                this.cdr.markForCheck();
            },
        );
    }

    /** Petición de página de chunks con el filtro de bloques del alcance. */
    private fetchChunksPage(
        pageNumber: number,
        pageSize: number,
        onNext: (page: ChunkPage) => void,
        onError: () => void,
    ) {
        if (!this.selectedBookId) {
            onError();
            return;
        }
        const blockIds =
            this.scope === 'blocks' ? [...this.selectedBlockIds] : [];
        this.uploadService
            .getBookChunks(this.selectedBookId, pageNumber, pageSize, blockIds)
            .subscribe({ next: onNext, error: onError });
    }

    statusSeverity(
        status: string,
    ): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
        switch (status) {
            case 'COMPLETED':
                return 'success';
            case 'PROCESSING':
                return 'info';
            case 'FAILED':
                return 'danger';
            case 'AWAITING':
                return 'warn';
            default:
                return 'secondary';
        }
    }

    stepLabel(step: string): string {
        const labels: Record<string, string> = {
            EXTRACT: 'Extracción',
            SPLIT: 'División',
            TRANSLATE: 'Traducción',
            AUDIO: 'Audio',
            FINALIZE: 'Finalización',
        };
        return labels[step] ?? step;
    }

    chunkStatusLabel(status: Chunk['status']): string {
        switch (status) {
            case 'COMPLETED':
                return 'Traducido';
            case 'PROCESSING':
                return 'Traduciendo';
            case 'FAILED':
                return 'Fallido';
            default:
                return 'En espera';
        }
    }

    chunkStatusSeverity(
        status: Chunk['status'],
    ): 'success' | 'info' | 'warn' | 'danger' {
        switch (status) {
            case 'COMPLETED':
                return 'success';
            case 'PROCESSING':
                return 'info';
            case 'FAILED':
                return 'danger';
            default:
                return 'warn';
        }
    }
}
