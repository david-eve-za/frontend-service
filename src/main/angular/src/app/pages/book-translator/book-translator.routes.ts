import { Routes } from '@angular/router';
import { BookTranslator } from './book-translator';
import { BookTranslatorHistory } from './history';
import { BookTranslatorSettings } from './settings';
import { Glossary } from './glossary';
import { UploadStep1FilesComponent } from './upload/upload-step1-files.component';
import { NovelsManager } from './novels-manager/novels-manager';

export default [
    { path: '', component: BookTranslator },
    { path: 'history', component: BookTranslatorHistory },
    { path: 'glossary', component: Glossary },
    { path: 'settings', component: BookTranslatorSettings },
    { path: 'upload', component: UploadStep1FilesComponent },
    { path: 'novels', component: NovelsManager },
    { path: '**', redirectTo: '' },
] as Routes;
