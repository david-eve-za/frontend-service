import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CardModule } from 'primeng/card';
import { ImageModule } from 'primeng/image';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ButtonModule } from 'primeng/button';
import { RippleModule } from 'primeng/ripple';
import { AccordionModule } from 'primeng/accordion';
import { TableModule } from 'primeng/table';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { SkeletonModule } from 'primeng/skeleton';
import { CatalogVolume, NovelSummary } from './novels-manager.service';

export interface NovelRow extends NovelSummary {
  volumesLoaded: boolean;
  anilistEnriching?: boolean;
  anilistError?: string;
}

@Component({
  selector: 'novel-card',
  standalone: true,
  imports: [
    CommonModule,
    CardModule,
    ImageModule,
    TagModule,
    TooltipModule,
    ButtonModule,
    RippleModule,
    AccordionModule,
    TableModule,
    ProgressSpinnerModule,
    SkeletonModule
  ],
  template: `
    <p-card [style]="{'height': '100%', 'display': 'flex', 'flexDirection': 'column'}" class="novel-card-content">
      <!-- Imagen de portada (skeleton mientras carga) -->
      @if (novel.anilistCoverUrl) {
        <p-image
          [src]="novel.anilistCoverUrl"
          [preview]="true"
          [style]="{'width': '100%', 'aspectRatio': '2/3', 'objectFit': 'cover', 'borderRadius': '0.5rem 0.5rem 0 0'}"
          class="novel-cover-img">
        </p-image>
      } @else {
        <p-skeleton width="100%" height="280px" styleClass="novel-cover-img"></p-skeleton>
      }

      <div class="p-3 novel-card-body">
        <!-- Título y categoría -->
        <div class="mb-2">
          <h4 class="font-semibold text-gray-900 text-sm mb-1 line-clamp-2">{{ novel.title }}</h4>
          <div class="flex items-center gap-2 text-xs">
            <p-tag *ngIf="novel.category" [value]="novel.category" severity="secondary" styleClass="genre-tag"></p-tag>
            <span *ngIf="!novel.category" class="text-gray-400">—</span>
            <span class="text-gray-400">{{ novel.volumeCount }} volúmenes</span>
          </div>
        </div>

        <!-- Info AniList si está disponible -->
        <div *ngIf="novel.anilistCoverUrl" class="mb-2 space-y-1.5">
          <!-- Score y estado -->
          <div class="flex items-center gap-2 text-xs">
            <p-tag *ngIf="novel.anilistAverageScore" [value]="'★ ' + novel.anilistAverageScore + '%'" severity="warn" styleClass="genre-tag"></p-tag>
            <p-tag *ngIf="novel.anilistStatus" [value]="novel.anilistStatus" severity="info" styleClass="genre-tag"></p-tag>
            <p-tag *ngIf="novel.anilistFormat" [value]="novel.anilistFormat" severity="secondary" styleClass="genre-tag"></p-tag>
          </div>

          <!-- Géneros -->
          <div *ngIf="novel.anilistGenres && novel.anilistGenres.length > 0" class="flex flex-wrap gap-1">
            @for (genre of novel.anilistGenres.slice(0, 4); track genre) {
              <p-tag [value]="genre" severity="contrast" styleClass="genre-tag"></p-tag>
            }
            @if (novel.anilistGenres.length > 4) {
              <p-tag [value]="'+' + (novel.anilistGenres.length - 4) + ' más'" severity="secondary" styleClass="genre-tag"></p-tag>
            }
          </div>

          <!-- Tags (primeros 3) -->
          <div *ngIf="novel.anilistTags && novel.anilistTags.length > 0" class="flex flex-wrap gap-1">
            @for (tag of novel.anilistTags.slice(0, 3); track tag) {
              <p-tag [value]="tag" severity="secondary" styleClass="genre-tag" [icon]="'pi pi-tag'"></p-tag>
            }
          </div>
        </div>

        <!-- Descripción -->
        <div *ngIf="novel.anilistDescription" class="novel-description text-gray-600 mb-2 flex-1">
          {{ novel.anilistDescription }}
        </div>
        <div *ngIf="!novel.anilistDescription && !novel.anilistCoverUrl" class="text-xs text-gray-400 italic mb-2 flex-1">
          Sin enriquecer — usa "Enriquecer" para obtener datos de AniList
        </div>

        <!-- Acciones -->
        <div class="flex flex-wrap gap-2 pt-2 border-t mt-auto">
          <p-button
            *ngIf="!novel.anilistCoverUrl && !novel.anilistEnriching"
            label="Enriquecer"
            icon="pi pi-sparkles"
            size="small"
            styleClass="p-button-info p-button-outlined flex-1"
            (click)="enrich.emit(novel)"
            pTooltip="Buscar en AniList y obtener carátula, descripción, géneros, puntuación">
          </p-button>
          <p-button
            *ngIf="novel.anilistEnriching"
            label="Enriqueciendo..."
            icon="pi pi-spin pi-spinner"
            size="small"
            styleClass="p-button-info flex-1"
            [disabled]="true">
          </p-button>
          <p-button
            *ngIf="novel.anilistError"
            label="Reintentar"
            icon="pi pi-refresh"
            size="small"
            styleClass="p-button-warning p-button-outlined flex-1"
            (click)="enrich.emit(novel)">
          </p-button>

          <!-- Toggle expand para volúmenes -->
          <p-button
            type="button"
            pButton
            [icon]="expanded ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
            [text]="true"
            (click)="toggleExpand.emit(novel.id)"
            pTooltip="{{ expanded ? 'Ocultar volúmenes' : 'Ver volúmenes' }}"
            class="p-button-sm">
          </p-button>
        </div>
      </div>

      <!-- Sección expandible de volúmenes (Accordion) -->
      <p-accordion *ngIf="expanded" [multiple]="true" class="mt-3">
        <p-accordion-panel [value]="novel.id">
          <p-accordion-header>
            <div class="flex items-center justify-between">
              <span class="font-medium text-gray-700">Volúmenes ({{ volumes[novel.id]?.length || 0 }})</span>
              <p-button
                *ngIf="volumeLoading[novel.id]"
                icon="pi pi-spin pi-spinner"
                text="true"
                size="small"
                [disabled]="true">
              </p-button>
            </div>
          </p-accordion-header>
          <p-accordion-content>
            <div class="volume-table-container">
              <p-table
                *ngIf="volumes[novel.id] && volumes[novel.id].length > 0"
                [value]="volumes[novel.id]"
                styleClass="p-table-nested"
                [tableStyle]="{'min-width': '100%'}">
                <ng-template pTemplate="header">
                  <tr>
                    <th style="width: 8rem">Volumen</th>
                    <th>Grupo</th>
                    <th>Formatos</th>
                    <th style="width: 10rem">Estado</th>
                    <th style="width: 16rem">Acciones</th>
                  </tr>
                </ng-template>
                <ng-template pTemplate="body" let-volume>
                  <tr>
                    <td>
                      <div class="font-medium text-sm">{{ volumeLabel(volume) }}</div>
                    </td>
                    <td class="text-sm">{{ volume.translatorGroup || '—' }}</td>
                    <td>
                      <div class="flex flex-wrap gap-1">
                        <p-tag
                          *ngFor="let file of volume.files"
                          [value]="file.format + (file.presentLocally ? ' ✓' : '')"
                          [severity]="file.presentLocally ? 'success' : 'info'"
                          [pTooltip]="file.fileName + ' — ' + formatSize(file.sizeBytes)"
                          styleClass="genre-tag">
                        </p-tag>
                      </div>
                    </td>
                    <td>
                      <div class="flex flex-wrap gap-1 items-center">
                        <p-tag
                          [value]="volume.translated ? 'Traducido' : 'No Traducido'"
                          [severity]="volume.translated ? 'success' : 'danger'"
                          styleClass="genre-tag">
                        </p-tag>
                        <p-tag
                          [value]="volume.audioCreated ? 'Audio' : 'No Audio'"
                          [severity]="volume.audioCreated ? 'success' : 'danger'"
                          styleClass="genre-tag">
                        </p-tag>
                        <p-tag *ngIf="volume.removedFromSource" value="Retirado" severity="warn" styleClass="genre-tag"></p-tag>
                        <p-tag *ngIf="volume.bookId && !volume.splitDone && !volume.running" value="Texto listo" severity="info" styleClass="genre-tag"></p-tag>
                        <span *ngIf="volume.running" class="text-xs text-blue-600 flex items-center gap-1">
                          <i class="pi pi-spin pi-spinner"></i>Procesando
                        </span>
                      </div>
                    </td>
                    <td>
                      <div class="flex flex-wrap gap-1">
                        <p-button
                          label="Split"
                          icon="pi pi-minus"
                          size="small"
                          [disabled]="!volume.canPrepareText || actionBusy[volume.id]"
                          [loading]="actionBusy[volume.id]"
                          (click)="prepareSplit.emit({volume, novel: novel})"
                          pTooltip="Preparar texto y abrir editor de split">
                        </p-button>
                        <p-button
                          label="Traducir"
                          icon="pi pi-language"
                          size="small"
                          [outlined]="true"
                          [disabled]="!volume.canTranslate"
                          (click)="translateVolume.emit({volume, novel: novel})"
                          pTooltip="Iniciar traducción (requiere split)">
                        </p-button>
                        <p-button
                          label="Audio"
                          icon="pi pi-volume-up"
                          size="small"
                          [outlined]="true"
                          [disabled]="!volume.canCreateAudio"
                          (click)="createAudio.emit({volume, novel: novel})"
                          pTooltip="Generar audiolibro (requiere traducción)">
                        </p-button>
                        <p-button
                          label="Descargar"
                          icon="pi pi-download"
                          size="small"
                          severity="success"
                          [outlined]="true"
                          [disabled]="!volume.audioCreated"
                          (click)="downloadAudio.emit(volume)"
                          pTooltip="Descargar audiolibro (mp3 o ZIP)">
                        </p-button>
                      </div>
                    </td>
                  </tr>
                </ng-template>
                <ng-template pTemplate="emptymessage">
                  <tr>
                    <td colspan="5" class="text-center text-gray-500 py-4">
                      Esta obra no tiene volúmenes registrados.
                    </td>
                  </tr>
                </ng-template>
              </p-table>
              <div *ngIf="!volumes[novel.id] || volumes[novel.id].length === 0" class="text-center text-gray-500 py-4">
                Esta obra no tiene volúmenes registrados.
              </div>
            </div>
          </p-accordion-content>
        </p-accordion-panel>
      </p-accordion>
    </p-card>
  `,
  styles: [`
    :host ::ng-deep .p-accordion .p-accordion-content {
      padding: 0.75rem 1rem;
    }
    :host ::ng-deep .p-accordion-header {
      padding: 0.75rem 1rem;
      background: #f8f9fa;
      border-bottom: 1px solid #e9ecef;
    }
    :host ::ng-deep .p-accordion-header:focus {
      box-shadow: none;
    }
    :host ::ng-deep .p-card {
      transition: transform 0.2s ease, box-shadow 0.2s ease;
    }
    :host ::ng-deep .p-card:hover {
      transform: translateY(-2px);
      box-shadow: 0 8px 16px rgba(0,0,0,0.1);
    }
    .novel-cover-img {
      aspect-ratio: 2/3;
      object-fit: cover;
      border-radius: 0.5rem 0.5rem 0 0;
      width: 100%;
    }
    .novel-card-content {
      display: flex;
      flex-direction: column;
      height: 100%;
    }
    .novel-card-body {
      flex: 1;
      display: flex;
      flex-direction: column;
    }
    .novel-description {
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
      font-size: 0.8125rem;
      line-height: 1.5;
      color: #4b5563;
    }
    .genre-tag {
      font-size: 0.625rem;
      padding: 0.125rem 0.5rem;
    }
    .volume-table-container {
      margin-top: 0.75rem;
    }
    :host ::ng-deep .volume-table-container .p-datatable-table {
      background: transparent;
    }
    :host ::ng-deep .volume-table-container thead tr th {
      background: #f8f9fa;
      font-size: 0.7rem;
      padding: 0.375rem 0.5rem;
      font-weight: 600;
      color: #6b7280;
      text-transform: uppercase;
      letter-spacing: 0.025em;
    }
    :host ::ng-deep .volume-table-container tbody tr td {
      padding: 0.5rem;
      font-size: 0.8125rem;
      border-bottom: 1px solid #f1f5f9;
    }
    :host ::ng-deep .volume-table-container tbody tr:last-child td {
      border-bottom: none;
    }
  `]
})
export class NovelCardComponent {
  @Input() novel!: NovelRow;
  @Input() expanded = false;
  @Input() volumeLoading: Record<string, boolean> = {};
  @Input() volumes: Record<string, CatalogVolume[]> = {};
  @Input() actionBusy: Record<string, boolean> = {};

  @Output() toggleExpand = new EventEmitter<string>();
  @Output() enrich = new EventEmitter<NovelRow>();
  @Output() prepareSplit = new EventEmitter<{volume: CatalogVolume, novel: NovelRow}>();
  @Output() translateVolume = new EventEmitter<{volume: CatalogVolume, novel: NovelRow}>();
  @Output() createAudio = new EventEmitter<{volume: CatalogVolume, novel: NovelRow}>();
  @Output() downloadAudio = new EventEmitter<CatalogVolume>();

  volumeLabel(volume: CatalogVolume): string {
    if (volume.volumeNumber !== null && volume.volumeNumber !== undefined) {
      return `Vol. ${String(volume.volumeNumber).padStart(2, '0')}`;
    }
    return volume.label || '—';
  }

  formatSize(bytes: number | null | undefined): string {
    if (!bytes && bytes !== 0) return 'tamaño desconocido';
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
}