import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { ProcessingConfig } from './upload-step2-config.component';
import { BookTranslatorUploadService, UploadResponse } from './book-translator-upload.service';

@Component({
  selector: 'app-upload-step3-confirm',
  standalone: true,
  imports: [CommonModule, ButtonModule, TagModule, ProgressSpinnerModule, ToastModule],
  template: `
    <p-toast></p-toast>

    <div class="card">
      <div class="font-semibold text-xl mb-4">Paso 3: Confirmar y Procesar</div>
      <p class="text-gray-600 mb-6">
        Revisa la configuración antes de iniciar el procesamiento.
      </p>

      <!-- Files Summary -->
      <div class="mb-6 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <div class="font-medium mb-2">Archivos a procesar ({{ selectedFiles.length }}):</div>
        <div class="space-y-1 max-h-40 overflow-y-auto">
          <div *ngFor="let file of selectedFiles" class="flex items-center gap-3 text-sm">
            <i class="pi" [ngClass]="getFileIcon(file.type)"></i>
            <span class="truncate">{{ file.name }}</span>
            <span class="text-gray-500">{{ formatFileSize(file.size) }}</span>
          </div>
        </div>
      </div>

      <!-- Configuration Summary -->
      <div class="mb-6 p-4 bg-gray-50 border rounded-lg">
        <div class="font-medium mb-3">Configuración de Procesamiento:</div>
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
          <div>
            <span class="text-gray-500">Idioma destino:</span>
            <span class="font-medium ml-2">{{ getLanguageLabel(config.targetLanguage) }}</span>
          </div>
          <div>
            <span class="text-gray-500">Voz TTS:</span>
            <span class="font-medium ml-2">{{ config.ttsVoice }}</span>
          </div>
          <div>
            <span class="text-gray-500">Patrones de limpieza:</span>
            <span class="font-medium ml-2">{{ config.regexPatternIds.length || 0 }} seleccionados</span>
          </div>
          <div>
            <span class="text-gray-500">Dividir en fragmentos:</span>
            <p-tag [value]="config.splitIntoChunks ? 'Sí' : 'No'" [severity]="config.splitIntoChunks ? 'success' : 'danger'" />
          </div>
          <div>
            <span class="text-gray-500">Generar audio:</span>
            <p-tag [value]="config.generateAudio ? 'Sí' : 'No'" [severity]="config.generateAudio ? 'success' : 'danger'" />
          </div>
        </div>
      </div>

      <!-- Processing Status -->
      <div *ngIf="processing" class="mb-6 p-4 bg-yellow-50 border border-yellow-200 rounded-lg">
        <div class="flex items-center gap-3">
          <p-progressSpinner styleClass="w-6 h-6" strokeWidth="3"></p-progressSpinner>
          <div>
            <div class="font-medium">{{ processingMessage }}</div>
            <div class="text-sm text-gray-600" *ngIf="currentBookId">Book ID: {{ currentBookId }}</div>
          </div>
        </div>
        <div class="mt-3 text-sm text-gray-600" *ngIf="processedCount !== undefined">
          Procesados: {{ processedCount }} de {{ totalFiles }}
        </div>
      </div>

      <!-- Results -->
      <div *ngIf="results.length > 0 && !processing" class="mb-6">
        <div class="font-medium mb-3">Resultados:</div>
        <div class="space-y-2 max-h-60 overflow-y-auto">
          <div *ngFor="let result of results" class="p-3 rounded-lg border" [ngClass]="result.success ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'">
            <div class="flex items-center justify-between">
              <div>
                <div class="font-medium">{{ result.fileName }}</div>
                <div class="text-sm text-gray-600">{{ result.message }}</div>
              </div>
              <p-tag [value]="result.success ? 'Éxito' : 'Error'" [severity]="result.success ? 'success' : 'danger'" />
            </div>
            <div *ngIf="result.bookId" class="mt-1 text-sm text-gray-500">Book ID: {{ result.bookId }}</div>
          </div>
        </div>
      </div>

      <!-- Action Buttons -->
      <div class="flex justify-content-between pt-4 border-t">
        <p-button
          label="Atrás"
          icon="pi pi-arrow-left"
          (click)="onBack()"
          styleClass="p-button-outlined p-button-secondary"
          iconPos="left"
          [disabled]="processing">
        </p-button>

        <div class="flex gap-2">
          <p-button
            *ngIf="results.length > 0"
            label="Ver Historial"
            icon="pi pi-history"
            (click)="onViewHistory()"
            styleClass="p-button-outlined p-button-primary"
            iconPos="right"
            [disabled]="processing">
          </p-button>

          <p-button
            *ngIf="!processing && results.length === 0"
            label="Iniciar Procesamiento"
            icon="pi pi-play"
            (click)="onStartProcessing()"
            styleClass="p-button-primary"
            iconPos="right"
            [disabled]="!canStart()">
          </p-button>

          <p-button
            *ngIf="processing"
            label="Procesando..."
            icon="pi pi-spin pi-spinner"
            styleClass="p-button-primary"
            iconPos="right"
            [disabled]="true">
          </p-button>
        </div>
      </div>
    </div>
  `,
  providers: [MessageService]
})
export class UploadStep3ConfirmComponent implements OnChanges {
  @Input() selectedFiles: File[] = [];
  @Input() config!: ProcessingConfig;
  @Output() back = new EventEmitter<void>();
  @Output() viewHistory = new EventEmitter<void>();
  @Output() processingComplete = new EventEmitter<UploadResponse[]>();

  processing = false;
  processingMessage = '';
  currentBookId = '';
  processedCount = 0;
  totalFiles = 0;
  results: { fileName: string; success: boolean; message: string; bookId?: string }[] = [];

  constructor(
    private uploadService: BookTranslatorUploadService,
    private messageService: MessageService
  ) {}

  ngOnChanges(changes: SimpleChanges) {
    if (changes['selectedFiles'] || changes['config']) {
      // Reset results when inputs change
      if (!this.processing) {
        this.results = [];
      }
    }
  }

  onBack() {
    if (!this.processing) {
      this.back.emit();
    }
  }

  onViewHistory() {
    this.viewHistory.emit();
  }

  canStart(): boolean {
    return this.selectedFiles.length > 0 &&
           !!this.config.targetLanguage &&
           !!this.config.ttsVoice;
  }

  async onStartProcessing() {
    if (!this.canStart() || this.processing) return;

    this.processing = true;
    this.results = [];
    this.totalFiles = this.selectedFiles.length;
    this.processedCount = 0;

    try {
      // Upload files sequentially
      for (const file of this.selectedFiles) {
        this.processingMessage = `Subiendo ${file.name}...`;
        this.currentBookId = '';

        try {
          const response = await this.uploadFile(file);

          if (response.success) {
            this.currentBookId = response.bookId || '';
            this.processingMessage = `Procesando ${file.name}...`;

            // Wait for processing to complete
            const finalStatus = await this.pollForCompletion(response.bookId!);

            this.results.push({
              fileName: file.name,
              success: finalStatus.status === 'COMPLETED',
              message: finalStatus.status === 'COMPLETED' ? 'Procesamiento completado' : `Error: ${finalStatus.status}`,
              bookId: response.bookId
            });
          } else {
            this.results.push({
              fileName: file.name,
              success: false,
              message: response.error || 'Error al subir archivo'
            });
          }
        } catch (err) {
          this.results.push({
            fileName: file.name,
            success: false,
            message: err instanceof Error ? err.message : 'Error desconocido'
          });
        }

        this.processedCount++;
      }

      this.processingMessage = 'Procesamiento completado';
      this.processingComplete.emit(this.results.filter(r => r.success).map(r => ({
        message: r.message,
        bookId: r.bookId!,
        status: 'COMPLETED'
      } as UploadResponse)));

    } catch (err) {
      this.messageService.add({
        severity: 'error',
        summary: 'Error',
        detail: 'Error durante el procesamiento'
      });
    } finally {
      this.processing = false;
      this.currentBookId = '';
    }
  }

  private uploadFile(file: File): Promise<UploadResponse & { success: boolean }> {
    return new Promise((resolve, reject) => {
      this.uploadService.uploadFile(file).subscribe({
        next: (response) => resolve({ ...response, success: !response.error }),
        error: (err) => reject(err)
      });
    });
  }

  private pollForCompletion(bookId: string): Promise<{ status: string }> {
    return new Promise((resolve, reject) => {
      const poll = () => {
        this.uploadService.getBookStatus(bookId).subscribe({
          next: (response) => {
            if (response.status === 'COMPLETED' || response.status === 'FAILED') {
              resolve(response);
            } else {
              setTimeout(poll, 3000);
            }
          },
          error: (err) => reject(err)
        });
      };
      poll();
    });
  }

  getLanguageLabel(code: string): string {
    const lang = [
      { label: 'Español (es)', value: 'es' },
      { label: 'Inglés (en)', value: 'en' },
      { label: 'Francés (fr)', value: 'fr' },
      { label: 'Alemán (de)', value: 'de' },
      { label: 'Italiano (it)', value: 'it' },
      { label: 'Portugués (pt)', value: 'pt' },
      { label: 'Chino (zh)', value: 'zh' },
      { label: 'Japonés (ja)', value: 'ja' },
      { label: 'Coreano (ko)', value: 'ko' }
    ].find(l => l.value === code);
    return lang?.label || code;
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