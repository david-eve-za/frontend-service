import { Routes } from '@angular/router';
import { BookTranslator } from './book-translator';

export default [
    { path: '', component: BookTranslator },
    { path: '**', redirectTo: '' }
] as Routes;
