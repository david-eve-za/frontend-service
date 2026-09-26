import { Component } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { RegexPatternTableComponent } from './regex-pattern-table.component';

@Component({
  selector: 'app-book-translator-settings',
  standalone: true,
  imports: [RegexPatternTableComponent, ButtonModule],
  template: `
    <div class="card mb-4">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-content-between gap-4 p-4 bg-blue-50 border-l-4 border-blue-500 rounded-r">
        <div>
          <h2 class="text-xl font-bold text-gray-800">Herramientas de Desarrollo</h2>
          <p class="text-sm text-gray-600 mt-1">Accesos rápidos a utilidades internas del servicio</p>
        </div>
        <div class="flex gap-2">
          <p-button
            label="Abrir Consola H2"
            icon="pi pi-database"
            (click)="openH2Console()"
            styleClass="p-button-primary">
          </p-button>
        </div>
      </div>
    </div>
    <app-regex-pattern-table></app-regex-pattern-table>
  `
})
export class BookTranslatorSettings {
  openH2Console(): void {
    window.open('/h2-console', '_blank');
  }
}
