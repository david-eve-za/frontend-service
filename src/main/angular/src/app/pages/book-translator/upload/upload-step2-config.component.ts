import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SelectModule } from 'primeng/select';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';

export interface ProcessingConfig {
    targetLanguage: string;
    ttsVoice: string;
}

/** Clave de persistencia local de la configuración de generación de audio. */
const AUDIOBOOK_CONFIG_KEY = 'audiobook-config';

/**
 * Página Generar Audiolibro del pipeline: configuración de la voz y las
 * demás opciones de generación de audio. La configuración se guarda en
 * localStorage hasta que el backend exponga un endpoint de configuración.
 *
 * Los patrones de limpieza no se eligen aquí: el backend aplica
 * automáticamente todos los patrones habilitados al extraer y almacenar
 * el texto del archivo (Extraer → Limpiar → Almacenar en BD).
 */
@Component({
    selector: 'app-upload-step2-config',
    standalone: true,
    imports: [
        CommonModule,
        RouterLink,
        FormsModule,
        SelectModule,
        ButtonModule,
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

            <!-- Automatic cleaning note -->
            <div
                class="mb-6 p-4 bg-blue-50 border border-blue-200 rounded-lg text-sm text-gray-600 flex items-start gap-3"
            >
                <i class="pi pi-info-circle text-blue-500 mt-0.5"></i>
                <span>
                    La limpieza del texto no se configura aquí: al subir un
                    archivo se aplican automáticamente todos los patrones de
                    limpieza habilitados en la
                    <a
                        routerLink="/book-translator/settings"
                        class="text-blue-600 hover:underline"
                        >Configuración</a
                    >
                    antes de almacenar el texto en la base de datos (Extraer →
                    Limpiar → Almacenar).
                </span>
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
    };

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
        private messageService: MessageService,
        private cdr: ChangeDetectorRef,
    ) {}

    ngOnInit() {
        this.loadSavedConfig();
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
