import { Component } from '@angular/core';

@Component({
    selector: 'app-book-translator',
    standalone: true,
    template: `
        <div class="card">
            <div class="font-semibold text-xl mb-4">Book Translator</div>
            <p>Upload a book to translate and generate audio.</p>
        </div>
    `
})
export class BookTranslator {}
