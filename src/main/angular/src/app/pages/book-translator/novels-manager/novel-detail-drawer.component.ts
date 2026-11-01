import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, signal, computed, ElementRef, ViewChild, AfterViewInit, OnDestroy } from '@angular/core';
import { CardModule } from 'primeng/card';
import { ImageModule } from 'primeng/image';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ButtonModule } from 'primeng/button';
import { RippleModule } from 'primeng/ripple';
import { AccordionModule } from 'primeng/accordion';
import { TableModule } from 'primeng/table';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ProgressBarModule } from 'primeng/progressbar';
import { BadgeModule } from 'primeng/badge';
import { DividerModule } from 'primeng/divider';
import { ScrollPanelModule } from 'primeng/scrollpanel';
import { DrawerModule } from 'primeng/drawer';
import { CatalogVolume, NovelSummary } from './novels-manager.service';

export interface NovelRow extends NovelSummary {
  volumesLoaded: boolean;
  anilistEnriching?: boolean;
  anilistError?: string;
}

@Component({
  selector: 'novel-detail-drawer',
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
    ProgressBarModule,
    BadgeModule,
    DividerModule,
    ScrollPanelModule,
    DrawerModule,
  ],
  template: `
    <p-drawer
      [visible]="visible"
      position="right"
      [style]="{ width: '500px', maxWidth: '100vw', height: '100vh' }"
      [showCloseIcon]="true"
      (onClose)="close.emit()"
      [modal]="true"
      [styleClass]="'novel-detail-drawer'">

      <ng-template pTemplate="header">
        <div class="drawer-header">
          <div class="header-content">
            <div *ngIf="novel.anilistCoverUrl; else coverPlaceholder">
              <p-image
                [src]="novel.anilistCoverUrl"
                [alt]="novel.title"
                class="header-cover-img"
                [preview]="false">
              </p-image>
            </div>
            <ng-template #coverPlaceholder>
              <div class="header-cover-placeholder">
                <i class="pi pi-book"></i>
              </div>
            </ng-template>
            <div class="header-info">
              <h2 class="drawer-title" [title]="novel.title">{{ novel.title }}</h2>
              <div class="drawer-meta">
                <p-tag *ngIf="novel.category" [value]="novel.category" severity="secondary" size="small"></p-tag>
                <span class="volume-count">{{ novel.volumeCount }} volúmenes</span>
              </div>
            </div>
          </div>

          <div *ngIf="isEnriched()" class="header-badges">
            <div *ngIf="novel.anilistAverageScore" class="score-badge" [pTooltip]="'Puntuación: ' + novel.anilistAverageScore + '%'">
              <i class="pi pi-star-fill"></i>
              <span>{{ novel.anilistAverageScore }}%</span>
            </div>
            <p-tag *ngIf="novel.anilistStatus" [value]="novel.anilistStatus" severity="info" size="small"></p-tag>
            <p-tag *ngIf="novel.anilistFormat" [value]="novel.anilistFormat" severity="secondary" size="small"></p-tag>
          </div>
        </div>
      </ng-template>

      <ng-template pTemplate="content">
        <div class="drawer-content">
          <p-scrollPanel [style]="{ height: 'calc(100vh - 200px)' }">
            <div class="drawer-body">
              <!-- Enriched State -->
              <div *ngIf="isEnriched()" class="enriched-section">
                <div class="section-header">
                  <i class="pi pi-verified text-green-500"></i>
                  <span>Enriquecido con AniList</span>
                </div>

                <div *ngIf="novel.anilistGenres && novel.anilistGenres.length > 0" class="field-group">
                  <label class="field-label">Géneros</label>
                  <div class="chips-container">
                    <p-tag *ngFor="let genre of novel.anilistGenres" [value]="genre" severity="contrast" size="small"></p-tag>
                  </div>
                </div>

                <div *ngIf="novel.anilistTags && novel.anilistTags.length > 0" class="field-group">
                  <label class="field-label">Tags</label>
                  <div class="chips-container">
                    <p-tag *ngFor="let tag of novel.anilistTags.slice(0, 6)" [value]="tag" severity="secondary" size="small" icon="pi pi-tag"></p-tag>
                    <span *ngIf="novel.anilistTags.length > 6" class="more-badge" [pTooltip]="novel.anilistTags.slice(6).join(', ')">
                      +{{ novel.anilistTags.length - 6 }}
                    </span>
                  </div>
                </div>

                <div *ngIf="novel.anilistDescription" class="field-group">
                  <label class="field-label">Sinopsis</label>
                  <div class="description-container">
                    <p class="description-text" [class.expanded]="descriptionExpanded()">
                      {{ novel.anilistDescription }}
                    </p>
                    <button *ngIf="descriptionTruncated()"
                      type="button"
                      class="expand-desc-btn"
                      (click)="toggleDescription()"
                      [attr.aria-expanded]="descriptionExpanded()">
                      {{ descriptionExpanded() ? 'Ver menos' : 'Ver más' }}
                    </button>
                  </div>
                </div>

                <div class="additional-info-grid">
                  <div *ngIf="novel.anilistSource" class="info-item">
                    <span class="info-label">Origen</span>
                    <span class="info-value">{{ novel.anilistSource }}</span>
                  </div>
                  <div *ngIf="novel.anilistCountryOfOrigin" class="info-item">
                    <span class="info-label">País</span>
                    <span class="info-value">{{ novel.anilistCountryOfOrigin }}</span>
                  </div>
                  <div *ngIf="novel.anilistVolumes" class="info-item">
                    <span class="info-label">Volúmenes (AniList)</span>
                    <span class="info-value">{{ novel.anilistVolumes }}</span>
                  </div>
                  <div *ngIf="novel.anilistChapters" class="info-item">
                    <span class="info-label">Capítulos (AniList)</span>
                    <span class="info-value">{{ novel.anilistChapters }}</span>
                  </div>
                </div>
              </div>

              <!-- Not Enriched State -->
              <div *ngIf="!isEnriched()" class="not-enriched-section">
                <div class="not-enriched-content">
                  <i class="pi pi-info-circle text-2xl"></i>
                  <span>Esta obra no ha sido enriquecida con AniList</span>
                </div>
                <p-button
                  *ngIf="!novel.anilistEnriching"
                  label="Enriquecer con AniList"
                  icon="pi pi-sparkles"
                  styleClass="p-button-info w-full"
                  (click)="onEnrich()"
                  pTooltip="Buscar en AniList y obtener carátula, descripción, géneros, puntuación">
                </p-button>
                <p-button
                  *ngIf="novel.anilistEnriching"
                  label="Enriqueciendo..."
                  icon="pi pi-spin pi-spinner"
                  styleClass="p-button-info w-full"
                  [disabled]="true">
                </p-button>
                <p-button
                  *ngIf="novel.anilistError"
                  label="Reintentar"
                  icon="pi pi-refresh"
                  styleClass="p-button-warning p-button-outlined w-full"
                  (click)="onEnrich()">
                </p-button>
                <div *ngIf="novel.anilistError" class="error-message mt-2">
                  <i class="pi pi-exclamation-triangle"></i>
                  <span>{{ novel.anilistError }}</span>
                </div>
              </div>

              <!-- Volumes Section -->
              <div class="volumes-section">
                <div class="section-header">
                  <h3 class="section-title">
                    <i class="pi pi-book mr-2"></i>
                    Volúmenes ({{ volumes[novel.id]?.length || 0 }})
                  </h3>
                  <p-progressSpinner *ngIf="volumeLoading[novel.id]" styleClass="w-5 h-5"></p-progressSpinner>
                </div>

                <div *ngIf="volumes[novel.id] && volumes[novel.id].length > 0" class="volumes-list">
                  <div *ngFor="let volume of volumes[novel.id]; trackBy: trackByVolumeId" class="volume-card" [class.running]="volume.running">
                    <div class="volume-header">
                      <div class="volume-identity">
                        <span class="volume-number">{{ volumeLabel(volume) }}</span>
                        <p-tag *ngIf="volume.translatorGroup" [value]="volume.translatorGroup" severity="secondary" size="small" class="translator-tag"></p-tag>
                      </div>

                      <div class="volume-formats">
                        <p-tag *ngFor="let file of volume.files"
                          [value]="file.format + (file.presentLocally ? ' ✓' : '')"
                          [severity]="file.presentLocally ? 'success' : 'info'"
                          size="small"
                          [pTooltip]="file.fileName + ' — ' + formatSize(file.sizeBytes)"
                          class="format-tag">
                        </p-tag>
                      </div>
                    </div>

                    <div class="volume-status-actions">
                      <div class="volume-status">
                        <p-tag [value]="volume.translated ? 'Traducido' : 'No Traducido'" [severity]="volume.translated ? 'success' : 'danger'" size="small"></p-tag>
                        <p-tag [value]="volume.audioCreated ? 'Audio ✓' : 'Sin Audio'" [severity]="volume.audioCreated ? 'success' : 'danger'" size="small"></p-tag>
                        <p-tag *ngIf="volume.removedFromSource" value="Retirado" severity="warn" size="small"></p-tag>
                        <p-tag *ngIf="volume.bookId && !volume.splitDone && !volume.running" value="Texto listo" severity="info" size="small"></p-tag>
                        <span *ngIf="volume.running" class="running-indicator">
                          <i class="pi pi-spin pi-spinner"></i> Procesando
                        </span>
                      </div>

                      <div class="volume-actions">
                        <p-button label="Split" icon="pi pi-minus" size="small" [disabled]="!volume.canPrepareText || actionBusy[volume.id]" [loading]="actionBusy[volume.id]" (click)="onPrepareSplit(volume)" pTooltip="Preparar texto y abrir editor de split"></p-button>
                        <p-button label="Traducir" icon="pi pi-language" size="small" [outlined]="true" [disabled]="!volume.canTranslate" (click)="onTranslateVolume(volume)" pTooltip="Iniciar traducción (requiere split)"></p-button>
                        <p-button label="Audio" icon="pi pi-volume-up" size="small" [outlined]="true" [disabled]="!volume.canCreateAudio" (click)="onCreateAudio(volume)" pTooltip="Generar audiolibro (requiere traducción)"></p-button>
                        <p-button label="Descargar" icon="pi pi-download" size="small" severity="success" [outlined]="true" [disabled]="!volume.audioCreated" (click)="onDownloadAudio(volume)" pTooltip="Descargar audiolibro (mp3 o ZIP)"></p-button>
                      </div>
                    </div>
                  </div>
                </div>

                <div *ngIf="!volumes[novel.id] || volumes[novel.id].length === 0" class="empty-state">
                  <i class="pi pi-inbox text-4xl text-gray-300"></i>
                  <p class="text-gray-500 mt-2">Esta obra no tiene volúmenes registrados</p>
                  <p class="text-xs text-gray-400 mt-1">Ejecuta una sincronización o enriquece la obra para obtener volúmenes</p>
                </div>
              </div>

              <div *ngIf="novel.anilistError" class="error-alert">
                <i class="pi pi-exclamation-triangle"></i>
                <span>{{ novel.anilistError }}</span>
              </div>
            </div>
          </p-scrollPanel>
        </div>
      </ng-template>

      <ng-template pTemplate="footer">
        <div class="drawer-footer">
          <p-button label="Cerrar" icon="pi pi-times" styleClass="p-button-text" (click)="close.emit()"></p-button>
          <p-button *ngIf="!isEnriched() && !novel.anilistEnriching" label="Enriquecer con AniList" icon="pi pi-sparkles" styleClass="p-button-info" (click)="onEnrich()" [loading]="novel.anilistEnriching"></p-button>
        </div>
      </ng-template>
    </p-drawer>
  `,
  styles: [`
    /* NOVEL DETAIL DRAWER - Modern Side Sheet */

    :host ::ng-deep .novel-detail-drawer .p-drawer {
      border-radius: 0;
      border-left: 1px solid #e5e7eb;
    }

    :host ::ng-deep .novel-detail-drawer .p-drawer-content {
      height: 100%;
      display: flex;
      flex-direction: column;
    }

    :host ::ng-deep .novel-detail-drawer .p-drawer-header {
      padding: 0;
      border-bottom: 1px solid #e5e7eb;
      background: #fafafa;
    }

    :host ::ng-deep .novel-detail-drawer .p-drawer-body {
      flex: 1;
      overflow: hidden;
    }

    :host ::ng-deep .novel-detail-drawer .p-drawer-footer {
      padding: 1rem;
      border-top: 1px solid #e5e7eb;
      background: #fafafa;
    }

    .drawer-header {
      padding: 1.5rem;
      background: linear-gradient(135deg, #fafafa 0%, #f3f4f6 100%);
    }

    .header-content {
      display: flex;
      gap: 1rem;
      margin-bottom: 1rem;
    }

    .header-cover-img {
      width: 80px;
      height: 120px;
      object-fit: cover;
      border-radius: 8px;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
      flex-shrink: 0;
    }

    .header-cover-placeholder {
      width: 80px;
      height: 120px;
      border-radius: 8px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      color: rgba(255, 255, 255, 0.9);
      flex-shrink: 0;
    }

    .header-cover-placeholder i {
      font-size: 3rem;
      opacity: 0.8;
    }

    .header-cover-placeholder span {
      font-size: 2rem;
      font-weight: 700;
      letter-spacing: 0.05em;
    }

    .header-info {
      flex: 1;
      min-width: 0;
    }

    .drawer-title {
      font-size: 1.125rem;
      font-weight: 700;
      color: #111827;
      line-height: 1.3;
      margin: 0 0 0.5rem 0;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .drawer-meta {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex-wrap: wrap;
    }

    .volume-count {
      font-size: 0.75rem;
      color: #6b7280;
      font-weight: 500;
    }

    .header-badges {
      display: flex;
      flex-wrap: wrap;
      gap: 0.375rem;
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

    .score-badge i { font-size: 0.75rem; }

    .drawer-body { padding: 1.5rem; }

    .enriched-section {
      margin-bottom: 1.5rem;
      padding-bottom: 1.5rem;
      border-bottom: 1px solid #e5e7eb;
    }

    .section-header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 1rem;
      padding-bottom: 0.75rem;
      border-bottom: 1px solid #e5e7eb;
    }

    .section-header i { font-size: 1.25rem; }
    .section-header span { font-size: 1rem; font-weight: 600; color: #374151; }

    .field-group { margin-bottom: 1rem; }

    .field-label {
      display: block;
      font-size: 0.75rem;
      font-weight: 600;
      color: #6b7280;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      margin-bottom: 0.5rem;
    }

    .chips-container { display: flex; flex-wrap: wrap; gap: 0.25rem; }

    .description-container { position: relative; }

    .description-text {
      font-size: 0.875rem;
      line-height: 1.6;
      color: #374151;
      margin: 0;
    }

    .description-text:not(.expanded) {
      display: -webkit-box;
      -webkit-line-clamp: 4;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .expand-desc-btn {
      background: none; border: none; color: #3b82f6;
      font-size: 0.75rem; font-weight: 500; cursor: pointer;
      padding: 0.25rem 0; margin-top: 0.25rem;
    }
    .expand-desc-btn:hover { text-decoration: underline; }

    .additional-info-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 0.75rem;
      margin-top: 1rem;
      padding-top: 1rem;
      border-top: 1px solid #e5e7eb;
    }

    .info-item { display: flex; flex-direction: column; gap: 0.25rem; }
    .info-label { font-size: 0.6875rem; font-weight: 600; color: #6b7280; text-transform: uppercase; letter-spacing: 0.05em; }
    .info-value { font-size: 0.8125rem; color: #374151; }

    .not-enriched-section {
      text-align: center;
      padding: 2rem 1rem;
      background: #fafafa;
      border-radius: 8px;
      margin-bottom: 1.5rem;
      border: 1px dashed #e5e7eb;
    }

    .not-enriched-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      color: #9ca3af;
      font-size: 0.8125rem;
      margin-bottom: 0.75rem;
    }
    .not-enriched-content i { font-size: 2.5rem; opacity: 0.5; }
    .not-enriched-content span { font-size: 1rem; color: #6b7280; }

    .volumes-section { margin-top: 1.5rem; }
    .section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1rem; }
    .section-title { font-size: 1rem; font-weight: 600; color: #374151; display: flex; align-items: center; gap: 0.5rem; }
    .section-title i { color: #3b82f6; }

    .volumes-list { display: flex; flex-direction: column; gap: 0.5rem; }

    .volume-card {
      display: flex; flex-direction: column; gap: 0.75rem;
      padding: 1rem; background: #fafafa; border: 1px solid #e5e7eb; border-radius: 10px;
      transition: all 0.2s ease;
    }
    .volume-card:hover { background: #f8fafc; border-color: #d1d5db; }
    .volume-running { border-color: #3b82f6; background: #eff6ff; }

    .volume-header { display: flex; flex-direction: column; gap: 0.5rem; }
    .volume-identity { display: flex; align-items: center; gap: 0.5rem; }
    .volume-number { font-size: 0.8125rem; font-weight: 600; color: #374151; background: #f3f4f6; padding: 0.125rem 0.625rem; border-radius: 6px; }
    .translator-tag { font-weight: 500; }

    .volume-formats { display: flex; flex-wrap: wrap; gap: 0.25rem; }
    .volume-formats ::ng-deep .p-tag { font-weight: 500; }

    .volume-status-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 0.5rem; min-width: 180px; }
    .volume-status { display: flex; flex-wrap: wrap; gap: 0.25rem; justify-content: flex-end; }
    .running-indicator { display: inline-flex; align-items: center; gap: 0.25rem; font-size: 0.6875rem; color: #3b82f6; font-weight: 500; }
    .volume-actions { display: flex; flex-wrap: wrap; gap: 0.375rem; justify-content: flex-end; }

    .empty-state {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      padding: 2rem; color: #9ca3af; text-align: center;
      background: #fafafa; border-radius: 8px; border: 1px dashed #e5e7eb;
    }
    .empty-state i { font-size: 2rem; margin-bottom: 0.5rem; opacity: 0.5; }
    .empty-state p { margin: 0; }
    .empty-state p:last-child { font-size: 0.75rem; color: #9ca3af; }

    .error-alert {
      display: inline-flex; align-items: flex-start; gap: 0.375rem;
      padding: 0.5rem 0.75rem; background: #fef2f2; border: 1px solid #fecaca; border-radius: 8px;
      color: #991b1b; font-size: 0.75rem; margin-top: 1rem;
    }
    .error-alert i { font-size: 0.875rem; margin-top: 0.125rem; flex-shrink: 0; }

    .drawer-footer { display: flex; justify-content: flex-end; gap: 0.75rem; padding-top: 1rem; }
    .drawer-footer ::ng-deep .p-button { min-width: 120px; }

    :host ::ng-deep .p-scrollpanel .p-scrollpanel-content { padding-right: 0.5rem; }
    :host ::ng-deep .p-scrollpanel .p-scrollpanel-bar { background: #e5e7eb; width: 6px; }
    :host ::ng-deep .p-scrollpanel .p-scrollpanel-bar:hover { background: #d1d5db; }

    @keyframes slideIn {
      from { opacity: 0; transform: translateX(20px); }
      to { opacity: 1; transform: translateX(0); }
    }
    :host ::ng-deep .novel-detail-drawer .p-drawer-content { animation: slideIn 0.3s ease-out; }

    @media (max-width: 768px) {
      .drawer-body { padding: 1rem; }
      .additional-info-grid { grid-template-columns: 1fr; }
    }
    @media (max-width: 480px) {
      .drawer-header { padding: 1rem; }
      .header-content { flex-direction: column; align-items: center; text-align: center; }
      .header-cover-img, .header-cover-placeholder { width: 60px; height: 90px; }
      .drawer-title { font-size: 1rem; }
      .additional-info-grid { grid-template-columns: 1fr; }
      .volume-status-actions { flex-direction: column; align-items: stretch; }
      .volume-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 0.375rem; }
      .volume-actions ::ng-deep .p-button { flex: 1; }
      .volume-status { justify-content: flex-start; }
    }
  `]
})
export class NovelDetailDrawerComponent implements AfterViewInit, OnDestroy {
  @Input() novel!: NovelRow;
  @Input() visible = false;
  @Input() volumes: Record<string, CatalogVolume[]> = {};
  @Input() volumeLoading: Record<string, boolean> = {};
  @Input() actionBusy: Record<string, boolean> = {};

  @Output() close = new EventEmitter<void>();
  @Output() prepareSplit = new EventEmitter<{volume: CatalogVolume, novel: NovelRow}>();
  @Output() translateVolume = new EventEmitter<{volume: CatalogVolume, novel: NovelRow}>();
  @Output() createAudio = new EventEmitter<{volume: CatalogVolume, novel: NovelRow}>();
  @Output() downloadAudio = new EventEmitter<CatalogVolume>();
  @Output() enrich = new EventEmitter<NovelRow>();

  descriptionExpanded = signal(false);

  isEnriched = computed(() => !!this.novel.anilistCoverUrl);
  descriptionTruncated = computed(() => {
    if (!this.novel.anilistDescription) return false;
    return this.novel.anilistDescription.length > 300;
  });

  ngAfterViewInit(): void {}
  ngOnDestroy(): void {}

  onEnrich(): void { this.enrich.emit(this.novel); }
  toggleDescription(): void { this.descriptionExpanded.set(!this.descriptionExpanded()); }
  onPrepareSplit(volume: CatalogVolume): void { this.prepareSplit.emit({ volume, novel: this.novel }); }
  onTranslateVolume(volume: CatalogVolume): void { this.translateVolume.emit({ volume, novel: this.novel }); }
  onCreateAudio(volume: CatalogVolume): void { this.createAudio.emit({ volume, novel: this.novel }); }
  onDownloadAudio(volume: CatalogVolume): void { this.downloadAudio.emit(volume); }

  getTitleInitial(): string { return this.novel.title?.charAt(0).toUpperCase() || '?'; }
  getProgressPercent(): number { return 0; }

  volumeLabel(volume: CatalogVolume): string {
    if (volume.volumeNumber !== null && volume.volumeNumber !== undefined) {
      return `Vol. ${String(volume.volumeNumber).padStart(2, '0')}`;
    }
    return volume.label || '—';
  }

  formatSize(bytes: number | null | undefined): string {
    if (!bytes && bytes !== 0) return 'tamaño desconocido';
    if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  trackByVolumeId(index: number, volume: CatalogVolume): string { return volume.id; }
}