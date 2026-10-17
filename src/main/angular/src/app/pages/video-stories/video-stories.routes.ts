import { Routes } from '@angular/router';
import { StoryWizard } from './story-wizard';

export default [
    // El asistente encadenado (timeline 1→4) es la única herramienta de la
    // sección: portada, ruta explícita y fallback.
    { path: '', redirectTo: 'wizard', pathMatch: 'full' },
    { path: 'wizard', component: StoryWizard },
    { path: '**', redirectTo: 'wizard' },
] as Routes;
