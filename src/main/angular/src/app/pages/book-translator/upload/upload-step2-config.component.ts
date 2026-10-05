import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SelectModule } from 'primeng/select';
import { CheckboxModule } from 'primeng/checkbox';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { RegexPatternService, RegexPattern } from '../regex-pattern.service';

export interface ProcessingConfig {
    targetLanguage: string;
    ttsVoice: string;
    regexPatternIds: string[];
    splitIntoChunks: boolean;
    generateAudio: boolean;
}

/** Clave de persistencia local de la configuración de generación de audio. */
const AUDIOBOOK_CONFIG_KEY = 'audiobook-config';

/**
 * Página Generar Audiolibro del pipeline: configuración de la voz y las
 * demás opciones de generación de audio. La configuración se guarda en
 * localStorage hasta que el backend exponga un endpoint de configuración.
 */
@Component({
    selector: 'app-upload-step2-config',
    standalone: true,
    imports: [
        CommonModule,
        RouterLink,
        FormsModule,
        SelectModule,
        CheckboxModule,
        ButtonModule,
        TagModule,
        TooltipModule,
        ToastModule,
    ],
    template: `
        <p-toast></p-toast>

        <div class="card">
            <div class="font-semibold text-xl mb-4">Generar Audiolibro</div>
            <p class="text-gray-600 mb-6">
                Configura la voz y las opciones con las que se generará el
                audiolibro.
            </p>

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
                    [showClear]="false"
                >
                </p-select>
                <small class="text-gray-500"
                    >Voz para generar el audiolibro</small
                >
            </div>

            <!-- Target Language -->
            <div class="mb-6">
                <label class="block text-sm font-medium mb-2"
                    >Idioma de Destino *</label
                >
                <p-select
                    [options]="languages"
                    [(ngModel)]="config.targetLanguage"
                    optionLabel="label"
                    optionValue="value"
                    placeholder="Seleccionar idioma"
                    styleClass="w-full"
                    [showClear]="false"
                >
                </p-select>
                <small class="text-gray-500"
                    >Idioma en el que se traducirá el texto antes de generar el
                    audio</small
                >
            </div>

            <!-- Regex Patterns -->
            <div class="mb-6">
                <label class="block text-sm font-medium mb-2"
                    >Patrones de Limpieza a Aplicar</label
                >
                <div class="flex flex-wrap gap-2 mb-2">
                    <p-button
                        label="Todos"
                        icon="pi pi-check-square"
                        (click)="selectAllPatterns()"
                        styleClass="p-button-sm p-button-outlined p-button-secondary"
                        [disabled]="
                            patterns.length === 0 || allPatternsSelected
                        "
                    >
                    </p-button>
                    <p-button
                        label="Ninguno"
                        icon="pi pi-minus-square"
                        (click)="deselectAllPatterns()"
                        styleClass="p-button-sm p-button-outlined p-button-secondary"
                        [disabled]="
                            patterns.length === 0 || !anyPatternSelected
                        "
                    >
                    </p-button>
                </div>
                <div
                    class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 max-h-60 overflow-y-auto border rounded-lg p-3 bg-gray-50"
                >
                    <div
                        *ngFor="let pattern of patterns"
                        class="flex items-center gap-2"
                    >
                        <p-checkbox
                            [(ngModel)]="pattern.selected"
                            [binary]="true"
                            [disabled]="loadingPatterns"
                            (ngModelChange)="onPatternChange(pattern)"
                        >
                        </p-checkbox>
                        <div class="flex-1 min-w-0">
                            <div class="font-medium text-sm truncate">
                                {{ pattern.displayName }}
                            </div>
                            <div class="text-xs text-gray-500 truncate">
                                {{ pattern.name }}
                            </div>
                        </div>
                        <p-tag
                            [value]="pattern.patternType"
                            [severity]="getPatternSeverity(pattern.patternType)"
                        />
                    </div>
                    <div
                        *ngIf="patterns.length === 0 && !loadingPatterns"
                        class="col-span-full text-center text-gray-500 py-4"
                    >
                        No hay patrones disponibles. Configura patrones en
                        <a
                            routerLink="/book-translator/settings"
                            class="text-blue-600 hover:underline"
                            >Configuración</a
                        >.
                    </div>
                    <div
                        *ngIf="loadingPatterns"
                        class="col-span-full text-center text-gray-500 py-4"
                    >
                        <i class="pi pi-spin pi-spinner"></i> Cargando
                        patrones...
                    </div>
                </div>
            </div>

            <!-- Action Buttons -->
            <div class="flex justify-content-end pt-4 border-t">
                <p-button
                    label="Guardar Configuración"
                    icon="pi pi-save"
                    (click)="saveConfig()"
                    styleClass="p-button-primary"
                    iconPos="right"
                    [disabled]="!isValid()"
                >
                </p-button>
            </div>
        </div>
    `,
    providers: [MessageService],
})
export class UploadStep2ConfigComponent implements OnInit {
    config: ProcessingConfig = {
        targetLanguage: 'es',
        ttsVoice: 'Paulina',
        regexPatternIds: [],
        splitIntoChunks: true,
        generateAudio: true,
    };

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
        { label: 'Coreano (ko)', value: 'ko' },
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
        { label: 'Yuna (Coreano - macOS)', value: 'Yuna' },
    ];

    constructor(
        private regexPatternService: RegexPatternService,
        private messageService: MessageService,
        private cdr: ChangeDetectorRef,
    ) {}

    ngOnInit() {
        this.loadSavedConfig();
        this.loadPatterns();
    }

    /** Recupera la configuración guardada en localStorage, si existe. */
    private loadSavedConfig() {
        try {
            const raw = localStorage.getItem(AUDIOBOOK_CONFIG_KEY);
            if (raw) {
                const saved = JSON.parse(raw) as Partial<ProcessingConfig>;
                this.config = { ...this.config, ...saved };
            }
        } catch {
            // Configuración corrupta: se usan los valores por defecto.
        }
    }

    loadPatterns() {
        this.loadingPatterns = true;
        this.regexPatternService.getEnabled().subscribe({
            next: (patterns) => {
                this.patterns = patterns.map((p) => ({
                    ...p,
                    selected:
                        this.config.regexPatternIds.includes(p.id!) || false,
                }));
                this.loadingPatterns = false;
                this.cdr.markForCheck();
            },
            error: () => {
                this.loadingPatterns = false;
                this.patterns = [];
                this.cdr.markForCheck();
            },
        });
    }

    onPatternChange(pattern: RegexPattern & { selected: boolean }) {
        if (pattern.selected) {
            if (!this.config.regexPatternIds.includes(pattern.id!)) {
                this.config.regexPatternIds.push(pattern.id!);
            }
        } else {
            this.config.regexPatternIds = this.config.regexPatternIds.filter(
                (id) => id !== pattern.id,
            );
        }
    }

    selectAllPatterns() {
        for (const pattern of this.patterns) {
            pattern.selected = true;
        }
        this.config.regexPatternIds = this.patterns
            .map((p) => p.id!)
            .filter(Boolean);
    }

    deselectAllPatterns() {
        for (const pattern of this.patterns) {
            pattern.selected = false;
        }
        this.config.regexPatternIds = [];
    }

    get allPatternsSelected(): boolean {
        return (
            this.patterns.length > 0 && this.patterns.every((p) => p.selected)
        );
    }

    get anyPatternSelected(): boolean {
        return this.patterns.some((p) => p.selected);
    }

    getPatternSeverity(type: string): 'success' | 'info' | 'warn' | 'danger' {
        const severityMap: Record<
            string,
            'success' | 'info' | 'warn' | 'danger'
        > = {
            URL: 'info',
            SOCIAL_MEDIA: 'success',
            ISBN: 'warn',
            PAGE_NUMBER: 'info',
            WHITESPACE: 'success',
            CUSTOM: 'info',
        };
        return severityMap[type] || 'info';
    }

    isValid(): boolean {
        return !!this.config.targetLanguage && !!this.config.ttsVoice;
    }

    saveConfig() {
        if (!this.isValid()) return;
        try {
            localStorage.setItem(
                AUDIOBOOK_CONFIG_KEY,
                JSON.stringify(this.config),
            );
            this.messageService.add({
                severity: 'success',
                summary: 'Configuración guardada',
                detail: 'Las opciones de generación de audio se guardaron correctamente.',
            });
        } catch {
            this.messageService.add({
                severity: 'error',
                summary: 'Error',
                detail: 'No se pudo guardar la configuración.',
            });
        }
    }
}
