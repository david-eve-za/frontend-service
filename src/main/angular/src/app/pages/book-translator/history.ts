import { Component } from '@angular/core';

@Component({
    selector: 'app-book-translator-history',
    standalone: true,
    template: `
        <div class="card">
            <div class="font-semibold text-xl mb-4">Translation History</div>
            <p>View your translation history here.</p>
        </div>
    `
})
export class BookTranslatorHistory {}
