import { Component, inject, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TagModule } from 'primeng/tag';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { ConfirmationService } from 'primeng/api';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { Observable } from 'rxjs';
import { Router } from '@angular/router';
import {
    DashboardService,
    DashboardStats,
    BookProcess,
} from '../dashboard.service';

/** Etiqueta en español de cada paso del pipeline. */
const STEP_LABELS: Record<string, string> = {
    EXTRACT: 'Extracción',
    SPLIT: 'División',
    TRANSLATE: 'Traducción',
    AUDIO: 'Audio',
    FINALIZE: 'Finalización',
};

/**
 * Panel de procesos: cada libro con ciclo de vida activo con su estado
 * (Running / En espera / Detenido / Fallido) y acciones manuales —
 * pausar (checkpoint), detener (inmediato) y arrancar — más el estado y
 * control de la sincronización del catálogo de elscione.
 */
@Component({
    selector: 'app-processes-widget',
    imports: [
        CommonModule,
        TagModule,
        ButtonModule,
        TooltipModule,
        ConfirmDialogModule,
        ToastModule,
    ],
    providers: [ConfirmationService, MessageService],
    template: `<div class="card mb-8!">
        <div class="font-semibold text-xl mb-4">Procesos</div>

        <span class="block text-muted-color font-medium mb-2"
            >Pipeline de libros</span
        >
        @if (stats(); as s) {
            @if (s.processes.books.length > 0) {
                <ul class="list-none p-0 m-0">
                    @for (
                        process of s.processes.books;
                        track process.bookId
                    ) {
                        <li
                            class="flex items-center justify-between gap-3 py-3 border-b border-surface"
                        >
                            <div class="flex items-center gap-3 min-w-0">
                                <i
                                    [class]="
                                        process.running
                                            ? 'pi pi-spin pi-spinner text-primary'
                                            : statusIcon(process.status)
                                    "
                                ></i>
                                <div class="min-w-0">
                                    <span class="font-medium block truncate">{{
                                        process.name || process.bookId
                                    }}</span>
                                    <span
                                        class="text-muted-color text-sm block"
                                        >{{ stepLabel(process.currentStep) }}</span
                                    >
                                </div>
                            </div>
                            <div
                                class="flex items-center gap-2 shrink-0"
                            >
                                <p-button
                                    icon="pi pi-list"
                                    size="small"
                                    severity="secondary"
                                    [outlined]="true"
                                    [disabled]="busy()"
                                    pTooltip="Ver logs en vivo de este proceso"
                                    tooltipPosition="top"
                                    (click)="viewLogs(process)"
                                />
                                <p-tag
                                    [value]="statusLabel(process.status)"
                                    [severity]="
                                        statusSeverity(process.status)
                                    "
                                />
                                @if (process.running) {
                                    <p-button
                                        icon="pi pi-pause"
                                        label="Pausar"
                                        size="small"
                                        severity="warn"
                                        [outlined]="true"
                                        [disabled]="busy()"
                                        pTooltip="Termina el paso en curso y se detiene en el checkpoint"
                                        tooltipPosition="top"
                                        (click)="pause(process)"
                                    />
                                    <p-button
                                        icon="pi pi-stop-circle"
                                        label="Detener"
                                        size="small"
                                        severity="danger"
                                        [outlined]="true"
                                        [disabled]="busy()"
                                        pTooltip="Cancela el trabajo en vuelo y deja el libro detenido"
                                        tooltipPosition="top"
                                        (click)="stop(process)"
                                    />
                                } @else {
                                    <p-button
                                        icon="pi pi-play"
                                        [label]="
                                            process.status === 'STOPPED'
                                                ? 'Arrancar'
                                                : 'Reanudar'
                                        "
                                        size="small"
                                        severity="success"
                                        [outlined]="true"
                                        [disabled]="busy()"
                                        pTooltip="Arranca el pipeline desde el último checkpoint"
                                        tooltipPosition="top"
                                        (click)="start(process)"
                                    />
                                    @if (
                                        process.status === 'AWAITING' ||
                                        process.status === 'UPLOADED' ||
                                        process.status === 'FAILED'
                                    ) {
                                        <p-button
                                            icon="pi pi-stop-circle"
                                            label="Detener"
                                            size="small"
                                            severity="danger"
                                            [outlined]="true"
                                            [disabled]="busy()"
                                            pTooltip="Lo saca de la rotación: quedará detenido hasta que lo arranques"
                                            tooltipPosition="top"
                                            (click)="stop(process)"
                                        />
                                    }
                                }
                            </div>
                        </li>
                    }
                </ul>
            } @else {
                <div class="text-muted-color py-3">
                    Ningún libro con proceso activo.
                </div>
            }

            <span class="block text-muted-color font-medium mt-4 mb-2"
                >Sincronización del catálogo</span
            >
            <div
                class="flex items-center justify-between gap-3 py-3 border-t border-surface"
            >
                <div class="flex items-center gap-3 min-w-0">
                    <i class="pi pi-cloud-download text-primary"></i>
                    <div class="min-w-0">
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
                <div class="flex items-center gap-2 shrink-0">
                    <p-button
                        icon="pi pi-list"
                        size="small"
                        severity="secondary"
                        [outlined]="true"
                        [disabled]="busy()"
                        pTooltip="Ver logs de la sincronización en curso"
                        tooltipPosition="top"
                        (click)="viewSyncLogs()"
                    />
                    <p-tag
                        [value]="s.processes.catalogSync.state"
                        [severity]="
                            syncSeverity(s.processes.catalogSync.state)
                        "
                    />
                    @if (s.processes.catalogSync.state === 'RUNNING') {
                        <p-button
                            icon="pi pi-stop-circle"
                            label="Cancelar"
                            size="small"
                            severity="danger"
                            [outlined]="true"
                            [disabled]="busy()"
                            pTooltip="Cancela la sincronización actual (retoma donde quedó en la próxima)"
                            tooltipPosition="top"
                            (click)="cancelSync()"
                        />
                    } @else {
                        <p-button
                            icon="pi pi-play"
                            label="Arrancar"
                            size="small"
                            severity="success"
                            [outlined]="true"
                            [disabled]="busy()"
                            pTooltip="Lanza una sincronización del catálogo de elscione ahora"
                            tooltipPosition="top"
                            (click)="startSync()"
                        />
                    }
                </div>
            </div>
            @if (s.processes.catalogSync.lastError) {
                <div class="text-red-500 text-sm mt-2">
                    {{ s.processes.catalogSync.lastError }}
                </div>
            }
        }
        <p-confirmDialog />
        <p-toast position="top-right" />
    </div>`,
})
export class ProcessesWidget {
    private readonly dashboardService = inject(DashboardService);
    private readonly confirmationService = inject(ConfirmationService);
    private readonly messageService = inject(MessageService);
    private readonly router = inject(Router);

    stats = input<DashboardStats | null>(null);

    /** Notifica al dashboard que refresque las métricas tras una acción. */
    processesActionPerformed = output<void>();

    busy = signal(false);

    stepLabel(step: string | null): string {
        return step ? (STEP_LABELS[step] ?? step) : 'En cola';
    }

    statusLabel(status: string): string {
        switch (status) {
            case 'PROCESSING':
                return 'Running';
            case 'AWAITING':
            case 'UPLOADED':
                return 'En espera';
            case 'STOPPED':
                return 'Detenido';
            case 'FAILED':
                return 'Fallido';
            default:
                return status;
        }
    }

    statusSeverity(
        status: string,
    ): 'info' | 'success' | 'danger' | 'warn' | 'secondary' {
        switch (status) {
            case 'PROCESSING':
                return 'info';
            case 'AWAITING':
            case 'UPLOADED':
                return 'warn';
            case 'STOPPED':
                return 'danger';
            case 'FAILED':
                return 'danger';
            default:
                return 'secondary';
        }
    }

    statusIcon(status: string): string {
        switch (status) {
            case 'PROCESSING':
                return 'pi pi-cog text-primary';
            case 'AWAITING':
            case 'UPLOADED':
                return 'pi pi-pause-circle text-muted-color';
            case 'STOPPED':
                return 'pi pi-stop-circle text-red-400';
            case 'FAILED':
                return 'pi pi-exclamation-triangle text-red-400';
            default:
                return 'pi pi-circle-fill text-muted-color';
        }
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

    start(process: BookProcess): void {
        this.run(() => this.dashboardService.startBook(process.bookId));
    }

    pause(process: BookProcess): void {
        this.run(() => this.dashboardService.pauseBook(process.bookId));
    }

    stop(process: BookProcess): void {
        this.confirmationService.confirm({
            header: 'Detener proceso',
            message: `¿Detener «${
                process.name || process.bookId
            }»? Se cancela el trabajo en vuelo y el libro queda detenido hasta que lo arranques manualmente.`,
            acceptLabel: 'Detener',
            rejectLabel: 'Cancelar',
            acceptButtonStyleClass: 'p-button-danger',
            accept: () =>
                this.run(() => this.dashboardService.stopBook(process.bookId)),
        });
    }

    startSync(): void {
        this.run(() => this.dashboardService.startCatalogSync());
    }

    cancelSync(): void {
        this.run(() => this.dashboardService.cancelCatalogSync());
    }

    /**
     * Lleva al visor de logs en vivo con el filtro MDC del libro ya
     * aplicado: ?book= casa con el bookId de CADA evento del pipeline de
     * ese libro (corridas del orquestador y traducciones por chunk, cuyo
     * traceId es el chunk pero cuyo bookId siempre viaja).
     */
    viewLogs(process: BookProcess): void {
        this.router.navigate(['/logs'], {
            queryParams: {
                book: process.bookId,
                name: process.name ?? process.bookId,
            },
        });
    }

    /**
     * Logs de la sincronización del catálogo: sus eventos no llevan MDC
     * de libro y quedan FUERA del visor por la exclusión por defecto
     * (NovelsCatalogSync), así que el enlace filtra por texto y desactiva
     * la exclusión de entrada.
     */
    viewSyncLogs(): void {
        this.router.navigate(['/logs'], {
            queryParams: {
                text: 'NovelsCatalogSync',
                clearExclude: 1,
                name: 'Sincronización del catálogo',
            },
        });
    }

    /** Ejecuta una acción: bloquea los botones, toast del resultado y refresco. */
    private run(action: () => Observable<object>): void {
        this.busy.set(true);
        action().subscribe({
            next: () => {
                this.busy.set(false);
                this.messageService.add({
                    severity: 'success',
                    summary: 'Acción aplicada',
                    detail: 'El panel se actualizará en un momento.',
                    life: 2000,
                });
                this.processesActionPerformed.emit();
            },
            error: (err) => {
                this.busy.set(false);
                this.messageService.add({
                    severity: 'error',
                    summary: 'No se pudo aplicar la acción',
                    detail: err?.error?.error ?? err?.message ?? 'error',
                    life: 4000,
                });
            },
        });
    }
}
