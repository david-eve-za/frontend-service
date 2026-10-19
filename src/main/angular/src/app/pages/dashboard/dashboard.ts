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

/** Frecuencia de sondeo de /api/dashboard/stats. */
const REFRESH_INTERVAL_MS = 15000;

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
    private subscription?: Subscription;

    stats = signal<DashboardStats | null>(null);
    loading = signal(false);
    error = signal<string | null>(null);
    lastUpdated = signal<Date | null>(null);

    ngOnInit() {
        // timer(0, ...) dispara la primera carga inmediata y luego sondea.
        this.subscription = timer(0, REFRESH_INTERVAL_MS).subscribe(() =>
            this.refresh(),
        );
    }

    ngOnDestroy() {
        this.subscription?.unsubscribe();
    }

    refresh() {
        this.loading.set(true);
        this.dashboardService.getStats().subscribe({
            next: (stats) => {
                this.stats.set(stats);
                this.lastUpdated.set(new Date());
                this.error.set(null);
                this.loading.set(false);
            },
            error: (err) => {
                this.error.set(err?.message ?? 'error desconocido');
                this.loading.set(false);
            },
        });
    }
}
