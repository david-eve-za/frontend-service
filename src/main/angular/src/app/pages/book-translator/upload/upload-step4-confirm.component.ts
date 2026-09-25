import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { firstValueFrom } from 'rxjs';
import { ProcessingConfig } from './upload-step2-config.component';
import { SplitBookState } from './upload-step3-split.component';
import { BookTranslatorUploadService, UploadResponse } from './book-translator-upload.service';

@Component({
  selector: 'app-upload-step4-confirm',
  standalone: true,
  imports: [CommonModule, ButtonModule, TagModule, ProgressSpinnerModule, ToastModule],
  template: `
    <p-toast></p-toast>

    <div class="card">
      <div class="font-semibold text-xl mb-4">Paso 4: Confirmar y Procesar</div>
      <p class="text-gray-600 mb-6">
        Revisa la configuración antes de iniciar el procesamiento.
      </p>

      <!-- Books Summary -->
      <div class="mb-6 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <div class="font-medium mb-2">Documentos a procesar ({{ books.length }}):</div>
        <div class="space-y-1 max-h-40 overflow-y-auto">
          <div *ngFor="let book of books" class="flex items-center gap-3 text-sm">
            <i class="pi pi-file"></i>
            <span class="truncate">{{ book.name }}</span>
            <span class="text-gray-500 truncate">{{ book.bookId }}</span>
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
        </div>
      </div>

      <!-- Processing Status -->
      <div *ngIf="processing" class="mb-6 p-4 bg-yellow-50 border border-yellow-200 rounded-lg">
        <div class="flex items-center gap-3">
          <p-progressSpinner styleClass="w-6 h-6" strokeWidth="3"></p-progressSpinner>
          <div class="font-medium">Dividiendo el texto en fragmentos...</div>
        </div>
        <div class="mt-3 text-sm text-gray-600">
          Procesados: {{ processedCount }} de {{ totalBooks }}
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
export class UploadStep4ConfirmComponent implements OnChanges {
  @Input() books: SplitBookState[] = [];
  @Input() config!: ProcessingConfig;
  @Output() back = new EventEmitter<void>();
  @Output() viewHistory = new EventEmitter<void>();
  @Output() processingComplete = new EventEmitter<UploadResponse[]>();

  processing = false;
  processedCount = 0;
  totalBooks = 0;
  results: { fileName: string; success: boolean; message: string; bookId?: string }[] = [];

  constructor(
    private uploadService: BookTranslatorUploadService,
    private messageService: MessageService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnChanges(changes: SimpleChanges) {
    if (changes['books'] || changes['config']) {
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
    return this.books.length > 0 &&
           !!this.config.targetLanguage &&
           !!this.config.ttsVoice;
  }

  async onStartProcessing() {
    if (!this.canStart() || this.processing) return;

    this.processing = true;
    this.results = [];
    this.totalBooks = this.books.length;
    this.processedCount = 0;

    for (const book of this.books) {
      try {
        await firstValueFrom(this.uploadService.splitBookIntoChunks(book.bookId));
        this.results.push({
          fileName: book.name,
          success: true,
          message: 'Texto dividido en fragmentos correctamente',
          bookId: book.bookId
        });
      } catch (err) {
        this.results.push({
          fileName: book.name,
          success: false,
          message: err instanceof Error ? err.message : 'Error al dividir el texto'
        });
      }
      this.processedCount++;
      this.cdr.markForCheck();
    }

    this.processing = false;
    this.cdr.markForCheck();

    const successes = this.results.filter(r => r.success);
    if (successes.length > 0) {
      this.processingComplete.emit(successes.map(r => ({
        message: r.message,
        bookId: r.bookId!,
        status: 'COMPLETED'
      } as UploadResponse)));
    } else {
      this.messageService.add({
        severity: 'error',
        summary: 'Error',
        detail: 'No se pudo procesar ningún documento.'
      });
    }
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
}
