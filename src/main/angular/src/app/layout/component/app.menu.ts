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
                label: 'Home',
                items: [{ label: 'Dashboard', icon: 'pi pi-fw pi-home', routerLink: ['/'] }]
            },
{
                label: 'Book Translator',
                icon: 'pi pi-fw pi-language',
                path: '/book-translator',
                items: [
                    {
                        label: 'Subir Archivos',
                        icon: 'pi pi-fw pi-upload',
                        routerLink: ['/book-translator/upload']
                    },
                    {
                        label: 'Translate Book',
                        icon: 'pi pi-fw pi-file-arrow-right',
                        routerLink: ['/book-translator']
                    },
                    {
                        label: 'History',
                        icon: 'pi pi-fw pi-history',
                        routerLink: ['/book-translator/history']
                    },
                    {
                        label: 'Settings',
                        icon: 'pi pi-fw pi-cog',
                        routerLink: ['/book-translator/settings']
                    }
                ]
            },
            {
                label: 'Get Started',
                items: [
                    {
                        label: 'Documentation',
                        icon: 'pi pi-fw pi-book',
                        routerLink: ['/documentation']
                    },
                    {
                        label: 'View Source',
                        icon: 'pi pi-fw pi-github',
                        url: 'https://github.com/primefaces/sakai-ng',
                        target: '_blank'
                    }
                ]
            }
        ];
    }
}
