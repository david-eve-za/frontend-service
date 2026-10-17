import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { AppMenuitem } from './app.menuitem';

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
export class AppMenu {
    model: MenuItem[] = [];

    ngOnInit() {
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
                        // Visor de logs en tiempo real (stream SSE del backend).
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
                        // Editor de bloques semánticos del pipeline.
                        label: 'Dividir Texto',
                        icon: 'pi pi-fw pi-list',
                        routerLink: ['/pipeline/split-text'],
                    },
                    {
                        // Comparación en paralelo original/traducción por chunk.
                        label: 'Traducir Libro',
                        icon: 'pi pi-fw pi-language',
                        routerLink: ['/pipeline/translate-book'],
                    },
                    {
                        // Opciones de generación de audio del pipeline.
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
                        // Curación de términos con traducción establecida.
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
                        // Única herramienta de la sección: la timeline completa
                        // encadenada por la IA (1 historia → 2 diseños →
                        // 3 assets → 4 escenas → 5 video opcional).
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
}
