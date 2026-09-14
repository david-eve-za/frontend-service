import { Routes } from '@angular/router';
import { BookTranslator } from './book-translator';
import { BookTranslatorHistory } from './history';
import { BookTranslatorSettings } from './settings';

export default [
    { path: '', component: BookTranslator },
    { path: 'history', component: BookTranslatorHistory },
    { path: 'settings', component: BookTranslatorSettings },
    { path: '**', redirectTo: '' }
] as Routes;
