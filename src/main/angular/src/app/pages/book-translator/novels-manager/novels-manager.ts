import { ViewChild, signal, computed, inject, OnInit } from '@angular/core';
import { ChangeDetectorRef, Component, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
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
import { PaginatorModule } from 'primeng/paginator'
import { DrawerModule } from 'primeng/drawer';
import { SelectModule } from 'primeng/select';
import { MessageService } from 'primeng/api';
import { firstValueFrom, Subscription, timer, switchMap, takeWhile, retry, catchError, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import {
  CatalogSyncStatus,
  CatalogVolume,
  NovelSummary,
  NovelsManagerService
} from './novels-manager.service';
import { SourcesService, SourceMetadata } from './sources.service';
import { NovelCardComponent } from './novel-card.component';
import { NovelCardSkeletonComponent } from './novel-card-skeleton.component';
import { NovelDetailDrawerComponent } from './novel-detail-drawer.component';

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
    DrawerModule,
    SelectModule,
    NovelCardComponent,
    NovelCardSkeletonComponent,
    NovelDetailDrawerComponent
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
            Catálogo sincronizado desde 
            <strong>{{ getCurrentSourceDisplayName() }}</strong> 
            con enriquecimiento AniList (carátula, descripción, géneros, puntuación).
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
          <p-button
            label="Traducir sinopsis (ES)"
            icon="pi pi-language"
            styleClass="p-button-secondary p-button-outlined"
            [loading]="translatingAll"
            (click)="translateAllSynopses()"
            pTooltip="Traduce todas las sinopsis/descripciones de AniList al español">
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

          <!-- Selector de fuente -->
          <div class="flex items-center gap-2" *ngIf="availableSources.length > 0">
            <label class="text-sm text-gray-600 whitespace-nowrap">Fuente:</label>
            <p-select
              [options]="availableSources.map(s => ({ label: s.displayName, value: s.id }))"
              [(ngModel)]="selectedSourceId"
              (onChange)="onSourceChange()"
              [style]="{ width: '180px' }"
              [disabled]="loadingSources"
              optionLabel="label"
              optionValue="value"
              [showClear]="false"
              placeholder="Seleccionar fuente"
              [filter]="true"
              filterBy="label">
              <ng-template let-source pTemplate="item">
                <div class="flex items-center gap-2">
                  <i class="{{source.icon}}"></i>
                  <span>{{source.label}}</span>
                  <span class="text-xs text-gray-500 uppercase">{{availableSources.find(s => s.id === source.value)?.language}}</span>
                </div>
              </ng-template>
            </p-select>
          </div>
          <div *ngIf="loadingSources" class="flex items-center gap-2 text-sm text-gray-500">
            <i class="pi pi-spin pi-spinner"></i>
            <span>Cargando fuentes...</span>
          </div>
          <div *ngIf="sourcesError" class="flex items-center gap-2 text-sm text-red-600">
            <i class="pi pi-exclamation-triangle"></i>
            <span>{{sourcesError}}</span>
          </div>
        </div>
      </div>

      <!-- Grid de tarjetas de novelas (estilo expositor) -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4" *ngIf="!loading && novels.length > 0">
        @for (novel of novels; track novel.id) {
          <novel-card
            [novel]="novel"
            [volumeLoading]="volumeLoading"
            [volumes]="volumes"
            [actionBusy]="actionBusy"
            (enrich)="enrichNovel($any($event))"
            (prepareSplit)="prepareSplit($any($event))"
            (translateVolume)="translateVolume($any($event))"
            (createAudio)="createAudio($any($event))"
            (downloadAudio)="downloadAudio($any($event))"
            (viewDetail)="openNovelDetail(novel.id)"
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

      <!-- Controles de paginación / infinite scroll -->
      <div class="flex flex-col md:flex-row items-center justify-between gap-3 mb-4">
        <div class="flex items-center gap-3">
          <label class="text-sm text-gray-600">Obras por página:</label>
          <p-select
            [options]="[
              { label: '20', value: 20 },
              { label: '50', value: 50 },
              { label: '100', value: 100 },
              { label: '200', value: 200 }
            ]"
            [(ngModel)]="pageSize"
            [style]="{ width: '80px' }"
            (onChange)="onPageSizeChange()">
          </p-select>
          <p-button
            icon="pi"
            [ngClass]="{ 'pi-infinity': !infiniteScroll(), 'pi-list': infiniteScroll() }"
            [outlined]="!infiniteScroll()"
            (click)="infiniteScroll.set(!infiniteScroll())"
            [pTooltip]="infiniteScroll() ? 'Cambiar a paginación' : 'Cambiar a scroll infinito'">
          </p-button>
        </div>
      </div>

      <!-- Paginación tradicional -->
      <div class="flex justify-center mt-4" *ngIf="!infiniteScroll() && totalRecords > pageSize">
        <p-paginator
          [rows]="pageSize"
          [totalRecords]="totalRecords"
          [first]="currentPage * pageSize"
          [rowsPerPageOptions]="[20, 50, 100, 200]"
          (onPageChange)="onPageChange($event)"
          [showCurrentPageReport]="true"
          currentPageReportTemplate="Mostrando {first} a {last} de {totalRecords} obras">
        </p-paginator>
      </div>

      <!-- Infinite Scroll - Botón "Cargar más" -->
      <div class="flex justify-center mt-4" *ngIf="infiniteScroll() && hasMore()">
        <p-button
          label="Cargar más"
          icon="pi pi-chevron-down"
          [loading]="loadingMore()"
          (click)="loadMore()"
          [disabled]="!hasMore() || loadingMore()">
        </p-button>
      </div>

      <!-- Mensaje cuando se cargaron todos los elementos en infinite scroll -->
      <div *ngIf="infiniteScroll() && !hasMore() && novels.length > 0" class="text-center text-gray-500 py-4">
        <i class="pi pi-check-circle text-2xl mb-2 block text-green-500"></i>
        <p>Se han cargado todas las {{ totalRecords }} obras</p>
      </div>
    </div>

    <!-- Drawer de detalle de novela -->
    <novel-detail-drawer
      [novel]="getDrawerNovel()"
      [visible]="drawerVisible()"
      [volumes]="volumes"
      [volumeLoading]="volumeLoading"
      [actionBusy]="actionBusy"
      (close)="closeNovelDetail()"
      (prepareSplit)="prepareSplit($any($event))"
      (translateVolume)="translateVolume($any($event))"
      (createAudio)="createAudio($any($event))"
      (downloadAudio)="downloadAudio($any($event))"
      (enrich)="enrichNovel($any($event))">
    </novel-detail-drawer>
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
  translatingAll = false;
  syncStatus: CatalogSyncStatus | null = null;

  /** Modo infinite scroll (cuando true, usa scroll infinito; cuando false, paginación tradicional) */
  infiniteScroll = signal(false);
  loadingMore = signal(false);
  hasMore = computed(() => this.novels.length < this.totalRecords);

  /** Control de visibilidad del drawer de detalle. */
  drawerVisible = signal(false);
  drawerNovelId: string | null = null;

  @ViewChild(NovelDetailDrawerComponent) novelDetailDrawer!: NovelDetailDrawerComponent;

  /** Volúmenes cargados por obra (lazy al expandir). */
  volumes: Record<string, CatalogVolume[]> = {};
  volumeLoading: Record<string, boolean> = {};
  actionBusy: Record<string, boolean> = {};

  /** Source selection */
  availableSources: SourceMetadata[] = [];
  selectedSourceId: string = 'elscione';
  loadingSources = false;
  sourcesError: string | null = null;

  private volumePollSubscription?: Subscription;
  private syncPollSubscription?: Subscription;
  private catalogSyncSubscription?: Subscription;
  private pollNovelId?: string;

  constructor(
    private novelsService: NovelsManagerService,
    private sourcesService: SourcesService,
    private router: Router,
    private route: ActivatedRoute,
    private messageService: MessageService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadSources();
    this.readSourceFromRoute();
    this.loadSyncStatus();
  }

  ngOnDestroy(): void {
    this.volumePollSubscription?.unsubscribe();
    this.syncPollSubscription?.unsubscribe();
    this.catalogSyncSubscription?.unsubscribe();
  }

  private loadSources(): void {
    this.loadingSources = true;
    this.sourcesService.getAllSources().subscribe({
      next: (sources) => {
        this.availableSources = sources;
        // Set default source from route or first available
        if (!this.selectedSourceId && sources.length > 0) {
          this.selectedSourceId = sources[0].id;
        }
        this.loadingSources = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.sourcesError = 'No se pudieron cargar las fuentes disponibles';
        this.loadingSources = false;
        // Fallback to elscione
        this.availableSources = [{ 
          id: 'elscione', 
          displayName: 'Elscione', 
          icon: 'pi pi-book', 
          language: 'es', 
          baseUrl: '', 
          rateLimitRpm: 20, 
          requiresLogin: false, 
          enabled: true, 
          tags: [] 
        }];
        this.selectedSourceId = 'elscione';
        this.cdr.markForCheck();
      }
    });
  }

  private readSourceFromRoute(): void {
    this.route.queryParams.subscribe(params => {
      if (params['source'] && this.availableSources.some(s => s.id === params['source'])) {
        this.selectedSourceId = params['source'];
      }
    });
  }

  onSourceChange(): void {
    this.router.navigate([], { 
      relativeTo: this.route, 
      queryParams: { source: this.selectedSourceId }, 
      queryParamsHandling: 'merge' 
    });
    this.reloadCatalog(true);
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
        this.novelsService.getCatalog(this.searchTerm, page, rows, this.selectedSourceId));
      this.novels = result.content.map(n => ({
        ...n,
        volumesLoaded: false,
        anilistEnriching: false,
        anilistError: undefined
      }));
      this.totalRecords = result.page.totalElements;
      this.currentPage = result.page.number;
      this.pageSize = result.page.size;
      this.volumes = {};
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

  /** Cambia el tamaño de página y recarga el catálogo. */
  onPageSizeChange(): void {
    this.currentPage = 0;
    this.loadCatalog({ first: 0, rows: this.pageSize });
  }

  /** Carga la siguiente página en modo infinite scroll. */
  async loadMore(): Promise<void> {
    if (this.loadingMore() || !this.hasMore()) return;
    this.loadingMore.set(true);
    const nextPage = this.currentPage + 1;
    try {
      const result = await firstValueFrom(
        this.novelsService.getCatalog(this.searchTerm, nextPage, this.pageSize, this.selectedSourceId));
      const newNovels = result.content.map(n => ({
        ...n,
        volumesLoaded: false,
        anilistEnriching: false,
        anilistError: undefined
      }));
      // Append new novels to existing list
      this.novels = [...this.novels, ...newNovels];
      this.totalRecords = result.page.totalElements;
      this.currentPage = nextPage;
    } catch {
      this.messageService.add({
        sticky: true, severity: 'error',
        summary: 'Error',
        detail: 'No se pudieron cargar más obras.'
      });
    } finally {
      this.loadingMore.set(false);
      this.cdr.markForCheck();
    }
  }

  /**
   * Refresco periódico mientras la sincronización corre.
   * Usa 30s en lugar de 10s para reducir carga, pausa si la pestaña no está visible,
   * y tiene backoff exponencial en caso de errores.
   */
  private startCatalogAutoRefresh(): void {
    this.catalogSyncSubscription?.unsubscribe();
    
    const baseIntervalMs = 30000; // 30 segundos en lugar de 10
    let currentIntervalMs = baseIntervalMs;
    let consecutiveErrors = 0;
    const maxIntervalMs = 120000; // Máximo 2 minutos
    
    const isVisible = () => !document.hidden;
    
    const poll = () => {
      if (!isVisible() || this.syncStatus?.state !== 'RUNNING') {
        // Si la pestaña no está visible o el sync terminó, reprogramar para más tarde
        setTimeout(poll, 5000);
        return;
      }
      
      this.refreshCatalogDuringSync()
        .then(() => {
          // Éxito: resetear backoff
          consecutiveErrors = 0;
          currentIntervalMs = baseIntervalMs;
          setTimeout(poll, currentIntervalMs);
        })
        .catch(() => {
          // Error: backoff exponencial
          consecutiveErrors++;
          currentIntervalMs = Math.min(currentIntervalMs * 2, maxIntervalMs);
          console.warn(`Catalog refresh failed, backing off to ${currentIntervalMs}ms (attempt ${consecutiveErrors})`);
          setTimeout(poll, currentIntervalMs);
        });
    };
    
    // Iniciar primera poll
    poll();
  }

  private async refreshCatalogDuringSync(): Promise<void> {
    try {
      const result = await firstValueFrom(
        this.novelsService.getCatalog(this.searchTerm, this.currentPage, this.pageSize, this.selectedSourceId));
      this.totalRecords = result.page.totalElements;
      this.novels = result.content.map(n => {
        const previous = this.novels.find(o => o.id === n.id);
        return {
          ...n,
          volumesLoaded: previous?.volumesLoaded ?? false,
          anilistEnriching: false,
          anilistError: undefined
        };
      });
    } catch {
      // Refresco transitorio fallido durante el sync: se conserva el último estado válido.
    } finally {
      this.cdr.markForCheck();
    }
  }

  /** Abre el drawer de detalle de la novela. */
  openNovelDetail(novelId: string): void {
    const novel = this.novels.find(n => n.id === novelId);
    if (!novel) return;
    
    this.drawerNovelId = novelId;
    this.drawerVisible.set(true);
    
    // Cargar volúmenes si no están cargados
    if (!novel.volumesLoaded && !this.volumeLoading[novelId]) {
      this.loadVolumes(novelId);
    }
  }

  /** Cierra el drawer de detalle. */
  closeNovelDetail(): void {
    this.drawerVisible.set(false);
    this.drawerNovelId = null;
  }

  /** Obtiene la novela actual del drawer. */
  getDrawerNovel(): NovelRow {
    if (!this.drawerNovelId) {
      // Return a dummy novel to satisfy the type, but the drawer should not be visible anyway
      return { id: "" } as NovelRow;
    }
    return this.novels.find(n => n.id === this.drawerNovelId)!;
  }

  // ------- Nivel 2: volúmenes -------

  async loadVolumes(novelId: string): Promise<void> {
    this.volumeLoading[novelId] = true;
    this.cdr.markForCheck();
    try {
      this.volumes[novelId] = await firstValueFrom(this.novelsService.getVolumes(novelId, this.selectedSourceId));
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
      switchMap(() => this.novelsService.getVolumes(novelId, this.selectedSourceId)),
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
      // Obtener TODOS los IDs de novelas (no solo la página actual)
      const allNovelIds = await firstValueFrom(this.novelsService.getAllNovelIds());
      const result = await firstValueFrom(
        this.novelsService.enrichAllNovels()
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

  async translateAllSynopses(): Promise<void> {
    if (this.translatingAll) return;
    this.translatingAll = true;
    this.cdr.markForCheck();

    try {
      const result = await firstValueFrom(this.novelsService.translateAllSynopses());
      this.messageService.add({
        severity: 'success',
        summary: 'Traducción completada',
        detail: `${result.length} sinopsis traducidas al español.`
      });
      // Optionally log or handle the result
      console.log('Translated synopses:', result);
    } catch (err: any) {
      const detail = err?.error?.error || 'Error traduciendo sinopsis.';
      this.messageService.add({ sticky: true, severity: 'error', summary: 'Error', detail });
    } finally {
      this.translatingAll = false;
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
    // 5 segundos en lugar de 2 para reducir carga
    const intervalMs = 5000;
    let consecutiveErrors = 0;
    const baseIntervalMs = intervalMs;
    const maxIntervalMs = 30000;
    
    const isVisible = () => !document.hidden;
    
    const poll = () => {
      if (!isVisible()) {
        // Si la pestaña no está visible, esperar y volver a comprobar
        setTimeout(poll, 5000);
        return;
      }
      
      this.novelsService.getSyncStatus().subscribe({
        next: status => {
          consecutiveErrors = 0; // Reset on success
          const finished = status.state !== 'RUNNING';
          this.syncStatus = status;
          if (finished) {
            this.catalogSyncSubscription?.unsubscribe();
            this.reloadCatalog(false);
          }
          this.cdr.markForCheck();
          
          if (!finished) {
            setTimeout(poll, baseIntervalMs);
          }
        },
        error: () => {
          consecutiveErrors++;
          const backoffMs = Math.min(baseIntervalMs * Math.pow(2, consecutiveErrors - 1), maxIntervalMs);
          console.warn(`Sync status poll failed, backing off to ${backoffMs}ms`);
          setTimeout(poll, backoffMs);
        }
      });
    };
    
    poll();
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

  getCurrentSourceDisplayName(): string {
    const source = this.availableSources.find(s => s.id === this.selectedSourceId);
    return source ? source.displayName : this.selectedSourceId;
  }
}