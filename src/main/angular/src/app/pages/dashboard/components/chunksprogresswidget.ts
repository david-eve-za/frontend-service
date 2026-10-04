import { Component, effect, inject, input, signal } from '@angular/core';
import { ChartModule } from 'primeng/chart';
import { LayoutService } from '@/app/layout/service/layout.service';
import { DashboardStats } from '../dashboard.service';

interface ChartDataset {
    label: string;
    backgroundColor: string[];
    data: number[];
}

interface ChartData {
    labels: string[];
    datasets: ChartDataset[];
}

/**
 * Progreso global de traducción a nivel de chunks: cuántos de todos los
 * fragmentos ya están completados frente a los pendientes.
 */
@Component({
    selector: 'app-chunks-progress-widget',
    imports: [ChartModule],
    template: `<div class="card mb-8!">
        <div class="flex items-center justify-between mb-4">
            <div class="font-semibold text-xl">Progreso de traducción</div>
            @if (stats(); as s) {
                <span class="text-muted-color text-sm"
                    >{{ s.chunks.completed }} de
                    {{ s.chunks.total }} chunks</span
                >
            }
        </div>
        @if (stats(); as s) {
            @if (s.chunks.total > 0) {
                <p-chart
                    type="doughnut"
                    [data]="chartData()"
                    [options]="chartOptions()"
                    class="h-72 flex justify-center"
                />
                <div class="text-center mt-2">
                    <span
                        class="text-2xl font-semibold text-surface-900 dark:text-surface-0"
                        >{{ percentage() }}%</span
                    >
                    <span class="text-muted-color block"
                        >chunks traducidos</span
                    >
                </div>
            } @else {
                <div
                    class="flex items-center justify-center h-72 text-muted-color"
                >
                    Sin chunks: todavía no se spliteó ningún libro.
                </div>
            }
        }
    </div>`,
})
export class ChunksProgressWidget {
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

    percentage(): number {
        const stats = this.stats();
        if (!stats || stats.chunks.total === 0) {
            return 0;
        }
        return Math.round((stats.chunks.completed / stats.chunks.total) * 100);
    }

    initChart() {
        const stats = this.stats();
        if (!stats || stats.chunks.total === 0) {
            this.chartData.set(null);
            return;
        }
        const documentStyle = getComputedStyle(document.documentElement);
        const textColor = documentStyle.getPropertyValue('--text-color');
        const pending = stats.chunks.total - stats.chunks.completed;

        this.chartData.set({
            labels: ['Traducidos', 'Pendientes'],
            datasets: [
                {
                    label: 'Chunks',
                    backgroundColor: [
                        documentStyle.getPropertyValue('--p-green-500'),
                        documentStyle.getPropertyValue('--p-slate-200'),
                    ],
                    data: [stats.chunks.completed, pending],
                },
            ],
        });

        this.chartOptions.set({
            maintainAspectRatio: false,
            aspectRatio: 0.8,
            cutout: '70%',
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: {
                        color: textColor,
                        usePointStyle: true,
                        pointStyle: 'circle',
                    },
                },
            },
        });
    }
}
