import { Routes } from '@angular/router';
import { BookTranslator } from './book-translator';
import { BookTranslatorHistory } from './history';
import { BookTranslatorSettings } from './settings';
import { UploadWizardComponent } from './upload/upload-wizard.component';

export default [
    { path: '', component: BookTranslator },
    { path: 'history', component: BookTranslatorHistory },
    { path: 'settings', component: BookTranslatorSettings },
    { path: 'upload', component: UploadWizardComponent },
    { path: '**', redirectTo: '' }
] as Routes;
