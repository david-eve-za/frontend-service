import { Routes } from '@angular/router';
import { BookTranslator } from './book-translator';
import { BookTranslatorHistory } from './history';
import { BookTranslatorSettings } from './settings';
import { UploadWizardComponent } from './upload/upload-wizard.component';
import { NovelsManager } from './novels-manager/novels-manager';

export default [
    { path: '', component: BookTranslator },
    { path: 'history', component: BookTranslatorHistory },
    { path: 'settings', component: BookTranslatorSettings },
    { path: 'upload', component: UploadWizardComponent },
    { path: 'novels', component: NovelsManager },
    { path: '**', redirectTo: '' }
] as Routes;
