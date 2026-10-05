import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SelectModule } from 'primeng/select';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { PaginatorModule } from 'primeng/paginator';
import { MessageService } from 'primeng/api';
import {
    BookTranslatorUploadService,
    BookListItem,
    Chunk,
    ChunkPage,
} from '../book-translator/upload/book-translator-upload.service';

/** Tamaño de página de chunks para la vista paralela. */
const PAGE_SIZE = 10;

/**
 * Página Traducir Libro del pipeline: comparación en paralelo del texto
 * original y el texto traducido de un libro, dividido en sus chunks.
 * Solo lectura; la traducción se dispara desde el Gestor de Novelas o el
 * Historial.
 */
@Component({
    selector: 'app-translate-book',
    standalone: true,
    imports: [
        CommonModule,
        RouterLink,
        FormsModule,
        SelectModule,
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
        this.loadChunks(0, PAGE_SIZE);
    }

    refresh() {
        this.loadChunks(
            this.page?.pageNumber ?? 0,
            this.page?.pageSize ?? PAGE_SIZE,
        );
    }

    onPageChange(event: { first?: number; rows?: number }) {
        const first = event.first ?? 0;
        const rows = event.rows ?? PAGE_SIZE;
        const pageNumber = Math.floor(first / rows);
        this.loadChunks(pageNumber, rows);
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
