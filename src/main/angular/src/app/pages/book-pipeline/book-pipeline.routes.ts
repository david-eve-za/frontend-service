import { Routes } from '@angular/router';
import { TranslateBook } from './translate-book';
import { UploadStep2ConfigComponent } from '../book-translator/upload/upload-step2-config.component';
import { UploadStep3SplitComponent } from '../book-translator/upload/upload-step3-split.component';

/**
 * Rutas de los pasos del pipeline de procesamiento de libros:
 * dividir texto, traducir libro y generar audiolibro.
 */
export default [
    { path: '', redirectTo: 'split-text', pathMatch: 'full' },
    { path: 'split-text', component: UploadStep3SplitComponent },
    { path: 'translate-book', component: TranslateBook },
    { path: 'generate-audiobook', component: UploadStep2ConfigComponent },
    { path: '**', redirectTo: 'split-text' },
] as Routes;
