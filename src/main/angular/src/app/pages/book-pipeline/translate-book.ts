import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
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

            @if (selectedBookId) {
                <!-- Alcance de la traducción -->
                <div class="mt-6 pt-4 border-t">
                    <label class="block text-sm font-medium mb-3"
                        >Alcance de la traducción</label
                    >
                    <p-selectbutton
                        [options]="scopeOptions"
                        [(ngModel)]="scope"
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
                            <div class="mt-2 text-xs text-gray-500">
                                Los chunks se regeneran con el contenido de los
                                bloques seleccionados y se traducen solo esos
                                bloques.
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
            @if (loading) {
                <div class="p-4 text-center text-gray-500">
                    <i class="pi pi-spin pi-spinner"></i> Cargando chunks...
                </div>
            } @else if (!selectedBookId) {
                <div class="p-4 text-center text-gray-500">
                    Selecciona un documento para ver el texto original y su
                    traducción en paralelo.
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
                        <div class="flex items-center justify-between mb-3">
                            <div class="font-medium">
                                Chunk #{{ chunk.position + 1 }}
                            </div>
                            <p-tag
                                [value]="chunkStatusLabel(chunk.status)"
                                [severity]="chunkStatusSeverity(chunk.status)"
                            />
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
export class TranslateBook implements OnInit {
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
        this.scope = 'full';
        this.blocks = [];
        this.selectedBlockIds = [];
        this.loadChunks(0, PAGE_SIZE);
        this.loadBlocks();
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

    private loadChunks(pageNumber: number, pageSize: number) {
        if (!this.selectedBookId) return;
        this.loading = true;
        this.uploadService
            .getBookChunks(this.selectedBookId, pageNumber, pageSize)
            .subscribe({
                next: (page: ChunkPage) => {
                    this.page = page;
                    this.loading = false;
                    this.cdr.markForCheck();
                },
                error: () => {
                    this.loading = false;
                    this.messageService.add({
                        severity: 'error',
                        summary: 'Error',
                        detail: 'No se pudieron cargar los chunks del documento.',
                    });
                    this.cdr.markForCheck();
                },
            });
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
