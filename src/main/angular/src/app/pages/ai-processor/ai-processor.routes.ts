import { Routes } from '@angular/router';
import { AiProcessor } from './ai-processor';

export default [
    { path: '', component: AiProcessor },
    { path: '**', redirectTo: '' }
] as Routes;