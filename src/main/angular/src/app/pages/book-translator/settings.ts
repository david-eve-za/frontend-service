import { Component } from '@angular/core';
import { RegexPatternFormComponent } from './regex-pattern-form.component';

@Component({
  selector: 'app-book-translator-settings',
  standalone: true,
  imports: [RegexPatternFormComponent],
  template: `
    <app-regex-pattern-form></app-regex-pattern-form>
  `
})
export class BookTranslatorSettings {}