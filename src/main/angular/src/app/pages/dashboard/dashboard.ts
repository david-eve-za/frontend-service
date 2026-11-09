import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { Subscription, timer } from 'rxjs';
import { DashboardService, DashboardStats } from './dashboard.service';
import { SystemStatusWidget } from './components/systemstatuswidget';
import { PipelineChartWidget } from './components/pipelinechartwidget';
import { StatusChartWidget } from './components/statuschartwidget';
import { ChunksProgressWidget } from './components/chunksprogresswidget';
import { ProcessesWidget } from './components/processeswidget';
import { ElscioneActivityWidget } from './components/elscioneactivitywidget';
import { WizardActivityWidget } from './components/wizardactivitywidget';

/** Frecuencia de sondeo de /api/dashboard/stats (30s en lugar de 15s). */
const REFRESH_INTERVAL_MS = 30000;
const MAX_BACKOFF_MS = 120000; // 2 minutos máximo

@Component({
    selector: 'app-dashboard',
    imports: [
        CommonModule,
        ButtonModule,
        SystemStatusWidget,
        PipelineChartWidget,
        StatusChartWidget,
        ChunksProgressWidget,
        ProcessesWidget,
        ElscioneActivityWidget,
        WizardActivityWidget,
    ],
    template: `
        <div class="flex items-center justify-between mb-4">
            <div>
                <h2
                    class="text-xl font-bold text-surface-900 dark:text-surface-0 m-0"
                >
                    Panel de estado
                </h2>
                <p class="text-muted-color text-sm mt-1">
                    Métricas en vivo de la base de datos, los procesos y el
                    pipeline de libros.
                </p>
            </div>
            <div class="flex items-center gap-3">
                @if (lastUpdated(); as date) {
                    <span class="text-muted-color text-sm hidden md:block"
                        >Actualizado {{ date | date: 'HH:mm:ss' }}</span
                    >
                }
                <p-button
                    icon="pi pi-refresh"
                    label="Actualizar"
                    severity="secondary"
                    [outlined]="true"
                    [loading]="loading()"
                    (click)="refresh()"
                />
            </div>
        </div>

        @if (error(); as message) {
            <div class="card mb-4 border-l-4 border-red-500">
                <div class="flex items-center gap-2 text-red-500">
                    <i class="pi pi-exclamation-triangle"></i>
                    <span
                        >No se pudieron cargar las métricas: {{ message }}</span
                    >
                </div>
            </div>
        }

        <div class="grid grid-cols-12 gap-8">
            <app-system-status-widget class="contents" [stats]="stats()" />
            <div class="col-span-12 xl:col-span-7">
                <app-pipeline-chart-widget [stats]="stats()" />
            </div>
            <div class="col-span-12 xl:col-span-5">
                <app-status-chart-widget [stats]="stats()" />
            </div>
            <div class="col-span-12 xl:col-span-5">
                <app-chunks-progress-widget [stats]="stats()" />
            </div>
            <div class="col-span-12 xl:col-span-7">
                <app-processes-widget
                    [stats]="stats()"
                    (processesActionPerformed)="refresh()"
                />
            </div>
            <div class="col-span-12">
                <app-elscione-activity-widget [stats]="stats()" />
            </div>
            <div class="col-span-12">
                <app-wizard-activity-widget [stats]="stats()" />
            </div>
        </div>
    `,
})
export class Dashboard implements OnInit, OnDestroy {
    private readonly dashboardService = inject(DashboardService);
    private pollingTimeout?: number;
    private consecutiveErrors = 0;
    private isPolling = false;

    stats = signal<DashboardStats | null>(null);
    loading = signal(false);
    error = signal<string | null>(null);
    lastUpdated = signal<Date | null>(null);

    ngOnInit() {
        this.startPolling();
    }

    ngOnDestroy() {
        this.stopPolling();
    }

    private startPolling(): void {
        this.isPolling = true;
        const poll = () => {
            if (!this.isPolling || document.hidden) {
                // Si la pestaña no está visible, esperar y volver a comprobar
                this.pollingTimeout = window.setTimeout(poll, 5000);
                return;
            }
            
            this.refresh()
                .then(() => {
                    // Éxito: resetear backoff
                    this.consecutiveErrors = 0;
                    if (this.isPolling) {
                        this.pollingTimeout = window.setTimeout(poll, REFRESH_INTERVAL_MS);
                    }
                })
                .catch(() => {
                    // Error: backoff exponencial
                    this.consecutiveErrors++;
                    const backoffMs = Math.min(REFRESH_INTERVAL_MS * Math.pow(2, this.consecutiveErrors - 1), MAX_BACKOFF_MS);
                    console.warn(`Dashboard stats poll failed, backing off to ${backoffMs}ms`);
                    if (this.isPolling) {
                        this.pollingTimeout = window.setTimeout(poll, backoffMs);
                    }
                });
        };
        
        poll();
    }

    private stopPolling(): void {
        this.isPolling = false;
        if (this.pollingTimeout) {
            clearTimeout(this.pollingTimeout);
            this.pollingTimeout = undefined;
        }
    }

    async refresh(): Promise<void> {
        this.loading.set(true);
        return new Promise((resolve) => {
            this.dashboardService.getStats().subscribe({
                next: (stats) => {
                    this.stats.set(stats);
                    this.lastUpdated.set(new Date());
                    this.error.set(null);
                    this.loading.set(false);
                    resolve();
                },
                error: (err) => {
                    this.error.set(err?.message ?? 'error desconocido');
                    this.loading.set(false);
                    resolve();
                },
            });
        });
    }
}
