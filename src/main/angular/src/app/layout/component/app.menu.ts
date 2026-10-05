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
                        label: 'Configuración',
                        icon: 'pi pi-fw pi-cog',
                        routerLink: ['/book-translator/settings'],
                    },
                ],
            },
            {
                label: 'Procesamiento de Libros',
                icon: 'pi pi-fw pi-sliders-h',
                items: [
                    {
                        // El paso de división de texto es el editor de bloques
                        // del pipeline (módulo book-pipeline).
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
