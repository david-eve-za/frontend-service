import {
    Component,
    ChangeDetectorRef,
    inject,
    signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { AccordionModule } from 'primeng/accordion';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { TableModule } from 'primeng/table';
import { DialogModule } from 'primeng/dialog';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ToastModule } from 'primeng/toast';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService, ConfirmationService } from 'primeng/api';
import {
    StoryWizardClientService,
    VideoStoryProject,
    VideoStoryProjectSummary,
    VideoStoryTraceEvent,
} from './story-wizard.service';

/** Opción de los p-select: etiqueta ES, valor EN listo para las plantillas. */
interface SelectOption {
    label: string;
    value: string;
}

/** Perfil por género: tono sugerido, conflicto por defecto y subgéneros. */
interface GenreProfile {
    tone: string;
    conflict: string;
    subgenres: string[];
}

/** Tarjeta de conflicto emocional del paso 1 (radiogroup de cards). */
interface ConflictCard {
    label: string;
    value: string;
    icon: string;
}

/** Premisa del arranque rápido: aplica género, tono, conflicto y final. */
interface PremisePreset {
    label: string;
    hint: string;
    icon: string;
    genre: string;
    tone: string;
    ending: string;
    conflict: string;
}

/**
 * ASISTENTE de Historias en Video: la timeline completa encadenada por la
 * IA — paso 1 la plantilla narrativa produce la historia; paso 2 la historia
 * produce los diseños de personajes; paso 3 historia+diseños producen la
 * biblioteca de ASSETS (entornos, props, tecnología, documentos, vehículos,
 * motifs con IDs estables); paso 4 historia+diseños producen las escenas;
 * paso 5 (opcional) la escena AUTO-SELECCIONADA (default: la primera) se
 * transporta a la PLANTILLA MASTER de 15 secciones de generación de video,
 * con una referencia opcional (archivo | URL | término de búsqueda web).
 * Cada paso es UNA llamada a la rotación NVIDIA con fallback a Ollama y
 * puede tardar varios minutos; la salida de cada paso alimenta al siguiente
 * y todo queda persistido en el proyecto.
 *
 * <p><b>Modo automático</b> (por defecto): tras generar la historia, los
 * pasos 2→5 se encadenan solos con los defaults — de un formulario a la
 * plantilla MASTER completa con interacción humana nula; el botón
 * «Completar automáticamente» reanuda los pasos pendientes de cualquier
 * proyecto.</p>
 */
@Component({
    selector: 'app-story-wizard',
    standalone: true,
    imports: [
        CommonModule,
        FormsModule,
        SelectModule,
        SelectButtonModule,
        ToggleSwitchModule,
        AccordionModule,
        ButtonModule,
        InputTextModule,
        TextareaModule,
        TableModule,
        DialogModule,
        ConfirmDialogModule,
        ToastModule,
        TagModule,
        TooltipModule,
        CheckboxModule,
    ],
    providers: [MessageService, ConfirmationService],
    styles: `
        /* Los grupos de chips envuelven en móvil (PrimeNG los deja en una
           fila con nowrap y desbordan el viewport). */
        .p-selectbutton {
            flex-wrap: wrap;
        }
    `,
    template: `
        <p-toast [life]="12000"></p-toast>
        <p-confirmDialog icon="pi pi-exclamation-triangle"></p-confirmDialog>

        <div class="card mb-4">
            <div class="flex justify-between items-center flex-wrap gap-2 mb-2">
                <div class="font-semibold text-xl">Asistente de Historias en Video</div>
                @if (project(); as p) {
                    <div class="flex gap-2">
                        <p-button
                            label="Descargar Storyboard"
                            icon="pi pi-download"
                            severity="secondary"
                            [outlined]="true"
                            [disabled]="!hasAnyOutput(p)"
                            (onClick)="downloadStoryboard(p.id, p.title)"
                        />
                        <p-button
                            label="Nueva Historia"
                            icon="pi pi-plus"
                            severity="secondary"
                            [outlined]="true"
                            (onClick)="resetWizard()"
                        />
                    </div>
                }
            </div>
            <p class="text-muted-color text-sm mb-4">
                La timeline completa encadenada: la IA genera cada paso usando la
                salida del anterior — historia, diseños de personajes,
                biblioteca de assets (utensilios, objetos, mobiliario…),
                escenas y la plantilla MASTER de video (15 secciones,
                opcional). El <b>modo automático</b> encadena los pasos 2→5
                tras la historia; el paso 5 usa la primera escena por defecto
                y acepta una referencia opcional (archivo, URL o búsqueda
                web). Cada paso puede tardar <b>varios minutos</b> (rotación
                NVIDIA con fallback al modelo local). Si un paso falla,
                puedes reintentarlo sin perder el trabajo anterior.
            </p>

            <!-- Indicador de la timeline -->
            <div class="flex flex-wrap gap-2 mb-4">
                <p-tag
                    [value]="'1. Historia' + (stepDone('story') ? ' ✓' : '')"
                    [severity]="stepDone('story') ? 'success' : 'secondary'"
                />
                <p-tag
                    [value]="'2. Diseños' + (stepDone('designs') ? ' ✓' : '')"
                    [severity]="stepDone('designs') ? 'success' : 'secondary'"
                />
                <p-tag
                    [value]="'3. Assets' + (stepDone('assets') ? ' ✓' : '')"
                    [severity]="stepDone('assets') ? 'success' : 'secondary'"
                />
                <p-tag
                    [value]="'4. Escenas' + (stepDone('scenes') ? ' ✓' : '')"
                    [severity]="stepDone('scenes') ? 'success' : 'secondary'"
                />
                <p-tag
                    [value]="'5. Video (opcional)' + (stepDone('video') ? ' ✓' : '')"
                    [severity]="stepDone('video') ? 'success' : 'secondary'"
                />
            </div>

            <!-- ═══════ Cadena automática: botón de arranque + progreso ═══════ -->
            @if (project(); as p) {
                @if (autoRunning) {
                    <div class="flex items-center gap-2 mb-4">
                        <p-tag [value]="autoStep || 'Ejecutando…'" severity="info"></p-tag>
                        <i class="pi pi-spin pi-spinner text-muted-color"></i>
                    </div>
                } @else if (pendingAutoSteps(p) > 0) {
                    <div class="mb-4">
                        <p-button
                            [label]="'Completar automáticamente (' + pendingAutoSteps(p) + ' paso(s) pendiente(s))'"
                            icon="pi pi-bolt"
                            severity="success"
                            [outlined]="true"
                            size="small"
                            (onClick)="runAllPendingSteps()"
                        />
                    </div>
                }
            }

            <!-- ═════════ PASO 1: HISTORIA ═════════ -->
            @if (!project()) {
                <div class="font-semibold text-lg mb-3">Paso 1 — Historia</div>
                <p class="text-muted-color text-sm mb-3">
                    Elige y genera: la IA escribirá la historia completa con
                    hook, estructura, personajes y cliffhanger. Todos los campos
                    traen un valor listo para usar; solo el género es
                    imprescindible.
                </p>

                <!-- Arranque rápido: premisas de un clic + sorpresa -->
                <div class="flex items-center justify-between flex-wrap gap-2 mb-2">
                    <span id="premiseLabel" class="text-sm font-medium">¿Cómo empiezas?</span>
                    <p-button
                        label="Sorpréndeme"
                        icon="pi pi-sparkles"
                        size="small"
                        [outlined]="true"
                        severity="secondary"
                        (onClick)="surpriseMe()"
                    />
                </div>
                <div
                    class="grid grid-cols-2 md:grid-cols-3 gap-2 mb-4"
                    role="group"
                    aria-labelledby="premiseLabel"
                >
                    @for (preset of premisePresets; track preset.label) {
                        <button
                            type="button"
                            class="p-3 text-left border rounded-lg bg-transparent cursor-pointer transition-colors duration-150 border-surface-200 dark:border-surface-600 hover:bg-surface-50 dark:hover:bg-surface-800"
                            (click)="applyPreset(preset)"
                        >
                            <i class="{{ preset.icon }} text-primary"></i>
                            <span class="block text-sm font-medium mt-1">{{ preset.label }}</span>
                            <span class="block text-xs text-muted-color">{{ preset.hint }}</span>
                        </button>
                    }
                </div>

                @if (storyErrors.length > 0) {
                    <div
                        class="mb-4 p-3 border rounded-lg text-sm border-orange-300 bg-orange-50"
                        role="alert"
                    >
                        <div class="font-medium mb-1">
                            <i class="pi pi-exclamation-circle text-orange-500"></i>
                            Antes de generar, revisa:
                        </div>
                        <ul class="ml-4 list-disc">
                            @for (error of storyErrors; track error) {
                                <li>{{ error }}</li>
                            }
                        </ul>
                    </div>
                }

                <!-- Género: la única decisión sin valor por defecto -->
                <div class="mb-4">
                    <label id="genreLabel" class="block text-sm font-medium mb-2">
                        Género <span class="text-red-500">*</span>
                        <span class="sr-only">(obligatorio)</span>
                    </label>
                    <p-selectbutton
                        [options]="genreOptions"
                        [ngModel]="genre"
                        (ngModelChange)="onGenreChange($event)"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                        ariaLabelledBy="genreLabel"
                    />
                    @if (genre === null && storyErrors.length > 0) {
                        <small class="block text-red-500 mt-1">
                            Elige un género para empezar.
                        </small>
                    }
                </div>

                <!-- Tono: sugerido según el género, editable -->
                <div class="mb-4">
                    <label id="toneLabel" class="block text-sm font-medium mb-2">Tono</label>
                    <p-selectbutton
                        [options]="toneOptions"
                        [ngModel]="tone"
                        (ngModelChange)="tone = $event"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                        ariaLabelledBy="toneLabel"
                    />
                </div>

                <!-- Plataforma: deriva aspecto, duración y ritmo de escenas -->
                <div class="mb-4">
                    <label id="platformLabel" class="block text-sm font-medium mb-2">
                        ¿Dónde se publicará?
                    </label>
                    <p-selectbutton
                        [options]="platformOptions"
                        [ngModel]="platform"
                        (ngModelChange)="onPlatformChange($event)"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                        ariaLabelledBy="platformLabel"
                    >
                        <ng-template pTemplate="item" let-item>
                            <i class="{{ item.icon }} text-primary"></i>
                            <span class="text-sm">{{ item.label }}</span>
                        </ng-template>
                    </p-selectbutton>
                    <small class="block text-muted-color mt-1">
                        Al cambiar de plataforma ajustamos el formato (vertical u
                        horizontal), la duración sugerida y el ritmo de escenas.
                    </small>
                </div>

                <!-- Duración del video -->
                <div class="mb-4">
                    <label id="durationLabel" class="block text-sm font-medium mb-2">
                        Duración del video
                    </label>
                    <p-selectbutton
                        [options]="videoDurationOptions"
                        [ngModel]="videoDurationSeconds"
                        (ngModelChange)="onDurationChange($event)"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                        ariaLabelledBy="durationLabel"
                    />
                </div>

                <!-- Audiencia -->
                <div class="mb-4">
                    <label id="audienceLabel" class="block text-sm font-medium mb-2">Audiencia</label>
                    <p-selectbutton
                        [options]="audienceOptions"
                        [ngModel]="targetAudience"
                        (ngModelChange)="onAudienceChange($event)"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                        ariaLabelledBy="audienceLabel"
                    />
                </div>

                <!-- Apto para familias (fijado a ON con audiencia infantil) -->
                <div class="mb-4 flex items-center gap-2 flex-wrap">
                    <p-toggleswitch
                        [ngModel]="familyFriendly"
                        (ngModelChange)="familyFriendly = $event"
                        inputId="familyFriendly"
                        [disabled]="targetAudience === 'Children'"
                    />
                    <label for="familyFriendly" class="text-sm cursor-pointer">Apto para familias</label>
                    @if (targetAudience === 'Children') {
                        <small class="text-muted-color">
                            Con audiencia «Niños» siempre está activado.
                        </small>
                    }
                </div>

                <!-- Conflicto emocional: tarjetas + «Otro» opcional -->
                <div class="mb-4">
                    <label id="conflictLabel" class="block text-sm font-medium mb-2">
                        Conflicto emocional
                        <span class="text-muted-color text-xs font-normal">
                            (el corazón del clímax)
                        </span>
                    </label>
                    <div
                        class="grid grid-cols-2 md:grid-cols-4 gap-2"
                        role="radiogroup"
                        aria-labelledby="conflictLabel"
                    >
                        @for (option of conflictOptions; track option.value) {
                            <button
                                type="button"
                                role="radio"
                                [attr.aria-checked]="conflict === option.value"
                                class="p-3 text-left border rounded-lg bg-transparent cursor-pointer transition-colors duration-150 border-surface-200 dark:border-surface-600"
                                [class.border-primary]="conflict === option.value"
                                [class.bg-surface-50]="conflict === option.value"
                                (click)="onConflictChange(option.value)"
                            >
                                <i class="{{ option.icon }} text-primary"></i>
                                <span class="block text-sm mt-1">{{ option.label }}</span>
                            </button>
                        }
                        <button
                            type="button"
                            role="radio"
                            [attr.aria-checked]="conflict === '__other__'"
                            class="p-3 text-left border rounded-lg bg-transparent cursor-pointer transition-colors duration-150 border-surface-200 dark:border-surface-600"
                            [class.border-primary]="conflict === '__other__'"
                            [class.bg-surface-50]="conflict === '__other__'"
                            (click)="onConflictChange('__other__')"
                        >
                            <i class="pi pi-pencil text-primary"></i>
                            <span class="block text-sm mt-1">Otro…</span>
                        </button>
                    </div>
                    @if (conflict === '__other__') {
                        <input
                            pInputText
                            type="text"
                            id="conflictOther"
                            class="w-full mt-2"
                            placeholder="P. ej. cuidar de su madre o aceptar el trabajo de su vida…"
                            [(ngModel)]="conflictOther"
                        />
                    }
                </div>

                <!-- Tipo de final -->
                <div class="mb-4">
                    <label id="endingLabel" class="block text-sm font-medium mb-2">Tipo de final</label>
                    <p-selectbutton
                        [options]="endingTypeOptions"
                        [ngModel]="endingType"
                        (ngModelChange)="onEndingChange($event)"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                        ariaLabelledBy="endingLabel"
                    />
                </div>

                <!-- Idioma del diálogo: recuerda la última elección -->
                <div class="mb-4">
                    <label id="languageLabel" class="block text-sm font-medium mb-2">
                        Idioma del diálogo
                    </label>
                    <p-selectbutton
                        [options]="languageOptions"
                        [ngModel]="dialogueLanguage"
                        (ngModelChange)="onLanguageChange($event)"
                        optionLabel="label"
                        optionValue="value"
                        [allowEmpty]="false"
                        ariaLabelledBy="languageLabel"
                    />
                </div>

                <!-- Ajustes avanzados: todo opcional y colapsado -->
                <p-accordion value="" class="mb-4">
                    <p-accordion-panel value="advanced">
                        <p-accordion-header>
                            <span class="text-sm font-medium">Ajustes avanzados</span>
                            <span class="text-muted-color text-xs"> (todo opcional)</span>
                        </p-accordion-header>
                        <p-accordion-content>
                            <div class="grid grid-cols-12 gap-4 pt-2">
                                <div class="col-span-12 md:col-span-6">
                                    <label for="title" class="block text-sm font-medium mb-2">Título</label>
                                    <input
                                        pInputText
                                        type="text"
                                        id="title"
                                        class="w-full"
                                        placeholder="Vacío: se genera uno automáticamente…"
                                        [(ngModel)]="title"
                                    />
                                </div>
                                <div class="col-span-12 md:col-span-6">
                                    <label id="subgenreLabel" class="block text-sm font-medium mb-2">
                                        Subgénero
                                        <span class="text-muted-color text-xs font-normal">(según el género)</span>
                                    </label>
                                    <p-selectbutton
                                        [options]="subgenreOptions"
                                        [ngModel]="subgenre"
                                        (ngModelChange)="onSubgenreChange($event)"
                                        optionLabel="label"
                                        optionValue="value"
                                        [allowEmpty]="true"
                                        ariaLabelledBy="subgenreLabel"
                                    />
                                    @if (subgenre === '__other__') {
                                        <input
                                            pInputText
                                            type="text"
                                            id="subgenreOther"
                                            class="w-full mt-2"
                                            placeholder="P. ej. slow burn de cocina…"
                                            [(ngModel)]="subgenreOther"
                                        />
                                    }
                                </div>
                                <div class="col-span-12 md:col-span-6">
                                    <label id="settingLabel" class="block text-sm font-medium mb-2">
                                        Ambientación
                                        <span class="text-muted-color text-xs font-normal">(vacío: la IA decide)</span>
                                    </label>
                                    <p-selectbutton
                                        [options]="settingOptions"
                                        [ngModel]="setting"
                                        (ngModelChange)="onSettingChange($event)"
                                        optionLabel="label"
                                        optionValue="value"
                                        [allowEmpty]="true"
                                        ariaLabelledBy="settingLabel"
                                    />
                                    @if (setting === '__other__') {
                                        <input
                                            pInputText
                                            type="text"
                                            id="settingOther"
                                            class="w-full mt-2"
                                            placeholder="P. ej. Kioto, en una panadería tradicional…"
                                            [(ngModel)]="settingOther"
                                        />
                                    }
                                </div>
                                <div class="col-span-12 md:col-span-6">
                                    <label id="unresolvedLabel" class="block text-sm font-medium mb-2">
                                        Lo que NO se resuelve
                                        <span class="text-muted-color text-xs font-normal">(ligado al final)</span>
                                    </label>
                                    <p-selectbutton
                                        [options]="unresolvedSelectOptions"
                                        [ngModel]="unresolved"
                                        (ngModelChange)="onUnresolvedChange($event)"
                                        optionLabel="label"
                                        optionValue="value"
                                        [allowEmpty]="false"
                                        ariaLabelledBy="unresolvedLabel"
                                    />
                                    @if (unresolved === '__other__') {
                                        <input
                                            pInputText
                                            type="text"
                                            id="unresolvedOther"
                                            class="w-full mt-2"
                                            placeholder="P. ej. qué contiene la carta que no se atreve a abrir…"
                                            [(ngModel)]="unresolvedOther"
                                        />
                                    }
                                </div>
                                <div class="col-span-12 md:col-span-4">
                                    <label id="characterCountLabel" class="block text-sm font-medium mb-2">
                                        Nº de personajes
                                    </label>
                                    <p-selectbutton
                                        [options]="characterCountOptions"
                                        [ngModel]="characterCount"
                                        (ngModelChange)="onCharacterCountChange($event)"
                                        optionLabel="label"
                                        optionValue="value"
                                        [allowEmpty]="false"
                                        ariaLabelledBy="characterCountLabel"
                                    />
                                </div>
                                <div class="col-span-12 md:col-span-4">
                                    <label for="character1Requirements" class="block text-sm font-medium mb-2">
                                        Personaje 1
                                    </label>
                                    <textarea
                                        pInputTextarea
                                        rows="2"
                                        id="character1Requirements"
                                        class="w-full"
                                        placeholder="Edad, personalidad, lo que oculta… (opcional)"
                                        [(ngModel)]="character1Requirements"
                                    ></textarea>
                                </div>
                                <div class="col-span-12 md:col-span-4">
                                    <label for="character2Requirements" class="block text-sm font-medium mb-2">
                                        Personaje 2
                                    </label>
                                    <textarea
                                        pInputTextarea
                                        rows="2"
                                        id="character2Requirements"
                                        class="w-full"
                                        placeholder="Edad, personalidad, lo que oculta… (opcional)"
                                        [(ngModel)]="character2Requirements"
                                    ></textarea>
                                </div>
                                <div class="col-span-12 md:col-span-4">
                                    <label for="otherCharacterRequirements" class="block text-sm font-medium mb-2">
                                        Otros personajes
                                    </label>
                                    <textarea
                                        pInputTextarea
                                        rows="2"
                                        id="otherCharacterRequirements"
                                        class="w-full"
                                        placeholder="Requisitos del resto del reparto (opcional)"
                                        [(ngModel)]="otherCharacterRequirements"
                                    ></textarea>
                                </div>
                                <div class="col-span-12 md:col-span-8">
                                    <label id="visualStyleLabel" class="block text-sm font-medium mb-2">
                                        Formato de animación
                                        <span class="text-muted-color text-xs font-normal">(vacío: 3D cinematográfico)</span>
                                    </label>
                                    <p-selectbutton
                                        [options]="visualStyleOptions"
                                        [ngModel]="visualStyle"
                                        (ngModelChange)="visualStyle = $event"
                                        optionLabel="label"
                                        optionValue="value"
                                        [allowEmpty]="true"
                                        ariaLabelledBy="visualStyleLabel"
                                    />
                                </div>
                                <div class="col-span-12 md:col-span-4">
                                    <label id="realismLabel" class="block text-sm font-medium mb-2">
                                        Realismo
                                        <span class="text-muted-color text-xs font-normal">(vacío: estilizado)</span>
                                    </label>
                                    <p-selectbutton
                                        [options]="realismOptions"
                                        [ngModel]="realismLevel"
                                        (ngModelChange)="realismLevel = $event"
                                        optionLabel="label"
                                        optionValue="value"
                                        [allowEmpty]="true"
                                        ariaLabelledBy="realismLabel"
                                    />
                                </div>
                                <div class="col-span-12 md:col-span-6">
                                    <label id="animationStyleLabel" class="block text-sm font-medium mb-2">
                                        Estilo de animación
                                        <span class="text-muted-color text-xs font-normal">
                                            (se hereda a los pasos 2, 3 y 5)
                                        </span>
                                    </label>
                                    <p-select
                                        [options]="animationStyleOptions"
                                        [ngModel]="animationStyle"
                                        (ngModelChange)="onAnimationStyleChange($event)"
                                        optionLabel="label"
                                        optionValue="value"
                                        ariaLabelledBy="animationStyleLabel"
                                        placeholder="Vacío: Pixar / DreamWorks"
                                        styleClass="w-full"
                                        [showClear]="true"
                                    />
                                </div>
                                <div class="col-span-12 md:col-span-6">
                                    <label id="pacingLabel" class="block text-sm font-medium mb-2">
                                        Ritmo narrativo
                                    </label>
                                    <p-selectbutton
                                        [options]="pacingOptions"
                                        [ngModel]="pacingPreset"
                                        (ngModelChange)="onPacingPresetChange($event)"
                                        optionLabel="label"
                                        optionValue="value"
                                        [allowEmpty]="false"
                                        ariaLabelledBy="pacingLabel"
                                    />
                                    @if (pacingPreset === 'custom') {
                                        <div class="grid grid-cols-3 gap-2 mt-2">
                                            <div>
                                                <label for="pacingFirstPercent" class="block text-xs text-muted-color mb-1">
                                                    Inicio %
                                                </label>
                                                <input
                                                    pInputText
                                                    type="number"
                                                    id="pacingFirstPercent"
                                                    min="0"
                                                    max="100"
                                                    class="w-full"
                                                    [(ngModel)]="pacingFirstPercent"
                                                />
                                            </div>
                                            <div>
                                                <label for="pacingMiddlePercent" class="block text-xs text-muted-color mb-1">
                                                    Medio %
                                                </label>
                                                <input
                                                    pInputText
                                                    type="number"
                                                    id="pacingMiddlePercent"
                                                    min="0"
                                                    max="100"
                                                    class="w-full"
                                                    [(ngModel)]="pacingMiddlePercent"
                                                />
                                            </div>
                                            <div>
                                                <label for="pacingFinalPercent" class="block text-xs text-muted-color mb-1">
                                                    Final %
                                                </label>
                                                <input
                                                    pInputText
                                                    type="number"
                                                    id="pacingFinalPercent"
                                                    min="0"
                                                    max="100"
                                                    class="w-full"
                                                    [(ngModel)]="pacingFinalPercent"
                                                />
                                            </div>
                                        </div>
                                        @if (pacingFirstPercent + pacingMiddlePercent + pacingFinalPercent !== 100) {
                                            <small class="block text-red-500 mt-1">
                                                La suma debe ser 100 % (ahora
                                                {{ pacingFirstPercent + pacingMiddlePercent + pacingFinalPercent }} %).
                                            </small>
                                        }
                                    }
                                </div>
                            </div>
                        </p-accordion-content>
                    </p-accordion-panel>
                </p-accordion>

                <div class="mt-4">
                    <p-button
                        label="Generar Historia con IA"
                        icon="pi pi-magic"
                        [disabled]="generatingStory"
                        [loading]="generatingStory"
                        (onClick)="generateStory()"
                    />
                </div>
                <div class="flex items-center gap-2 mt-3">
                    <p-checkbox [(ngModel)]="autoMode" [binary]="true" inputId="autoMode"></p-checkbox>
                    <label for="autoMode" class="text-sm text-muted-color cursor-pointer">
                        Modo automático: al generar la historia, encadenar los pasos 2→5
                        (diseños, assets, escenas y plantilla MASTER de video) sin intervención
                    </label>
                </div>
            }

            @if (project(); as p) {
                <!-- ═════════ HISTORIA GENERADA ═════════ -->
                @if (p.story) {
                    <div class="flex items-center justify-between mt-2 mb-2">
                        <div class="font-semibold text-lg">Paso 1 — Historia</div>
                        <p-button
                            icon="pi pi-copy"
                            severity="secondary"
                            [text]="true"
                            ariaLabel="Copiar la historia"
                            (onClick)="copyText(p.story!, 'La historia')"
                        />
                    </div>
                    <textarea pInputTextarea rows="7" class="w-full" readonly id="outputStory" aria-label="Historia generada" [value]="p.story"></textarea>
                }

                <!-- ═════════ PASO 2: DISEÑOS ═════════ -->
                @if (p.story) {
                    <div class="font-semibold text-lg mt-6 mb-3">Paso 2 — Diseño de Personajes</div>
                    <p class="text-muted-color text-sm mb-3">
                        La IA diseñará a cada personaje basándose en la historia
                        generada, con consistencia visual entre escenas.
                    </p>
                    <div class="grid grid-cols-12 gap-4">
                        <div class="col-span-12 md:col-span-8">
                            <label id="designStyleLabel" class="block text-sm font-medium mb-2">
                                Estilo de animación 3D
                                <span class="text-muted-color text-xs font-normal">(heredado; editable)</span>
                            </label>
                            <p-select
                                [options]="animationStyleOptions"
                                [(ngModel)]="designStyle"
                                optionLabel="label"
                                optionValue="value"
                                ariaLabelledBy="designStyleLabel"
                                placeholder="Elige el estilo visual"
                                styleClass="w-full"
                                [showClear]="false"
                            />
                        </div>
                        <div class="col-span-12 md:col-span-4">
                            <label id="designCountLabel" class="block text-sm font-medium mb-2">
                                Personajes a diseñar
                                <span class="text-muted-color text-xs font-normal">(heredado del paso 1)</span>
                            </label>
                            <p-selectbutton
                                [options]="designCountOptions"
                                [(ngModel)]="designCharacterCount"
                                optionLabel="label"
                                optionValue="value"
                                [allowEmpty]="false"
                                ariaLabelledBy="designCountLabel"
                            />
                        </div>
                    </div>
                    <div class="mt-4">
                        <p-button
                            label="Generar Diseños con IA"
                            icon="pi pi-magic"
                            [disabled]="!designsFormValid() || generatingDesigns"
                            [loading]="generatingDesigns"
                            (onClick)="generateDesigns()"
                        />
                    </div>
                }

                @if (p.designs) {
                    <div class="flex items-center justify-between mt-4 mb-2">
                        <div class="font-semibold text-lg">Diseños generados</div>
                        <p-button
                            icon="pi pi-copy"
                            severity="secondary"
                            [text]="true"
                            ariaLabel="Copiar los diseños"
                            (onClick)="copyText(p.designs!, 'Los diseños')"
                        />
                    </div>
                    <textarea pInputTextarea rows="7" class="w-full" readonly id="outputDesigns" aria-label="Diseños de personajes generados" [value]="p.designs"></textarea>
                }

                <!-- ═════════ PASO 3: ASSETS ═════════ -->
                @if (p.designs) {
                    <div class="font-semibold text-lg mt-6 mb-3">Paso 3 — Diseño de Assets (utensilios, objetos, mobiliario…)</div>
                    <p class="text-muted-color text-sm mb-3">
                        La IA analizará la historia y los diseños aprobados para
                        crear la biblioteca reutilizable de assets —entornos,
                        props narrativos, tecnología, documentos, vehículos y
                        motifs— con IDs estables (ENV/INT/EXT/PROP/TECH/VEH/DOC)
                        que las escenas referenciarán. Todo es opcional: el
                        estilo hereda el del paso 2 y el resto se deriva de la
                        historia. La salida es grande; puede tardar más que los
                        pasos anteriores.
                    </p>
                    <div class="grid grid-cols-12 gap-4">
                        <div class="col-span-12 md:col-span-5">
                            <label id="assetsAspectLabel" class="block text-sm font-medium mb-2">
                                Relación de aspecto
                                <span class="text-muted-color text-xs font-normal">
                                    (sugerida por tu plataforma: {{ platformLabel() }})
                                </span>
                            </label>
                            <p-selectbutton
                                [options]="aspectRatioOptions"
                                [(ngModel)]="assetsAspectRatio"
                                optionLabel="label"
                                optionValue="value"
                                [allowEmpty]="false"
                                ariaLabelledBy="assetsAspectLabel"
                            />
                        </div>
                        <div class="col-span-12 md:col-span-7">
                            <label id="assetsPaletteLabel" class="block text-sm font-medium mb-2">
                                Paleta de colores
                                <span class="text-muted-color text-xs font-normal">(vacío: se deriva de la historia)</span>
                            </label>
                            <p-selectbutton
                                [options]="paletteOptions"
                                [ngModel]="assetsPalette"
                                (ngModelChange)="onPaletteChange($event)"
                                optionLabel="label"
                                optionValue="value"
                                [allowEmpty]="true"
                                ariaLabelledBy="assetsPaletteLabel"
                            />
                            @if (assetsPalette === '__other__') {
                                <input
                                    pInputText
                                    type="text"
                                    id="assetsPaletteOther"
                                    class="w-full mt-2"
                                    placeholder="Describe tu paleta (p. ej. azules fríos con acentos ámbar)…"
                                    [(ngModel)]="assetsPaletteOther"
                                />
                            }
                        </div>
                        <div class="col-span-12">
                            <small class="text-muted-color text-sm">
                                El estilo hereda el del paso 2 ({{ designStyleLabel() }}) y la
                                audiencia se deriva de la historia.
                            </small>
                        </div>
                    </div>
                    <div class="mt-4">
                        <p-button
                            label="Generar Assets con IA"
                            icon="pi pi-magic"
                            [disabled]="generatingAssets"
                            [loading]="generatingAssets"
                            (onClick)="generateAssets()"
                        />
                    </div>
                }

                @if (p.assets) {
                    <div class="flex items-center justify-between mt-4 mb-2">
                        <div class="font-semibold text-lg">Biblioteca de assets generada</div>
                        <p-button
                            icon="pi pi-copy"
                            severity="secondary"
                            [text]="true"
                            ariaLabel="Copiar los assets"
                            (onClick)="copyText(p.assets!, 'Los assets')"
                        />
                    </div>
                    <textarea pInputTextarea rows="8" class="w-full" readonly id="outputAssets" aria-label="Biblioteca de assets generada" [value]="p.assets"></textarea>
                }

                <!-- ═════════ PASO 3: ESCENAS ═════════ -->
                @if (p.designs) {
                    <div class="font-semibold text-lg mt-6 mb-3">Paso 4 — Generador de Escenas</div>
                    <p class="text-muted-color text-sm mb-3">
                        La IA dividirá la historia en escenas cinematográficas
                        usando los diseños establecidos, con continuidad total —
                        el número de escenas y su reparto de tiempos se basan en
                        la duración del video del paso 1.
                    </p>
                    <div class="grid grid-cols-12 gap-4">
                        <div class="col-span-12 md:col-span-4">
                            <label id="sceneAvgLabel" class="block text-sm font-medium mb-2">
                                Duración media por escena
                                <span class="text-muted-color text-xs font-normal">(sugerida por la plataforma)</span>
                            </label>
                            <p-selectbutton
                                [options]="sceneAvgOptions"
                                [(ngModel)]="sceneAvgSeconds"
                                optionLabel="label"
                                optionValue="value"
                                [allowEmpty]="false"
                                ariaLabelledBy="sceneAvgLabel"
                            />
                        </div>
                        <div class="col-span-12 md:col-span-8 flex items-end">
                            @if (p.videoDurationSeconds) {
                                <p class="text-muted-color text-sm">
                                    Duración total del video: <b>{{ durationLabel(p.videoDurationSeconds) }}</b>
                                    → se generarán <b>≈ {{ impliedSceneCount(p) }} escenas</b> que
                                    juntas cubrirán esa duración.
                                </p>
                            } @else {
                                <p class="text-muted-color text-sm">
                                    Este proyecto no tiene duración (creado antes del cambio):
                                    se generarán 5 escenas.
                                </p>
                            }
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label id="speakerFirstLabel" class="block text-sm font-medium mb-2">Habla primero</label>
                            @if (speakerOptions.length > 0) {
                                <p-select
                                    [options]="speakerOptions"
                                    [(ngModel)]="speakerFirst"
                                    optionLabel="label"
                                    optionValue="value"
                                    placeholder="La IA decide"
                                    styleClass="w-full"
                                    [showClear]="true"
                                    ariaLabelledBy="speakerFirstLabel"
                                />
                            } @else {
                                <input
                                    pInputText
                                    type="text"
                                    id="speakerFirst"
                                    class="w-full"
                                    placeholder="Nombre del personaje…"
                                    [(ngModel)]="speakerFirst"
                                />
                            }
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label id="speakerSecondLabel" class="block text-sm font-medium mb-2">Habla segundo</label>
                            @if (speakerOptions.length > 0) {
                                <p-select
                                    [options]="speakerOptions"
                                    [(ngModel)]="speakerSecond"
                                    optionLabel="label"
                                    optionValue="value"
                                    placeholder="La IA decide"
                                    styleClass="w-full"
                                    [showClear]="true"
                                    ariaLabelledBy="speakerSecondLabel"
                                />
                            } @else {
                                <input
                                    pInputText
                                    type="text"
                                    id="speakerSecond"
                                    class="w-full"
                                    placeholder="Nombre del personaje…"
                                    [(ngModel)]="speakerSecond"
                                />
                            }
                        </div>
                        <div class="col-span-12">
                            <small class="text-muted-color text-sm">
                                Los diálogos usarán el idioma del paso 1 ({{ languageLabel() }});
                                sin hablantes fijados, la IA reparte las líneas sola.
                            </small>
                        </div>
                    </div>
                    <div class="mt-4">
                        <p-button
                            label="Generar Escenas con IA"
                            icon="pi pi-magic"
                            [disabled]="generatingScenes"
                            [loading]="generatingScenes"
                            (onClick)="generateScenes()"
                        />
                    </div>
                }

                @if (p.scenes) {
                    <div class="flex items-center justify-between mt-4 mb-2">
                        <div class="font-semibold text-lg">Escenas generadas</div>
                        <p-button
                            icon="pi pi-copy"
                            severity="secondary"
                            [text]="true"
                            ariaLabel="Copiar las escenas"
                            (onClick)="copyText(p.scenes!, 'Las escenas')"
                        />
                    </div>
                    <textarea pInputTextarea rows="8" class="w-full" readonly id="outputScenes" aria-label="Escenas generadas" [value]="p.scenes"></textarea>
                }

                <!-- ═══════ PASO 5 (OPCIONAL): PLANTILLA MASTER DE VIDEO ═══════ -->
                @if (p.scenes) {
                    <div class="font-semibold text-lg mt-6 mb-3">Paso 5 — Plantilla MASTER de Video</div>
                    <p class="text-muted-color text-sm mb-3">
                        La IA transporta UNA escena a la plantilla MASTER de 15 secciones
                        (información del proyecto, ajustes globales, referencias de personajes y
                        assets, timing, hoja de diálogo, cámara, audio, negativas, continuidad y
                        validación) usando TODA la cadena aprobada. Sin tocar nada: un clic y se
                        usa la primera escena. Opcionalmente aporta UNA referencia (archivo, URL
                        o término de búsqueda web) como inspiración.
                    </p>
                    <div class="grid grid-cols-12 gap-4">
                        <div class="col-span-6 md:col-span-3">
                            <label id="videoSceneLabel" class="block text-sm font-medium mb-2">
                                Escena objetivo
                            </label>
                            @if (parsedSceneOptions.length > 0) {
                                <p-select
                                    [options]="parsedSceneOptions"
                                    [(ngModel)]="videoSceneIndex"
                                    optionLabel="label"
                                    optionValue="value"
                                    ariaLabelledBy="videoSceneLabel"
                                    styleClass="w-full"
                                    [showClear]="false"
                                />
                            } @else {
                                <input
                                    pInputText
                                    type="number"
                                    min="1"
                                    id="videoSceneNumber"
                                    class="w-full"
                                    placeholder="Nº de escena (1, 2, 3…)"
                                    aria-label="Escena objetivo (número)"
                                    [(ngModel)]="videoSceneIndex"
                                />
                            }
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label id="referenceModeLabel" class="block text-sm font-medium mb-2">Referencia (opcional)</label>
                            <p-select
                                [options]="referenceModeOptions"
                                [(ngModel)]="referenceMode"
                                optionLabel="label"
                                optionValue="value"
                                ariaLabelledBy="referenceModeLabel"
                                styleClass="w-full"
                                [showClear]="false"
                            />
                        </div>
                        @switch (referenceMode) {
                            @case ('file') {
                                <div class="col-span-6 md:col-span-3">
                                    <label for="referenceFile" class="block text-sm font-medium mb-2">
                                        Archivo de referencia
                                    </label>
                                    <input
                                        type="file"
                                        id="referenceFile"
                                        class="text-sm w-full"
                                        accept=".txt,.md,.markdown,.json,.csv,.xml,.yml,.yaml,.html,.htm,.log,text/plain,text/markdown"
                                        (change)="onReferenceFile($event)"
                                    />
                                    @if (referenceFileName) {
                                        <small class="text-muted-color">{{ referenceFileName }}</small>
                                    }
                                </div>
                            }
                            @case ('url') {
                                <div class="col-span-6 md:col-span-3">
                                    <label for="referenceUrl" class="block text-sm font-medium mb-2">
                                        URL de referencia
                                    </label>
                                    <input
                                        pInputText
                                        type="url"
                                        id="referenceUrl"
                                        class="w-full"
                                        placeholder="https://…"
                                        [(ngModel)]="referenceUrl"
                                    />
                                </div>
                            }
                            @case ('search') {
                                <div class="col-span-6 md:col-span-3">
                                    <label for="referenceSearch" class="block text-sm font-medium mb-2">
                                        Buscar en la web
                                    </label>
                                    <input
                                        pInputText
                                        type="text"
                                        id="referenceSearch"
                                        class="w-full"
                                        placeholder="Término a buscar (p. ej. «panaderías de Kioto»)…"
                                        [(ngModel)]="referenceSearch"
                                    />
                                </div>
                            }
                        }
                    </div>
                    <div class="mt-4">
                        <p-button
                            label="Generar Plantilla MASTER de Video"
                            icon="pi pi-magic"
                            [disabled]="generatingVideo"
                            [loading]="generatingVideo"
                            severity="secondary"
                            (onClick)="generateVideo()"
                        />
                    </div>
                }

                @if (p.videoPrompt) {
                    <div class="flex items-center justify-between mt-4 mb-2">
                        <div class="font-semibold text-lg">Plantilla MASTER de video</div>
                        <p-button
                            icon="pi pi-copy"
                            severity="secondary"
                            [text]="true"
                            ariaLabel="Copiar la plantilla MASTER de video"
                            (onClick)="copyText(p.videoPrompt!, 'La plantilla de video')"
                        />
                    </div>
                    <textarea
                        pInputTextarea
                        rows="8"
                        class="w-full"
                        readonly
                        id="outputVideoPrompt"
                        aria-label="Plantilla MASTER de video generada"
                        [value]="p.videoPrompt"
                    ></textarea>
                }

                <!-- ═══════ CRONOLOGÍA DEL PROYECTO (trazas de cada intento de paso) ═══════ -->
                @if (wizardTimeline.length > 0) {
                    <div class="flex items-center justify-between mt-6 mb-2">
                        <div class="font-semibold text-lg">Cronología del proyecto</div>
                        <div class="flex gap-2">
                            <p-button
                                icon="pi pi-list"
                                severity="secondary"
                                [text]="true"
                                ariaLabel="Ver logs en vivo de este proyecto"
                                pTooltip="Ver logs en vivo de este proyecto (traza = id del proyecto)"
                                tooltipPosition="top"
                                (onClick)="viewProjectLogs(p)"
                            />
                            <p-button
                                icon="pi pi-refresh"
                                severity="secondary"
                                [text]="true"
                                ariaLabel="Refrescar la cronología"
                                [loading]="timelineLoading"
                                pTooltip="Refresca la cronología"
                                tooltipPosition="top"
                                (onClick)="loadTimeline()"
                            />
                        </div>
                    </div>
                    <p-table
                        [value]="wizardTimeline"
                        dataKey="id"
                        [paginator]="wizardTimeline.length > 8"
                        [rows]="8"
                        [tableStyle]="{ 'min-width': '640px' }"
                    >
                        <ng-template pTemplate="header">
                            <tr>
                                <th>Fecha</th>
                                <th>Paso</th>
                                <th>Estado</th>
                                <th class="text-center">Intento</th>
                                <th>Duración</th>
                                <th>Detalle</th>
                            </tr>
                        </ng-template>
                        <ng-template pTemplate="body" let-ev>
                            <tr>
                                <td>{{ ev.startedAt | date: 'dd/MM HH:mm' }}</td>
                                <td>{{ traceStepLabel(ev.step) }}</td>
                                <td>
                                    <p-tag
                                        [value]="traceStatusLabel(ev.status)"
                                        [severity]="traceStatusSeverity(ev.status)"
                                    />
                                </td>
                                <td class="text-center">{{ ev.attempt }}</td>
                                <td>{{ traceDuration(ev.durationMs) }}</td>
                                <td
                                    class="text-sm"
                                    [class.text-red-500]="!!ev.errorMessage"
                                    [pTooltip]="ev.errorMessage || ev.details || ''"
                                    tooltipPosition="top"
                                >
                                    {{ ev.errorMessage || ev.details || '' }}
                                </td>
                            </tr>
                        </ng-template>
                    </p-table>
                }
            }
        </div>

        <!-- ═════════ BIBLIOTECA DE PROYECTOS ═════════ -->
        <div class="card">
            <div class="flex justify-between items-center mb-4">
                <div class="font-semibold text-xl">Proyectos del Asistente</div>
                <p-button
                    icon="pi pi-refresh"
                    severity="secondary"
                    [outlined]="true"
                    [loading]="loading()"
                    ariaLabel="Recargar la lista de proyectos"
                    (onClick)="loadProjects()"
                />
            </div>

            <p-table [value]="projects()" [loading]="loading()" dataKey="id">
                <ng-template pTemplate="header">
                    <tr>
                        <th>Título</th>
                        <th>Progreso</th>
                        <th>Creado</th>
                        <th class="text-right">Acciones</th>
                    </tr>
                </ng-template>
                <ng-template pTemplate="body" let-s>
                    <tr>
                        <td>{{ s.title }}</td>
                        <td>
                            <div class="flex flex-wrap gap-1">
                                <p-tag value="1" [severity]="s.hasStory ? 'success' : 'secondary'" />
                                <p-tag value="2" [severity]="s.hasDesigns ? 'success' : 'secondary'" />
                                <p-tag value="3" [severity]="s.hasAssets ? 'success' : 'secondary'" />
                                <p-tag value="4" [severity]="s.hasScenes ? 'success' : 'secondary'" />
                                @if (s.hasVideoPrompt) {
                                    <p-tag value="5" severity="success" />
                                }
                            </div>
                        </td>
                        <td>{{ s.createdAt | date: 'dd/MM/yy HH:mm' }}</td>
                        <td class="text-right">
                            <p-button
                                icon="pi pi-play"
                                severity="success"
                                [text]="true"
                                [rounded]="true"
                                ariaLabel="Continuar este proyecto"
                                pTooltip="Continuar este proyecto"
                                tooltipPosition="left"
                                (onClick)="continueProject(s)"
                            />
                            <p-button
                                icon="pi pi-eye"
                                severity="secondary"
                                [text]="true"
                                ariaLabel="Abrir el detalle del proyecto"
                                (onClick)="openProject(s)"
                            />
                            <p-button
                                icon="pi pi-download"
                                severity="secondary"
                                [text]="true"
                                ariaLabel="Descargar el storyboard"
                                pTooltip="Descargar storyboard"
                                tooltipPosition="left"
                                (onClick)="downloadStoryboard(s.id, s.title)"
                            />
                            <p-button
                                icon="pi pi-trash"
                                severity="danger"
                                [text]="true"
                                ariaLabel="Eliminar el proyecto"
                                (onClick)="confirmDelete(s)"
                            />
                        </td>
                    </tr>
                </ng-template>
                <ng-template pTemplate="emptymessage">
                    <tr>
                        <td colspan="4" class="text-center text-muted-color">
                            Todavía no hay proyectos: lanza el paso 1 arriba.
                        </td>
                    </tr>
                </ng-template>
            </p-table>
        </div>

        <!-- ═════════ DETALLE DEL PROYECTO ═════════ -->
        <p-dialog
            [(visible)]="detailVisible"
            [header]="detail?.title || 'Proyecto'"
            [modal]="true"
            [style]="{ width: '780px' }"
        >
            @if (detail; as d) {
                @if (d.story) {
                    <div class="font-semibold mb-2">Historia</div>
                    <textarea pInputTextarea rows="5" class="w-full mb-3" readonly id="detailStory" aria-label="Historia generada" [value]="d.story"></textarea>
                }
                @if (d.designs) {
                    <div class="font-semibold mb-2">Diseños de personajes</div>
                    <textarea pInputTextarea rows="5" class="w-full mb-3" readonly id="detailDesigns" aria-label="Diseños de personajes generados" [value]="d.designs"></textarea>
                }
                @if (d.assets) {
                    <div class="font-semibold mb-2">Biblioteca de assets</div>
                    <textarea pInputTextarea rows="5" class="w-full mb-3" readonly id="detailAssets" aria-label="Biblioteca de assets generada" [value]="d.assets"></textarea>
                }
                @if (d.scenes) {
                    <div class="font-semibold mb-2">Escenas</div>
                    <textarea pInputTextarea rows="6" class="w-full mb-3" readonly id="detailScenes" aria-label="Escenas generadas" [value]="d.scenes"></textarea>
                }
                @if (d.videoPrompt) {
                    <div class="font-semibold mb-2">Plantilla de video</div>
                    <textarea pInputTextarea rows="6" class="w-full" readonly id="detailVideoPrompt" aria-label="Plantilla MASTER de video generada" [value]="d.videoPrompt"></textarea>
                }
            }
        </p-dialog>
    `,
})
export class StoryWizard {
    private readonly client = inject(StoryWizardClientService);
    private readonly messageService = inject(MessageService);
    private readonly confirmationService = inject(ConfirmationService);
    private readonly cdr = inject(ChangeDetectorRef);
    private readonly router = inject(Router);

    /** Estado del wizard y biblioteca. */
    readonly project = signal<VideoStoryProject | null>(null);
    readonly projects = signal<VideoStoryProjectSummary[]>([]);
    readonly loading = signal(false);

    detailVisible = false;
    detail: VideoStoryProject | null = null;

    /** Loading por paso (una llamada a la vez; cada una tarda minutos). */
    generatingStory = false;
    generatingDesigns = false;
    generatingScenes = false;
    generatingVideo = false;

    // ── Paso 1: vista guiada — todos los campos traen un default editable. ──
    /** Errores de validación del paso 1 (vacío = sin errores). */
    storyErrors: string[] = [];

    title = '';
    /** La ÚNICA decisión sin valor por defecto del formulario. */
    genre: string | null = null;
    /** Tono sugerido según el género (editable). */
    tone: string | null = null;
    targetAudience = 'Young adults';
    platform = 'YouTube Shorts';
    videoDurationSeconds = 60;
    /** Duración tocada a mano: la plataforma deja de sugerirla. */
    durationTouched = false;
    /** Tarjeta de conflicto elegida o '__other__' para texto libre. */
    conflict: string | null = null;
    conflictOther = '';
    /** ¿El usuario eligió conflicto a mano? (si no, se sugiere por género). */
    conflictTouched = false;
    endingType: string | null = 'Cliffhanger';
    /** Ligado al tipo de final (editable en avanzados). */
    unresolved: string | null = null;
    unresolvedOther = '';
    unresolvedTouched = false;
    /** Idioma del diálogo: recuerda la última elección (localStorage). */
    dialogueLanguage = StoryWizard.readStoredLanguage();
    familyFriendly = true;

    // ── Paso 1: ajustes avanzados (todo opcional y colapsado). ──
    subgenre: string | null = null;
    subgenreOther = '';
    /** Ambientación: fusiona país/cultura y ubicación en un solo concepto. */
    setting: string | null = null;
    settingOther = '';
    characterCount = 2;
    character1Requirements = '';
    character2Requirements = '';
    otherCharacterRequirements = '';
    /** Formato de animación (vacío = 3D cinematográfico, default del backend). */
    visualStyle: string | null = null;
    /** Realismo (vacío = estilizado, default del backend). */
    realismLevel: string | null = null;
    /** Estilo de animación global: se hereda a los pasos 2, 3 y 5. */
    animationStyle: string | null = null;
    /** Preset de ritmo; 'custom' habilita los tres porcentajes a mano. */
    pacingPreset: 'balanced' | 'fastStart' | 'slowBurn' | 'custom' = 'balanced';
    pacingFirstPercent = 30;
    pacingMiddlePercent = 50;
    pacingFinalPercent = 20;

    // ── Paso 2: diseños (heredan el estilo global y el nº del paso 1). ──
    designStyle: string | null = null;
    designCharacterCount = 2;

    // ── Paso 3: assets (aspecto derivado de la plataforma; paleta opcional). ──
    generatingAssets = false;
    assetsAspectRatio = '9:16';
    /** Chip de paleta o '__other__'; null = la IA la deriva de la historia. */
    assetsPalette: string | null = null;
    assetsPaletteOther = '';

    // ── Paso 4: escenas (idioma heredado del paso 1). ──
    sceneAvgSeconds = 5;
    speakerFirst: string | null = null;
    speakerSecond: string | null = null;
    /** Nombres parseados de los diseños/historia para los «habla primero/segundo». */
    parsedCharacterNames: string[] = [];

    // ── Paso 5: plantilla MASTER de video (cero interacción obligatoria). ──
    /** Índice 1-based de la escena objetivo (default: la primera). */
    videoSceneIndex = 1;
    /** Escenas del paso 4 parseadas para el selector (mismo formato que el backend). */
    parsedSceneOptions: { label: string; value: number }[] = [];
    /** Modo de la referencia opcional: ninguna | archivo | url | búsqueda. */
    referenceMode: 'none' | 'file' | 'url' | 'search' = 'none';
    referenceUrl = '';
    referenceSearch = '';
    /** Contenido de texto del archivo de referencia (leído en el navegador). */
    referenceFileContent: string | null = null;
    referenceFileName: string | null = null;

    // ── Modo automático: encadena 2→5 tras la historia o a demanda. ──
    autoMode = true;
    autoRunning = false;
    autoStep = '';

    // ── Cronología del proyecto (trazas persistidas de cada intento de paso). ──
    wizardTimeline: VideoStoryTraceEvent[] = [];
    timelineLoading = false;

    // ── Selectores (etiqueta ES, valor EN). ──
    readonly genreOptions: SelectOption[] = [
        { label: 'Romance', value: 'Romance' },
        { label: 'Drama romántico', value: 'Romantic drama' },
        { label: 'Comedia', value: 'Comedy' },
        { label: 'Comedia romántica', value: 'Romantic comedy' },
        { label: 'Drama', value: 'Drama' },
        { label: 'Suspenso', value: 'Suspense' },
        { label: 'Misterio', value: 'Mystery' },
        { label: 'Aventura', value: 'Adventure' },
        { label: 'Fantasía', value: 'Fantasy' },
        { label: 'Ciencia ficción', value: 'Science fiction' },
        { label: 'Slice of life', value: 'Slice of life' },
    ];

    readonly audienceOptions: SelectOption[] = [
        { label: 'Niños', value: 'Children' },
        { label: 'Adolescentes', value: 'Teenagers' },
        { label: 'Jóvenes adultos', value: 'Young adults' },
        { label: 'Adultos', value: 'Adults' },
        { label: 'Todas las edades', value: 'All ages' },
    ];

    readonly toneOptions: SelectOption[] = [
        { label: 'Romántico', value: 'Romantic' },
        { label: 'Cómico', value: 'Comedic' },
        { label: 'Dramático', value: 'Dramatic' },
        { label: 'Tenso', value: 'Suspenseful' },
        { label: 'Emotivo', value: 'Emotional' },
        { label: 'Tierno y cálido', value: 'Heartwarming' },
        { label: 'Agridulce', value: 'Bittersweet' },
    ];

    readonly visualStyleOptions: SelectOption[] = [
        { label: 'Largometraje 3D animado', value: 'CINEMATIC 3D ANIMATED FEATURE' },
        { label: 'Animación 2D', value: '2D ANIMATED FEATURE' },
        { label: 'Estilo live action', value: 'LIVE ACTION STYLE' },
        { label: 'Stop motion', value: 'STOP MOTION' },
    ];

    readonly realismOptions: SelectOption[] = [
        { label: 'Realista', value: 'REALISTIC' },
        { label: 'Estilizado', value: 'STYLIZED' },
        { label: 'Fantasía', value: 'FANTASY' },
    ];

    /** Tarjetas de conflicto emocional: el corazón del clímax en UNA selección. */
    readonly conflictOptions: ConflictCard[] = [
        {
            label: 'Decirlo todo antes de perderse',
            value: 'Saying what they truly feel before losing each other',
            icon: 'pi pi-heart',
        },
        {
            label: 'Lealtad a la familia frente al corazón',
            value: 'Loyalty to family versus following their own heart',
            icon: 'pi pi-users',
        },
        {
            label: 'Rivales por el mismo sueño',
            value: 'Being rivals for the same dream they both need',
            icon: 'pi pi-trophy',
        },
        {
            label: 'Un secreto del pasado que lo cambia todo',
            value: 'A secret from the past that changes everything',
            icon: 'pi pi-eye-slash',
        },
        {
            label: 'Una promesa que ata a caminos distintos',
            value: 'A promise that binds them to separate paths',
            icon: 'pi pi-link',
        },
        {
            label: 'Confiar cuando todo indica lo contrario',
            value: 'Trusting each other when every sign says otherwise',
            icon: 'pi pi-shield',
        },
        {
            label: 'Elegir entre la ambición y la persona amada',
            value: 'Choosing between ambition and the person they love',
            icon: 'pi pi-star',
        },
        {
            label: 'Perdonar una traición apenas soportable',
            value: 'Forgiving a betrayal they can barely survive',
            icon: 'pi pi-gift',
        },
    ];

    /** Lo que NO se resuelve: emparejado 1:1 con cada tipo de final. */
    readonly unresolvedOptions: SelectOption[] = [
        {
            label: 'La identidad de quien lo provocó todo',
            value: 'the true identity of the person behind everything',
        },
        { label: 'Toda la verdad tras la revelación', value: 'the full truth behind the revelation' },
        {
            label: 'Si los sentimientos confesados serán correspondidos',
            value: 'whether the confessed feelings will be answered',
        },
        { label: 'El verdadero motivo de la traición', value: 'the real motive behind the betrayal' },
        { label: 'Quién envió el mensaje y por qué', value: 'who sent the message and why' },
        { label: 'Las intenciones del recién llegado', value: 'the newcomer\'s true intentions' },
        { label: 'La identidad oculta del personaje', value: 'the character\'s hidden identity' },
        { label: 'Qué decidirá el protagonista a continuación', value: 'what the protagonist will decide next' },
    ];

    /** Lo anterior + «Otro…» para el selector de avanzados. */
    readonly unresolvedSelectOptions: SelectOption[] = [
        ...this.unresolvedOptions,
        { label: 'Otro…', value: '__other__' },
    ];

    /** Premisas del arranque rápido: género + tono + conflicto + final en un clic. */
    readonly premisePresets: PremisePreset[] = [
        {
            label: 'Romance de oficina',
            hint: 'Slow burn entre compañeros de trabajo',
            icon: 'pi pi-briefcase',
            genre: 'Romance',
            tone: 'Romantic',
            ending: 'Confession',
            conflict: 'Choosing between ambition and the person they love',
        },
        {
            label: 'Amor entre rivales',
            hint: 'Dos rivales, un mismo sueño',
            icon: 'pi pi-trophy',
            genre: 'Romantic comedy',
            tone: 'Comedic',
            ending: 'Cliffhanger',
            conflict: 'Being rivals for the same dream they both need',
        },
        {
            label: 'Secreto familiar',
            hint: 'El pasado vuelve para quedarse',
            icon: 'pi pi-users',
            genre: 'Drama',
            tone: 'Dramatic',
            ending: 'Shocking revelation',
            conflict: 'Loyalty to family versus following their own heart',
        },
        {
            label: 'Reencuentro inesperado',
            hint: 'Un mensaje que reabre la herida',
            icon: 'pi pi-send',
            genre: 'Romantic drama',
            tone: 'Bittersweet',
            ending: 'Unexpected message',
            conflict: 'A secret from the past that changes everything',
        },
        {
            label: 'Farsa cómica',
            hint: 'Equívocos sin parar',
            icon: 'pi pi-face-smile',
            genre: 'Comedy',
            tone: 'Comedic',
            ending: 'Cliffhanger',
            conflict: 'Trusting each other when every sign says otherwise',
        },
        {
            label: 'Mundo oculto',
            hint: 'Fantasía escondida en la ciudad',
            icon: 'pi pi-globe',
            genre: 'Fantasy',
            tone: 'Emotional',
            ending: 'Hidden identity',
            conflict: 'A promise that binds them to separate paths',
        },
    ];

    /** Ambientación: fusión de país/cultura y ubicación (vacío = la IA decide). */
    readonly settingOptions: SelectOption[] = [
        { label: 'Japón', value: 'Japan' },
        { label: 'Corea', value: 'South Korea' },
        { label: 'España', value: 'Spain' },
        { label: 'Estados Unidos', value: 'United States' },
        { label: 'Latinoamérica', value: 'Latin America' },
        { label: 'Ciudad ficticia', value: 'A fictional modern city' },
        { label: 'Mundo de fantasía', value: 'An original fantasy world' },
        { label: 'Otro…', value: '__other__' },
    ];

    /** Presets de ritmo narrativo (los tres % siempre suman 100). */
    readonly pacingOptions: SelectOption[] = [
        { label: 'Equilibrado', value: 'balanced' },
        { label: 'Inicio rápido', value: 'fastStart' },
        { label: 'Desarrollo lento', value: 'slowBurn' },
        { label: 'Personalizado', value: 'custom' },
    ];

    /** Paletas sugeridas para la biblioteca de assets (vacío = deriva de la historia). */
    readonly paletteOptions: SelectOption[] = [
        {
            label: 'Cálida y dorada',
            value: 'Warm golden-hour palette with soft pastel accents',
        },
        {
            label: 'Neones nocturnos',
            value: 'Vibrant neon night palette with deep blues and magentas',
        },
        {
            label: 'Tonos tierra',
            value: 'Earthy natural palette with terracotta, sand and olive tones',
        },
        {
            label: 'Fría y tecnológica',
            value: 'Cool technology palette with teal, silver and soft blue accents',
        },
        { label: 'Pastel suave', value: 'Soft pastel palette with muted candy tones' },
        { label: 'Otro…', value: '__other__' },
    ];

    readonly endingTypeOptions: SelectOption[] = [
        { label: 'Cliffhanger', value: 'Cliffhanger' },
        { label: 'Revelación impactante', value: 'Shocking revelation' },
        { label: 'Confesión', value: 'Confession' },
        { label: 'Traición repentina', value: 'Sudden betrayal' },
        { label: 'Mensaje inesperado', value: 'Unexpected message' },
        { label: 'Llegada inesperada', value: 'Arrival of another character' },
        { label: 'Identidad oculta', value: 'Hidden identity' },
        { label: 'Final abierto', value: 'Open ending' },
    ];

    readonly characterCountOptions: { label: string; value: number }[] = [
        { label: '2 personajes', value: 2 },
        { label: '3 personajes', value: 3 },
        { label: '4 personajes', value: 4 },
        { label: '5 personajes', value: 5 },
    ];

    readonly languageOptions: SelectOption[] = [
        { label: 'Español', value: 'Spanish' },
        { label: 'Inglés', value: 'English' },
        { label: 'Portugués', value: 'Portuguese' },
        { label: 'Francés', value: 'French' },
        { label: 'Japonés', value: 'Japanese' },
    ];

    readonly videoDurationOptions: { label: string; value: number }[] = [
        { label: '30 segundos', value: 30 },
        { label: '1 minuto', value: 60 },
        { label: '1.5 minutos', value: 90 },
        { label: '2 minutos', value: 120 },
        { label: '3 minutos', value: 180 },
        { label: '4 minutos', value: 240 },
        { label: '5 minutos', value: 300 },
    ];

    /** Plataformas con icono: derivan aspecto (vertical/horizontal) y ritmo. */
    readonly platformOptions: (SelectOption & { icon: string })[] = [
        { label: 'YouTube Shorts', value: 'YouTube Shorts', icon: 'pi pi-mobile' },
        { label: 'TikTok', value: 'TikTok', icon: 'pi pi-video' },
        { label: 'Instagram Reels', value: 'Instagram Reels', icon: 'pi pi-play-circle' },
        { label: 'YouTube', value: 'YouTube', icon: 'pi pi-globe' },
        { label: 'Cortometraje', value: 'Short film', icon: 'pi pi-image' },
        { label: 'Demo de animación', value: 'Animation demo', icon: 'pi pi-sparkles' },
    ];

    readonly animationStyleOptions: SelectOption[] = [
        {
            label: 'Estilo Pixar / DreamWorks',
            value: 'Premium polished 3D animation, Pixar and DreamWorks style',
        },
        {
            label: 'Anime 3D estilizado',
            value: '3D animation with anime-inspired stylized aesthetics',
        },
        { label: 'Cinematográfico realista', value: 'Realistic cinematic 3D animation' },
        { label: 'Suave y estilizado', value: 'Soft stylized 3D animation with rounded shapes' },
        { label: 'Infantil colorido', value: 'Colorful family-friendly 3D animation for kids' },
        { label: 'Fantasía brillante', value: 'Bright fantasy-styled 3D animation' },
    ];

    readonly designCountOptions: { label: string; value: number }[] = [
        { label: '2 personajes', value: 2 },
        { label: '3 personajes', value: 3 },
        { label: '4 personajes', value: 4 },
        { label: '5 personajes', value: 5 },
        { label: '6 personajes', value: 6 },
    ];

    /** Modos de la referencia opcional del paso 5. */
    readonly referenceModeOptions: SelectOption[] = [
        { label: 'Ninguna', value: 'none' },
        { label: 'Archivo', value: 'file' },
        { label: 'URL', value: 'url' },
        { label: 'Búsqueda web', value: 'search' },
    ];

    readonly sceneAvgOptions: { label: string; value: number }[] = [
        { label: '5 s/escena', value: 5 },
        { label: '8 s/escena', value: 8 },
        { label: '10 s/escena', value: 10 },
        { label: '12 s/escena', value: 12 },
        { label: '15 s/escena', value: 15 },
    ];

    readonly aspectRatioOptions: SelectOption[] = [
        { label: '16:9 (horizontal)', value: '16:9' },
        { label: '9:16 (vertical)', value: '9:16' },
        { label: '1:1 (cuadrado)', value: '1:1' },
    ];

    // ── Defaults inteligentes (estáticos, sin estado). ──

    /** Clave de localStorage del último idioma de diálogo usado. */
    private static readonly LANGUAGE_KEY = 'wizard.dialogueLanguage';

    /** Plataformas verticales: fuerzan 9:16 y escenas cortas. */
    private static readonly VERTICAL_PLATFORMS = new Set([
        'YouTube Shorts',
        'TikTok',
        'Instagram Reels',
    ]);

    /** Perfil por género: tono sugerido, conflicto por defecto y subgéneros. */
    private static readonly GENRE_PROFILES: Record<string, GenreProfile> = {
        Romance: {
            tone: 'Romantic',
            conflict: 'Saying what they truly feel before losing each other',
            subgenres: ['Slow burn', 'Office romance', 'Second chance'],
        },
        'Romantic drama': {
            tone: 'Bittersweet',
            conflict: 'A secret from the past that changes everything',
            subgenres: ['Class difference', 'Reunion', 'Love against the odds'],
        },
        Comedy: {
            tone: 'Comedic',
            conflict: 'Trusting each other when every sign says otherwise',
            subgenres: ['Workplace comedy', 'Mistaken identity', 'Awkward hero'],
        },
        'Romantic comedy': {
            tone: 'Comedic',
            conflict: 'Trusting each other when every sign says otherwise',
            subgenres: ['Enemies to lovers', 'Fake dating', 'Forced proximity'],
        },
        Drama: {
            tone: 'Dramatic',
            conflict: 'Loyalty to family versus following their own heart',
            subgenres: ['Family secrets', 'Career vs love', 'Redemption'],
        },
        Suspense: {
            tone: 'Suspenseful',
            conflict: 'A secret from the past that changes everything',
            subgenres: ['Slow reveal', 'Psychological suspense', 'Techno-thriller'],
        },
        Mystery: {
            tone: 'Suspenseful',
            conflict: 'A secret from the past that changes everything',
            subgenres: ['Small town secret', 'Missing person', 'Whodunit'],
        },
        Adventure: {
            tone: 'Heartwarming',
            conflict: 'Trusting each other when every sign says otherwise',
            subgenres: ['Road trip', 'Treasure hunt', 'Survival'],
        },
        Fantasy: {
            tone: 'Heartwarming',
            conflict: 'A promise that binds them to separate paths',
            subgenres: ['Modern fairy tale', 'Hidden world', 'Chosen one'],
        },
        'Science fiction': {
            tone: 'Suspenseful',
            conflict: 'Choosing between ambition and the person they love',
            subgenres: ['Near future', 'Space station', 'Time loop'],
        },
        'Slice of life': {
            tone: 'Heartwarming',
            conflict: 'Saying what they truly feel before losing each other',
            subgenres: ['Food and memory', 'Small victories', 'Everyday warmth'],
        },
    };

    /** Perfil de reserva para un género desconocido. */
    private static readonly DEFAULT_PROFILE: GenreProfile = {
        tone: 'Heartwarming',
        conflict: 'Saying what they truly feel before losing each other',
        subgenres: ['Slow burn', 'Found family', 'Second chance'],
    };

    /** Índice en unresolvedOptions emparejado con cada tipo de final. */
    private static readonly ENDING_TO_UNRESOLVED: Record<string, number> = {
        Cliffhanger: 0,
        'Shocking revelation': 1,
        Confession: 2,
        'Sudden betrayal': 3,
        'Unexpected message': 4,
        'Arrival of another character': 5,
        'Hidden identity': 6,
        'Open ending': 7,
    };

    private static profileFor(genre: string | null): GenreProfile {
        if (!genre) return StoryWizard.DEFAULT_PROFILE;
        return StoryWizard.GENRE_PROFILES[genre] ?? StoryWizard.DEFAULT_PROFILE;
    }

    private static isVerticalPlatform(platform: string | null): boolean {
        return !!platform && StoryWizard.VERTICAL_PLATFORMS.has(platform);
    }

    /** Último idioma usado (localStorage); 'Spanish' si no hay nada guardado. */
    private static readStoredLanguage(): string {
        try {
            return localStorage.getItem(StoryWizard.LANGUAGE_KEY) ?? 'Spanish';
        } catch {
            return 'Spanish';
        }
    }

    ngOnInit() {
        // El estilo de los diseños (y por herencia, de assets y video) empieza
        // en el primero de la lista; el usuario puede cambiarlo en avanzados.
        this.designStyle = this.animationStyleOptions[0].value;
        // «Lo que NO se resuelve» arranca emparejado con el final por defecto.
        this.syncUnresolved();
        this.loadProjects();
    }

    loadProjects() {
        this.loading.set(true);
        this.client.list().subscribe({
            next: (projects) => {
                this.projects.set(projects);
                this.loading.set(false);
                this.cdr.markForCheck();
            },
            error: () => {
                this.loading.set(false);
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudieron cargar los proyectos.',
                });
                this.cdr.markForCheck();
            },
        });
    }

    stepDone(step: 'story' | 'designs' | 'assets' | 'scenes' | 'video'): boolean {
        const p = this.project();
        if (!p) return false;
        switch (step) {
            case 'story':
                return !!p.story;
            case 'designs':
                return !!p.designs;
            case 'assets':
                return !!p.assets;
            case 'scenes':
                return !!p.scenes;
            case 'video':
                return !!p.videoPrompt;
        }
    }

    // ═══════════════ PASO 1: historia ═══════════════

    /** Errores de validación del paso 1 (vacío = listo para generar). */
    storyValidationErrors(): string[] {
        const errors: string[] = [];
        if (!this.genre) {
            errors.push('Género (es lo único imprescindible)');
        }
        if (this.conflict === '__other__' && !this.conflictOther.trim()) {
            errors.push('Conflicto: «Otro» necesita tu texto');
        }
        if (this.unresolved === '__other__' && !this.unresolvedOther.trim()) {
            errors.push('Elemento sin resolver: «Otro» necesita tu texto');
        }
        if (this.setting === '__other__' && !this.settingOther.trim()) {
            errors.push('Ambientación: «Otro» necesita tu texto');
        }
        if (this.subgenre === '__other__' && !this.subgenreOther.trim()) {
            errors.push('Subgénero: «Otro» necesita tu texto');
        }
        if (
            this.pacingPreset === 'custom' &&
            this.pacingFirstPercent + this.pacingMiddlePercent + this.pacingFinalPercent !== 100
        ) {
            errors.push('Ritmo personalizado: la suma debe ser 100 %');
        }
        return errors;
    }

    generateStory() {
        if (this.generatingStory) return;
        this.storyErrors = this.storyValidationErrors();
        if (this.storyErrors.length > 0) {
            this.messageService.add({
                severity: 'warn',
                summary: 'Falta poco',
                detail: this.storyErrors.join(' · '),
            });
            this.cdr.markForCheck();
            return;
        }
        this.generatingStory = true;
        this.persistLanguage();

        this.client
            .createWithStory({
                title: this.resolveTitle(),
                genre: this.genre!,
                subgenre: this.resolveSubgenre(),
                targetAudience: this.targetAudience,
                countryCulture: this.resolveSetting(),
                location: null,
                familyFriendly: this.familyFriendly ? 'YES' : 'NO',
                visualStyle: this.visualStyle,
                tone: this.tone!,
                realismLevel: this.realismLevel,
                character1Requirements: this.character1Requirements.trim() || null,
                character2Requirements: this.character2Requirements.trim() || null,
                otherCharacterRequirements: this.otherCharacterRequirements.trim() || null,
                mainEmotionalConflict: this.resolveConflict(),
                unresolvedElement: this.resolveUnresolved(),
                endingType: this.endingType,
                characterCount: this.characterCount,
                dialogueLanguage: this.dialogueLanguage,
                videoDurationSeconds: this.videoDurationSeconds,
                pacingFirstPercent: this.pacingFirstPercent,
                pacingMiddlePercent: this.pacingMiddlePercent,
                pacingFinalPercent: this.pacingFinalPercent,
                platform: this.platform!,
            })
            .subscribe({
                next: (project) => {
                    this.generatingStory = false;
                    this.project.set(project);
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Historia generada',
                        detail: `«${project.title}» — continúa con el paso 2 cuando quieras.`,
                    });
                    this.loadProjects();
                    this.loadTimeline();
                    this.cdr.markForCheck();
                    // Modo automático: la cadena 2→5 arranca sola (nula
                    // interacción tras el formulario de historia).
                    if (this.autoMode) {
                        this.runAllPendingSteps();
                    }
                },
                error: (message: string) => {
                    this.generatingStory = false;
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Fallo el paso 1',
                        detail: message,
                    });
                    this.cdr.markForCheck();
                },
            });
    }

    // ═════════ PASO 1: selección guiada (handlers y resolvers) ═════════

    /** Género: sugiere tono y conflicto, y refresca los subgéneros. */
    onGenreChange(value: string) {
        this.genre = value;
        const profile = StoryWizard.profileFor(value);
        this.tone = profile.tone;
        if (!this.conflictTouched) {
            this.conflict = profile.conflict;
        }
        this.subgenre = null;
        this.subgenreOther = '';
        this.storyErrors = this.storyErrors.filter((e) => !e.startsWith('Género'));
    }

    /** Conflicto elegido a mano (tarjeta u «Otro»): ya no se sugiere por género. */
    onConflictChange(value: string) {
        this.conflict = value;
        this.conflictTouched = true;
        this.storyErrors = this.storyErrors.filter((e) => !e.startsWith('Conflicto'));
    }

    /** Final: mientras el usuario no toque «lo que no se resuelve», van en pareja. */
    onEndingChange(value: string) {
        this.endingType = value;
        if (!this.unresolvedTouched) {
            this.syncUnresolved();
        }
    }

    onUnresolvedChange(value: string) {
        this.unresolved = value;
        this.unresolvedTouched = true;
    }

    private syncUnresolved(): void {
        const index = StoryWizard.ENDING_TO_UNRESOLVED[this.endingType ?? ''] ?? 0;
        this.unresolved = this.unresolvedOptions[index]?.value
            ?? this.unresolvedOptions[0].value;
    }

    /** Plataforma: deriva aspecto, duración sugerida y ritmo de escenas. */
    onPlatformChange(value: string) {
        this.platform = value;
        const vertical = StoryWizard.isVerticalPlatform(value);
        this.assetsAspectRatio = vertical ? '9:16' : '16:9';
        this.sceneAvgSeconds = vertical ? 5 : 10;
        if (!this.durationTouched) {
            this.videoDurationSeconds = vertical ? 60 : 120;
        }
    }

    onDurationChange(value: number) {
        this.videoDurationSeconds = value;
        this.durationTouched = true;
    }

    /** Audiencia infantil → siempre apto para familias. */
    onAudienceChange(value: string) {
        this.targetAudience = value;
        if (value === 'Children') {
            this.familyFriendly = true;
        }
    }

    onLanguageChange(value: string) {
        this.dialogueLanguage = value;
        this.persistLanguage();
    }

    private persistLanguage(): void {
        try {
            localStorage.setItem(StoryWizard.LANGUAGE_KEY, this.dialogueLanguage);
        } catch {
            // Sin localStorage (p. ej. modo privado): no es crítico.
        }
    }

    /** Mantiene la coherencia: mismos personajes en la historia y los diseños. */
    onCharacterCountChange(value: number) {
        this.characterCount = value;
        this.designCharacterCount = value;
    }

    /** El estilo global se hereda a los diseños (y de ahí a assets y video). */
    onAnimationStyleChange(value: string | null) {
        this.animationStyle = value;
        this.designStyle = value ?? this.animationStyleOptions[0].value;
    }

    onPacingPresetChange(value: string) {
        this.pacingPreset = value as 'balanced' | 'fastStart' | 'slowBurn' | 'custom';
        switch (this.pacingPreset) {
            case 'fastStart':
                this.pacingFirstPercent = 20;
                this.pacingMiddlePercent = 50;
                this.pacingFinalPercent = 30;
                break;
            case 'slowBurn':
                this.pacingFirstPercent = 40;
                this.pacingMiddlePercent = 45;
                this.pacingFinalPercent = 15;
                break;
            case 'balanced':
            default:
                this.pacingFirstPercent = 30;
                this.pacingMiddlePercent = 50;
                this.pacingFinalPercent = 20;
                break;
        }
    }

    onSubgenreChange(value: string | null) {
        this.subgenre = value;
    }

    onSettingChange(value: string | null) {
        this.setting = value;
    }

    onPaletteChange(value: string | null) {
        this.assetsPalette = value;
    }

    /** Aplica una premisa completa (género, tono, conflicto y final). */
    applyPreset(preset: PremisePreset) {
        this.onGenreChange(preset.genre);
        this.tone = preset.tone;
        this.conflict = preset.conflict;
        this.conflictTouched = true;
        this.endingType = preset.ending;
        this.unresolvedTouched = false;
        this.syncUnresolved();
        this.storyErrors = [];
        this.messageService.add({
            severity: 'info',
            summary: 'Plantilla aplicada',
            detail: `«${preset.label}» — todos los campos siguen editables.`,
        });
    }

    /** Rellena el formulario con una combinación aleatoria coherente. */
    surpriseMe() {
        const genre = this.genreOptions[Math.floor(Math.random() * this.genreOptions.length)];
        const conflict = this.conflictOptions[Math.floor(Math.random() * this.conflictOptions.length)];
        const ending = this.endingTypeOptions[Math.floor(Math.random() * this.endingTypeOptions.length)];
        this.onGenreChange(genre.value);
        this.conflict = conflict.value;
        this.conflictTouched = true;
        this.endingType = ending.value;
        this.unresolvedTouched = false;
        this.syncUnresolved();
        this.storyErrors = [];
        this.messageService.add({
            severity: 'info',
            summary: '¡Sorpresa lista!',
            detail: `Género ${genre.label.toLowerCase()} con final ${ending.label.toLowerCase()} — ajústalo a tu gusto.`,
        });
    }

    // ── Resolvers: lo que realmente se envía al backend. ──

    /** Título: si va vacío, se genera uno con la fecha (el backend lo exige). */
    private resolveTitle(): string {
        const trimmed = this.title.trim();
        if (trimmed) {
            return trimmed;
        }
        const date = new Date().toLocaleDateString('es-ES', {
            day: 'numeric',
            month: 'short',
        });
        return `Historia — ${date}`;
    }

    private resolveSubgenre(): string | null {
        if (this.subgenre === '__other__') {
            return this.subgenreOther.trim() || null;
        }
        return this.subgenre;
    }

    private resolveSetting(): string | null {
        if (this.setting === '__other__') {
            return this.settingOther.trim() || null;
        }
        return this.setting;
    }

    private resolveConflict(): string {
        if (this.conflict === '__other__') {
            return this.conflictOther.trim();
        }
        if (this.conflict) {
            return this.conflict;
        }
        return StoryWizard.profileFor(this.genre).conflict;
    }

    private resolveUnresolved(): string {
        if (this.unresolved === '__other__') {
            return this.unresolvedOther.trim();
        }
        return this.unresolved ?? this.unresolvedOptions[0].value;
    }

    private resolvePalette(): string | null {
        if (this.assetsPalette === '__other__') {
            return this.assetsPaletteOther.trim() || null;
        }
        return this.assetsPalette;
    }

    // ═══════════════ PASO 2: diseños ═══════════════

    designsFormValid(): boolean {
        return !!this.designStyle;
    }

    generateDesigns(onDone?: () => void) {
        const p = this.project();
        if (!p || !this.designsFormValid() || this.generatingDesigns) return;
        this.generatingDesigns = true;

        this.client
            .generateDesigns(p.id, {
                animationStyle: this.designStyle!,
                characterCount: this.designCharacterCount,
            })
            .subscribe({
                next: (project) => {
                    this.generatingDesigns = false;
                    this.project.set(project);
                    // Los «habla primero/segundo» del paso 4 se alimentan de los
                    // nombres de los personajes recién diseñados.
                    this.refreshCharacterNames(project);
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Diseños generados',
                        detail: 'Continúa con el paso 3 cuando quieras.',
                    });
                    this.loadProjects();
                    this.loadTimeline();
                    this.cdr.markForCheck();
                    onDone?.();
                },
                error: (message: string) => {
                    this.generatingDesigns = false;
                    if (this.autoRunning) this.finishAutoChain(false);
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Fallo el paso 2',
                        detail: message,
                    });
                    this.cdr.markForCheck();
                },
            });
    }

    // ═══════════════ PASO 3: assets ═══════════════

    generateAssets(onDone?: () => void) {
        const p = this.project();
        if (!p || this.generatingAssets) return;
        this.generatingAssets = true;

        this.client
            .generateAssets(p.id, {
                // El estilo hereda el del paso 2; la audiencia se deriva de
                // la historia en la propia plantilla MASTER de assets.
                animationStyle: p.designStyle ?? this.designStyle,
                targetAudience: null,
                aspectRatio: this.assetsAspectRatio,
                colorPalette: this.resolvePalette(),
            })
            .subscribe({
                next: (project) => {
                    this.generatingAssets = false;
                    this.project.set(project);
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Assets generados',
                        detail: 'Biblioteca con IDs estables lista para las escenas.',
                    });
                    this.loadProjects();
                    this.loadTimeline();
                    this.cdr.markForCheck();
                    onDone?.();
                },
                error: (message: string) => {
                    this.generatingAssets = false;
                    if (this.autoRunning) this.finishAutoChain(false);
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Fallo el paso 3',
                        detail: message,
                    });
                    this.cdr.markForCheck();
                },
            });
    }

    // ═══════════════ PASO 4: escenas ═══════════════

    generateScenes(onDone?: () => void) {
        const p = this.project();
        if (!p || this.generatingScenes) return;
        this.generatingScenes = true;

        this.client
            .generateScenes(p.id, {
                averageSceneSeconds: this.sceneAvgSeconds,
                speakerFirst: this.speakerFirst?.trim() || null,
                speakerSecond: this.speakerSecond?.trim() || null,
                // El idioma de los diálogos hereda el del paso 1.
                language: this.dialogueLanguage,
            })
            .subscribe({
                next: (project) => {
                    this.generatingScenes = false;
                    this.project.set(project);
                    this.refreshSceneOptions(project.scenes);
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Escenas generadas',
                        detail: 'El paso 5 (plantilla MASTER de video) ya está disponible.',
                    });
                    this.loadProjects();
                    this.loadTimeline();
                    this.cdr.markForCheck();
                    onDone?.();
                },
                error: (message: string) => {
                    this.generatingScenes = false;
                    if (this.autoRunning) this.finishAutoChain(false);
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Fallo el paso 4',
                        detail: message,
                    });
                    this.cdr.markForCheck();
                },
            });
    }

    // ═══════════════ PASO 5 (opcional): plantilla MASTER de video ═══════════════

    generateVideo(onDone?: () => void) {
        const p = this.project();
        if (!p || this.generatingVideo) return;
        this.generatingVideo = true;

        // Cero interacción: sin escena elegida el backend usa la PRIMERA; el
        // estilo hereda el del paso 2; la referencia solo se envía en el modo
        // activo (el backend admite UNA).
        this.client
            .generateVideo(p.id, {
                sceneIndex: this.videoSceneIndex,
                animationStyle: p.designStyle ?? this.designStyle,
                referenceFileContent:
                    this.referenceMode === 'file' ? this.referenceFileContent : null,
                referenceUrl:
                    this.referenceMode === 'url' ? this.referenceUrl.trim() || null : null,
                referenceSearch:
                    this.referenceMode === 'search' ? this.referenceSearch.trim() || null : null,
            })
            .subscribe({
                next: (project) => {
                    this.generatingVideo = false;
                    this.project.set(project);
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Plantilla MASTER de video lista',
                        detail: 'Cópiala a tu herramienta de video (Sora, Veo, Runway, Kling…).',
                    });
                    this.loadProjects();
                    this.loadTimeline();
                    this.cdr.markForCheck();
                    onDone?.();
                },
                error: (message: string) => {
                    this.generatingVideo = false;
                    if (this.autoRunning) this.finishAutoChain(false);
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Fallo el paso 5',
                        detail: message,
                    });
                    this.cdr.markForCheck();
                },
            });
    }

    /**
     * Lee el archivo de referencia en el navegador: solo texto plano
     * (txt/md/json/csv/xml/html…), máximo 500 KB — no hay persistencia de
     * archivos, el contenido viaja en la petición del paso 5.
     */
    onReferenceFile(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        if (!file) {
            this.referenceFileContent = null;
            this.referenceFileName = null;
            return;
        }
        if (file.size > 500 * 1024) {
            this.referenceFileContent = null;
            this.referenceFileName = null;
            input.value = '';
            this.messageService.add({
                sticky: true, severity: 'error',
                summary: 'Archivo demasiado grande',
                detail: 'La referencia de texto admite un máximo de 500 KB.',
            });
            this.cdr.markForCheck();
            return;
        }
        const reader = new FileReader();
        reader.onload = () => {
            this.referenceFileContent = String(reader.result ?? '');
            this.referenceFileName = `${file.name} (${file.size} B)`;
            this.cdr.markForCheck();
        };
        reader.onerror = () => {
            this.referenceFileContent = null;
            this.referenceFileName = null;
            this.messageService.add({
                sticky: true, severity: 'error',
                summary: 'Error',
                detail: 'No se pudo leer el archivo de referencia.',
            });
            this.cdr.markForCheck();
        };
        reader.readAsText(file);
    }

    /**
     * Escenas del paso 4 parseadas para el selector (la MISMA regla del
     * backend): una línea que solo contiene «SCENE NN» abre cada bloque.
     */
    private refreshSceneOptions(scenes: string | null | undefined): void {
        this.videoSceneIndex = 1;
        if (!scenes) {
            this.parsedSceneOptions = [];
            return;
        }
        const headers = [...scenes.matchAll(/^\s*SCENE\s+(\d+)\s*$/gm)];
        if (headers.length === 0) {
            this.parsedSceneOptions = [];
            return;
        }
        this.parsedSceneOptions = headers.map((match, i) => ({
            label: `Escena ${Number(match[1])} (SCENE ${match[1]})`,
            value: i + 1,
        }));
    }

    /**
     * Nombres de personaje para los «habla primero/segundo» del paso 4,
     * parseados de los diseños (bloques «**Character N:**») o, como
     * alternativa, de la lista de personajes de la historia. Si no hay
     * nombres reconocibles, los campos vuelven a ser texto libre.
     */
    private refreshCharacterNames(project: VideoStoryProject): void {
        const fromDesigns = StoryWizard.parseCharacterNames(project.designs);
        this.parsedCharacterNames = fromDesigns.length > 0
            ? fromDesigns
            : StoryWizard.parseCharacterNamesFromStory(project.story);
    }

    /** «**Character N:**» abre cada bloque; el nombre abre la línea siguiente. */
    private static parseCharacterNames(designs: string | null | undefined): string[] {
        if (!designs) {
            return [];
        }
        const names: string[] = [];
        const re = /\*\*\s*Character\s+\d+\s*:\s*\*\*\s*[\r\n]+([^\r\n–—-]*)/g;
        let match: RegExpExecArray | null;
        while ((match = re.exec(designs)) !== null) {
            const name = match[1].replace(/[*_]/g, '').trim();
            if (name && name.length <= 48 && !names.includes(name)) {
                names.push(name);
            }
        }
        return names.slice(0, 8);
    }

    /** Fallback: viñetas «- **Nombre**» de la sección CHARACTER LIST. */
    private static parseCharacterNamesFromStory(story: string | null | undefined): string[] {
        if (!story) {
            return [];
        }
        const section = /CHARACTER LIST([\s\S]*)$/.exec(story)?.[1];
        if (!section) {
            return [];
        }
        const names: string[] = [];
        const re = /-\s*\*\*([^*\n]+)\*\*/g;
        let match: RegExpExecArray | null;
        while ((match = re.exec(section)) !== null) {
            const name = match[1].trim();
            if (name && name.length <= 48 && !names.includes(name)) {
                names.push(name);
            }
        }
        return names.slice(0, 8);
    }

    // ── Opciones y etiquetas derivadas para la vista. ──

    /** Subgéneros sugeridos según el género + «Otro…». */
    get subgenreOptions(): SelectOption[] {
        const profile = StoryWizard.profileFor(this.genre);
        const chips = profile.subgenres.map((s) => ({ label: s, value: s }));
        return [...chips, { label: 'Otro…', value: '__other__' }];
    }

    /** Nombres parseados → opciones de los «habla primero/segundo». */
    get speakerOptions(): SelectOption[] {
        return this.parsedCharacterNames.map((name) => ({ label: name, value: name }));
    }

    /** Etiqueta legible del idioma de diálogo actual. */
    languageLabel(): string {
        return this.languageOptions.find((o) => o.value === this.dialogueLanguage)?.label
            ?? this.dialogueLanguage;
    }

    /** Etiqueta ES de la plataforma elegida (para las pistas de los pasos 3-4). */
    platformLabel(): string {
        return this.platformOptions.find((o) => o.value === this.platform)?.label ?? '';
    }

    /** Etiqueta corta del estilo de animación activo. */
    designStyleLabel(): string {
        return this.animationStyleOptions.find((o) => o.value === this.designStyle)?.label ?? '';
    }

    // ═══════════════ Cadena automática (mínima/nula interacción) ═══════════════

    /** Pasos pendientes de la cadena 2→5 del proyecto. */
    pendingAutoSteps(p: VideoStoryProject): number {
        let pending = 0;
        if (!p.designs) pending++;
        if (!p.assets) pending++;
        if (!p.scenes) pending++;
        if (!p.videoPrompt) pending++;
        return pending;
    }

    /**
     * Encadena automáticamente los pasos pendientes 2→5 con los valores
     * actuales de los formularios y sus defaults (estilo Pixar si no se
     * eligió otro, 16:9, 10 s/escena, español, primera escena, sin
     * referencia) — reutilizando los endpoints existentes paso a paso.
     */
    runAllPendingSteps(): void {
        const p = this.project();
        if (!p || this.autoRunning) return;
        this.autoRunning = true;
        this.autoStep = 'Preparando la cadena automática…';
        this.cdr.markForCheck();
        this.runNextPendingStep();
    }

    /** Lanza el siguiente paso pendiente; al completarse continúa la cadena. */
    private runNextPendingStep(): void {
        const p = this.project();
        if (!p) {
            this.finishAutoChain(false);
            return;
        }
        if (!p.designs) {
            this.autoStep = 'Ejecutando paso 2 de 5 (diseños)…';
            // Default del estilo: el elegido o el primero de la lista.
            this.designStyle = this.designStyle ?? this.animationStyleOptions[0].value;
            this.cdr.markForCheck();
            this.generateDesigns(() => this.runNextPendingStep());
            return;
        }
        if (!p.assets) {
            this.autoStep = 'Ejecutando paso 3 de 5 (assets)…';
            this.cdr.markForCheck();
            this.generateAssets(() => this.runNextPendingStep());
            return;
        }
        if (!p.scenes) {
            this.autoStep = 'Ejecutando paso 4 de 5 (escenas)…';
            this.cdr.markForCheck();
            this.generateScenes(() => this.runNextPendingStep());
            return;
        }
        if (!p.videoPrompt) {
            this.autoStep = 'Ejecutando paso 5 de 5 (plantilla MASTER de video)…';
            this.cdr.markForCheck();
            this.generateVideo(() => this.runNextPendingStep());
            return;
        }
        this.finishAutoChain(true);
    }

    /**
     * Cierra la cadena automática: toast de cadena completa SOLO si llegó
     * al final; un fallo del paso ya mostró su propio toast sticky y solo
     * se restaura el estado.
     */
    private finishAutoChain(succeeded: boolean): void {
        const wasRunning = this.autoRunning;
        this.autoRunning = false;
        this.autoStep = '';
        if (wasRunning && succeeded) {
            this.messageService.add({
                severity: 'success',
                summary: 'Cadena automática completa',
                detail: 'Historia → diseños → assets → escenas → plantilla MASTER de video.',
            });
        }
        this.loadProjects();
        this.loadTimeline();
        this.cdr.markForCheck();
    }

    // ═══════════════ Biblioteca ═══════════════

    resetWizard() {
        this.project.set(null);
        this.storyErrors = [];
        this.title = '';
        this.genre = null;
        this.tone = null;
        this.targetAudience = 'Young adults';
        this.platform = 'YouTube Shorts';
        this.videoDurationSeconds = 60;
        this.durationTouched = false;
        this.conflict = null;
        this.conflictOther = '';
        this.conflictTouched = false;
        this.endingType = 'Cliffhanger';
        this.unresolved = null;
        this.unresolvedOther = '';
        this.unresolvedTouched = false;
        // El idioma NO se resetea: se conserva la última elección del usuario.
        this.familyFriendly = true;
        this.subgenre = null;
        this.subgenreOther = '';
        this.setting = null;
        this.settingOther = '';
        this.characterCount = 2;
        this.character1Requirements = '';
        this.character2Requirements = '';
        this.otherCharacterRequirements = '';
        this.visualStyle = null;
        this.realismLevel = null;
        this.animationStyle = null;
        this.pacingPreset = 'balanced';
        this.pacingFirstPercent = 30;
        this.pacingMiddlePercent = 50;
        this.pacingFinalPercent = 20;
        this.designStyle = this.animationStyleOptions[0].value;
        this.designCharacterCount = 2;
        this.assetsAspectRatio = '9:16';
        this.assetsPalette = null;
        this.assetsPaletteOther = '';
        this.sceneAvgSeconds = 5;
        this.speakerFirst = null;
        this.speakerSecond = null;
        this.parsedCharacterNames = [];
        this.videoSceneIndex = 1;
        this.parsedSceneOptions = [];
        this.referenceMode = 'none';
        this.referenceUrl = '';
        this.referenceSearch = '';
        this.referenceFileContent = null;
        this.referenceFileName = null;
        this.autoRunning = false;
        this.autoStep = '';
        this.wizardTimeline = [];
        this.timelineLoading = false;
    }

    openProject(summary: VideoStoryProjectSummary) {
        this.client.get(summary.id).subscribe({
            next: (project) => {
                this.detail = project;
                this.detailVisible = true;
                this.cdr.markForCheck();
            },
            error: () => {
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo cargar el proyecto.',
                });
            },
        });
    }

    confirmDelete(summary: VideoStoryProjectSummary) {
        this.confirmationService.confirm({
            header: 'Eliminar proyecto',
            message: `¿Eliminar «${summary.title}» con toda su cadena generada?`,
            acceptLabel: 'Eliminar',
            rejectLabel: 'Cancelar',
            acceptButtonStyleClass: 'p-button-danger',
            accept: () => {
                this.client.delete(summary.id).subscribe({
                    next: () => {
                        this.messageService.add({
                            severity: 'success',
                            summary: 'Proyecto eliminado',
                            detail: `«${summary.title}» eliminado.`,
                        });
                        // Si era el proyecto activo, se reinicia el wizard.
                        if (this.project()?.id === summary.id) {
                            this.resetWizard();
                        }
                        this.loadProjects();
                    },
                    error: () => {
                        this.messageService.add({
                            sticky: true, severity: 'error',
                            summary: 'Error',
                            detail: 'No se pudo eliminar el proyecto.',
                        });
                    },
                });
            },
        });
    }

    // ═══════════════ Storyboard y reanudación ═══════════════

    /** ¿Tiene el proyecto algún output ya generado (para el storyboard)? */
    hasAnyOutput(p: VideoStoryProject): boolean {
        return !!(p.story || p.designs || p.assets || p.scenes || p.videoPrompt);
    }

    /** Duración legible: 90 → '90 s', 120 → '2 min'. */
    durationLabel(seconds: number): string {
        if (seconds >= 60 && seconds % 60 === 0) {
            const m = seconds / 60;
            return m + ' min' + (m === 1 ? '' : 's');
        }
        return seconds + ' s';
    }

    /** Nº de escenas implícito: duración total / duración media por escena. */
    impliedSceneCount(p: VideoStoryProject): number {
        if (!p.videoDurationSeconds) return 5;
        return Math.max(2, Math.min(20, Math.round(p.videoDurationSeconds / this.sceneAvgSeconds)));
    }

    /** Descarga el storyboard completo en un solo documento markdown. */
    downloadStoryboard(id: string, title: string) {
        const link = document.createElement('a');
        link.href = `/api/video-story-projects/${id}/storyboard`;
        link.download = 'storyboard';
        document.body.appendChild(link);
        link.click();
        link.remove();
        this.messageService.add({
            severity: 'info',
            summary: 'Storyboard',
            detail: `Descargando el storyboard de «${title}»…`,
        });
    }

    // ═══════════════ Cronología del proyecto (trazas) ═══════════════

    /** Cronología del proyecto activo: un evento por intento de paso. */
    loadTimeline(): void {
        const p = this.project();
        if (!p) {
            this.wizardTimeline = [];
            return;
        }
        this.timelineLoading = true;
        this.client.timeline(p.id).subscribe({
            next: (events) => {
                this.wizardTimeline = events;
                this.timelineLoading = false;
                this.cdr.markForCheck();
            },
            error: () => {
                // La cronología es un extra de monitoreo: degrada en silencio.
                this.timelineLoading = false;
            },
        });
    }

    traceStepLabel(step: string): string {
        switch (step) {
            case 'STORY':
                return '1. Historia';
            case 'DESIGNS':
                return '2. Diseños';
            case 'ASSETS':
                return '3. Assets';
            case 'SCENES':
                return '4. Escenas';
            case 'VIDEO':
                return '5. Plantilla MASTER';
            default:
                return step;
        }
    }

    traceStatusLabel(status: string): string {
        switch (status) {
            case 'STARTED':
                return 'En curso';
            case 'COMPLETED':
                return 'Completado';
            case 'FAILED':
                return 'Fallido';
            case 'SKIPPED':
                return 'Omitido';
            case 'CANCELLED':
                return 'Cancelado';
            default:
                return status;
        }
    }

    traceStatusSeverity(
        status: string,
    ): 'info' | 'success' | 'danger' | 'warn' | 'secondary' {
        switch (status) {
            case 'STARTED':
                return 'info';
            case 'COMPLETED':
                return 'success';
            case 'FAILED':
                return 'danger';
            case 'SKIPPED':
                return 'secondary';
            case 'CANCELLED':
                return 'warn';
            default:
                return 'secondary';
        }
    }

    /** Duración legible de un intento: 45 s / 2.3 min. */
    traceDuration(ms: number | null): string {
        if (ms == null) {
            return '—';
        }
        if (ms < 60_000) {
            return (ms / 1000).toFixed(1) + ' s';
        }
        return (ms / 60_000).toFixed(1) + ' min';
    }

    /**
     * Logs en vivo del proyecto: su traza MDC ES el id del proyecto, así
     * que ?trace= separa TODOS los pasos de este proyecto (incluida la
     * llamada del paso 1) de cualquier otro proceso.
     */
    viewProjectLogs(project: VideoStoryProject): void {
        this.router.navigate(['/logs'], {
            queryParams: {
                trace: project.id,
                name: `Asistente — ${project.title}`,
            },
        });
    }

    /**
     * REANUDA un proyecto de la biblioteca: lo carga en el asistente con
     * los formularios pre-rellenos para continuar los pasos pendientes.
     */
    continueProject(summary: VideoStoryProjectSummary) {
        this.client.get(summary.id).subscribe({
            next: (project) => {
                this.project.set(project);
                this.loadTimeline();
                // Prefill de los formularios de los pasos con lo ya configurado.
                this.designStyle = project.designStyle ?? this.designStyle;
                this.designCharacterCount = project.designCharacterCount ?? this.designCharacterCount;
                this.assetsAspectRatio = project.assetsAspectRatio ?? this.assetsAspectRatio;
                this.speakerFirst = project.sceneSpeaker1 ?? null;
                this.speakerSecond = project.sceneSpeaker2 ?? null;
                this.sceneAvgSeconds = project.sceneAvgSeconds ?? this.sceneAvgSeconds;
                this.refreshSceneOptions(project.scenes);
                this.refreshCharacterNames(project);
                this.referenceMode = 'none';
                this.referenceUrl = '';
                this.referenceSearch = '';
                this.referenceFileContent = null;
                this.referenceFileName = null;
                this.messageService.add({
                    severity: 'success',
                    summary: 'Proyecto cargado',
                    detail: `«${project.title}» — continúa en el paso ${this.nextStepLabel(project)}.`,
                });
                this.cdr.markForCheck();
            },
            error: () => {
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo cargar el proyecto.',
                });
            },
        });
    }

    /** El siguiente paso pendiente del proyecto cargado (para el toast). */
    private nextStepLabel(p: VideoStoryProject): number {
        if (!p.story) return 1;
        if (!p.designs) return 2;
        if (!p.assets) return 3;
        if (!p.scenes) return 4;
        return 5;
    }

    copyText(text: string, label: string) {
        navigator.clipboard.writeText(text).then(
            () => {
                this.messageService.add({
                    severity: 'success',
                    summary: 'Copiado',
                    detail: `${label} están en el portapapeles.`,
                });
            },
            () => {
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo copiar.',
                });
            },
        );
    }
}
