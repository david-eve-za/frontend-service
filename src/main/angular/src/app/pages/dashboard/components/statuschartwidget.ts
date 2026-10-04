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

/** Etiquetas en español y color del gráfico por estado del libro. */
const STATUS_META: Record<string, { label: string; cssVar: string }> = {
    UPLOADED: { label: 'Cargados', cssVar: '--p-slate-400' },
    PROCESSING: { label: 'Procesando', cssVar: '--p-blue-500' },
    COMPLETED: { label: 'Completados', cssVar: '--p-green-500' },
    FAILED: { label: 'Fallidos', cssVar: '--p-red-500' },
    AWAITING: { label: 'En espera', cssVar: '--p-amber-500' },
    STOPPED: { label: 'Detenidos', cssVar: '--p-gray-500' },
};

/**
 * Distribución de libros por estado de procesamiento (doughnut).
 * Solo se muestran los estados con al menos un libro.
 */
@Component({
    selector: 'app-status-chart-widget',
    imports: [ChartModule],
    template: `<div class="card mb-8!">
        <div class="font-semibold text-xl mb-4">Libros por estado</div>
        @if (hasData()) {
            <p-chart
                type="doughnut"
                [data]="chartData()"
                [options]="chartOptions()"
                class="h-80 flex justify-center"
            />
        } @else {
            <div class="flex items-center justify-center h-80 text-muted-color">
                Aún no hay libros cargados.
            </div>
        }
    </div>`,
})
export class StatusChartWidget {
    layoutService = inject(LayoutService);

    stats = input<DashboardStats | null>(null);

    chartData = signal<ChartData | null>(null);

    chartOptions = signal<unknown>(null);

    hasData = signal(false);

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
            this.hasData.set(false);
            return;
        }
        const documentStyle = getComputedStyle(document.documentElement);
        const textColor = documentStyle.getPropertyValue('--text-color');
        const textMutedColor = documentStyle.getPropertyValue(
            '--text-color-secondary',
        );

        const entries = Object.entries(stats.books.byStatus)
            .filter(([, count]) => count > 0)
            .map(([status, count]) => {
                const meta = STATUS_META[status] ?? {
                    label: status,
                    cssVar: '--p-gray-500',
                };
                return {
                    label: meta.label,
                    color: documentStyle.getPropertyValue(meta.cssVar),
                    value: count,
                };
            });

        this.hasData.set(entries.length > 0);

        this.chartData.set({
            labels: entries.map((entry) => entry.label),
            datasets: [
                {
                    label: 'Libros',
                    backgroundColor: entries.map((entry) => entry.color),
                    data: entries.map((entry) => entry.value),
                },
            ],
        });

        this.chartOptions.set({
            maintainAspectRatio: false,
            aspectRatio: 0.8,
            cutout: '65%',
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
