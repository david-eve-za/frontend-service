import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { TableModule } from 'primeng/table';
import { MessageService } from 'primeng/api';
import { Subscription, firstValueFrom } from 'rxjs';
import {
  BookTranslatorUploadService,
  TraceEvent,
  BookStatusResponse
} from './upload/book-translator-upload.service';

interface BookRow {
  id: string;
  name: string;
  status: string;
  currentStep?: string;
  lastTraceId?: string;
  audioFilePath?: string;
}

@Component({
  selector: 'app-book-translator-history',
  standalone: true,
  imports: [CommonModule, ButtonModule, TagModule, ToastModule, TableModule],
  providers: [MessageService],
  template: `
    <p-toast></p-toast>

    <div class="card mb-4">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-content-between gap-4 mb-4 p-4 bg-blue-50 border-l-4 border-blue-500 rounded-r">
        <div>
          <h2 class="text-xl font-bold text-gray-800">Seguimiento de Procesamiento</h2>
          <p class="text-sm text-gray-600 mt-1">
            Estado de cada libro, línea de tiempo completa del pipeline (extracción, división,
            traducción y audio) y reanudación desde el último punto.
          </p>
        </div>
        <div class="flex gap-2">
          <p-button label="Actualizar" icon="pi pi-refresh" (click)="loadBooks()" styleClass="p-button-secondary" [loading]="loading"></p-button>
        </div>
      </div>

      <p-table [value]="books" [tableStyle]="{ 'min-width': '600px' }">
        <ng-template pTemplate="header">
          <tr>
            <th>Libro</th>
            <th>Estado</th>
            <th>Paso actual</th>
            <th>Audio</th>
            <th style="width: 220px">Acciones</th>
          </tr>
        </ng-template>
        <ng-template pTemplate="body" let-book>
          <tr>
            <td>
              <div class="font-medium">{{ book.name }}</div>
              <div class="text-xs text-gray-500">{{ book.id }}</div>
            </td>
            <td><p-tag [value]="book.status" [severity]="statusSeverity(book.status)"></p-tag></td>
            <td>{{ stepLabel(book.currentStep) }}</td>
            <td>
              <span *ngIf="book.audioFilePath" class="text-xs">{{ book.audioFilePath }}</span>
              <span *ngIf="!book.audioFilePath" class="text-xs text-gray-400">—</span>
            </td>
            <td>
              <div class="flex gap-1">
                <p-button
                  icon="pi pi-list"
                  label="Timeline"
                  styleClass="p-button-sm p-button-outlined p-button-info"
                  (click)="loadTrace(book.id)">
                </p-button>
                <p-button
                  *ngIf="book.status !== 'COMPLETED'"
                  icon="pi pi-play-circle"
                  [label]="book.status === 'FAILED' || book.currentStep ? 'Reanudar' : 'Iniciar'"
                  styleClass="p-button-sm"
                  [styleClass]="'p-button-sm ' + (book.status === 'FAILED' ? 'p-button-warning' : 'p-button-primary')"
                  [disabled]="resuming[book.id]"
                  (click)="resumeBook(book)">
                </p-button>
              </div>
            </td>
          </tr>
        </ng-template>
        <ng-template pTemplate="emptymessage">
          <tr>
            <td colspan="5" class="text-center text-gray-500 py-4">No hay libros cargados todavía.</td>
          </tr>
        </ng-template>
      </p-table>
    </div>

    <div class="card" *ngIf="selectedBookId">
      <div class="flex items-center justify-between mb-4">
        <div>
          <h3 class="text-lg font-bold text-gray-800">Línea de tiempo</h3>
          <p class="text-sm text-gray-600">Libro: {{ selectedBookName }}</p>
        </div>
        <p-button icon="pi pi-times" styleClass="p-button-text p-button-secondary" (click)="closeTimeline()"></p-button>
      </div>

      <div *ngIf="traceLoading" class="text-center py-4 text-gray-500">Cargando línea de tiempo...</div>

      <div *ngIf="!traceLoading && trace.length === 0" class="text-center py-4 text-gray-500">
        Este libro aún no tiene eventos de procesamiento.
      </div>

      <div *ngIf="!traceLoading && trace.length > 0" class="space-y-0">
        <div *ngFor="let event of trace; let i = index" class="flex gap-4">
          <div class="flex flex-col items-center">
            <div class="rounded-full border-2 w-8 h-8 flex items-center justify-content-center"
                 [ngClass]="eventDotClass(event.status)">
              <i class="pi text-sm" [class]="eventIcon(event.status)"></i>
            </div>
            <div *ngIf="i < trace.length - 1" class="w-px flex-1 bg-gray-200 my-1"></div>
          </div>
          <div class="pb-6 min-w-0">
            <div class="flex flex-wrap items-center gap-2">
              <span class="font-medium">{{ stepLabel(event.step) }}</span>
              <p-tag [value]="eventStatusLabel(event.status)" [severity]="eventSeverity(event.status)"></p-tag>
              <span class="text-xs text-gray-400">intento #{{ event.attempt }}</span>
              <span class="text-xs text-gray-400">{{ event.traceId }}</span>
            </div>
            <div class="text-xs text-gray-500 mt-1">
              {{ event.startedAt | date: 'dd/MM/yy HH:mm:ss' }}
              <span *ngIf="event.finishedAt"> → {{ event.finishedAt | date: 'dd/MM/yy HH:mm:ss' }}</span>
              <span *ngIf="event.finishedAt"> ({{ durationMs(event) }} ms)</span>
            </div>
            <div *ngIf="event.details" class="text-sm text-gray-600 mt-1">{{ event.details }}</div>
            <div *ngIf="event.errorMessage" class="text-sm text-red-600 mt-1 font-medium">
              <i class="pi pi-exclamation-triangle mr-1"></i>{{ event.errorMessage }}
            </div>
          </div>
        </div>
      </div>
    </div>
  `
})
export class BookTranslatorHistory implements OnInit, OnDestroy {

  books: BookRow[] = [];
  loading = false;

  selectedBookId?: string;
  selectedBookName?: string;
  trace: TraceEvent[] = [];
  traceLoading = false;

  resuming: Record<string, boolean> = {};
  private pollSubscription?: Subscription;

  constructor(
    private uploadService: BookTranslatorUploadService,
    private messageService: MessageService
  ) {}

  ngOnInit(): void {
    this.loadBooks();
  }

  ngOnDestroy(): void {
    this.pollSubscription?.unsubscribe();
  }

  async loadBooks(): Promise<void> {
    this.loading = true;
    try {
      const all = await firstValueFrom(this.uploadService.getAllBooks());
      this.books = all.map(b => ({
        id: b.id,
        name: b.name,
        status: b.status,
        currentStep: b.currentStep,
        lastTraceId: b.lastTraceId,
        audioFilePath: b.audioFilePath
      }));
    } catch (err) {
      this.messageService.add({
        severity: 'error',
        summary: 'Error',
        detail: 'No se pudo cargar la lista de libros.'
      });
    } finally {
      this.loading = false;
    }
  }

  async loadTrace(bookId: string): Promise<void> {
    this.selectedBookId = bookId;
    this.selectedBookName = this.books.find(b => b.id === bookId)?.name || bookId;
    this.traceLoading = true;
    try {
      this.trace = await firstValueFrom(this.uploadService.getBookTrace(bookId));
    } catch {
      this.messageService.add({
        severity: 'error',
        summary: 'Error',
        detail: 'No se pudo cargar la línea de tiempo.'
      });
    } finally {
      this.traceLoading = false;
    }
  }

  closeTimeline(): void {
    this.selectedBookId = undefined;
    this.selectedBookName = undefined;
    this.trace = [];
  }

  async resumeBook(book: BookRow): Promise<void> {
    if (this.resuming[book.id]) return;
    this.resuming[book.id] = true;
    try {
      await firstValueFrom(this.uploadService.processBook(book.id));
      this.messageService.add({
        severity: 'success',
        summary: 'Procesamiento',
        detail: 'Pipeline iniciado/reanudado.'
      });
      this.watchBook(book.id);
    } catch (err: any) {
      const detail = err?.error?.error || 'No se pudo iniciar el procesamiento.';
      this.messageService.add({ severity: 'error', summary: 'Error', detail });
      this.resuming[book.id] = false;
    }
  }

  private watchBook(bookId: string): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = this.uploadService.pollBookStatus(bookId, 2000).subscribe({
      next: (status: BookStatusResponse) => {
        const row = this.books.find(b => b.id === bookId);
        if (row) {
          row.status = status.status;
          row.currentStep = status.currentStep;
          row.audioFilePath = status.audioFilePath;
        }
        if (this.selectedBookId === bookId) {
          this.loadTrace(bookId);
        }
      },
      error: () => this.resuming[bookId] = false,
      complete: () => {
        this.resuming[bookId] = false;
        const row = this.books.find(b => b.id === bookId);
        if (row) {
          this.messageService.add({
            severity: row.status === 'COMPLETED' ? 'success' : 'warn',
            summary: 'Proceso finalizado',
            detail: `Libro "${row.name}" terminó con estado ${row.status}.`
          });
        }
      }
    });
  }

  statusSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'COMPLETED': return 'success';
      case 'FAILED': return 'danger';
      case 'PROCESSING': return 'warn';
      case 'STOPPED': return 'secondary';
      default: return 'info';
    }
  }

  eventSeverity(status: TraceEvent['status']): 'success' | 'info' | 'warn' | 'danger' {
    switch (status) {
      case 'COMPLETED': return 'success';
      case 'FAILED': return 'danger';
      case 'STARTED': return 'warn';
      default: return 'info';
    }
  }

  eventStatusLabel(status: TraceEvent['status']): string {
    switch (status) {
      case 'COMPLETED': return 'Completado';
      case 'FAILED': return 'Fallido';
      case 'STARTED': return 'En progreso';
      default: return 'Omitido';
    }
  }

  eventDotClass(status: TraceEvent['status']): string {
    switch (status) {
      case 'COMPLETED': return 'bg-green-100 border-green-500 text-green-600';
      case 'FAILED': return 'bg-red-100 border-red-500 text-red-600';
      case 'STARTED': return 'bg-yellow-100 border-yellow-500 text-yellow-600';
      default: return 'bg-gray-100 border-gray-400 text-gray-500';
    }
  }

  eventIcon(status: TraceEvent['status']): string {
    switch (status) {
      case 'COMPLETED': return 'pi-check';
      case 'FAILED': return 'pi-times';
      case 'STARTED': return 'pi-spin pi-spinner';
      default: return 'pi-minus';
    }
  }

  stepLabel(step?: string): string {
    if (!step) return '—';
    switch (step) {
      case 'EXTRACT': return 'Extracción de texto';
      case 'SPLIT': return 'División en fragmentos';
      case 'TRANSLATE': return 'Traducción';
      case 'AUDIO': return 'Generación de audio';
      case 'FINALIZE': return 'Finalización';
      default: return step;
    }
  }

  durationMs(event: TraceEvent): number {
    if (!event.finishedAt) return 0;
    return new Date(event.finishedAt).getTime() - new Date(event.startedAt).getTime();
  }
}
