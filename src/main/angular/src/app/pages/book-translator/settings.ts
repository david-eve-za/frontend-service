import { Component } from '@angular/core';

@Component({
    selector: 'app-book-translator-settings',
    standalone: true,
    template: `
        <div class="card">
            <div class="font-semibold text-xl mb-4">Settings</div>
            <p>Configure your translation preferences.</p>
        </div>
    `
})
export class BookTranslatorSettings {}
