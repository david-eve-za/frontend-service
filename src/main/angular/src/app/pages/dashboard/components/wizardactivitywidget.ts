import { Component, effect, inject, input, signal } from '@angular/core';
import { ChartModule } from 'primeng/chart';
import { LayoutService } from '@/app/layout/service/layout.service';
import { DashboardStats } from '../dashboard.service';

interface ChartDataset {
    label: string;
    backgroundColor: string;
    borderRadius: number;
    data: number[];
}

interface ChartData {
    labels: string[];
    datasets: ChartDataset[];
}

/**
 * Monitoreo del Asistente de Historias: proyectos creados y pasos
 * completados/fallidos por día, en la ventana de actividad que envía el
 * backend (14 días rellenados a cero, como el gráfico del catálogo). La
 * cabecera resume la biblioteca: proyectos, cadenas completas y pasos con
 * fallo acumulados.
 */
@Component({
    selector: 'app-wizard-activity-widget',
    imports: [ChartModule],
    template: `<div class="card mb-8!">
        <div class="flex items-center justify-between mb-4">
            <div class="font-semibold text-xl">
                Asistente de Historias — actividad por día
            </div>
            @if (stats(); as s) {
                <span class="text-muted-color text-sm"
                    >{{ s.wizard.totalProjects }} proyectos ·
                    {{ s.wizard.completedChains }} cadenas completas ·
                    {{ s.wizard.stepsFailed }} pasos fallidos</span
                >
            }
        </div>
        <p-chart
            type="bar"
            [data]="chartData()"
            [options]="chartOptions()"
            class="h-80"
        />
    </div>`,
})
export class WizardActivityWidget {
    layoutService = inject(LayoutService);

    stats = input<DashboardStats | null>(null);

    chartData = signal<ChartData | null>(null);

    chartOptions = signal<unknown>(null);

    constructor() {
        effect(() => {
            this.stats();
            this.layoutService.layoutConfig().darkTheme;
            setTimeout(() => this.initChart(), 150);
        });
    }

    initChart() {
        const stats = this.stats();
        if (!stats || !stats.wizard?.activity?.length) {
            this.chartData.set(null);
            return;
        }
        const documentStyle = getComputedStyle(document.documentElement);
        const textMutedColor = documentStyle.getPropertyValue(
            '--text-color-secondary',
        );
        const borderColor = documentStyle.getPropertyValue('--surface-border');

        // "2026-10-16" -> "16/10" para un eje compacto de 14 días.
        const labels = stats.wizard.activity.map((point) => {
            const parts = point.day.split('-');
            return `${parts[2]}/${parts[1]}`;
        });

        this.chartData.set({
            labels,
            datasets: [
                {
                    label: 'Proyectos creados',
                    backgroundColor: documentStyle.getPropertyValue(
                        '--p-primary-500',
                    ),
                    borderRadius: 8,
                    data: stats.wizard.activity.map(
                        (point) => point.createdProjects,
                    ),
                },
                {
                    label: 'Pasos completados',
                    backgroundColor: documentStyle.getPropertyValue(
                        '--p-teal-500',
                    ),
                    borderRadius: 8,
                    data: stats.wizard.activity.map(
                        (point) => point.completedSteps,
                    ),
                },
                {
                    label: 'Pasos fallidos',
                    backgroundColor: documentStyle.getPropertyValue(
                        '--p-orange-500',
                    ),
                    borderRadius: 8,
                    data: stats.wizard.activity.map(
                        (point) => point.failedSteps,
                    ),
                },
            ],
        });

        this.chartOptions.set({
            maintainAspectRatio: false,
            aspectRatio: 0.8,
            plugins: {
                legend: {
                    display: true,
                    labels: {
                        color: textMutedColor,
                    },
                },
            },
            scales: {
                x: {
                    stacked: false,
                    ticks: {
                        color: textMutedColor,
                    },
                    grid: {
                        color: 'transparent',
                        borderColor: 'transparent',
                    },
                },
                y: {
                    beginAtZero: true,
                    ticks: {
                        color: textMutedColor,
                        precision: 0,
                    },
                    grid: {
                        color: borderColor,
                        borderColor: 'transparent',
                        drawTicks: false,
                    },
                },
            },
        });
    }
}
