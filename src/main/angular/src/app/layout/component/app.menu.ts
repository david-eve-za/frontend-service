import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { Subject, takeUntil } from 'rxjs';
import { AppMenuitem } from './app.menuitem';
import { SourcesService, SourceMetadata } from '../../pages/book-translator/novels-manager/sources.service';

@Component({
    selector: 'app-menu',
    standalone: true,
    imports: [CommonModule, AppMenuitem, RouterModule],
    template: `<ul class="layout-menu">
        @for (item of model; track item.label) {
            @if (!item.separator) {
                <li app-menuitem [item]="item" [root]="true"></li>
            } @else {
                <li class="menu-separator"></li>
            }
        }
    </ul> `,
})
export class AppMenu implements OnInit, OnDestroy {
    model: MenuItem[] = [];
    private destroy$ = new Subject<void>();

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

    private loadDynamicSources() {
        this.sourcesService.getAllSources()
            .pipe(takeUntil(this.destroy$))
            .subscribe({
                next: (sources) => {
                    this.updateSourcesSubmenu(sources);
                },
                error: (err) => {
                    console.warn('Failed to load sources for menu:', err);
                    // Keep static elscione as fallback
                }
            });
    }

    private updateSourcesSubmenu(sources: SourceMetadata[]) {
        const bookTranslatorMenu = this.model.find(m => m.label === 'Traductor de Libros');
        if (!bookTranslatorMenu || !bookTranslatorMenu.items) {
            return;
        }

        // Find the "Gestor de Novelas" item
        const novelsManagerIndex = bookTranslatorMenu.items.findIndex(
            item => item.label === 'Gestor de Novelas'
        );

        if (novelsManagerIndex === -1) {
            return;
        }

        // Create source submenu items
        const sourceItems: MenuItem[] = sources
            .filter(s => s.enabled)
            .map(source => ({
                label: source.displayName,
                icon: source.icon,
                routerLink: ['/book-translator/novels'],
                queryParams: { source: source.id },
                // Add language flag as tooltip/badge
                title: `${source.displayName} (${source.language.toUpperCase()})`
            }));

        // If we have sources, replace the simple routerLink with a submenu
        if (sourceItems.length > 0) {
            const novelsManagerItem = bookTranslatorMenu.items[novelsManagerIndex];
            novelsManagerItem.items = sourceItems;
            // Remove direct routerLink since it's now a submenu
            delete novelsManagerItem.routerLink;
        }
    }
}