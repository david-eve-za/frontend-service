import { Component } from '@angular/core';
import { RegexPatternTableComponent } from './regex-pattern-table.component';

@Component({
  selector: 'app-book-translator-settings',
  standalone: true,
  imports: [RegexPatternTableComponent],
  template: `
    <app-regex-pattern-table></app-regex-pattern-table>
  `
})
export class BookTranslatorSettings {}