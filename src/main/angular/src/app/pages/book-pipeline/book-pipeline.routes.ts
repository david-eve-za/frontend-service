import { Routes } from '@angular/router';
import { TranslateBook } from './translate-book';
import { GenerateAudioBook } from './generate-audiobook';

export default [
    { path: '', redirectTo: 'translate-book', pathMatch: 'full' },
    { path: 'translate-book', component: TranslateBook },
    { path: 'generate-audiobook', component: GenerateAudioBook },
    { path: '**', redirectTo: 'translate-book' },
] as Routes;
