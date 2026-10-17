import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FileUploadModule } from 'primeng/fileupload';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';
import { TagModule } from 'primeng/tag';
import { RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { FileSelectEvent, FileUploadEvent } from 'primeng/fileupload';
import { firstValueFrom } from 'rxjs';
import { BookTranslatorUploadService } from './book-translator-upload.service';

export interface SelectedFile {
    file: File;
    name: string;
    size: number;
    type: string;
    valid: boolean;
    error?: string;
}

interface UploadResult {
    fileName: string;
    success: boolean;
    message: string;
    bookId?: string;
}

const ALLOWED_TYPES = [
    'application/pdf',
    'application/epub+zip',
    'text/plain',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
];
const MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

/**
 * Página de Subir Archivos del pipeline: selección y subida de los libros
 * (extracción de texto inmediata en el backend). La división en bloques se
 * hace después en la página Dividir Texto.
 */
@Component({
    selector: 'app-upload-step1-files',
    standalone: true,
    imports: [
        CommonModule,
        RouterLink,
        FileUploadModule,
        ButtonModule,
        ToastModule,
        TagModule,
    ],
    template: `
        <p-toast [life]="12000"></p-toast>

        <div class="card">
            <div class="font-semibold text-xl mb-4">Subir Archivos</div>
            <p class="text-gray-600 mb-6">
                Selecciona uno o más archivos de libro para procesar. Formatos
                soportados: PDF, EPUB, TXT, DOCX (máx. 100MB c/u). El texto se
                extrae al subir; después divídelo en bloques desde
                <a
                    routerLink="/pipeline/split-text"
                    class="text-blue-600 hover:underline"
                    >Dividir Texto</a
                >.
            </p>

            <!-- File Upload -->
            <p-fileupload
                #fileUpload
                name="books[]"
                [multiple]="true"
                [accept]="acceptTypes"
                [maxFileSize]="maxFileSize"
                mode="advanced"
                chooseLabel="Seleccionar Archivos"
                chooseIcon="pi pi-upload"
                uploadLabel="Subir"
                uploadIcon="pi pi-cloud-upload"
                cancelLabel="Cancelar"
                cancelIcon="pi pi-times"
                (onSelect)="onFileSelect($event)"
                (onRemove)="onFileRemove($event)"
                (onClear)="onClear($event)"
                (onUpload)="onUpload($event)"
                [showUploadButton]="true"
                [showCancelButton]="true"
                [auto]="false"
                [customUpload]="true"
                (uploadHandler)="customUpload($event)"
            >
                <ng-template #empty>
                    <div class="text-center py-8">
                        <i
                            class="pi pi-cloud-upload text-4xl text-gray-300"
                        ></i>
                        <p class="mt-2 text-gray-500">
                            Arrastra y suelta archivos aquí o haz clic para
                            seleccionar
                        </p>
                    </div>
                </ng-template>
                <ng-template #content let-files>
                    <div *ngIf="files.length === 0" class="text-center py-8">
                        <i class="pi pi-inbox text-4xl text-gray-300"></i>
                        <p class="mt-2 text-gray-500">
                            No hay archivos seleccionados
                        </p>
                    </div>
                </ng-template>
            </p-fileupload>

            <!-- Selected Files Summary -->
            <div *ngIf="selectedFiles.length > 0" class="mt-6">
                <div class="font-medium mb-3">
                    Archivos seleccionados ({{ selectedFiles.length }}):
                </div>
                <div class="space-y-2 max-h-60 overflow-y-auto">
                    <div
                        *ngFor="let f of selectedFiles; let i = index"
                        class="flex items-center gap-3 p-3 bg-gray-50 rounded-lg border"
                    >
                        <div class="flex-shrink-0">
                            <i class="pi" [ngClass]="getFileIcon(f.type)"></i>
                        </div>
                        <div class="flex-1 min-w-0">
                            <div class="font-medium truncate">{{ f.name }}</div>
                            <div class="text-sm text-gray-500">
                                {{ formatFileSize(f.size) }} • {{ f.type }}
                            </div>
                        </div>
                        <p-tag
                            *ngIf="f.valid"
                            value="Válido"
                            severity="success"
                        />
                        <p-tag
                            *ngIf="!f.valid"
                            value="Error: {{ f.error }}"
                            severity="danger"
                        />
                        <p-button
                            icon="pi pi-times"
                            (click)="removeFile(i)"
                            styleClass="p-button-text p-button-sm p-button-danger"
                            pTooltip="Eliminar"
                        >
                        </p-button>
                    </div>
                </div>

                <!-- Action Buttons -->
                <div class="flex justify-content-end gap-2 mt-4 pt-4 border-t">
                    <p-button
                        label="Limpiar Todo"
                        icon="pi pi-trash"
                        (click)="clearAll()"
                        styleClass="p-button-outlined p-button-secondary"
                        [disabled]="uploading"
                    >
                    </p-button>
                    <p-button
                        label="Subir Archivos"
                        icon="pi pi-cloud-upload"
                        (click)="uploadSelected()"
                        styleClass="p-button-primary"
                        [disabled]="!hasValidFiles || uploading"
                        [loading]="uploading"
                    >
                    </p-button>
                </div>
            </div>

            <div
                *ngIf="selectedFiles.length === 0 && results.length === 0"
                class="mt-6 text-center text-gray-500"
            >
                <p>Selecciona al menos un archivo válido para subir</p>
            </div>

            <!-- Upload Results -->
            <div *ngIf="results.length > 0" class="mt-6">
                <div class="font-medium mb-3">Resultados de la subida:</div>
                <div class="space-y-2 max-h-60 overflow-y-auto">
                    <div
                        *ngFor="let result of results"
                        class="p-3 rounded-lg border"
                        [ngClass]="
                            result.success
                                ? 'bg-green-50 border-green-200'
                                : 'bg-red-50 border-red-200'
                        "
                    >
                        <div class="flex items-center justify-between">
                            <div>
                                <div class="font-medium">
                                    {{ result.fileName }}
                                </div>
                                <div class="text-sm text-gray-600">
                                    {{ result.message }}
                                </div>
                            </div>
                            <p-tag
                                [value]="result.success ? 'Éxito' : 'Error'"
                                [severity]="
                                    result.success ? 'success' : 'danger'
                                "
                            />
                        </div>
                        <div
                            *ngIf="result.bookId"
                            class="mt-1 text-sm text-gray-500"
                        >
                            Book ID: {{ result.bookId }}
                        </div>
                    </div>
                </div>
                <div
                    *ngIf="allSucceeded"
                    class="mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg flex items-center justify-between gap-3"
                >
                    <span class="text-sm"
                        >Textos extraídos correctamente. Continúa marcando los
                        bloques semánticos.</span
                    >
                    <p-button
                        label="Ir a Dividir Texto"
                        icon="pi pi-arrow-right"
                        routerLink="/pipeline/split-text"
                        styleClass="p-button-primary"
                        iconPos="right"
                    />
                </div>
            </div>
        </div>
    `,
    providers: [MessageService],
    styles: [
        `
            :host ::ng-deep .p-fileupload .p-fileupload-content {
                padding: 1rem;
            }
            :host ::ng-deep .p-fileupload .p-fileupload-buttonbar {
                padding: 1rem;
            }
        `,
    ],
})
export class UploadStep1FilesComponent implements OnInit {
    selectedFiles: SelectedFile[] = [];
    uploading = false;
    results: UploadResult[] = [];

    acceptTypes = '.pdf,.epub,.txt,.docx';
    maxFileSize = MAX_FILE_SIZE;

    constructor(
        private messageService: MessageService,
        private uploadService: BookTranslatorUploadService,
    ) {}

    ngOnInit() {}

    get hasValidFiles(): boolean {
        return this.selectedFiles.some((f) => f.valid);
    }

    get allSucceeded(): boolean {
        return this.results.length > 0 && this.results.every((r) => r.success);
    }

    onFileSelect(event: FileSelectEvent) {
        const files = event.currentFiles || [];
        for (const file of files) {
            const selectedFile = this.validateFile(file);
            // Check for duplicates
            const exists = this.selectedFiles.some(
                (f) => f.file.name === file.name && f.file.size === file.size,
            );
            if (!exists) {
                this.selectedFiles.push(selectedFile);
            }
        }
    }

    onFileRemove(event: any) {
        const file = event.file;
        this.selectedFiles = this.selectedFiles.filter((f) => f.file !== file);
    }

    onClear(event: any) {
        this.selectedFiles = [];
    }

    onUpload(event: FileUploadEvent) {
        // Handled by customUpload
    }

    customUpload(event: any) {
        this.uploadSelected();
        if (event?.callback) {
            event.callback();
        }
    }

    removeFile(index: number) {
        this.selectedFiles.splice(index, 1);
    }

    clearAll() {
        this.selectedFiles = [];
        this.results = [];
    }

    async uploadSelected() {
        if (!this.hasValidFiles || this.uploading) return;

        this.uploading = true;
        this.results = [];
        const validFiles = this.selectedFiles.filter((f) => f.valid);

        for (const selected of validFiles) {
            try {
                const response = await firstValueFrom(
                    this.uploadService.uploadFile(selected.file),
                );
                if (response?.bookId) {
                    this.results.push({
                        fileName: selected.name,
                        success: true,
                        message: 'Texto extraído correctamente',
                        bookId: response.bookId,
                    });
                } else {
                    this.results.push({
                        fileName: selected.name,
                        success: false,
                        message:
                            response?.error ||
                            'Respuesta inválida del servidor',
                    });
                }
            } catch (err) {
                this.results.push({
                    fileName: selected.name,
                    success: false,
                    message:
                        err instanceof Error
                            ? err.message
                            : 'Error al subir el archivo',
                });
            }
        }

        this.uploading = false;

        const successes = this.results.filter((r) => r.success).length;
        if (successes > 0) {
            this.selectedFiles = this.selectedFiles.filter(
                (f) =>
                    !this.results.some(
                        (r) => r.fileName === f.name && r.success,
                    ),
            );
            this.messageService.add({
                severity: 'success',
                summary: 'Subida completada',
                detail: `${successes} de ${validFiles.length} archivo(s) subido(s) correctamente`,
            });
        }
        if (successes < validFiles.length) {
            this.messageService.add({
                sticky: true, severity: 'error',
                summary: 'Errores en la subida',
                detail: `${validFiles.length - successes} archivo(s) no se pudieron subir`,
            });
        }
    }

    private validateFile(file: File): SelectedFile {
        const validType = ALLOWED_TYPES.includes(file.type);
        const validSize = file.size <= MAX_FILE_SIZE;

        let error = '';
        if (!validType) {
            error = 'Tipo de archivo no permitido';
        } else if (!validSize) {
            error = 'Archivo demasiado grande (máx. 100MB)';
        }

        return {
            file,
            name: file.name,
            size: file.size,
            type: file.type || 'Desconocido',
            valid: validType && validSize,
            error: error || undefined,
        };
    }

    getFileIcon(type: string): string {
        if (type.includes('pdf')) return 'pi pi-file-pdf text-red-500';
        if (type.includes('epub')) return 'pi pi-book text-blue-500';
        if (type.includes('text')) return 'pi pi-file text-gray-500';
        if (type.includes('word') || type.includes('document'))
            return 'pi pi-file-word text-blue-600';
        return 'pi pi-file text-gray-500';
    }

    formatFileSize(bytes: number): string {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }
}
