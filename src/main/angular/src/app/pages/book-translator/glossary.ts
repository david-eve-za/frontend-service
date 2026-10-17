import {
    Component,
    ChangeDetectorRef,
    inject,
    signal,
    computed,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SelectModule } from 'primeng/select';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { DialogModule } from 'primeng/dialog';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ToastModule } from 'primeng/toast';
import { TagModule } from 'primeng/tag';
import { MessageService, ConfirmationService } from 'primeng/api';
import { HttpErrorResponse } from '@angular/common/http';
import { GlossaryClientService, GlossaryTerm } from './glossary.service';
import {
    NovelsManagerService,
    NovelSummary,
} from './novels-manager/novels-manager.service';
import {
    BookTranslatorUploadService,
    BookListItem,
} from './upload/book-translator-upload.service';

/** Alcance del glosario que se está gestionando. */
type GlossaryScope = 'global' | 'novel' | 'book';

/**
 * Página del Glosario de Traducción: consulta y curación de los términos
 * con traducción establecida (global, por serie o por libro) que el
 * pipeline inyecta en cada traducción para mantener la consistencia.
 */
@Component({
    selector: 'app-glossary',
    standalone: true,
    imports: [
        CommonModule,
        FormsModule,
        SelectModule,
        ButtonModule,
        InputTextModule,
        TableModule,
        DialogModule,
        ConfirmDialogModule,
        ToastModule,
        TagModule,
    ],
    providers: [MessageService, ConfirmationService],
    template: `
        <p-toast [life]="12000"></p-toast>
        <p-confirmDialog icon="pi pi-exclamation-triangle"></p-confirmDialog>

        <div class="card mb-4">
            <div class="font-semibold text-xl mb-2">Glosario de Traducción</div>
            <p class="text-muted-color text-sm mb-4">
                Términos con su traducción establecida que el pipeline inyecta
                en cada traducción para mantener la consistencia entre chunks y
                entre volúmenes de una misma serie. El glosario de cada serie se
                extrae automáticamente la primera vez que se traduce uno de sus
                volúmenes; aquí puedes curarlo a mano.
            </p>

            <div class="grid grid-cols-12 gap-4 items-end">
                <div class="col-span-12 md:col-span-3">
                    <label class="block text-sm font-medium mb-2"
                        >Alcance</label
                    >
                    <p-select
                        [options]="scopeOptions"
                        [(ngModel)]="scope"
                        optionLabel="label"
                        optionValue="value"
                        (ngModelChange)="onScopeChange()"
                        styleClass="w-full"
                        [showClear]="false"
                    />
                </div>

                @if (scope() === 'novel') {
                    <div class="col-span-12 md:col-span-6">
                        <label class="block text-sm font-medium mb-2"
                            >Buscar serie</label
                        >
                        <div class="flex gap-2">
                            <input
                                pInputText
                                type="text"
                                class="flex-1"
                                placeholder="Título de la serie…"
                                [(ngModel)]="novelSearch"
                                (keyup.enter)="searchNovels()"
                            />
                            <p-button
                                icon="pi pi-search"
                                label="Buscar"
                                severity="secondary"
                                [outlined]="true"
                                [loading]="searchingNovels"
                                (click)="searchNovels()"
                            />
                        </div>
                        @if (novelResults().length > 0) {
                            <div class="mt-2 text-sm text-muted-color">
                                Resultados: elige la serie cuyo glosario quieres
                                gestionar.
                            </div>
                        }
                    </div>
                }

                @if (scope() === 'book') {
                    <div class="col-span-12 md:col-span-6">
                        <label class="block text-sm font-medium mb-2"
                            >Documento</label
                        >
                        <p-select
                            [options]="bookOptions()"
                            [(ngModel)]="selectedBookId"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="Selecciona un documento"
                            styleClass="w-full"
                            [showClear]="false"
                            (ngModelChange)="loadTerms()"
                        />
                    </div>
                }
            </div>

            @if (novelResults().length > 0) {
                <div
                    class="mt-3 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2"
                >
                    @for (novel of novelResults(); track novel.id) {
                        <button
                            class="text-left p-3 border rounded-lg hover:bg-surface-50 dark:hover:bg-surface-800 transition-colors"
                            [ngClass]="
                                selectedNovelId() === novel.id
                                    ? 'border-primary bg-primary-50 dark:bg-primary-400/10'
                                    : 'border-surface'
                            "
                            (click)="selectNovel(novel)"
                        >
                            <div class="font-medium text-sm truncate">
                                {{ novel.title }}
                            </div>
                            <div class="text-xs text-muted-color">
                                {{ novel.volumeCount }} volumen(es) ·
                                {{ novel.category || 'sin categoría' }}
                            </div>
                        </button>
                    }
                </div>
            }
            @if (novelSearched() && novelResults().length === 0) {
                <div class="mt-3 text-sm text-muted-color">
                    Ninguna serie coincide con la búsqueda.
                </div>
            }
        </div>

        <div class="card">
            <div
                class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4"
            >
                <div>
                    <span class="font-medium"
                        >{{ terms().length }} término(s)</span
                    >
                    @if (scopeLabel()) {
                        <span class="text-muted-color text-sm ml-2"
                            >· {{ scopeLabel() }}</span
                        >
                    }
                </div>
                <p-button
                    label="Agregar Término"
                    icon="pi pi-plus"
                    styleClass="p-button-primary"
                    [disabled]="!canAddTerm()"
                    (click)="openCreateDialog()"
                />
            </div>

            <p-table [value]="terms()" [tableStyle]="{ 'min-width': '500px' }">
                <ng-template pTemplate="header">
                    <tr>
                        <th>Término original</th>
                        <th>Traducción establecida</th>
                        <th style="width: 140px">Acciones</th>
                    </tr>
                </ng-template>
                <ng-template pTemplate="body" let-term>
                    <tr>
                        <td class="font-medium">{{ term.sourceTerm }}</td>
                        <td>{{ term.targetTerm }}</td>
                        <td>
                            <div class="flex gap-1">
                                <p-button
                                    icon="pi pi-pencil"
                                    styleClass="p-button-sm p-button-text p-button-info"
                                    pTooltip="Editar"
                                    (click)="openEditDialog(term)"
                                />
                                <p-button
                                    icon="pi pi-trash"
                                    styleClass="p-button-sm p-button-text p-button-danger"
                                    pTooltip="Eliminar"
                                    (click)="confirmDelete(term)"
                                />
                            </div>
                        </td>
                    </tr>
                </ng-template>
                <ng-template pTemplate="emptymessage">
                    <tr>
                        <td
                            colspan="3"
                            class="text-center text-muted-color py-4"
                        >
                            @if (scope() === 'novel' && !selectedNovelId()) {
                                Busca y elige una serie para ver su glosario.
                            } @else if (
                                scope() === 'book' && !selectedBookId()
                            ) {
                                Selecciona un documento para ver su glosario.
                            } @else if (scope() === 'novel') {
                                Esta serie aún no tiene glosario: se extraerá
                                automáticamente la primera vez que traduzcas uno
                                de sus volúmenes, o puedes agregar términos a
                                mano.
                            } @else if (scope() === 'book') {
                                Este documento aún no tiene glosario: se
                                extraerá automáticamente la primera vez que lo
                                traduzcas, o puedes agregar términos a mano.
                            } @else {
                                No hay términos globales todavía. Agrégalos para
                                aplicarlos a todas las traducciones.
                            }
                        </td>
                    </tr>
                </ng-template>
            </p-table>
        </div>

        <p-dialog
            [(visible)]="dialogVisible"
            [header]="editingId() ? 'Editar término' : 'Agregar término'"
            [modal]="true"
            [style]="{ width: '450px' }"
        >
            <div class="flex flex-col gap-4">
                <div>
                    <label class="block text-sm font-medium mb-2"
                        >Término original</label
                    >
                    <input
                        pInputText
                        type="text"
                        class="w-full"
                        placeholder="p.ej. the Angel Lord"
                        [(ngModel)]="dialogSource"
                        [disabled]="editingId() !== null"
                    />
                    @if (editingId() === null) {
                        <small class="text-muted-color"
                            >Si ya existe en el alcance, se actualizará su
                            traducción.</small
                        >
                    }
                </div>
                <div>
                    <label class="block text-sm font-medium mb-2"
                        >Traducción establecida</label
                    >
                    <input
                        pInputText
                        type="text"
                        class="w-full"
                        placeholder="p.ej. el Señor de los Ángeles"
                        [(ngModel)]="dialogTarget"
                    />
                    <small class="text-muted-color"
                        >La traducción que usará el pipeline en todos los
                        chunks.</small
                    >
                </div>
            </div>
            <ng-template pTemplate="footer">
                <div class="flex justify-end gap-2">
                    <p-button
                        label="Cancelar"
                        severity="secondary"
                        [outlined]="true"
                        (click)="closeDialog()"
                    />
                    <p-button
                        label="Guardar"
                        styleClass="p-button-primary"
                        [disabled]="!dialogValid()"
                        [loading]="saving"
                        (click)="saveTerm()"
                    />
                </div>
            </ng-template>
        </p-dialog>
    `,
})
export class Glossary {
    private readonly glossaryClient = inject(GlossaryClientService);
    private readonly novelsService = inject(NovelsManagerService);
    private readonly uploadService = inject(BookTranslatorUploadService);
    private readonly messageService = inject(MessageService);
    private readonly confirmationService = inject(ConfirmationService);
    private readonly cdr = inject(ChangeDetectorRef);

    scopeOptions = [
        { label: 'Global', value: 'global' },
        { label: 'Por serie', value: 'novel' },
        { label: 'Por documento', value: 'book' },
    ];

    scope = signal<GlossaryScope>('global');
    terms = signal<GlossaryTerm[]>([]);
    loading = signal(false);
    saving = false;

    // Serie
    novelSearch = '';
    novelResults = signal<NovelSummary[]>([]);
    novelSearched = signal(false);
    searchingNovels = false;
    selectedNovelId = signal<string | null>(null);
    selectedNovelTitle = signal<string | null>(null);

    // Libro
    books = signal<BookListItem[]>([]);
    selectedBookId = signal<string | null>(null);

    // Diálogo
    dialogVisible = false;
    dialogSource = '';
    dialogTarget = '';
    editingId = signal<string | null>(null);

    bookOptions = computed(() =>
        this.books().map((book) => ({ label: book.name, value: book.id })),
    );

    scopeLabel = computed(() => {
        switch (this.scope()) {
            case 'global':
                return 'términos globales (aplican a todas las traducciones)';
            case 'novel':
                return this.selectedNovelTitle()
                    ? `glosario de la serie «${this.selectedNovelTitle()}»`
                    : null;
            case 'book': {
                const book = this.books().find(
                    (b) => b.id === this.selectedBookId(),
                );
                return book ? `glosario del documento «${book.name}»` : null;
            }
        }
    });

    constructor() {
        this.loadTerms();
    }

    onScopeChange() {
        this.novelResults.set([]);
        this.novelSearched.set(false);
        this.selectedNovelId.set(null);
        this.selectedNovelTitle.set(null);
        this.selectedBookId.set(null);
        this.terms.set([]);

        if (this.scope() === 'book' && this.books().length === 0) {
            this.uploadService.getAllBooks().subscribe({
                next: (books) => {
                    this.books.set(books);
                    this.cdr.markForCheck();
                },
                error: () =>
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Error',
                        detail: 'No se pudo cargar la lista de documentos.',
                    }),
            });
        }
        this.loadTerms();
    }

    searchNovels() {
        if (this.searchingNovels) return;
        this.searchingNovels = true;
        this.novelsService
            .getCatalog(this.novelSearch || null, 0, 12)
            .subscribe({
                next: (page) => {
                    this.novelResults.set(page.content);
                    this.novelSearched.set(true);
                    this.searchingNovels = false;
                    this.cdr.markForCheck();
                },
                error: () => {
                    this.searchingNovels = false;
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Error',
                        detail: 'No se pudo buscar la serie.',
                    });
                    this.cdr.markForCheck();
                },
            });
    }

    selectNovel(novel: NovelSummary) {
        this.selectedNovelId.set(novel.id);
        this.selectedNovelTitle.set(novel.title);
        this.loadTerms();
    }

    canAddTerm(): boolean {
        switch (this.scope()) {
            case 'global':
                return true;
            case 'novel':
                return this.selectedNovelId() !== null;
            case 'book':
                return this.selectedBookId() !== null;
        }
    }

    loadTerms() {
        const scope = this.scope();
        if (scope === 'novel' && !this.selectedNovelId()) return;
        if (scope === 'book' && !this.selectedBookId()) return;

        this.loading.set(true);
        const request =
            scope === 'global'
                ? this.glossaryClient.listGlobal()
                : scope === 'novel'
                  ? this.glossaryClient.listByNovel(this.selectedNovelId()!)
                  : this.glossaryClient.listByBook(this.selectedBookId()!);

        request.subscribe({
            next: (terms) => {
                this.terms.set(terms);
                this.loading.set(false);
                this.cdr.markForCheck();
            },
            error: () => {
                this.loading.set(false);
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo cargar el glosario.',
                });
                this.cdr.markForCheck();
            },
        });
    }

    openCreateDialog() {
        this.editingId.set(null);
        this.dialogSource = '';
        this.dialogTarget = '';
        this.dialogVisible = true;
    }

    openEditDialog(term: GlossaryTerm) {
        this.editingId.set(term.id);
        this.dialogSource = term.sourceTerm;
        this.dialogTarget = term.targetTerm;
        this.dialogVisible = true;
    }

    closeDialog() {
        this.dialogVisible = false;
    }

    dialogValid(): boolean {
        return (
            this.dialogSource.trim().length > 0 &&
            this.dialogTarget.trim().length > 0
        );
    }

    saveTerm() {
        if (!this.dialogValid() || this.saving) return;
        this.saving = true;

        const payload = {
            sourceTerm: this.dialogSource.trim(),
            targetTerm: this.dialogTarget.trim(),
            novelId: this.scope() === 'novel' ? this.selectedNovelId() : null,
            bookId: this.scope() === 'book' ? this.selectedBookId() : null,
        };

        this.glossaryClient.save(payload).subscribe({
            next: () => {
                this.saving = false;
                this.dialogVisible = false;
                this.messageService.add({
                    severity: 'success',
                    summary: 'Término guardado',
                    detail: `«${payload.sourceTerm}» → «${payload.targetTerm}»`,
                });
                this.loadTerms();
            },
            error: (err: HttpErrorResponse) => {
                this.saving = false;
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail:
                        err?.error?.error || 'No se pudo guardar el término.',
                });
                this.cdr.markForCheck();
            },
        });
    }

    confirmDelete(term: GlossaryTerm) {
        this.confirmationService.confirm({
            header: 'Eliminar término',
            message: `¿Eliminar «${term.sourceTerm}» → «${term.targetTerm}» del glosario?`,
            acceptLabel: 'Eliminar',
            rejectLabel: 'Cancelar',
            acceptButtonStyleClass: 'p-button-danger',
            accept: () => this.deleteTerm(term),
        });
    }

    private deleteTerm(term: GlossaryTerm) {
        this.glossaryClient.delete(term.id).subscribe({
            next: () => {
                this.messageService.add({
                    severity: 'success',
                    summary: 'Término eliminado',
                    detail: `«${term.sourceTerm}»`,
                });
                this.loadTerms();
            },
            error: () =>
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo eliminar el término.',
                }),
        });
    }
}
