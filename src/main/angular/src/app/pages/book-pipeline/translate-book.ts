import { Component } from '@angular/core';

/**
 * Paso Traducir Libro del pipeline de procesamiento. Página en blanco:
 * la funcionalidad de traducción se integrará aquí más adelante.
 */
@Component({
    selector: 'app-translate-book',
    standalone: true,
    template: ` <div class="card">
        <div class="font-semibold text-xl mb-4">Traducir Libro</div>
        <p class="text-muted-color">
            Página en blanco: aquí se gestionará el paso de traducción del
            pipeline.
        </p>
    </div>`,
})
export class TranslateBook {}
