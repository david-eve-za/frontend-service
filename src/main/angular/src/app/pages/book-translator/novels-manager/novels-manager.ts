import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { FormsModule } from '@angular/forms';
import { InputTextModule } from 'primeng/inputtext';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ToastModule } from 'primeng/toast';
import { TableModule } from 'primeng/table';
import { CardModule } from 'primeng/card';
import { SkeletonModule } from 'primeng/skeleton';
import { ImageModule } from 'primeng/image';
import { AccordionModule } from 'primeng/accordion';
import { RippleModule } from 'primeng/ripple';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { PaginatorModule } from 'primeng/paginator';
import { MessageService } from 'primeng/api';
import { firstValueFrom, Subscription, timer, switchMap, takeWhile } from 'rxjs';
import {
  CatalogSyncStatus,
  CatalogVolume,
  NovelSummary,
  NovelsManagerService
} from './novels-manager.service';
import { NovelCardComponent } from './novel-card.component';
import { NovelCardSkeletonComponent } from './novel-card-skeleton.component';

interface NovelRow extends NovelSummary {
  volumesLoaded: boolean;
  anilistEnriching?: boolean;
  anilistError?: string;
}

@Component({
  selector: 'app-novels-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ButtonModule,
    InputTextModule,
    TagModule,
    TooltipModule,
    ToastModule,
    TableModule,
    CardModule,
    SkeletonModule,
    ImageModule,
    AccordionModule,
    RippleModule,
    ProgressSpinnerModule,
    PaginatorModule,
    NovelCardComponent,
    NovelCardSkeletonComponent
  ],
  providers: [MessageService],
  template: `
    <p-toast [life]="12000"></p-toast>

    <!-- Barandilla de sincronización de metadatos -->
    <div class="card mb-4">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-4 p-4 bg-blue-50 border-l-4 border-blue-500 rounded-r">
        <div>
          <h2 class="text-xl font-bold text-gray-800">Gestor de Novelas</h2>
          <p class="text-sm text-gray-600 mt-1">
            Catálogo sincronizado desde elscione con enriquecimiento AniList (carátula, descripción, géneros, puntuación).
            Estado del pipeline por volumen: split, traducción y audio.
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <p-button
            label="Sincronizar ahora"
            icon="pi pi-refresh"
            styleClass="p-button-secondary"
            [loading]="syncStarting"
            [disabled]="syncRunning()"
            (click)="startSync()">
          </p-button>
          <p-button
            *ngIf="syncRunning()"
            label="Cancelar sync"
            icon="pi pi-times"
            styleClass="p-button-danger p-button-outlined"
            (click)="cancelSync()">
          </p-button>
          <p-button
            label="Enriquecer todo (AniList)"
            icon="pi pi-sparkles"
            styleClass="p-button-info p-button-outlined"
            [loading]="enrichingAll"
            (click)="enrichAllNovels()"
            pTooltip="Busca en AniList y enriquece todas las obras de la página actual">
          </p-button>
        </div>
      </div>

      <div class="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm" *ngIf="syncStatus">
        <div class="p-3 bg-gray-50 rounded border">
          <div class="text-gray-500 text-xs">Estado</div>
          <p-tag [value]="syncStateLabel()" [severity]="syncStateSeverity()"></p-tag>
        </div>
        <div class="p-3 bg-gray-50 rounded border">
          <div class="text-gray-500 text-xs">Obras / Volúmenes vistos</div>
          <div class="font-semibold">{{ syncStatus.novelsSeen }} / {{ syncStatus.volumesSeen }}</div>
        </div>
        <div class="p-3 bg-gray-50 rounded border">
          <div class="text-gray-500 text-xs">Nuevos (obra / volumen)</div>
          <div class="font-semibold">{{ syncStatus.newNovels }} / {{ syncStatus.newVolumes }}</div>
        </div>
        <div class="p-3 bg-gray-50 rounded border">
          <div class="text-gray-500 text-xs">Última ejecución</div>
          <div class="font-semibold">
            {{ syncStatus.finishedAt ? (syncStatus.finishedAt | date: 'dd/MM/yy HH:mm') : '—' }}
          </div>
        </div>
      </div>
      <div *ngIf="syncStatus?.lastError" class="mt-3 text-sm text-red-600">
        <i class="pi pi-exclamation-triangle mr-1"></i>{{ syncStatus?.lastError }}
      </div>
    </div>

    <!-- Toolbar de búsqueda y acciones -->
    <div class="card mb-4">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4">
        <h3 class="text-lg font-bold text-gray-800">Obras del catálogo</h3>
        <div class="flex flex-wrap gap-2">
          <input
            pInputText
            type="text"
            placeholder="Buscar obra por título..."
            [(ngModel)]="searchTerm"
            (keyup.enter)="reloadCatalog()"
            class="sm:w-64" />
          <p-button icon="pi pi-search" styleClass="p-button-outlined" (click)="reloadCatalog()"></p-button>
          <p-button
            label="Limpiar filtro"
            icon="pi pi-filter-slash"
            styleClass="p-button-text"
            (click)="clearSearch()"
            *ngIf="searchTerm">
          </p-button>
        </div>
      </div>

      <!-- Grid de tarjetas de novelas (estilo expositor) -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4" *ngIf="!loading && novels.length > 0">
        @for (novel of novels; track novel.id) {
          <novel-card
            [novel]="novel"
            [expanded]="expandedNovelIds[novel.id]"
            [volumeLoading]="volumeLoading"
            [volumes]="volumes"
            [actionBusy]="actionBusy"
            (toggleExpand)="toggleNovelExpand($any($event))"
            (enrich)="enrichNovel($any($event))"
            (prepareSplit)="prepareSplit($any($event))"
            (translateVolume)="translateVolume($any($event))"
            (createAudio)="createAudio($any($event))"
            (downloadAudio)="downloadAudio($any($event))"
            [volumeLoading]="volumeLoading"
            [volumes]="volumes"
            [actionBusy]="actionBusy">
          </novel-card>
        }
      </div>

      <!-- Skeletons mientras carga -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4" *ngIf="loading">
        @for (i of [1,2,3,4,5,6,7,8]; track i) {
          <novel-card-skeleton></novel-card-skeleton>
        }
      </div>

      <!-- Mensaje vacío -->
      <div *ngIf="!loading && novels.length === 0" class="text-center text-gray-500 py-12">
        <i class="pi pi-book text-4xl mb-2 block"></i>
        <p class="text-lg">No hay obras en el catálogo</p>
        <p class="text-sm mt-1">Ejecuta una sincronización para poblarlo o ajusta el filtro de búsqueda.</p>
      </div>

      <!-- Paginación -->
      <div class="flex justify-center mt-4" *ngIf="totalRecords > pageSize">
        <p-paginator
          [rows]="pageSize"
          [totalRecords]="totalRecords"
          [first]="currentPage * pageSize"
          [rowsPerPageOptions]="[20, 50, 100]"
          (onPageChange)="onPageChange($event)"
          [showCurrentPageReport]="true"
          currentPageReportTemplate="Mostrando {first} a {last} de {totalRecords} obras">
        </p-paginator>
      </div>
    </div>
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
    .line-clamp-2 {
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
  `]
})
export class NovelsManager implements OnInit, OnDestroy {
  novels: NovelRow[] = [];
  totalRecords = 0;
  loading = false;
  pageSize = 24;
  currentPage = 0;
  searchTerm = '';
  syncStarting = false;
  enrichingAll = false;
  syncStatus: CatalogSyncStatus | null = null;

  /** IDs de novelas expandidas para mostrar volúmenes. */
  expandedNovelIds: Record<string, boolean> = {};

  /** Volúmenes cargados por obra (lazy al expandir). */
  volumes: Record<string, CatalogVolume[]> = {};
  volumeLoading: Record<string, boolean> = {};
  actionBusy: Record<string, boolean> = {};

  private volumePollSubscription?: Subscription;
  private syncPollSubscription?: Subscription;
  private catalogSyncSubscription?: Subscription;
  private pollNovelId?: string;

  constructor(
    private novelsService: NovelsManagerService,
    private router: Router,
    private messageService: MessageService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadSyncStatus();
  }

  ngOnDestroy(): void {
    this.volumePollSubscription?.unsubscribe();
    this.syncPollSubscription?.unsubscribe();
    this.catalogSyncSubscription?.unsubscribe();
  }

  // ------- Nivel 1: catálogo de obras -------

  async loadCatalog(event?: { first?: number | null; rows?: number | null }): Promise<void> {
    this.loading = true;
    const first = event?.first ?? this.currentPage * this.pageSize;
    const rows = event?.rows ?? this.pageSize;
    const page = Math.floor(first / rows);
    this.pageSize = rows;
    this.currentPage = page;

    try {
      const result = await firstValueFrom(
        this.novelsService.getCatalog(this.searchTerm, page, rows));
      this.novels = result.content.map(n => ({
        ...n,
        volumesLoaded: false,
        anilistEnriching: false,
        anilistError: undefined
      }));
      this.totalRecords = result.totalElements;
      this.volumes = {};
      this.expandedNovelIds = {};
    } catch {
      this.messageService.add({
        sticky: true, severity: 'error',
        summary: 'Error',
        detail: 'No se pudo cargar el catálogo de novelas.'
      });
    } finally {
      this.loading = false;
      this.cdr.markForCheck();
    }
  }

  reloadCatalog(resetPage = true): void {
    if (resetPage) {
      this.currentPage = 0;
    }
    this.loadCatalog({ first: this.currentPage * this.pageSize, rows: this.pageSize });
  }

  clearSearch(): void {
    this.searchTerm = '';
    this.reloadCatalog(true);
  }

  onPageChange(event: { first?: number; rows?: number }): void {
    this.loadCatalog({ first: event.first ?? this.currentPage * this.pageSize, rows: event.rows ?? this.pageSize });
  }

  /**
   * Refresco periódico (10s) mientras la sincronización corre.
   */
  private startCatalogAutoRefresh(): void {
    this.catalogSyncSubscription?.unsubscribe();
    this.catalogSyncSubscription = timer(10000, 10000)
      .pipe(takeWhile(() => this.syncStatus?.state === 'RUNNING'))
      .subscribe(() => this.refreshCatalogDuringSync());
  }

  private async refreshCatalogDuringSync(): Promise<void> {
    try {
      const result = await firstValueFrom(
        this.novelsService.getCatalog(this.searchTerm, this.currentPage, this.pageSize));
      this.totalRecords = result.totalElements;
      this.novels = result.content.map(n => {
        const previous = this.novels.find(o => o.id === n.id);
        return {
          ...n,
          volumesLoaded: previous?.volumesLoaded ?? false,
          anilistEnriching: false,
          anilistError: undefined
        };
      });
      for (const novelId of Object.keys(this.expandedNovelIds)) {
        if (this.expandedNovelIds[novelId] && this.volumes[novelId]) {
          this.volumes[novelId] = await firstValueFrom(this.novelsService.getVolumes(novelId));
        }
      }
    } catch {
      // Refresco transitorio fallido durante el sync: se conserva el último estado válido.
    } finally {
      this.cdr.markForCheck();
    }
  }

  toggleNovelExpand(novelId: string): void {
    this.expandedNovelIds[novelId] = !this.expandedNovelIds[novelId];
    if (this.expandedNovelIds[novelId]) {
      const novel = this.novels.find(n => n.id === novelId);
      if (novel && !novel.volumesLoaded) {
        this.loadVolumes(novelId);
      }
    }
  }

  // ------- Nivel 2: volúmenes -------

  async loadVolumes(novelId: string): Promise<void> {
    this.volumeLoading[novelId] = true;
    this.cdr.markForCheck();
    try {
      this.volumes[novelId] = await firstValueFrom(this.novelsService.getVolumes(novelId));
      const novel = this.novels.find(n => n.id === novelId);
      if (novel) {
        novel.volumesLoaded = true;
      }
      this.watchRunningVolumes(novelId);
    } catch {
      this.messageService.add({
        sticky: true, severity: 'error',
        summary: 'Error',
        detail: 'No se pudieron cargar los volúmenes de la obra.'
      });
    } finally {
      this.volumeLoading[novelId] = false;
      this.cdr.markForCheck();
    }
  }

  private watchRunningVolumes(novelId: string): void {
    const anyRunning = (this.volumes[novelId] || []).some(v => v.running);
    if (!anyRunning) return;
    this.pollNovelId = novelId;
    this.volumePollSubscription?.unsubscribe();
    this.volumePollSubscription = timer(3000, 3000).pipe(
      switchMap(() => this.novelsService.getVolumes(novelId)),
      takeWhile(volumes => volumes.some(v => v.running), true)
    ).subscribe({
      next: volumes => {
        this.volumes[novelId] = volumes;
        const finished = !volumes.some(v => v.running);
        if (finished) {
          const failed = volumes.filter(v => v.bookStatus === 'FAILED');
          if (failed.length > 0) {
            this.messageService.add({
              sticky: true, severity: 'warn',
              summary: 'Proceso terminado con fallos',
              detail: `${failed.length} volumen(es) quedaron en estado FAILED.`
            });
          } else {
            this.messageService.add({
              severity: 'success',
              summary: 'Proceso terminado',
              detail: 'Los volúmenes se actualizaron.'
            });
          }
        }
        this.cdr.markForCheck();
      },
      error: () => this.cdr.markForCheck()
    });
  }

  // ------- Enriquecimiento AniList -------

  async enrichNovel(event: NovelRow): Promise<void> {
    const novel = event;
    if (novel.anilistEnriching) return;
    novel.anilistEnriching = true;
    novel.anilistError = undefined;
    this.cdr.markForCheck();

    try {
      const result = await firstValueFrom(
        this.novelsService.enrichNovelAnilist(novel.id)
      );
      if (result.success) {
        // Recargar la novela con datos frescos
        const refreshed = await firstValueFrom(this.novelsService.getNovel(novel.id));
        Object.assign(novel, refreshed);
        this.messageService.add({
          severity: 'success',
          summary: 'Enriquecido',
          detail: `«${novel.title}» enriquecida con datos de AniList.`
        });
      } else {
        novel.anilistError = result.message;
        this.messageService.add({
          severity: 'warn',
          summary: 'No enriquecido',
          detail: result.message
        });
      }
    } catch (err: any) {
      const detail = err?.error?.error || 'Error enriqueciendo con AniList.';
      novel.anilistError = detail;
      this.messageService.add({ sticky: true, severity: 'error', summary: 'Error', detail });
    } finally {
      novel.anilistEnriching = false;
      this.cdr.markForCheck();
    }
  }

  async enrichAllNovels(): Promise<void> {
    if (this.enrichingAll) return;
    this.enrichingAll = true;
    this.cdr.markForCheck();

    try {
      const novelIds = this.novels.map(n => n.id);
      const result = await firstValueFrom(
        this.novelsService.enrichBatchAnilist(novelIds)
      );
      // Recargar la página completa
      await this.loadCatalog({ first: this.currentPage * this.pageSize, rows: this.pageSize });
      this.messageService.add({
        severity: 'success',
        summary: 'Enriquecimiento masivo completado',
        detail: `${result.enriched} enriquecidas, ${result.notFound} no encontradas, ${result.errors} errores.`
      });
    } catch (err: any) {
      const detail = err?.error?.error || 'Error en enriquecimiento masivo.';
      this.messageService.add({ sticky: true, severity: 'error', summary: 'Error', detail });
    } finally {
      this.enrichingAll = false;
      this.cdr.markForCheck();
    }
  }

  // ------- Acciones con dependencias Split -> Traducir -> Audio -------

  async prepareSplit(event: { volume: CatalogVolume; novel: NovelRow }): Promise<void> {
    const { volume, novel } = event;
    if (!volume.canPrepareText || this.actionBusy[volume.id]) return;
    this.actionBusy[volume.id] = true;
    this.cdr.markForCheck();
    try {
      const prepared = await firstValueFrom(this.novelsService.prepareVolumeText(volume.id));
      this.messageService.add({
        severity: 'success', summary: 'Texto listo',
        detail: `"${prepared.bookName}" quedó enlazado al volumen; continúa con el split en el editor.`
      });
      this.router.navigate(['/pipeline/split-text'], {
        queryParams: { bookId: prepared.bookId, name: prepared.bookName }
      });
    } catch (err: any) {
      const detail = err?.error?.error || 'No se pudo preparar el texto del volumen.';
      this.messageService.add({ sticky: true, severity: 'error', summary: 'Error', detail });
    } finally {
      this.actionBusy[volume.id] = false;
      this.cdr.markForCheck();
    }
  }

  async translateVolume(event: { volume: CatalogVolume; novel: NovelRow }): Promise<void> {
    const { volume, novel } = event;
    await this.runVolumeStage(volume, novel, 'TRANSLATE', 'Traducción iniciada');
  }

  async createAudio(event: { volume: CatalogVolume; novel: NovelRow }): Promise<void> {
    const { volume, novel } = event;
    await this.runVolumeStage(volume, novel, 'AUDIO', 'Generación de audio iniciada');
  }

  downloadAudio(event: CatalogVolume): void {
    const url = `/api/novels-manager/volumes/${event.id}/audio/download`;
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.target = '_blank';
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }

  private async runVolumeStage(volume: CatalogVolume, novel: NovelRow,
                               target: 'TRANSLATE' | 'AUDIO', okDetail: string): Promise<void> {
    if (!volume.bookId) return;
    this.actionBusy[volume.id] = true;
    this.cdr.markForCheck();
    try {
      await firstValueFrom(this.novelsService.processVolumeBook(volume.bookId, target));
      this.messageService.add({ severity: 'info', summary: 'Pipeline', detail: okDetail });
      volume.running = true;
      this.watchRunningVolumes(novel.id);
    } catch (err: any) {
      const detail = err?.error?.error || 'No se pudo iniciar la etapa del pipeline.';
      this.messageService.add({ sticky: true, severity: 'error', summary: 'Error', detail });
    } finally {
      this.actionBusy[volume.id] = false;
      this.cdr.markForCheck();
    }
  }

  // ------- Sincronización de metadatos -------

  async loadSyncStatus(): Promise<void> {
    try {
      this.syncStatus = await firstValueFrom(this.novelsService.getSyncStatus());
      if (this.syncStatus.state === 'RUNNING') {
        this.pollSync();
        this.startCatalogAutoRefresh();
      }
      this.cdr.markForCheck();
    } catch {
      // Estado inicial inaccesible: no bloquea la pantalla.
    }
  }

  async startSync(): Promise<void> {
    this.syncStarting = true;
    this.cdr.markForCheck();
    try {
      this.syncStatus = await firstValueFrom(this.novelsService.startSync());
      this.messageService.add({
        severity: 'success', summary: 'Sincronización',
        detail: 'Sincronización de metadatos iniciada.'
      });
      this.pollSync();
      this.startCatalogAutoRefresh();
    } catch (err: any) {
      const detail = err?.error?.error || err?.message || 'No se pudo iniciar la sincronización.';
      this.messageService.add({ sticky: true, severity: 'error', summary: 'Error', detail });
    } finally {
      this.syncStarting = false;
      this.cdr.markForCheck();
    }
  }

  async cancelSync(): Promise<void> {
    try {
      await firstValueFrom(this.novelsService.cancelSync());
    } catch { }
  }

  private pollSync(): void {
    this.syncPollSubscription?.unsubscribe();
    this.syncPollSubscription = this.novelsService.pollSyncStatus(2000).subscribe({
      next: status => {
        const finished = status.state !== 'RUNNING';
        this.syncStatus = status;
        if (finished) {
          this.catalogSyncSubscription?.unsubscribe();
          this.reloadCatalog(false);
        }
        this.cdr.markForCheck();
      }
    });
  }

  syncRunning(): boolean { return this.syncStatus?.state === 'RUNNING'; }

  syncStateLabel(): string {
    switch (this.syncStatus?.state) {
      case 'RUNNING': return 'Sincronizando';
      case 'COMPLETED': return 'Completado';
      case 'CANCELLED': return 'Cancelado';
      case 'FAILED': return 'Fallido';
      default: return 'Sin ejecutar';
    }
  }

  syncStateSeverity(): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (this.syncStatus?.state) {
      case 'COMPLETED': return 'success';
      case 'RUNNING': return 'info';
      case 'CANCELLED': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }

  // ------- Helpers de plantilla -------

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