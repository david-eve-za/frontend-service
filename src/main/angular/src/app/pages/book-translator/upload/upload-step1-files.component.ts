import { Component, EventEmitter, Output, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FileUploadModule } from 'primeng/fileupload';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';
import { FileSelectEvent, FileUploadEvent } from 'primeng/fileupload';

export interface SelectedFile {
  file: File;
  name: string;
  size: number;
  type: string;
  valid: boolean;
  error?: string;
}

const ALLOWED_TYPES = ['application/pdf', 'application/epub+zip', 'text/plain', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'];
const MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

@Component({
  selector: 'app-upload-step1-files',
  standalone: true,
  imports: [CommonModule, FileUploadModule, ButtonModule, ToastModule, TagModule],
  template: `
    <p-toast></p-toast>

    <div class="card">
      <div class="font-semibold text-xl mb-4">Paso 1: Subir Archivos</div>
      <p class="text-gray-600 mb-6">
        Selecciona uno o más archivos de libro para procesar. Formatos soportados: PDF, EPUB, TXT, DOCX (máx. 100MB c/u).
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
        (uploadHandler)="customUpload($event)">
        <ng-template #empty>
          <div class="text-center py-8">
            <i class="pi pi-cloud-upload text-4xl text-gray-300"></i>
            <p class="mt-2 text-gray-500">Arrastra y suelta archivos aquí o haz clic para seleccionar</p>
          </div>
        </ng-template>
        <ng-template #content let-files>
          <div *ngIf="files.length === 0" class="text-center py-8">
            <i class="pi pi-inbox text-4xl text-gray-300"></i>
            <p class="mt-2 text-gray-500">No hay archivos seleccionados</p>
          </div>
        </ng-template>
      </p-fileupload>

      <!-- Selected Files Summary -->
      <div *ngIf="selectedFiles.length > 0" class="mt-6">
        <div class="font-medium mb-3">Archivos seleccionados ({{ selectedFiles.length }}):</div>
        <div class="space-y-2 max-h-60 overflow-y-auto">
          <div *ngFor="let f of selectedFiles; let i = index" class="flex items-center gap-3 p-3 bg-gray-50 rounded-lg border">
            <div class="flex-shrink-0">
              <i class="pi" [ngClass]="getFileIcon(f.type)"></i>
            </div>
            <div class="flex-1 min-w-0">
              <div class="font-medium truncate">{{ f.name }}</div>
              <div class="text-sm text-gray-500">{{ formatFileSize(f.size) }} • {{ f.type }}</div>
            </div>
            <p-tag *ngIf="f.valid" value="Válido" severity="success" />
            <p-tag *ngIf="!f.valid" value="Error: {{ f.error }}" severity="danger" />
            <p-button
              icon="pi pi-times"
              (click)="removeFile(i)"
              styleClass="p-button-text p-button-sm p-button-danger"
              pTooltip="Eliminar">
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
            [disabled]="uploading">
          </p-button>
          <p-button
            label="Continuar"
            icon="pi pi-arrow-right"
            (click)="onContinue()"
            styleClass="p-button-primary"
            [disabled]="!hasValidFiles || uploading"
            iconPos="right">
          </p-button>
        </div>
      </div>

      <div *ngIf="selectedFiles.length === 0" class="mt-6 text-center text-gray-500">
        <p>Selecciona al menos un archivo válido para continuar</p>
      </div>
    </div>
  `,
  providers: [MessageService],
  styles: [`
    :host ::ng-deep .p-fileupload .p-fileupload-content {
      padding: 1rem;
    }
    :host ::ng-deep .p-fileupload .p-fileupload-buttonbar {
      padding: 1rem;
    }
  `]
})
export class UploadStep1FilesComponent implements OnInit {
  @Output() filesSelected = new EventEmitter<SelectedFile[]>();
  @Output() continue = new EventEmitter<void>();

  selectedFiles: SelectedFile[] = [];
  uploading = false;

  acceptTypes = '.pdf,.epub,.txt,.docx';
  maxFileSize = MAX_FILE_SIZE;

  constructor(private messageService: MessageService) {}

  ngOnInit() {}

  onFileSelect(event: FileSelectEvent) {
    const files = event.currentFiles || [];
    for (const file of files) {
      const selectedFile = this.validateFile(file);
      // Check for duplicates
      const exists = this.selectedFiles.some(f => f.file.name === file.name && f.file.size === file.size);
      if (!exists) {
        this.selectedFiles.push(selectedFile);
      }
    }
    this.filesSelected.emit(this.selectedFiles);
  }

  onFileRemove(event: any) {
    const file = event.file;
    this.selectedFiles = this.selectedFiles.filter(f => f.file !== file);
    this.filesSelected.emit(this.selectedFiles);
  }

  onClear(event: any) {
    this.selectedFiles = [];
    this.filesSelected.emit(this.selectedFiles);
  }

  onUpload(event: FileUploadEvent) {
    // Handled by customUpload
  }

  customUpload(event: any) {
    // This is handled by the parent wizard component
    event.callback();
  }

  removeFile(index: number) {
    this.selectedFiles.splice(index, 1);
    this.filesSelected.emit(this.selectedFiles);
  }

  clearAll() {
    this.selectedFiles = [];
    this.filesSelected.emit(this.selectedFiles);
  }

  onContinue() {
    if (this.hasValidFiles) {
      this.continue.emit();
    }
  }

  get hasValidFiles(): boolean {
    return this.selectedFiles.some(f => f.valid);
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
      error: error || undefined
    };
  }

  getFileIcon(type: string): string {
    if (type.includes('pdf')) return 'pi pi-file-pdf text-red-500';
    if (type.includes('epub')) return 'pi pi-book text-blue-500';
    if (type.includes('text')) return 'pi pi-file text-gray-500';
    if (type.includes('word') || type.includes('document')) return 'pi pi-file-word text-blue-600';
    return 'pi pi-file text-gray-500';
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}