import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SelectModule } from 'primeng/select';
import { InputTextModule } from 'primeng/inputtext';
import { CheckboxModule } from 'primeng/checkbox';
import { RadioButtonModule } from 'primeng/radiobutton';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ToastModule } from 'primeng/toast';
import { RegexPatternService, RegexPattern } from '../regex-pattern.service';

export interface ProcessingConfig {
  targetLanguage: string;
  ttsVoice: string;
  regexPatternIds: string[];
  splitIntoChunks: boolean;
  generateAudio: boolean;
}

@Component({
  selector: 'app-upload-step2-config',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectModule, InputTextModule, CheckboxModule, RadioButtonModule, ButtonModule, TagModule, TooltipModule, ToastModule],
  template: `
    <p-toast></p-toast>

    <div class="card">
      <div class="font-semibold text-xl mb-4">Paso 2: Configurar Procesamiento</div>
      <p class="text-gray-600 mb-6">
        Define cómo se procesarán los archivos subidos. La configuración se aplicará a todos los archivos.
      </p>

      <!-- Target Language -->
      <div class="mb-6">
        <label class="block text-sm font-medium mb-2">Idioma de Destino *</label>
        <p-select
          [options]="languages"
          [(ngModel)]="config.targetLanguage"
          optionLabel="label"
          optionValue="value"
          placeholder="Seleccionar idioma"
          styleClass="w-full"
          [showClear]="false">
        </p-select>
        <small class="text-gray-500">El texto se traducirá al idioma seleccionado</small>
      </div>

      <!-- TTS Voice -->
      <div class="mb-6">
        <label class="block text-sm font-medium mb-2">Voz TTS *</label>
        <p-select
          [options]="ttsVoices"
          [(ngModel)]="config.ttsVoice"
          optionLabel="label"
          optionValue="value"
          placeholder="Seleccionar voz"
          styleClass="w-full"
          [showClear]="false">
        </p-select>
        <small class="text-gray-500">Voz para generar el audiolibro (usa comando 'say' de macOS)</small>
      </div>

      <!-- Regex Patterns -->
      <div class="mb-6">
        <label class="block text-sm font-medium mb-2">Patrones de Limpieza a Aplicar</label>
        <div class="flex flex-wrap gap-2 mb-2">
          <p-button
            label="Todos"
            icon="pi pi-check-square"
            (click)="selectAllPatterns()"
            styleClass="p-button-sm p-button-outlined p-button-secondary"
            [disabled]="patterns.length === 0 || allPatternsSelected">
          </p-button>
          <p-button
            label="Ninguno"
            icon="pi pi-minus-square"
            (click)="deselectAllPatterns()"
            styleClass="p-button-sm p-button-outlined p-button-secondary"
            [disabled]="patterns.length === 0 || !anyPatternSelected">
          </p-button>
        </div>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 max-h-60 overflow-y-auto border rounded-lg p-3 bg-gray-50">
          <div *ngFor="let pattern of patterns" class="flex items-center gap-2">
            <p-checkbox
              [(ngModel)]="pattern.selected"
              [binary]="true"
              [disabled]="loadingPatterns">
            </p-checkbox>
            <div class="flex-1 min-w-0">
              <div class="font-medium text-sm truncate">{{ pattern.displayName }}</div>
              <div class="text-xs text-gray-500 truncate">{{ pattern.name }}</div>
            </div>
            <p-tag [value]="pattern.patternType" [severity]="getPatternSeverity(pattern.patternType)" />
          </div>
          <div *ngIf="patterns.length === 0 && !loadingPatterns" class="col-span-full text-center text-gray-500 py-4">
            No hay patrones disponibles. Configura patrones en <a routerLink="/book-translator/settings" class="text-blue-600 hover:underline">Ajustes</a>.
          </div>
          <div *ngIf="loadingPatterns" class="col-span-full text-center text-gray-500 py-4">
            <i class="pi pi-spin pi-spinner"></i> Cargando patrones...
          </div>
        </div>
      </div>

      <!-- Processing Options -->
      <div class="mb-6">
        <fieldset>
          <legend class="block text-sm font-medium mb-3">Opciones de Procesamiento</legend>
          <div class="space-y-3">
            <div class="flex items-center gap-2">
              <p-checkbox [(ngModel)]="config.splitIntoChunks" [binary]="true" />
              <label class="text-sm">
                <span class="font-medium">Dividir en fragmentos</span>
                <small class="text-gray-500 ml-1">(Requerido para traducción)</small>
              </label>
            </div>
            <div class="flex items-center gap-2">
              <p-checkbox [(ngModel)]="config.generateAudio" [binary]="true" />
              <label class="text-sm">
                <span class="font-medium">Generar audio</span>
                <small class="text-gray-500 ml-1">(Crea archivo de audio del texto traducido)</small>
              </label>
            </div>
          </div>
        </fieldset>
      </div>

      <!-- Action Buttons -->
      <div class="flex justify-content-between pt-4 border-t">
        <p-button
          label="Atrás"
          icon="pi pi-arrow-left"
          (click)="onBack()"
          styleClass="p-button-outlined p-button-secondary"
          iconPos="left">
        </p-button>
        <p-button
          label="Continuar"
          icon="pi pi-arrow-right"
          (click)="onContinue()"
          styleClass="p-button-primary"
          iconPos="right"
          [disabled]="!isValid()">
        </p-button>
      </div>
    </div>
  `
})
export class UploadStep2ConfigComponent implements OnInit, OnChanges {
  @Input() config!: ProcessingConfig;
  @Input() selectedFiles: File[] = [];
  @Output() configChange = new EventEmitter<ProcessingConfig>();
  @Output() back = new EventEmitter<void>();
  @Output() continue = new EventEmitter<void>();

  patterns: (RegexPattern & { selected: boolean })[] = [];
  loadingPatterns = true;

  languages = [
    { label: 'Español (es)', value: 'es' },
    { label: 'Inglés (en)', value: 'en' },
    { label: 'Francés (fr)', value: 'fr' },
    { label: 'Alemán (de)', value: 'de' },
    { label: 'Italiano (it)', value: 'it' },
    { label: 'Portugués (pt)', value: 'pt' },
    { label: 'Chino (zh)', value: 'zh' },
    { label: 'Japonés (ja)', value: 'ja' },
    { label: 'Coreano (ko)', value: 'ko' }
  ];

  ttsVoices = [
    { label: 'Paulina (Español - macOS)', value: 'Paulina' },
    { label: 'Monica (Español - macOS)', value: 'Monica' },
    { label: 'Jorge (Español - macOS)', value: 'Jorge' },
    { label: 'Samantha (Inglés - macOS)', value: 'Samantha' },
    { label: 'Alex (Inglés - macOS)', value: 'Alex' },
    { label: 'Victoria (Inglés - macOS)', value: 'Victoria' },
    { label: 'Amelie (Francés - macOS)', value: 'Amelie' },
    { label: 'Yannick (Francés - macOS)', value: 'Yannick' },
    { label: 'Anna (Alemán - macOS)', value: 'Anna' },
    { label: 'Markus (Alemán - macOS)', value: 'Markus' },
    { label: 'Luca (Italiano - macOS)', value: 'Luca' },
    { label: 'Joana (Portugués - macOS)', value: 'Joana' },
    { label: 'Tingting (Chino - macOS)', value: 'Tingting' },
    { label: 'Kyoko (Japonés - macOS)', value: 'Kyoko' },
    { label: 'Yuna (Coreano - macOS)', value: 'Yuna' }
  ];

  constructor(private regexPatternService: RegexPatternService) {}

  ngOnInit() {
    this.loadPatterns();
    this.syncConfig();
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['config'] && this.config) {
      this.syncConfig();
    }
  }

  private syncConfig() {
    // Initialize regexPatternIds from config
    this.config.regexPatternIds = this.config.regexPatternIds || [];
    this.config.splitIntoChunks = this.config.splitIntoChunks !== false;
    this.config.generateAudio = this.config.generateAudio !== false;

    // Sync pattern selections
    for (const pattern of this.patterns) {
      pattern.selected = this.config.regexPatternIds.includes(pattern.id!);
    }
  }

  loadPatterns() {
    this.loadingPatterns = true;
    this.regexPatternService.getEnabled().subscribe({
      next: (patterns) => {
        this.patterns = patterns.map(p => ({
          ...p,
          selected: this.config?.regexPatternIds?.includes(p.id!) || false
        }));
        this.loadingPatterns = false;
      },
      error: () => {
        this.loadingPatterns = false;
        this.patterns = [];
      }
    });
  }

  onPatternChange(pattern: RegexPattern & { selected: boolean }) {
    if (pattern.selected) {
      if (!this.config.regexPatternIds.includes(pattern.id!)) {
        this.config.regexPatternIds.push(pattern.id!);
      }
    } else {
      this.config.regexPatternIds = this.config.regexPatternIds.filter(id => id !== pattern.id);
    }
    this.emitConfig();
  }

  selectAllPatterns() {
    for (const pattern of this.patterns) {
      pattern.selected = true;
    }
    this.config.regexPatternIds = this.patterns.map(p => p.id!).filter(Boolean);
    this.emitConfig();
  }

  deselectAllPatterns() {
    for (const pattern of this.patterns) {
      pattern.selected = false;
    }
    this.config.regexPatternIds = [];
    this.emitConfig();
  }

  get allPatternsSelected(): boolean {
    return this.patterns.length > 0 && this.patterns.every(p => p.selected);
  }

  get anyPatternSelected(): boolean {
    return this.patterns.some(p => p.selected);
  }

  getPatternSeverity(type: string): 'success' | 'info' | 'warn' | 'danger' {
    const severityMap: Record<string, 'success' | 'info' | 'warn' | 'danger'> = {
      'URL': 'info',
      'SOCIAL_MEDIA': 'success',
      'ISBN': 'warn',
      'PAGE_NUMBER': 'info',
      'WHITESPACE': 'success',
      'CUSTOM': 'info'
    };
    return severityMap[type] || 'info';
  }

  onBack() {
    this.back.emit();
  }

  onContinue() {
    if (this.isValid()) {
      this.emitConfig();
      this.continue.emit();
    }
  }

  isValid(): boolean {
    return !!this.config.targetLanguage && !!this.config.ttsVoice;
  }

  private emitConfig() {
    this.configChange.emit({ ...this.config });
  }
}