import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SelectModule } from 'primeng/select';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';
import { RegexPatternTableComponent } from './regex-pattern-table.component';
import {
    AiProviderClientService,
    AiProviderState,
    AiProvider,
} from './ai-provider.service';

/** Opción del selector: etiqueta ES, valor nvidia|ollama. */
interface ProviderOption {
    label: string;
    value: AiProvider;
}

/**
 * Configuración del servicio: incluye el SELECTOR DE PROVEEDOR DE IA por
 * caso de uso — traducción de libros y Asistente de Historias pueden usar
 * el pool de NVIDIA (cloud con fallback local) o el pool 100% local de
 * Ollama (hy-mt2 especializado en traducción, gemma4/gpt-oss creativos).
 * El cambio aplica en caliente a la siguiente llamada y persiste entre
 * reinicios.
 */
@Component({
    selector: 'app-book-translator-settings',
    standalone: true,
    imports: [
        CommonModule,
        FormsModule,
        SelectModule,
        ButtonModule,
        ToastModule,
        TagModule,
        RegexPatternTableComponent,
    ],
    providers: [MessageService],
    template: `
        <p-toast [life]="12000"></p-toast>

        <div class="card mb-4">
            <div class="font-semibold text-xl mb-2">Proveedor de IA</div>
            <p class="text-muted-color text-sm mb-4">
                Elige qué pool de modelos usa cada parte del servicio. El cambio
                aplica <b>en caliente</b> (a la siguiente llamada, sin reiniciar)
                y queda guardado entre reinicios.
            </p>

            <div class="grid grid-cols-12 gap-4">
                <div class="col-span-12 md:col-span-6">
                    <label class="block text-sm font-medium mb-2">
                        Traducción de libros
                    </label>
                    <p-select
                        [options]="providerOptions"
                        [(ngModel)]="translationProvider"
                        optionLabel="label"
                        optionValue="value"
                        styleClass="w-full"
                        [showClear]="false"
                    />
                    <div class="mt-2 text-sm text-muted-color">
                        @if (translationProvider === 'ollama') {
                            Pool local:
                            <p-tag
                                *ngFor="let m of ollamaModels()"
                                [value]="m.replace('ollama:', '')"
                                severity="info"
                                styleClass="mr-1"
                            />
                        } @else {
                            Pool NVIDIA:
                            <p-tag
                                *ngFor="let m of nvidiaModels()"
                                [value]="m"
                                severity="info"
                                styleClass="mr-1"
                            />
                        }
                    </div>
                </div>

                <div class="col-span-12 md:col-span-6">
                    <label class="block text-sm font-medium mb-2">
                        Historias en Video (asistente)
                    </label>
                    <p-select
                        [options]="providerOptions"
                        [(ngModel)]="storyProvider"
                        optionLabel="label"
                        optionValue="value"
                        styleClass="w-full"
                        [showClear]="false"
                    />
                    <div class="mt-2 text-sm text-muted-color">
                        @if (storyProvider === 'ollama') {
                            Pool local:
                            <p-tag
                                *ngFor="let m of ollamaModels()"
                                [value]="m.replace('ollama:', '')"
                                severity="info"
                                styleClass="mr-1"
                            />
                        } @else {
                            Pool NVIDIA:
                            <p-tag
                                *ngFor="let m of nvidiaModels()"
                                [value]="m"
                                severity="info"
                                styleClass="mr-1"
                            />
                        }
                    </div>
                </div>
            </div>

            <div class="mt-4">
                <p-button
                    label="Guardar selección"
                    icon="pi pi-check"
                    [loading]="saving"
                    [disabled]="!changed()"
                    (onClick)="save()"
                />
            </div>
        </div>

        <div class="card mb-4">
            <div class="flex flex-col sm:flex-row sm:items-center sm:justify-content-between gap-4 p-4 bg-blue-50 border-l-4 border-blue-500 rounded-r">
                <div>
                    <h2 class="text-xl font-bold text-gray-800">Herramientas de Desarrollo</h2>
                    <p class="text-sm text-gray-600 mt-1">Accesos rápidos a utilidades internas del servicio</p>
                </div>
                <div class="flex gap-2">
                    <p-button
                        label="Abrir Consola H2"
                        icon="pi pi-database"
                        (click)="openH2Console()"
                        styleClass="p-button-primary">
                    </p-button>
                </div>
            </div>
        </div>
        <app-regex-pattern-table></app-regex-pattern-table>
    `,
})
export class BookTranslatorSettings {
    private readonly client = inject(AiProviderClientService);
    private readonly messageService = inject(MessageService);

    /** Estado cargado del backend. */
    readonly nvidiaModels = signal<string[]>([]);
    readonly ollamaModels = signal<string[]>([]);
    readonly loading = signal(false);
    saving = false;

    translationProvider: AiProvider = 'nvidia';
    storyProvider: AiProvider = 'nvidia';

    /** Copia del estado persistido, para habilitar Guardar solo si cambió. */
    private savedTranslation: AiProvider = 'nvidia';
    private savedStory: AiProvider = 'nvidia';

    readonly providerOptions: ProviderOption[] = [
        {
            label: 'NVIDIA (nube + fallback local)',
            value: 'nvidia',
        },
        {
            label: 'Ollama (100% local)',
            value: 'ollama',
        },
    ];

    ngOnInit() {
        this.load();
    }

    load() {
        this.loading.set(true);
        this.client.load().subscribe({
            next: (state: AiProviderState) => this.applyState(state),
            error: () => {
                this.loading.set(false);
                this.messageService.add({
                    sticky: true, severity: 'error',
                    summary: 'Error',
                    detail: 'No se pudo cargar la selección de proveedores.',
                });
            },
        });
    }

    private applyState(state: AiProviderState) {
        this.translationProvider = state.translationProvider;
        this.storyProvider = state.storyProvider;
        this.savedTranslation = state.translationProvider;
        this.savedStory = state.storyProvider;
        this.nvidiaModels.set(state.nvidiaModels ?? []);
        this.ollamaModels.set(state.ollamaModels ?? []);
        this.loading.set(false);
    }

    changed(): boolean {
        return (
            this.translationProvider !== this.savedTranslation ||
            this.storyProvider !== this.savedStory
        );
    }

    save() {
        if (!this.changed() || this.saving) return;
        this.saving = true;
        this.client
            .update({
                translationProvider: this.translationProvider,
                storyProvider: this.storyProvider,
            })
            .subscribe({
                next: (state) => {
                    this.saving = false;
                    this.applyState(state);
                    this.messageService.add({
                        severity: 'success',
                        summary: 'Proveedores actualizados',
                        detail:
                            'Traducción: ' +
                            (state.translationProvider === 'ollama'
                                ? 'Ollama (local)'
                                : 'NVIDIA') +
                            ' · Historias: ' +
                            (state.storyProvider === 'ollama'
                                ? 'Ollama (local)'
                                : 'NVIDIA'),
                    });
                },
                error: () => {
                    this.saving = false;
                    this.messageService.add({
                        sticky: true, severity: 'error',
                        summary: 'Error',
                        detail: 'No se pudo actualizar la selección.',
                    });
                },
            });
    }

    openH2Console(): void {
        window.open('/h2-console', '_blank');
    }
}
