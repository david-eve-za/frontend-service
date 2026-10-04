import { Component, effect, inject, input, signal } from '@angular/core';
import { ChartModule } from 'primeng/chart';
import { LayoutService } from '@/app/layout/service/layout.service';
import { DashboardStats } from '../dashboard.service';

interface ChartDataset {
    label: string;
    backgroundColor: string[];
    borderRadius: number;
    data: number[];
}

interface ChartData {
    labels: string[];
    datasets: ChartDataset[];
}

/**
 * Embudo del pipeline de libros: cuántos llegaron a cada etapa
 * (spliteados → traducidos → listos para audio → con audio).
 */
@Component({
    selector: 'app-pipeline-chart-widget',
    imports: [ChartModule],
    template: `<div class="card mb-8!">
        <div class="flex items-center justify-between mb-4">
            <div class="font-semibold text-xl">
                Pipeline de libros por etapa
            </div>
            @if (stats(); as s) {
                <span class="text-muted-color text-sm"
                    >{{ s.books.readyForAudio }} listos para audio</span
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
export class PipelineChartWidget {
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
        if (!stats) {
            this.chartData.set(null);
            return;
        }
        const documentStyle = getComputedStyle(document.documentElement);
        const textColor = documentStyle.getPropertyValue('--text-color');
        const textMutedColor = documentStyle.getPropertyValue(
            '--text-color-secondary',
        );
        const borderColor = documentStyle.getPropertyValue('--surface-border');

        this.chartData.set({
            labels: [
                'Totales',
                'Spliteados',
                'Traducidos',
                'Listos p/ audio',
                'Con audio',
            ],
            datasets: [
                {
                    label: 'Libros',
                    backgroundColor: [
                        documentStyle.getPropertyValue('--p-primary-500'),
                        documentStyle.getPropertyValue('--p-primary-400'),
                        documentStyle.getPropertyValue('--p-primary-300'),
                        documentStyle.getPropertyValue('--p-teal-500'),
                        documentStyle.getPropertyValue('--p-purple-500'),
                    ],
                    borderRadius: 8,
                    data: [
                        stats.books.total,
                        stats.books.split,
                        stats.books.translated,
                        stats.books.readyForAudio,
                        stats.books.audioCreated,
                    ],
                },
            ],
        });

        this.chartOptions.set({
            maintainAspectRatio: false,
            aspectRatio: 0.8,
            plugins: {
                legend: {
                    display: false,
                },
            },
            scales: {
                x: {
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
