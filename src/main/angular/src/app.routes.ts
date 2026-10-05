import { Routes } from '@angular/router';
import { AppLayout } from './app/layout/component/app.layout';
import { Dashboard } from './app/pages/dashboard/dashboard';
import { Documentation } from './app/pages/documentation/documentation';
import { Landing } from './app/pages/landing/landing';
import { Notfound } from './app/pages/notfound/notfound';
import { LogViewer } from './app/pages/logs-viewer/logs-viewer';

export const appRoutes: Routes = [
    {
        path: '',
        component: AppLayout,
        children: [
            { path: '', component: Dashboard },
            { path: 'logs', component: LogViewer },
            {
                path: 'uikit',
                loadChildren: () => import('./app/pages/uikit/uikit.routes'),
            },
            { path: 'documentation', component: Documentation },
            {
                path: 'pages',
                loadChildren: () => import('./app/pages/pages.routes'),
            },
            {
                path: 'book-translator',
                loadChildren: () =>
                    import('./app/pages/book-translator/book-translator.routes'),
            },
            {
                path: 'pipeline',
                loadChildren: () =>
                    import('./app/pages/book-pipeline/book-pipeline.routes'),
            },
        ],
    },
    { path: 'landing', component: Landing },
    { path: 'notfound', component: Notfound },
    {
        path: 'auth',
        loadChildren: () => import('./app/pages/auth/auth.routes'),
    },
    { path: '**', redirectTo: '/notfound' },
];
