import {
    Component,
    ChangeDetectorRef,
    inject,
    signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SelectModule } from 'primeng/select';
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
} from './story-wizard.service';

/** Opción de los p-select: etiqueta ES, valor EN listo para las plantillas. */
interface SelectOption {
    label: string;
    value: string;
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
                    Rellena la plantilla narrativa; la IA escribirá la historia
                    completa con hook, estructura, personajes y cliffhanger.
                </p>

                <div class="grid grid-cols-12 gap-4">
                    <div class="col-span-12 md:col-span-6">
                        <label class="block text-sm font-medium mb-2">Título *</label>
                        <input
                            pInputText
                            type="text"
                            class="w-full"
                            placeholder="Nombre interno de la historia…"
                            [(ngModel)]="title"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Género *</label>
                        <p-select
                            [options]="genreOptions"
                            [(ngModel)]="genre"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="Elige el género"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Subgénero</label>
                        <input
                            pInputText
                            type="text"
                            class="w-full"
                            placeholder="P. ej. slow burn office romance…"
                            [(ngModel)]="subgenre"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Audiencia *</label>
                        <p-select
                            [options]="audienceOptions"
                            [(ngModel)]="targetAudience"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="¿Para quién?"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">País / cultura</label>
                        <input
                            pInputText
                            type="text"
                            class="w-full"
                            placeholder="P. ej. Japón…"
                            [(ngModel)]="countryCulture"
                        />
                    </div>
                    <div class="col-span-12 md:col-span-6">
                        <label class="block text-sm font-medium mb-2">Ubicación preferida</label>
                        <input
                            pInputText
                            type="text"
                            class="w-full"
                            placeholder="Ciudad o tipo de lugar…"
                            [(ngModel)]="location"
                        />
                    </div>
                    <div class="col-span-12">
                        <label class="block text-sm font-medium mb-2">
                            Temas, conflictos y relaciones
                        </label>
                        <textarea
                            pInputTextarea
                            rows="2"
                            class="w-full"
                            placeholder="Ambición, celos, un secreto del pasado… (opcional)"
                            [(ngModel)]="themes"
                        ></textarea>
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Tono emocional *</label>
                        <p-select
                            [options]="toneOptions"
                            [(ngModel)]="tone"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="¿Cómo debe sentirse?"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Estilo visual</label>
                        <p-select
                            [options]="visualStyleOptions"
                            [(ngModel)]="visualStyle"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="Formato de animación"
                            styleClass="w-full"
                            [showClear]="true"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Realismo</label>
                        <p-select
                            [options]="realismOptions"
                            [(ngModel)]="realismLevel"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="Realismo"
                            styleClass="w-full"
                            [showClear]="true"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Apto para familias</label>
                        <p-select
                            [options]="familyFriendlyOptions"
                            [(ngModel)]="familyFriendly"
                            optionLabel="label"
                            optionValue="value"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                    <div class="col-span-12 md:col-span-4">
                        <label class="block text-sm font-medium mb-2">Requisitos del personaje 1</label>
                        <textarea
                            pInputTextarea
                            rows="2"
                            class="w-full"
                            placeholder="Edad, personalidad, lo que oculta… (opcional)"
                            [(ngModel)]="character1Requirements"
                        ></textarea>
                    </div>
                    <div class="col-span-12 md:col-span-4">
                        <label class="block text-sm font-medium mb-2">Requisitos del personaje 2</label>
                        <textarea
                            pInputTextarea
                            rows="2"
                            class="w-full"
                            placeholder="Edad, personalidad, lo que oculta… (opcional)"
                            [(ngModel)]="character2Requirements"
                        ></textarea>
                    </div>
                    <div class="col-span-12 md:col-span-4">
                        <label class="block text-sm font-medium mb-2">Otros personajes</label>
                        <textarea
                            pInputTextarea
                            rows="2"
                            class="w-full"
                            placeholder="Requisitos del resto del reparto (opcional)"
                            [(ngModel)]="otherCharacterRequirements"
                        ></textarea>
                    </div>
                    <div class="col-span-12 md:col-span-6">
                        <label class="block text-sm font-medium mb-2">
                            Conflicto emocional principal *
                        </label>
                        <textarea
                            pInputTextarea
                            rows="2"
                            class="w-full"
                            placeholder="Lo que confrontan en el clímax…"
                            [(ngModel)]="mainEmotionalConflict"
                        ></textarea>
                    </div>
                    <div class="col-span-12 md:col-span-6">
                        <label class="block text-sm font-medium mb-2">Elemento sin resolver *</label>
                        <textarea
                            pInputTextarea
                            rows="2"
                            class="w-full"
                            placeholder="Lo que NO se resuelve al final…"
                            [(ngModel)]="unresolvedElement"
                        ></textarea>
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Tipo de final</label>
                        <p-select
                            [options]="endingTypeOptions"
                            [(ngModel)]="endingType"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="Cómo termina"
                            styleClass="w-full"
                            [showClear]="true"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Nº de personajes</label>
                        <p-select
                            [options]="characterCountOptions"
                            [(ngModel)]="characterCount"
                            optionLabel="label"
                            optionValue="value"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Idioma del diálogo *</label>
                        <p-select
                            [options]="languageOptions"
                            [(ngModel)]="dialogueLanguage"
                            optionLabel="label"
                            optionValue="value"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Duración del video *</label>
                        <p-select
                            [options]="videoDurationOptions"
                            [(ngModel)]="videoDurationSeconds"
                            optionLabel="label"
                            optionValue="value"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                    <div class="col-span-4 md:col-span-2">
                        <label class="block text-sm font-medium mb-2">Ritmo inicio %</label>
                        <input
                            pInputText
                            type="number"
                            min="0"
                            max="100"
                            class="w-full"
                            [(ngModel)]="pacingFirstPercent"
                        />
                    </div>
                    <div class="col-span-4 md:col-span-2">
                        <label class="block text-sm font-medium mb-2">Ritmo medio %</label>
                        <input
                            pInputText
                            type="number"
                            min="0"
                            max="100"
                            class="w-full"
                            [(ngModel)]="pacingMiddlePercent"
                        />
                    </div>
                    <div class="col-span-4 md:col-span-2">
                        <label class="block text-sm font-medium mb-2">Ritmo final %</label>
                        <input
                            pInputText
                            type="number"
                            min="0"
                            max="100"
                            class="w-full"
                            [(ngModel)]="pacingFinalPercent"
                        />
                    </div>
                    <div class="col-span-6 md:col-span-3">
                        <label class="block text-sm font-medium mb-2">Plataforma / formato *</label>
                        <p-select
                            [options]="platformOptions"
                            [(ngModel)]="platform"
                            optionLabel="label"
                            optionValue="value"
                            placeholder="¿Dónde se publicará?"
                            styleClass="w-full"
                            [showClear]="false"
                        />
                    </div>
                </div>

                <div class="mt-4">
                    <p-button
                        label="Generar Historia con IA"
                        icon="pi pi-magic"
                        [disabled]="!storyFormValid() || generatingStory"
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
                            (onClick)="copyText(p.story!, 'La historia')"
                        />
                    </div>
                    <textarea pInputTextarea rows="7" class="w-full" readonly [value]="p.story"></textarea>
                }

                <!-- ═════════ PASO 2: DISEÑOS ═════════ -->
                @if (p.story) {
                    <div class="font-semibold text-lg mt-6 mb-3">Paso 2 — Diseño de Personajes</div>
                    <p class="text-muted-color text-sm mb-3">
                        La IA diseñará a cada personaje basándose en la historia
                        generada, con consistencia visual entre escenas.
                    </p>
                    <div class="grid grid-cols-12 gap-4">
                        <div class="col-span-12 md:col-span-6">
                            <label class="block text-sm font-medium mb-2">Estilo de animación 3D *</label>
                            <p-select
                                [options]="animationStyleOptions"
                                [(ngModel)]="designStyle"
                                optionLabel="label"
                                optionValue="value"
                                placeholder="Elige el estilo visual"
                                styleClass="w-full"
                                [showClear]="false"
                            />
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label class="block text-sm font-medium mb-2">Nº de personajes</label>
                            <p-select
                                [options]="designCountOptions"
                                [(ngModel)]="designCharacterCount"
                                optionLabel="label"
                                optionValue="value"
                                styleClass="w-full"
                                [showClear]="false"
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
                            (onClick)="copyText(p.designs!, 'Los diseños')"
                        />
                    </div>
                    <textarea pInputTextarea rows="7" class="w-full" readonly [value]="p.designs"></textarea>
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
                        <div class="col-span-12 md:col-span-4">
                            <label class="block text-sm font-medium mb-2">Estilo de animación 3D</label>
                            <p-select
                                [options]="animationStyleOptions"
                                [(ngModel)]="assetsStyle"
                                optionLabel="label"
                                optionValue="value"
                                placeholder="Vacío hereda el del paso 2"
                                styleClass="w-full"
                                [showClear]="true"
                            />
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label class="block text-sm font-medium mb-2">Audiencia destino</label>
                            <input
                                pInputText
                                type="text"
                                class="w-full"
                                placeholder="Vacío: se deriva de la historia"
                                [(ngModel)]="assetsAudience"
                            />
                        </div>
                        <div class="col-span-6 md:col-span-2">
                            <label class="block text-sm font-medium mb-2">Aspecto</label>
                            <p-select
                                [options]="aspectRatioOptions"
                                [(ngModel)]="assetsAspectRatio"
                                optionLabel="label"
                                optionValue="value"
                                styleClass="w-full"
                                [showClear]="false"
                            />
                        </div>
                        <div class="col-span-12 md:col-span-3">
                            <label class="block text-sm font-medium mb-2">Paleta de colores</label>
                            <input
                                pInputText
                                type="text"
                                class="w-full"
                                placeholder="Vacío: se deriva de la historia"
                                [(ngModel)]="assetsPalette"
                            />
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
                            (onClick)="copyText(p.assets!, 'Los assets')"
                        />
                    </div>
                    <textarea pInputTextarea rows="8" class="w-full" readonly [value]="p.assets"></textarea>
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
                        <div class="col-span-6 md:col-span-3">
                            <label class="block text-sm font-medium mb-2">Duración media por escena</label>
                            <p-select
                                [options]="sceneAvgOptions"
                                [(ngModel)]="sceneAvgSeconds"
                                optionLabel="label"
                                optionValue="value"
                                styleClass="w-full"
                                [showClear]="false"
                            />
                        </div>
                        <div class="col-span-12 md:col-span-9 flex items-end">
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
                            <label class="block text-sm font-medium mb-2">Habla primero</label>
                            <input
                                pInputText
                                type="text"
                                class="w-full"
                                placeholder="Nombre del personaje…"
                                [(ngModel)]="speakerFirst"
                            />
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label class="block text-sm font-medium mb-2">Habla segundo</label>
                            <input
                                pInputText
                                type="text"
                                class="w-full"
                                placeholder="Nombre del personaje…"
                                [(ngModel)]="speakerSecond"
                            />
                        </div>
                        <div class="col-span-6 md:col-span-2">
                            <label class="block text-sm font-medium mb-2">Pronunciación</label>
                            <p-select
                                [options]="languageOptions"
                                [(ngModel)]="sceneLanguage"
                                optionLabel="label"
                                optionValue="value"
                                styleClass="w-full"
                                [showClear]="false"
                            />
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
                            (onClick)="copyText(p.scenes!, 'Las escenas')"
                        />
                    </div>
                    <textarea pInputTextarea rows="8" class="w-full" readonly [value]="p.scenes"></textarea>
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
                            <label class="block text-sm font-medium mb-2">Escena objetivo</label>
                            @if (parsedSceneOptions.length > 0) {
                                <p-select
                                    [options]="parsedSceneOptions"
                                    [(ngModel)]="videoSceneIndex"
                                    optionLabel="label"
                                    optionValue="value"
                                    styleClass="w-full"
                                    [showClear]="false"
                                />
                            } @else {
                                <input
                                    pInputText
                                    type="number"
                                    min="1"
                                    class="w-full"
                                    placeholder="Nº de escena (1, 2, 3…)"
                                    [(ngModel)]="videoSceneIndex"
                                />
                            }
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label class="block text-sm font-medium mb-2">Estilo de animación 3D</label>
                            <p-select
                                [options]="animationStyleOptions"
                                [(ngModel)]="videoStyle"
                                optionLabel="label"
                                optionValue="value"
                                placeholder="Vacío usa el estilo del paso 2"
                                styleClass="w-full"
                                [showClear]="true"
                            />
                        </div>
                        <div class="col-span-6 md:col-span-3">
                            <label class="block text-sm font-medium mb-2">Referencia (opcional)</label>
                            <p-select
                                [options]="referenceModeOptions"
                                [(ngModel)]="referenceMode"
                                optionLabel="label"
                                optionValue="value"
                                styleClass="w-full"
                                [showClear]="false"
                            />
                        </div>
                        @switch (referenceMode) {
                            @case ('file') {
                                <div class="col-span-6 md:col-span-3">
                                    <label class="block text-sm font-medium mb-2">Archivo de referencia</label>
                                    <input
                                        type="file"
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
                                    <label class="block text-sm font-medium mb-2">URL de referencia</label>
                                    <input
                                        pInputText
                                        type="url"
                                        class="w-full"
                                        placeholder="https://…"
                                        [(ngModel)]="referenceUrl"
                                    />
                                </div>
                            }
                            @case ('search') {
                                <div class="col-span-6 md:col-span-3">
                                    <label class="block text-sm font-medium mb-2">Buscar en la web</label>
                                    <input
                                        pInputText
                                        type="text"
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
                            (onClick)="copyText(p.videoPrompt!, 'La plantilla de video')"
                        />
                    </div>
                    <textarea
                        pInputTextarea
                        rows="8"
                        class="w-full"
                        readonly
                        [value]="p.videoPrompt"
                    ></textarea>
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
                                pTooltip="Continuar este proyecto"
                                tooltipPosition="left"
                                (onClick)="continueProject(s)"
                            />
                            <p-button
                                icon="pi pi-eye"
                                severity="secondary"
                                [text]="true"
                                (onClick)="openProject(s)"
                            />
                            <p-button
                                icon="pi pi-download"
                                severity="secondary"
                                [text]="true"
                                pTooltip="Descargar storyboard"
                                tooltipPosition="left"
                                (onClick)="downloadStoryboard(s.id, s.title)"
                            />
                            <p-button
                                icon="pi pi-trash"
                                severity="danger"
                                [text]="true"
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
                    <textarea pInputTextarea rows="5" class="w-full mb-3" readonly [value]="d.story"></textarea>
                }
                @if (d.designs) {
                    <div class="font-semibold mb-2">Diseños de personajes</div>
                    <textarea pInputTextarea rows="5" class="w-full mb-3" readonly [value]="d.designs"></textarea>
                }
                @if (d.assets) {
                    <div class="font-semibold mb-2">Biblioteca de assets</div>
                    <textarea pInputTextarea rows="5" class="w-full mb-3" readonly [value]="d.assets"></textarea>
                }
                @if (d.scenes) {
                    <div class="font-semibold mb-2">Escenas</div>
                    <textarea pInputTextarea rows="6" class="w-full mb-3" readonly [value]="d.scenes"></textarea>
                }
                @if (d.videoPrompt) {
                    <div class="font-semibold mb-2">Plantilla de video</div>
                    <textarea pInputTextarea rows="6" class="w-full" readonly [value]="d.videoPrompt"></textarea>
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

    // ── Paso 1: formulario de historia (mismos campos que el Creador). ──
    title = '';
    genre: string | null = null;
    subgenre = '';
    targetAudience: string | null = null;
    countryCulture = '';
    location = '';
    themes = '';
    tone: string | null = null;
    visualStyle: string | null = null;
    realismLevel: string | null = null;
    familyFriendly = 'YES';
    character1Requirements = '';
    character2Requirements = '';
    otherCharacterRequirements = '';
    mainEmotionalConflict = '';
    unresolvedElement = '';
    endingType: string | null = null;
    characterCount = 2;
    dialogueLanguage = 'Spanish';
    videoDurationSeconds = 60;
    pacingFirstPercent = 30;
    pacingMiddlePercent = 50;
    pacingFinalPercent = 20;
    platform: string | null = null;

    // ── Paso 2: diseños. ──
    designStyle: string | null = null;
    designCharacterCount = 4;

    // ── Paso 3: assets (todo opcional; el estilo hereda el del paso 2). ──
    generatingAssets = false;
    assetsStyle: string | null = null;
    assetsAudience = '';
    assetsAspectRatio = '16:9';
    assetsPalette = '';

    // ── Paso 4: escenas (nº derivado de la duración total del video). ──
    sceneAvgSeconds = 10;
    speakerFirst = '';
    speakerSecond = '';
    sceneLanguage = 'Spanish';

    // ── Paso 5: plantilla MASTER de video (cero interacción obligatoria). ──
    /** Índice 1-based de la escena objetivo (default: la primera). */
    videoSceneIndex = 1;
    videoStyle: string | null = null;
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

    readonly familyFriendlyOptions: SelectOption[] = [
        { label: 'Sí', value: 'YES' },
        { label: 'No', value: 'NO' },
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

    readonly platformOptions: SelectOption[] = [
        { label: 'YouTube Shorts', value: 'YouTube Shorts' },
        { label: 'TikTok', value: 'TikTok' },
        { label: 'Instagram Reels', value: 'Instagram Reels' },
        { label: 'YouTube', value: 'YouTube' },
        { label: 'Cortometraje', value: 'Short film' },
        { label: 'Demo de animación', value: 'Animation demo' },
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

    ngOnInit() {
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

    storyFormValid(): boolean {
        return (
            this.title.trim().length > 0 &&
            !!this.genre &&
            !!this.targetAudience &&
            !!this.tone &&
            this.mainEmotionalConflict.trim().length > 0 &&
            this.unresolvedElement.trim().length > 0 &&
            !!this.dialogueLanguage &&
            !!this.videoDurationSeconds &&
            !!this.platform
        );
    }

    generateStory() {
        if (!this.storyFormValid() || this.generatingStory) return;
        this.generatingStory = true;

        this.client
            .createWithStory({
                title: this.title.trim(),
                genre: this.genre!,
                subgenre: this.subgenre.trim() || null,
                targetAudience: this.targetAudience!,
                countryCulture: this.countryCulture.trim() || null,
                location: this.location.trim() || null,
                familyFriendly: this.familyFriendly,
                visualStyle: this.visualStyle,
                tone: this.tone!,
                realismLevel: this.realismLevel,
                character1Requirements: this.character1Requirements.trim() || null,
                character2Requirements: this.character2Requirements.trim() || null,
                otherCharacterRequirements: this.otherCharacterRequirements.trim() || null,
                mainEmotionalConflict: this.mainEmotionalConflict.trim(),
                unresolvedElement: this.unresolvedElement.trim(),
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
                    // El estilo de los assets (paso 3) por defecto hereda el del paso 2.
                    this.assetsStyle = project.designStyle ?? this.assetsStyle;
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Diseños generados',
                        detail: 'Continúa con el paso 3 cuando quieras.',
                    });
                    this.loadProjects();
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
                animationStyle: this.assetsStyle,
                targetAudience: this.assetsAudience.trim() || null,
                aspectRatio: this.assetsAspectRatio,
                colorPalette: this.assetsPalette.trim() || null,
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
                speakerFirst: this.speakerFirst.trim() || null,
                speakerSecond: this.speakerSecond.trim() || null,
                language: this.sceneLanguage,
            })
            .subscribe({
                next: (project) => {
                    this.generatingScenes = false;
                    this.project.set(project);
                    // El estilo del paso 5 por defecto es el del paso 2.
                    this.videoStyle = project.designStyle ?? this.videoStyle;
                    this.refreshSceneOptions(project.scenes);
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Escenas generadas',
                        detail: 'El paso 5 (plantilla MASTER de video) ya está disponible.',
                    });
                    this.loadProjects();
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

        // Cero interacción: sin escena elegida el backend usa la PRIMERA; la
        // referencia solo se envía en el modo activo (el backend admite UNA).
        this.client
            .generateVideo(p.id, {
                sceneIndex: this.videoSceneIndex,
                animationStyle: this.videoStyle,
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
        this.cdr.markForCheck();
    }

    // ═══════════════ Biblioteca ═══════════════

    resetWizard() {
        this.project.set(null);
        this.title = '';
        this.genre = null;
        this.subgenre = '';
        this.targetAudience = null;
        this.countryCulture = '';
        this.location = '';
        this.themes = '';
        this.tone = null;
        this.visualStyle = null;
        this.realismLevel = null;
        this.familyFriendly = 'YES';
        this.character1Requirements = '';
        this.character2Requirements = '';
        this.otherCharacterRequirements = '';
        this.mainEmotionalConflict = '';
        this.unresolvedElement = '';
        this.endingType = null;
        this.characterCount = 2;
        this.dialogueLanguage = 'Spanish';
        this.videoDurationSeconds = 60;
        this.pacingFirstPercent = 30;
        this.pacingMiddlePercent = 50;
        this.pacingFinalPercent = 20;
        this.platform = null;
        this.designStyle = null;
        this.designCharacterCount = 4;
        this.assetsStyle = null;
        this.assetsAudience = '';
        this.assetsAspectRatio = '16:9';
        this.assetsPalette = '';
        this.sceneAvgSeconds = 10;
        this.speakerFirst = '';
        this.speakerSecond = '';
        this.sceneLanguage = 'Spanish';
        this.videoSceneIndex = 1;
        this.parsedSceneOptions = [];
        this.videoStyle = null;
        this.referenceMode = 'none';
        this.referenceUrl = '';
        this.referenceSearch = '';
        this.referenceFileContent = null;
        this.referenceFileName = null;
        this.autoRunning = false;
        this.autoStep = '';
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

    /**
     * REANUDA un proyecto de la biblioteca: lo carga en el asistente con
     * los formularios pre-rellenos para continuar los pasos pendientes.
     */
    continueProject(summary: VideoStoryProjectSummary) {
        this.client.get(summary.id).subscribe({
            next: (project) => {
                this.project.set(project);
                // Prefill de los formularios de los pasos con lo ya configurado.
                this.designStyle = project.designStyle ?? this.designStyle;
                this.assetsStyle = project.designStyle ?? null;
                this.assetsAspectRatio = project.assetsAspectRatio ?? '16:9';
                this.sceneLanguage = project.sceneLanguage ?? 'Spanish';
                this.speakerFirst = project.sceneSpeaker1 ?? '';
                this.speakerSecond = project.sceneSpeaker2 ?? '';
                this.sceneAvgSeconds = project.sceneAvgSeconds ?? 10;
                this.videoStyle = project.designStyle ?? null;
                this.refreshSceneOptions(project.scenes);
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
