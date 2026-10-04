import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TagModule } from 'primeng/tag';
import { DashboardStats } from '../dashboard.service';

/** Etiqueta en español de cada paso del pipeline. */
const STEP_LABELS: Record<string, string> = {
    EXTRACT: 'Extracción',
    SPLIT: 'División',
    TRANSLATE: 'Traducción',
    AUDIO: 'Audio',
    FINALIZE: 'Finalización',
};

/**
 * Panel de procesos activos: libros dentro del pipeline en este momento
 * y estado de la sincronización del catálogo de novelas.
 */
@Component({
    selector: 'app-processes-widget',
    imports: [CommonModule, TagModule],
    template: `<div class="card mb-8!">
        <div class="font-semibold text-xl mb-4">Procesos en ejecución</div>

        <span class="block text-muted-color font-medium mb-2"
            >Pipeline de libros</span
        >
        @if (stats(); as s) {
            @if (s.processes.running.length > 0) {
                <ul class="list-none p-0 m-0">
                    @for (
                        process of s.processes.running;
                        track process.bookId
                    ) {
                        <li
                            class="flex items-center justify-between py-3 border-b border-surface"
                        >
                            <div class="flex items-center gap-3">
                                <i
                                    class="pi pi-spin pi-spinner text-primary"
                                ></i>
                                <div>
                                    <span class="font-medium block">{{
                                        process.name || process.bookId
                                    }}</span>
                                    <span class="text-muted-color text-sm">{{
                                        process.bookId
                                    }}</span>
                                </div>
                            </div>
                            <p-tag
                                [value]="stepLabel(process.currentStep)"
                                severity="info"
                            />
                        </li>
                    }
                </ul>
            } @else {
                <div class="text-muted-color py-3">
                    Ningún libro se está procesando ahora.
                </div>
            }

            <span class="block text-muted-color font-medium mt-4 mb-2"
                >Sincronización del catálogo</span
            >
            <div
                class="flex items-center justify-between py-3 border-t border-surface"
            >
                <div class="flex items-center gap-3">
                    <i class="pi pi-cloud-download text-primary"></i>
                    <div>
                        <span class="font-medium block">{{
                            syncLabel(s.processes.catalogSync.state)
                        }}</span>
                        @if (s.processes.catalogSync.finishedAt) {
                            <span class="text-muted-color text-sm"
                                >Última sync:
                                {{
                                    s.processes.catalogSync.finishedAt
                                        | date: 'dd/MM/yyyy HH:mm'
                                }}</span
                            >
                        } @else if (s.processes.catalogSync.startedAt) {
                            <span class="text-muted-color text-sm"
                                >Iniciada:
                                {{
                                    s.processes.catalogSync.startedAt
                                        | date: 'dd/MM/yyyy HH:mm'
                                }}</span
                            >
                        } @else {
                            <span class="text-muted-color text-sm"
                                >Sin ejecuciones registradas</span
                            >
                        }
                    </div>
                </div>
                <p-tag
                    [value]="s.processes.catalogSync.state"
                    [severity]="syncSeverity(s.processes.catalogSync.state)"
                />
            </div>
            @if (s.processes.catalogSync.lastError) {
                <div class="text-red-500 text-sm mt-2">
                    {{ s.processes.catalogSync.lastError }}
                </div>
            }
        }
    </div>`,
})
export class ProcessesWidget {
    stats = input<DashboardStats | null>(null);

    stepLabel(step: string | null): string {
        return step ? (STEP_LABELS[step] ?? step) : 'En cola';
    }

    syncLabel(state: string): string {
        switch (state) {
            case 'RUNNING':
                return 'Sincronización en curso';
            case 'COMPLETED':
                return 'Catálogo sincronizado';
            case 'FAILED':
                return 'Sincronización fallida';
            case 'CANCELLED':
                return 'Sincronización cancelada';
            default:
                return 'Sincronización inactiva';
        }
    }

    syncSeverity(
        state: string,
    ): 'info' | 'success' | 'danger' | 'warn' | 'secondary' {
        switch (state) {
            case 'RUNNING':
                return 'info';
            case 'COMPLETED':
                return 'success';
            case 'FAILED':
                return 'danger';
            case 'CANCELLED':
                return 'warn';
            default:
                return 'secondary';
        }
    }
}
