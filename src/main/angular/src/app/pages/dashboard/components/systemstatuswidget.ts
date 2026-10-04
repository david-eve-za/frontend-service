import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TagModule } from 'primeng/tag';
import { DashboardStats } from '../dashboard.service';

/**
 * Fila de indicadores clave del sistema: base de datos, procesos activos,
 * libros en el pipeline y estado del audio.
 */
@Component({
    selector: 'app-system-status-widget',
    imports: [CommonModule, TagModule],
    template: `@if (stats(); as s) {
        <div class="col-span-12 lg:col-span-6 xl:col-span-3">
            <div class="card mb-0">
                <div class="flex justify-between mb-4">
                    <div>
                        <span class="block text-muted-color font-medium mb-4"
                            >Base de datos</span
                        >
                        <div class="flex items-center gap-2">
                            <p-tag
                                [value]="
                                    s.database.status === 'UP'
                                        ? 'En línea'
                                        : 'Caída'
                                "
                                [severity]="
                                    s.database.status === 'UP'
                                        ? 'success'
                                        : 'danger'
                                "
                            />
                        </div>
                    </div>
                    <div
                        class="flex items-center justify-center bg-blue-100 dark:bg-blue-400/10 rounded-border"
                        style="width: 2.5rem; height: 2.5rem"
                    >
                        <i class="pi pi-database text-blue-500 text-xl!"></i>
                    </div>
                </div>
                @if (s.database.status === 'UP') {
                    <span class="text-muted-color"
                        >{{ s.database.product }} {{ s.database.version }}</span
                    >
                    @if (dbSize() !== null) {
                        <span class="text-muted-color"> · {{ dbSize() }}</span>
                    }
                } @else {
                    <span class="text-red-500 text-sm">{{
                        s.database.error || 'Sin conexión'
                    }}</span>
                }
            </div>
        </div>
        <div class="col-span-12 lg:col-span-6 xl:col-span-3">
            <div class="card mb-0">
                <div class="flex justify-between mb-4">
                    <div>
                        <span class="block text-muted-color font-medium mb-4"
                            >Procesos activos</span
                        >
                        <div
                            class="text-surface-900 dark:text-surface-0 font-medium text-xl"
                        >
                            {{ s.processes.running.length }}
                        </div>
                    </div>
                    <div
                        class="flex items-center justify-center bg-orange-100 dark:bg-orange-400/10 rounded-border"
                        style="width: 2.5rem; height: 2.5rem"
                    >
                        <i class="pi pi-bolt text-orange-500 text-xl!"></i>
                    </div>
                </div>
                <span class="text-muted-color"
                    >{{ s.processes.running.length }} libro(s) en curso · sync
                    {{
                        s.processes.catalogSync.state === 'RUNNING'
                            ? 'activa'
                            : 'inactiva'
                    }}</span
                >
            </div>
        </div>
        <div class="col-span-12 lg:col-span-6 xl:col-span-3">
            <div class="card mb-0">
                <div class="flex justify-between mb-4">
                    <div>
                        <span class="block text-muted-color font-medium mb-4"
                            >Libros en pipeline</span
                        >
                        <div
                            class="text-surface-900 dark:text-surface-0 font-medium text-xl"
                        >
                            {{ s.books.total }}
                        </div>
                    </div>
                    <div
                        class="flex items-center justify-center bg-cyan-100 dark:bg-cyan-400/10 rounded-border"
                        style="width: 2.5rem; height: 2.5rem"
                    >
                        <i class="pi pi-book text-cyan-500 text-xl!"></i>
                    </div>
                </div>
                <span class="text-muted-color"
                    >{{ s.catalog.volumes }} volúmenes ·
                    {{ s.catalog.novels }} obras</span
                >
            </div>
        </div>
        <div class="col-span-12 lg:col-span-6 xl:col-span-3">
            <div class="card mb-0">
                <div class="flex justify-between mb-4">
                    <div>
                        <span class="block text-muted-color font-medium mb-4"
                            >Audio</span
                        >
                        <div
                            class="text-surface-900 dark:text-surface-0 font-medium text-xl"
                        >
                            {{ s.books.audioCreated }}
                        </div>
                    </div>
                    <div
                        class="flex items-center justify-center bg-purple-100 dark:bg-purple-400/10 rounded-border"
                        style="width: 2.5rem; height: 2.5rem"
                    >
                        <i class="pi pi-volume-up text-purple-500 text-xl!"></i>
                    </div>
                </div>
                <span class="text-primary font-medium"
                    >{{ s.books.readyForAudio }}
                </span>
                <span class="text-muted-color">listos para generar audio</span>
            </div>
        </div>
    }`,
})
export class SystemStatusWidget {
    stats = input<DashboardStats | null>(null);

    dbSize(): string | null {
        const bytes = this.stats()?.database.fileSizeBytes;
        if (bytes === null || bytes === undefined) {
            return null;
        }
        const mb = bytes / (1024 * 1024);
        return mb >= 1
            ? `${mb.toFixed(1)} MB`
            : `${(bytes / 1024).toFixed(0)} KB`;
    }
}
