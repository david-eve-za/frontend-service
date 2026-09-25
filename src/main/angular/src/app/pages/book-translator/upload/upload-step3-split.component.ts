import { Component, ElementRef, Input, Output, EventEmitter, ViewChild, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SelectModule } from 'primeng/select';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { firstValueFrom } from 'rxjs';
import { BookTranslatorUploadService, SemanticBlock } from './book-translator-upload.service';

export interface SplitBookState {
  name: string;
  bookId: string;
  text: string;
  loaded: boolean;
  saved: boolean;
}

export type BlockTypeValue = 'PROLOGUE' | 'CHAPTER' | 'EPILOGUE';

@Component({
  selector: 'app-upload-step3-split',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectModule, ButtonModule, TagModule, ToastModule],
  template: `
    <p-toast></p-toast>

    <div class="card">
      <div class="font-semibold text-xl mb-4">Paso 3: Split del Texto</div>
      <p class="text-gray-600 mb-6">
        Marca los bloques semánticos del texto extraído: inserta la apertura y el cierre de cada bloque
        (Prologue, Chapter, Epilogue) usando la barra de herramientas y guarda para continuar.
      </p>

      <div *ngIf="uploading" class="mb-6 p-4 bg-blue-50 border border-blue-200 rounded-lg flex items-center gap-3">
        <i class="pi pi-spin pi-spinner"></i>
        <span class="font-medium">Subiendo archivos... ({{ uploadedCount }} de {{ totalToUpload }})</span>
      </div>

      <ng-container *ngIf="!uploading">
        <div *ngIf="books.length > 1" class="mb-4">
          <label class="block text-sm font-medium mb-2">Documento</label>
          <p-select
            [options]="bookOptions"
            [(ngModel)]="activeIndex"
            (ngModelChange)="onActiveChange()"
            optionLabel="label"
            optionValue="value"
            styleClass="w-full"
            [showClear]="false">
          </p-select>
        </div>

        <ng-container *ngIf="activeBook">
          <div class="flex flex-wrap items-center gap-3 mb-4 p-3 bg-gray-50 border rounded-lg">
            <p-select
              [options]="blockTypes"
              [(ngModel)]="selectedBlockType"
              optionLabel="label"
              optionValue="value"
              styleClass="w-[10rem]"
              [showClear]="false">
            </p-select>
            <p-button
              label="Insertar apertura"
              icon="pi pi-plus"
              (click)="insertOpening()"
              styleClass="p-button-sm"
              [disabled]="loadingText">
            </p-button>
            <p-button
              label="Cerrar bloque"
              icon="pi pi-times"
              (click)="closeBlock()"
              styleClass="p-button-sm p-button-outlined p-button-secondary"
              [disabled]="loadingText">
            </p-button>
            <p-tag *ngIf="activeBook.saved" value="Guardado" severity="success" />
          </div>

          <div *ngIf="loadingText" class="p-4 text-center text-gray-500">
            <i class="pi pi-spin pi-spinner"></i> Cargando texto...
          </div>

          <textarea
            *ngIf="!loadingText"
            #editor
            [(ngModel)]="activeText"
            class="w-full p-3 border border-gray-300 rounded-lg font-mono text-sm"
            style="min-height: 60vh; resize: vertical; overflow-y: auto;">
          </textarea>
        </ng-container>

        <div *ngIf="books.length === 0" class="p-4 text-center text-gray-500">
          No hay documentos subidos.
        </div>
      </ng-container>

      <div class="flex justify-content-between pt-4 border-t">
        <p-button
          label="Atrás"
          icon="pi pi-arrow-left"
          (click)="onBack()"
          styleClass="p-button-outlined p-button-secondary"
          iconPos="left"
          [disabled]="uploading">
        </p-button>
        <p-button
          label="Guardar y continuar"
          icon="pi pi-save"
          (click)="saveAndContinue()"
          styleClass="p-button-primary"
          iconPos="right"
          [disabled]="!canSave"
          [loading]="saving">
        </p-button>
      </div>
    </div>
  `,
  providers: [MessageService]
})
export class UploadStep3SplitComponent implements OnInit {
  @Input() selectedFiles: File[] = [];
  @Input() books: SplitBookState[] = [];
  @Output() back = new EventEmitter<void>();
  @Output() continue = new EventEmitter<void>();

  @ViewChild('editor') editor?: ElementRef<HTMLTextAreaElement>;

  uploading = false;
  loadingText = false;
  saving = false;
  uploadedCount = 0;
  totalToUpload = 0;
  activeIndex = 0;
  selectedBlockType: BlockTypeValue = 'CHAPTER';

  blockTypes = [
    { label: 'Prologue', value: 'PROLOGUE' },
    { label: 'Chapter', value: 'CHAPTER' },
    { label: 'Epilogue', value: 'EPILOGUE' }
  ];

  constructor(
    private uploadService: BookTranslatorUploadService,
    private messageService: MessageService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    if (this.books.length === 0) {
      this.uploadAll();
    } else {
      this.ensureTextLoaded();
    }
  }

  get activeBook(): SplitBookState | undefined {
    return this.books[this.activeIndex];
  }

  get activeText(): string {
    return this.activeBook?.text ?? '';
  }

  set activeText(value: string) {
    const book = this.activeBook;
    if (book) {
      book.text = value;
    }
  }

  get bookOptions() {
    return this.books.map((book, index) => ({
      label: book.saved ? `${book.name} (guardado)` : book.name,
      value: index
    }));
  }

  get canSave(): boolean {
    return !!this.activeBook && !this.uploading && !this.loadingText && !this.saving;
  }

  onActiveChange() {
    this.ensureTextLoaded();
  }

  onBack() {
    if (!this.uploading) {
      this.back.emit();
    }
  }

  insertOpening() {
    const book = this.activeBook;
    if (!book) return;
    const el = this.editor?.nativeElement;
    const pos = el ? el.selectionStart : book.text.length;
    if (this.depthAt(book.text, pos) > 0) {
      this.messageService.add({
        severity: 'warn',
        summary: 'Advertencia',
        detail: 'No se puede anidar bloques: hay un bloque abierto sin cerrar.'
      });
      return;
    }
    this.insertAtCursor(this.buildOpeningTag());
  }

  closeBlock() {
    const book = this.activeBook;
    if (!book) return;
    const stack = this.unclosedStack(book.text);
    const last = stack.length > 0 ? stack[stack.length - 1] : null;
    if (!last) {
      this.messageService.add({
        severity: 'warn',
        summary: 'Advertencia',
        detail: 'No hay ningún bloque abierto para cerrar.'
      });
      return;
    }
    this.insertAtCursor(`<<</${last}>>>`);
  }

  saveAndContinue() {
    const book = this.activeBook;
    if (!book || !this.canSave) return;

    const balanceError = this.validateBalance(book.text);
    if (balanceError) {
      this.messageService.add({ severity: 'error', summary: 'Error', detail: balanceError });
      return;
    }

    const blocks = this.parseBlocks(book.text);
    this.saving = true;
    this.uploadService.saveBlocks({ documentId: book.bookId, rawText: book.text, blocks }).subscribe({
      next: () => {
        book.saved = true;
        this.saving = false;
        this.cdr.markForCheck();
        const nextIndex = this.books.findIndex(b => !b.saved);
        if (nextIndex >= 0) {
          this.activeIndex = nextIndex;
          this.ensureTextLoaded();
          this.messageService.add({
            severity: 'info',
            summary: 'Guardado',
            detail: `Bloques de '${book.name}' guardados. Continúa con '${this.books[nextIndex].name}'.`
          });
        } else {
          this.continue.emit();
        }
      },
      error: () => {
        this.saving = false;
        this.messageService.add({ severity: 'error', summary: 'Error', detail: 'Error al guardar los bloques.' });
        this.cdr.markForCheck();
      }
    });
  }

  private async uploadAll() {
    if (this.selectedFiles.length === 0) {
      this.messageService.add({ severity: 'error', summary: 'Error', detail: 'No hay archivos para subir.' });
      return;
    }

    this.uploading = true;
    this.totalToUpload = this.selectedFiles.length;
    this.uploadedCount = 0;

    for (const file of this.selectedFiles) {
      try {
        const response = await firstValueFrom(this.uploadService.uploadFile(file));
        if (response?.bookId) {
          this.books.push({ name: file.name, bookId: response.bookId, text: '', loaded: false, saved: false });
          this.uploadedCount++;
          this.cdr.markForCheck();
        } else {
          this.messageService.add({
            severity: 'error',
            summary: 'Error',
            detail: `No se pudo subir '${file.name}': ${response?.error || 'respuesta inválida'}`
          });
        }
      } catch {
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: `No se pudo subir '${file.name}'.`
        });
      }
    }

    this.uploading = false;
    this.cdr.markForCheck();

    if (this.books.length === 0) return;

    this.activeIndex = 0;
    this.ensureTextLoaded();
  }

  private ensureTextLoaded() {
    const book = this.activeBook;
    if (!book || book.loaded) return;
    this.loadingText = true;
    this.uploadService.getBookFullText(book.bookId).subscribe({
      next: (text) => {
        book.text = text ?? '';
        book.loaded = true;
        this.loadingText = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loadingText = false;
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: 'No se pudo cargar el texto del documento.'
        });
        this.cdr.markForCheck();
      }
    });
  }

  private buildOpeningTag(): string {
    if (this.selectedBlockType === 'PROLOGUE') return '<<<PROLOGUE>>>';
    if (this.selectedBlockType === 'EPILOGUE') return '<<<EPILOGUE>>>';
    return `<<<CHAPTER id="${this.nextChapterId()}">>>`;
  }

  private insertAtCursor(tag: string) {
    const book = this.activeBook;
    if (!book) return;
    const el = this.editor?.nativeElement;
    const start = el ? el.selectionStart : book.text.length;
    const end = el ? el.selectionEnd : start;
    const text = book.text;
    book.text = text.slice(0, start) + tag + text.slice(end);
    if (el) {
      const cursor = start + tag.length;
      setTimeout(() => {
        el.selectionStart = el.selectionEnd = cursor;
        el.focus();
      });
    }
  }

  private nextChapterId(): number {
    const re = /<<<CHAPTER\s+id="(\d+)">>>/g;
    let max = 0;
    let match: RegExpExecArray | null;
    while ((match = re.exec(this.activeText)) !== null) {
      max = Math.max(max, parseInt(match[1], 10));
    }
    return max + 1;
  }

  private scanTags(text: string): { closing: boolean; type: string; pos: number }[] {
    const tags: { closing: boolean; type: string; pos: number }[] = [];
    const re = /<<<(\/?)(PROLOGUE|CHAPTER|EPILOGUE)(?:\s+id="(\d+)")?>>>/g;
    let match: RegExpExecArray | null;
    while ((match = re.exec(text)) !== null) {
      tags.push({ closing: match[1] === '/', type: match[2], pos: match.index });
    }
    return tags;
  }

  private depthAt(text: string, pos: number): number {
    let depth = 0;
    for (const tag of this.scanTags(text)) {
      if (tag.pos >= pos) continue;
      depth += tag.closing ? -1 : 1;
    }
    return depth;
  }

  private unclosedStack(text: string): string[] {
    const stack: string[] = [];
    for (const tag of this.scanTags(text)) {
      if (!tag.closing) {
        stack.push(tag.type);
      } else if (stack.length > 0) {
        stack.pop();
      }
    }
    return stack;
  }

  private validateBalance(text: string): string | null {
    const stack: string[] = [];
    for (const tag of this.scanTags(text)) {
      if (!tag.closing) {
        stack.push(tag.type);
        continue;
      }
      const open = stack.pop();
      if (!open) {
        return 'Hay etiquetas de cierre sin su correspondiente apertura.';
      }
      if (open !== tag.type) {
        return `La etiqueta de cierre ${tag.type} no coincide con el bloque abierto ${open}.`;
      }
    }
    if (stack.length > 0) {
      return `Hay bloques sin cerrar: ${stack.join(', ')}.`;
    }
    return null;
  }

  private parseBlocks(text: string): SemanticBlock[] {
    const blocks: SemanticBlock[] = [];
    const re = /<<<(PROLOGUE|CHAPTER|EPILOGUE)(?:\s+id="(\d+)")?>>>([\s\S]*?)<<<\/\1>>>/g;
    let match: RegExpExecArray | null;
    let index = 0;
    while ((match = re.exec(text)) !== null) {
      index++;
      blocks.push({
        type: match[1].toLowerCase() as SemanticBlock['type'],
        id: match[2] ? parseInt(match[2], 10) : index,
        content: match[3]
      });
    }
    return blocks;
  }
}
