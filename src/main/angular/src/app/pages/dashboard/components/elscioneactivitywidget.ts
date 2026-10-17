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
 * Monitoreo del catálogo de elscione: novelas y volúmenes descubiertos
 * (creados por el sync) por día, en la ventana de actividad que envía el
 * backend. Días sin descubrimientos llegan ya rellenados a cero, así que
 * el eje temporal siempre es continuo.
 */
@Component({
    selector: 'app-elscione-activity-widget',
    imports: [ChartModule],
    template: `<div class="card mb-8!">
        <div class="flex items-center justify-between mb-4">
            <div class="font-semibold text-xl">
                Catálogo elscione — descubrimientos por día
            </div>
            @if (stats(); as s) {
                <span class="text-muted-color text-sm"
                    >{{ s.catalog.novels }} obras ·
                    {{ s.catalog.volumes }} volúmenes sincronizados</span
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
export class ElscioneActivityWidget {
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
        if (!stats || !stats.catalog.activity?.length) {
            this.chartData.set(null);
            return;
        }
        const documentStyle = getComputedStyle(document.documentElement);
        const textMutedColor = documentStyle.getPropertyValue(
            '--text-color-secondary',
        );
        const borderColor = documentStyle.getPropertyValue('--surface-border');

        // "2026-10-16" -> "16/10" para un eje compacto de 14 días.
        const labels = stats.catalog.activity.map((point) => {
            const parts = point.day.split('-');
            return `${parts[2]}/${parts[1]}`;
        });

        this.chartData.set({
            labels,
            datasets: [
                {
                    label: 'Novelas',
                    backgroundColor: documentStyle.getPropertyValue(
                        '--p-primary-500',
                    ),
                    borderRadius: 8,
                    data: stats.catalog.activity.map(
                        (point) => point.novels,
                    ),
                },
                {
                    label: 'Volúmenes',
                    backgroundColor: documentStyle.getPropertyValue(
                        '--p-teal-500',
                    ),
                    borderRadius: 8,
                    data: stats.catalog.activity.map(
                        (point) => point.volumes,
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
