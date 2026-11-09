import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { Subject, takeUntil } from 'rxjs';
import { AppMenuitem } from './app.menuitem';
import { SourcesService, SourceMetadata } from '../../pages/book-translator/novels-manager/sources.service';
import { SourceSelectorComponent } from './source-selector';

@Component({
    selector: 'app-menu',
    standalone: true,
    imports: [CommonModule, AppMenuitem, RouterModule, SourceSelectorComponent],
    template: `
    <ul class="layout-menu">
        @for (item of model; track item.label) {
            @if (!item.separator) {
                <!-- Special handling for "Gestor de Novelas" with dynamic source selector -->
                @if (item.label === 'Gestor de Novelas' && sourcesLoaded && availableSources.length > 0) {
                    <li class="layout-root-menuitem">
                        <app-source-selector
                            [sources]="availableSources"
                            [selectedSourceId]="selectedSourceId"
                            [loading]="loadingSources"
                            [error]="sourcesError"
                            [healthStatus]="healthStatus"
                            (sourceSelected)="onSourceSelected($event)"
                            (retry)="loadDynamicSources()">
                        </app-source-selector>
                    </li>
                } @else {
                    <li app-menuitem [item]="item" [root]="true"></li>
                }
            } @else {
                <li class="menu-separator"></li>
            }
        }
    </ul> `,
})
export class AppMenu implements OnInit, OnDestroy {
    model: MenuItem[] = [];
    private destroy$ = new Subject<void>();

    // Source selector state
    availableSources: SourceMetadata[] = [];
    selectedSourceId = 'elscione';
    sourcesLoaded = false;
    loadingSources = false;
    sourcesError: string | null = null;
    healthStatus: { healthy: boolean; details: string } = { healthy: true, details: 'Todas saludables' };

    constructor(private sourcesService: SourcesService) {}

    ngOnInit() {
        this.initializeStaticMenu();
        this.loadDynamicSources();
    }

    ngOnDestroy() {
        this.destroy$.next();
        this.destroy$.complete();
    }

    private initializeStaticMenu() {
        this.model = [
            {
                label: 'Inicio',
                items: [
                    {
                        label: 'Panel de Estado',
                        icon: 'pi pi-fw pi-home',
                        routerLink: ['/'],
                    },
                    {
                        label: 'Visor de Logs',
                        icon: 'pi pi-fw pi-eye',
                        routerLink: ['/logs'],
                    },
                ],
            },
            {
                label: 'Traductor de Libros',
                icon: 'pi pi-fw pi-language',
                path: '/book-translator',
                items: [
                    {
                        label: 'Subir Archivos',
                        icon: 'pi pi-fw pi-upload',
                        routerLink: ['/book-translator/upload'],
                    },
                    {
                        label: 'Dividir Texto',
                        icon: 'pi pi-fw pi-list',
                        routerLink: ['/pipeline/split-text'],
                    },
                    {
                        label: 'Traducir Libro',
                        icon: 'pi pi-fw pi-language',
                        routerLink: ['/pipeline/translate-book'],
                    },
                    {
                        label: 'Generar Audiolibro',
                        icon: 'pi pi-fw pi-volume-up',
                        routerLink: ['/pipeline/generate-audiobook'],
                    },
                    {
                        label: 'Gestor de Novelas',
                        icon: 'pi pi-fw pi-book',
                        routerLink: ['/book-translator/novels'],
                    },
                    {
                        label: 'Historial',
                        icon: 'pi pi-fw pi-history',
                        routerLink: ['/book-translator/history'],
                    },
                    {
                        label: 'Glosario de Traducción',
                        icon: 'pi pi-fw pi-spell-check',
                        routerLink: ['/book-translator/glossary'],
                    },
                    {
                        label: 'Configuración',
                        icon: 'pi pi-fw pi-cog',
                        routerLink: ['/book-translator/settings'],
                    },
                ],
            },
            {
                label: 'Historias en Video',
                icon: 'pi pi-fw pi-video-camera',
                path: '/video-stories',
                items: [
                    {
                        label: 'Asistente de Historias',
                        icon: 'pi pi-fw pi-magic',
                        routerLink: ['/video-stories/wizard'],
                    },
                ],
            },
            {
                label: 'Procesador IA',
                icon: 'pi pi-fw pi-cpu',
                routerLink: ['/ai-processor'],
            },
            {
                label: 'Primeros Pasos',
                items: [
                    {
                        label: 'Documentación',
                        icon: 'pi pi-fw pi-book',
                        routerLink: ['/documentation'],
                    },
                    {
                        label: 'Ver Código Fuente',
                        icon: 'pi pi-fw pi-github',
                        url: 'https://github.com/primefaces/sakai-ng',
                        target: '_blank',
                    },
                ],
            },
        ];
    }

    loadDynamicSources(): void {
        this.loadingSources = true;
        this.sourcesError = null;
        
        this.sourcesService.getAllSources()
            .pipe(takeUntil(this.destroy$))
            .subscribe({
                next: (sources) => {
                    this.availableSources = sources;
                    this.sourcesLoaded = true;
                    this.loadingSources = false;
                    this.loadHealthStatus();
                },
                error: (err) => {
                    console.warn('Failed to load sources for menu:', err);
                    this.sourcesError = 'No se pudieron cargar las fuentes';
                    this.loadingSources = false;
                    // Fallback to elscione
                    this.availableSources = [{ 
                        id: 'elscione', 
                        displayName: 'Elscione', 
                        icon: 'pi pi-book', 
                        language: 'es', 
                        baseUrl: '', 
                        rateLimitRpm: 20, 
                        requiresLogin: false, 
                        enabled: true, 
                        tags: [] 
                    }];
                    this.sourcesLoaded = true;
                    this.loadHealthStatus();
                }
            });
    }

    private loadHealthStatus() {
        this.sourcesService.healthCheckAll()
            .pipe(takeUntil(this.destroy$))
            .subscribe({
                next: (status) => {
                    const healthy = Object.values(status).every(v => v === true);
                    const unhealthy = Object.entries(status).filter(([, v]) => !v).map(([k]) => k);
                    this.healthStatus = {
                        healthy,
                        details: healthy ? 'Todas saludables' : `${unhealthy.length} con problemas: ${unhealthy.join(', ')}`
                    };
                },
                error: () => {
                    this.healthStatus = { healthy: false, details: 'Error al verificar salud' };
                }
            });
    }

    onSourceSelected(source: SourceMetadata) {
        this.selectedSourceId = source.id;
        // Navigation is handled by the SourceSubmenu component via router
    }
}