import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, signal, computed } from '@angular/core';
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
import { ProgressBarModule } from 'primeng/progressbar';
import { BadgeModule } from 'primeng/badge';
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
    SkeletonModule,
    ProgressBarModule,
    BadgeModule,
  ],
  template: `
    <p-card
      class="novel-card"
      [ngClass]="{ 'novel-card--enriched': isEnriched(), 'novel-card--loading': volumeLoading[novel.id] }"
      [style]="{ height: '100%', display: 'flex', flexDirection: 'column' }">
      <!-- Cover Section -->
      <div class="novel-card-cover" #coverRef>
        @if (novel.anilistCoverUrl) {
          <p-image
            [src]="novel.anilistCoverUrl"
            [preview]="true"
            [alt]="novel.title"
            class="novel-card-cover-img"
            (click)="onCoverClick($event)">
          </p-image>
        } @else {
          <div class="novel-card-cover-placeholder">
            <div class="cover-placeholder-content">
              <i class="pi pi-book"></i>
              <span>{{ getTitleInitial() }}</span>
            </div>
          </div>
        }

        <!-- Overlay Actions (on hover/focus) -->
        <div class="novel-card-overlay">
          <div class="overlay-actions">
            <!-- Progress Badge -->
            @if (hasProgress()) {
              <p-badge
                [value]="getProgressPercent() + '%'"
                severity="info"
                class="progress-badge"
                [pTooltip]="'Le\u00eddo: ' + getProgressPercent() + '%'">
              </p-badge>
            }

            <!-- Enriched Indicator -->
            @if (isEnriched()) {
              <p-badge value="AniList" severity="success" size="small" class="enriched-badge">
                <i class="pi pi-verified mr-1"></i>
              </p-badge>
            }
          </div>

          <!-- Quick Actions on Hover -->
          <div class="overlay-quick-actions">
            @if (!novel.anilistCoverUrl && !novel.anilistEnriching) {
              <p-button
                icon="pi pi-sparkles"
                severity="info"
                rounded="true"
                [text]="true"
                (click)="onEnrich($event)"
                pTooltip="Enriquecer con AniList"
                class="overlay-btn">
              </p-button>
            }
            @if (novel.anilistEnriching) {
              <p-button
                icon="pi pi-spin pi-spinner"
                severity="info"
                rounded="true"
                [text]="true"
                [disabled]="true"
                class="overlay-btn">
              </p-button>
            }
          </div>
        </div>
      </div>

      <!-- Card Content -->
      <div class="novel-card-content">
        <!-- Header: Title & Category -->
        <div class="novel-card-header">
          <div class="header-main">
            <h3 class="novel-title" [title]="novel.title">{{ novel.title }}</h3>
            <button
              type="button"
              class="detail-btn"
              (click)="viewDetail.emit(novel.id)"
              [attr.aria-label]="'Ver detalle de ' + novel.title"
              pTooltip="Ver detalle completo">
              <i class="pi pi-eye"></i>
              <span>Ver detalle</span>
            </button>
          </div>
          <div class="novel-meta">
            <p-tag
              *ngIf="novel.category"
              [value]="novel.category"
              severity="secondary"
              size="small"
              class="category-tag">
            </p-tag>
            <span class="volume-count" *ngIf="!novel.category">{{ novel.volumeCount }} vol.</span>
          </div>
        </div>

        <!-- AniList Enriched Info (progressive disclosure) -->
        @if (isEnriched()) {
          <div class="novel-enriched-info">
            <!-- Score Row -->
            <div class="enriched-row">
              @if (novel.anilistAverageScore) {
                <div class="score-badge" [pTooltip]="'Puntuaci\u00f3n: ' + novel.anilistAverageScore + '%'">
                  <i class="pi pi-star-fill"></i>
                  <span>{{ novel.anilistAverageScore }}%</span>
                </div>
              }
              @if (novel.anilistStatus) {
                <p-tag
                  [value]="novel.anilistStatus"
                  severity="info"
                  size="small"
                  class="status-tag">
                </p-tag>
              }
              @if (novel.anilistFormat) {
                <p-tag
                  [value]="novel.anilistFormat"
                  severity="secondary"
                  size="small"
                  class="format-tag">
                </p-tag>
              }
            </div>

            <!-- Genres -->
            @if (novel.anilistGenres && novel.anilistGenres.length > 0) {
              <div class="genres-row">
                @for (genre of novel.anilistGenres.slice(0, 3); track genre) {
                  <p-tag [value]="genre" severity="contrast" size="small" class="genre-tag"></p-tag>
                }
                @if (novel.anilistGenres.length > 3) {
                  <span class="more-badge" [pTooltip]="novel.anilistGenres.slice(3).join(', ')">
                    +{{ novel.anilistGenres.length - 3 }}
                  </span>
                }
              </div>
            }

            <!-- Tags (progressive) -->
            @if (novel.anilistTags && novel.anilistTags.length > 0) {
              <div class="tags-row">
                @for (tag of novel.anilistTags.slice(0, 2); track tag) {
                  <p-tag [value]="tag" severity="secondary" size="small" icon="pi pi-tag" class="tag-tag"></p-tag>
                }
                @if (novel.anilistTags.length > 2) {
                  <span class="more-badge" [pTooltip]="novel.anilistTags.slice(2).join(', ')">
                    +{{ novel.anilistTags.length - 2 }}
                  </span>
                }
              </div>
            }

            <!-- Description (truncated, expandable) -->
            @if (novel.anilistDescription) {
              <div class="description-row">
                <p [class]="descriptionExpanded() ? 'description-full' : 'description-truncated'"
                   [title]="novel.anilistDescription">
                  {{ novel.anilistDescription }}
                </p>
                @if (descriptionTruncated()) {
                  <button
                    type="button"
                    class="expand-desc-btn"
                    (click)="toggleDescription($event)"
                    [attr.aria-expanded]="descriptionExpanded()">
                    {{ descriptionExpanded() ? 'Ver menos' : 'Ver m\u00e1s' }}
                  </button>
                }
              </div>
            }
          </div>
        }

        <!-- Not Enriched State -->
        @if (!isEnriched()) {
          <div class="novel-not-enriched">
            <div class="not-enriched-content">
              <i class="pi pi-info-circle"></i>
              <span>Sin enriquecer</span>
            </div>
            <p-button
              *ngIf="!novel.anilistEnriching"
              label="Enriquecer"
              icon="pi pi-sparkles"
              size="small"
              styleClass="p-button-info p-button-outlined w-full"
              (click)="onEnrich($event)"
              pTooltip="Buscar en AniList y obtener car\u00e1tula, descripci\u00f3n, g\u00e9neros, puntuaci\u00f3n">
            </p-button>
            <p-button
              *ngIf="novel.anilistEnriching"
              label="Enriqueciendo..."
              icon="pi pi-spin pi-spinner"
              size="small"
              styleClass="p-button-info w-full"
              [disabled]="true">
            </p-button>
            <p-button
              *ngIf="novel.anilistError"
              label="Reintentar"
              icon="pi pi-refresh"
              size="small"
              styleClass="p-button-warning p-button-outlined w-full"
              (click)="onEnrich($event)">
            </p-button>
          </div>
        }

        <!-- Progress Bar (if we have progress data) -->
        @if (hasProgress()) {
          <div class="progress-section">
            <div class="progress-header">
              <span class="progress-label">Progreso de lectura</span>
              <span class="progress-value">{{ getProgressPercent() }}%</span>
            </div>
            <p-progressBar
              [value]="getProgressPercent()"
              [showValue]="false"
              [style]="{ height: '6px', borderRadius: '9999px' }"
              [styleClass]="'progress-bar-custom'">
          </p-progressBar>
        </div>

        }

        <!-- Error Message -->        <!-- Error Message -->
        @if (novel.anilistError) {
          <div class="error-message">
            <i class="pi pi-exclamation-triangle"></i>
            <span>{{ novel.anilistError }}</span>
          </div>
        }
      </div>
    </p-card>
  `,
  styles: [`
    /* ============================================================
       NOVEL CARD - Modern Redesign
       ============================================================ */

    :host {
      display: block;
    }

    /* --- Base Card --- */
    .novel-card {
      height: 100%;
      display: flex;
      flex-direction: column;
      border-radius: 12px;
      overflow: hidden;
      border: 1px solid #e5e7eb;
      background: #ffffff;
      transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
    }

    .novel-card:hover {
      transform: translateY(-4px);
      box-shadow: 0 12px 24px rgba(0, 0, 0, 0.08), 0 4px 8px rgba(0, 0, 0, 0.04);
      border-color: #d1d5db;
    }

    .novel-card:focus-within {
      outline: 2px solid #3b82f6;
      outline-offset: 2px;
    }

    /* Enriched State */
    .novel-card--enriched {
      border-color: #3b82f6;
    }

    .novel-card--enriched:hover {
      box-shadow: 0 12px 24px rgba(59, 130, 246, 0.15), 0 4px 8px rgba(59, 130, 246, 0.08);
      border-color: #3b82f6;
    }

    /* Expanded State */
    .novel-card--expanded {
      border-radius: 12px 12px 0 0;
    }

    /* Loading State */
    .novel-card--loading {
      opacity: 0.7;
    }

    /* --- Cover Section --- */
    .novel-card-cover {
      position: relative;
      width: 100%;
      aspect-ratio: 2/3;
      background: linear-gradient(135deg, #f3f4f6 0%, #e5e7eb 100%);
      overflow: hidden;
      border-radius: 12px 12px 0 0;
    }

    .novel-card-cover-img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      transition: transform 0.4s ease, filter 0.3s ease;
      filter: brightness(1);
    }

    .novel-card:hover .novel-card-cover-img {
      transform: scale(1.05);
      filter: brightness(1.05);
    }

    .novel-card-cover-placeholder {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    }

    .cover-placeholder-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.5rem;
      color: rgba(255, 255, 255, 0.9);
    }

    .cover-placeholder-content i {
      font-size: 3rem;
      opacity: 0.8;
    }

    .cover-placeholder-content span {
      font-size: 2rem;
      font-weight: 700;
      letter-spacing: 0.05em;
    }

    /* --- Overlay Actions --- */
    .novel-card-overlay {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      padding: 1rem;
      opacity: 0;
      background: linear-gradient(to top, rgba(0, 0, 0, 0.6) 0%, transparent 60%);
      transition: opacity 0.25s ease;
      pointer-events: none;
    }

    .novel-card:hover .novel-card-overlay,
    .novel-card:focus-within .novel-card-overlay {
      opacity: 1;
      pointer-events: auto;
    }

    .overlay-actions {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
    }

    .progress-badge {
      font-weight: 600;
      background: rgba(59, 130, 246, 0.95) !important;
      color: white !important;
      backdrop-filter: blur(4px);
    }

    .enriched-badge {
      background: rgba(16, 185, 129, 0.95) !important;
      color: white !important;
      backdrop-filter: blur(4px);
    }

    .overlay-quick-actions {
      display: flex;
      gap: 0.5rem;
    }

    .overlay-btn {
      width: 36px;
      height: 36px;
      background: rgba(255, 255, 255, 0.9) !important;
      border-radius: 50% !important;
      backdrop-filter: blur(4px);
      transition: transform 0.2s ease, background 0.2s ease;
    }

    .overlay-btn:hover {
      transform: scale(1.1);
      background: white !important;
    }

    .overlay-btn:focus-visible {
      outline: 2px solid #3b82f6;
      outline-offset: 2px;
    }

    /* --- Card Content --- */
    .novel-card-content {
      flex: 1;
      display: flex;
      flex-direction: column;
      padding: 1rem;
      min-height: 0;
    }

    /* --- Header --- */
    .novel-card-header {
      margin-bottom: 0.75rem;
    }

    .header-main {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }

    .novel-title {
      font-size: 0.9375rem;
      font-weight: 600;
      color: #111827;
      line-height: 1.4;
      margin: 0;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      transition: color 0.2s ease;
      flex: 1;
      min-width: 0;
    }

    .novel-card:hover .novel-title {
      color: #2563eb;
    }

    .detail-btn {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.375rem 0.75rem;
      background: #f3f4f6;
      border: 1px solid #e5e7eb;
      border-radius: 6px;
      color: #374151;
      font-size: 0.75rem;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.2s ease;
      white-space: nowrap;
      flex-shrink: 0;
    }

    .detail-btn:hover {
      background: #e5e7eb;
      border-color: #d1d5db;
      color: #111827;
    }

    .detail-btn:focus {
      outline: none;
      box-shadow: 0 0 0 2px #3b82f6;
    }

    .detail-btn i {
      font-size: 0.875rem;
    }

    .novel-meta {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex-wrap: wrap;
    }

    .category-tag {
      font-weight: 500;
    }

    .volume-count {
      font-size: 0.75rem;
      color: #6b7280;
      font-weight: 500;
    }

    /* --- Enriched Info --- */
    .novel-enriched-info {
      margin-bottom: 0.75rem;
      padding-top: 0.5rem;
      border-top: 1px solid #f1f5f9;
      animation: fadeIn 0.2s ease;
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(-4px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .enriched-row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.375rem;
      margin-bottom: 0.375rem;
    }

    .score-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.1875rem 0.5rem;
      background: #fef3c7;
      color: #92400e;
      border-radius: 9999px;
      font-size: 0.7rem;
      font-weight: 600;
    }

    .score-badge i {
      font-size: 0.75rem;
    }

    .status-tag,
    .format-tag {
      font-weight: 500;
    }

    .genres-row,
    .tags-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      align-items: center;
      margin-bottom: 0.375rem;
    }

    .genre-tag {
      font-weight: 500;
    }

    .tag-tag {
      font-weight: 500;
    }

    .more-badge {
      font-size: 0.625rem;
      color: #9ca3af;
      padding: 0.125rem 0.375rem;
      background: #f3f4f6;
      border-radius: 9999px;
      font-weight: 500;
    }

    .description-row {
      margin-top: 0.25rem;
    }

    .description-truncated {
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
      font-size: 0.8125rem;
      line-height: 1.5;
      color: #4b5563;
    }

    .description-full {
      font-size: 0.8125rem;
      line-height: 1.5;
      color: #4b5563;
    }

    .expand-desc-btn {
      background: none;
      border: none;
      color: #3b82f6;
      font-size: 0.75rem;
      font-weight: 500;
      cursor: pointer;
      padding: 0.25rem 0;
      margin-top: 0.25rem;
    }

    .expand-desc-btn:hover {
      text-decoration: underline;
    }

    /* --- Not Enriched State --- */
    .novel-not-enriched {
      margin-top: 0.5rem;
      padding-top: 0.75rem;
      border-top: 1px dashed #e5e7eb;
      animation: fadeIn 0.2s ease;
    }

    .not-enriched-content {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      color: #9ca3af;
      font-size: 0.8125rem;
      margin-bottom: 0.75rem;
    }

    .not-enriched-content i {
      font-size: 1.25rem;
    }

    /* --- Progress Section --- */
    .progress-section {
      margin-top: 0.5rem;
      padding-top: 0.75rem;
      border-top: 1px solid #f1f5f9;
    }

    .progress-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 0.375rem;
    }

    .progress-label {
      font-size: 0.75rem;
      font-weight: 500;
      color: #6b7280;
    }

    .progress-value {
      font-size: 0.75rem;
      font-weight: 600;
      color: #3b82f6;
    }

    .progress-bar-custom {
      height: 6px;
      border-radius: 9999px;
      background: #e5e7eb;
    }

    :host ::ng-deep .progress-bar-custom .p-progressbar-value {
      background: linear-gradient(90deg, #3b82f6 0%, #8b5cf6 100%);
      border-radius: 9999px;
      transition: width 0.4s ease;
    }

    /* --- Volumes Section --- */
    .volumes-section {
      margin-top: 0.75rem;
      padding-top: 0.75rem;
      border-top: 1px solid #e5e7eb;
      animation: slideDown 0.25s ease;
    }

    @keyframes slideDown {
      from { opacity: 0; transform: translateY(-8px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .volumes-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 0.75rem;
    }

    .volumes-title {
      font-size: 0.8125rem;
      font-weight: 600;
      color: #374151;
    }

    /* --- Error Message --- */
    .error-message {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.5rem 0.75rem;
      background: #fef2f2;
      border: 1px solid #fecaca;
      border-radius: 8px;
      color: #991b1b;
      font-size: 0.75rem;
      margin-top: 0.5rem;
    }

    .error-message i {
      font-size: 0.875rem;
    }

    /* ============================================================
       RESPONSIVE
       ============================================================ */
    @media (max-width: 768px) {
      .novel-card-content {
        padding: 0.875rem;
      }

      .novel-title {
        font-size: 0.875rem;
      }

      .novel-card-cover {
        aspect-ratio: 3/4;
      }
    }

    @media (max-width: 480px) {
      .novel-card-cover {
        aspect-ratio: 3/4;
      }

      .novel-card-content {
        padding: 0.75rem;
      }

      .novel-title {
        font-size: 0.8125rem;
      }

      .enriched-row {
        gap: 0.25rem;
      }

      .score-badge {
        padding: 0.125rem 0.375rem;
        font-size: 0.65rem;
      }

      .volume-item {
        flex-direction: column;
        gap: 0.75rem;
      }

      .volume-status-actions {
        flex-direction: column;
        align-items: stretch;
      }

      .volume-actions {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 0.375rem;
      }
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
  @Output() viewDetail = new EventEmitter<string>();

  // Internal state
  descriptionExpanded = signal(false);

  // Computed
  isEnriched = computed(() => !!this.novel.anilistCoverUrl);
  hasProgress = computed(() => false); // Placeholder - would need progress data
  descriptionTruncated = computed(() => {
    if (!this.novel.anilistDescription) return false;
    return this.novel.anilistDescription.length > 200;
  });

  // Event handlers
  onEnrich(event: Event): void {
    event.stopPropagation();
    this.enrich.emit(this.novel);
  }

  toggleDescription(event: Event): void {
    event.stopPropagation();
    this.descriptionExpanded.set(!this.descriptionExpanded());
  }

  onCoverClick(event: Event): void {
    // Could navigate to detail view
  }


  onPrepareSplit(volume: CatalogVolume): void {
    this.prepareSplit.emit({ volume, novel: this.novel });
  }

  onTranslateVolume(volume: CatalogVolume): void {
    this.translateVolume.emit({ volume, novel: this.novel });
  }

  onCreateAudio(volume: CatalogVolume): void {
    this.createAudio.emit({ volume, novel: this.novel });
  }

  onDownloadAudio(volume: CatalogVolume): void {
    this.downloadAudio.emit(volume);
  }

  // Helpers
  getTitleInitial(): string {
    return this.novel.title?.charAt(0).toUpperCase() || '?';
  }

  getProgressPercent(): number {
    // Placeholder - would need progress data from the backend
    return 0;
  }

  volumeLabel(volume: CatalogVolume): string {
    if (volume.volumeNumber !== null && volume.volumeNumber !== undefined) {
      return `Vol. ${String(volume.volumeNumber).padStart(2, '0')}`;
    }
    return volume.label || '\u2014';
  }

  formatSize(bytes: number | null | undefined): string {
    if (!bytes && bytes !== 0) return 'tama\u00f1o desconocido';
    if (bytes < 1024 * 1024) return '${Math.round(bytes / 1024)} KB';
    return '${(bytes / (1024 * 1024)).toFixed(1)} MB';
  }
}