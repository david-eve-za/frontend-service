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
import { MessageService } from 'primeng/api';
import { firstValueFrom, Subscription, timer, switchMap, takeWhile } from 'rxjs';
import {
  CatalogSyncStatus,
  CatalogVolume,
  NovelSummary,
  NovelsManagerService
} from './novels-manager.service';

interface NovelRow extends NovelSummary {
  volumesLoaded: boolean;
}

@Component({
  selector: 'app-novels-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, ButtonModule, InputTextModule, TagModule, TooltipModule, ToastModule, TableModule],
  providers: [MessageService],
  template: `
    <p-toast></p-toast>

    <!-- Barandilla de sincronización de metadatos -->
    <div class="card mb-4">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-4 p-4 bg-blue-50 border-l-4 border-blue-500 rounded-r">
        <div>
          <h2 class="text-xl font-bold text-gray-800">Gestor de Novelas</h2>
          <p class="text-sm text-gray-600 mt-1">
            Catálogo sincronizado desde elscione (solo metadatos) con el estado del pipeline por volumen:
            split, traducción y audio.
          </p>
        </div>
        <div class="flex gap-2">
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

    <!-- Nivel 1: obras -->
    <div class="card">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4">
        <h3 class="text-lg font-bold text-gray-800">Obras del catálogo</h3>
        <div class="flex gap-2">
          <input
            pInputText
            type="text"
            placeholder="Buscar obra por título..."
            [(ngModel)]="searchTerm"
            (keyup.enter)="reloadCatalog()"
            class="sm:w-64" />
          <p-button icon="pi pi-search" styleClass="p-button-outlined" (click)="reloadCatalog()"></p-button>
        </div>
      </div>

      <p-table
        [value]="novels"
        [lazy]="true"
        [paginator]="true"
        [rows]="pageSize"
        [totalRecords]="totalRecords"
        [loading]="loading"
        dataKey="id"
        (onLazyLoad)="loadCatalog($event)"
        (onRowExpand)="onNovelExpand($event)"
        (onRowCollapse)="onNovelCollapse($event)">
        <ng-template pTemplate="header">
          <tr>
            <th style="width: 3rem"></th>
            <th>Obra</th>
            <th>Categoría</th>
            <th>Volúmenes</th>
            <th>Último sync</th>
          </tr>
        </ng-template>
        <ng-template pTemplate="body" let-novel let-expanded="expanded">
          <tr>
            <td>
              <button
                type="button"
                pButton
                class="p-button-text p-row-toggler"
                [pRowToggler]="novel">
                <i class="pi" [class]="expanded ? 'pi-chevron-down' : 'pi-chevron-right'"></i>
              </button>
            </td>
            <td>
              <div class="font-medium">{{ novel.title }}</div>
              <div class="text-xs text-gray-400">{{ novel.id }}</div>
            </td>
            <td>
              <p-tag *ngIf="novel.category" [value]="novel.category" severity="secondary"></p-tag>
              <span *ngIf="!novel.category" class="text-xs text-gray-400">—</span>
            </td>
            <td>{{ novel.volumeCount }}</td>
            <td>{{ novel.lastSyncedAt ? (novel.lastSyncedAt | date: 'dd/MM/yy HH:mm') : '—' }}</td>
          </tr>
        </ng-template>

        <!-- Nivel 2: volúmenes de la obra -->
        <ng-template pTemplate="expandedrow" let-novel>
          <tr class="bg-gray-50">
            <td colspan="5" class="p-0">
              <div *ngIf="volumeLoading[novel.id]" class="text-center text-gray-500 py-4">
                <i class="pi pi-spin pi-spinner mr-2"></i>Cargando volúmenes...
              </div>
              <p-table
                *ngIf="volumes[novel.id]"
                [value]="volumes[novel.id]"
                styleClass="p-table-nested">
                <ng-template pTemplate="header">
                  <tr>
                    <th>Volumen</th>
                    <th>Grupo</th>
                    <th>Formatos</th>
                    <th>Estado</th>
                    <th>Acciones</th>
                  </tr>
                </ng-template>
                <ng-template pTemplate="body" let-volume>
                  <tr>
                    <td>
                      <div class="font-medium">
                        {{ volumeLabel(volume) }}
                      </div>
                    </td>
                    <td>{{ volume.translatorGroup || '—' }}</td>
                    <td>
                      <div class="flex flex-wrap gap-1">
                        <p-tag
                          *ngFor="let file of volume.files"
                          [value]="file.format + (file.presentLocally ? ' ✓' : '')"
                          [severity]="file.presentLocally ? 'success' : 'info'"
                          [pTooltip]="file.fileName + ' — ' + formatSize(file.sizeBytes)">
                        </p-tag>
                      </div>
                    </td>
                    <td>
                      <div class="flex flex-wrap gap-1 items-center">
                        <p-tag
                          [value]="volume.translated ? 'Traducido' : 'No Traducido'"
                          [severity]="volume.translated ? 'success' : 'danger'">
                        </p-tag>
                        <p-tag
                          [value]="volume.audioCreated ? 'Audio' : 'No Audio'"
                          [severity]="volume.audioCreated ? 'success' : 'danger'">
                        </p-tag>
                        <p-tag *ngIf="volume.removedFromSource" value="Retirado de la fuente" severity="warn"></p-tag>
                        <p-tag *ngIf="volume.bookId && !volume.splitDone && !volume.running"
                               [value]="'Texto listo (sin split)'" severity="info"></p-tag>
                        <span *ngIf="volume.running" class="text-sm text-blue-600">
                          <i class="pi pi-spin pi-spinner mr-1"></i>Procesando...
                        </span>
                      </div>
                    </td>
                    <td>
                      <div class="flex flex-wrap gap-1">
                        <!-- 1. Hacer Split del Volumen -->
                        <p-button
                          label="Hacer Split del Volumen"
                          icon="pi pi-minus"
                          styleClass="p-button-sm"
                          [disabled]="!volume.canPrepareText || actionBusy[volume.id]"
                          [loading]="actionBusy[volume.id]"
                          (click)="prepareSplit(volume, novel)">
                        </p-button>
                        <!-- 2. Traducir Volumen (depende del split) -->
                        <p-button
                          label="Traducir Volumen"
                          icon="pi pi-language"
                          styleClass="p-button-sm p-button-outlined"
                          [disabled]="!volume.canTranslate"
                          (click)="translateVolume(volume, novel)">
                        </p-button>
                        <!-- 3. Crear Audio (depende de la traducción) -->
                        <p-button
                          label="Crear Audio"
                          icon="pi pi-volume-up"
                          styleClass="p-button-sm p-button-outlined"
                          [disabled]="!volume.canCreateAudio"
                          (click)="createAudio(volume, novel)">
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
            </td>
          </tr>
        </ng-template>

        <ng-template pTemplate="emptymessage">
          <tr>
            <td colspan="5" class="text-center text-gray-500 py-4">
              No hay obras en el catálogo. Ejecuta una sincronización para poblarlo.
            </td>
          </tr>
        </ng-template>
      </p-table>
    </div>
  `,
  styles: [`
    :host ::ng-deep .p-table-nested .p-datatable-table {
      background: transparent;
    }
    :host ::ng-deep .p-table-nested thead tr th {
      background: #eef2f7;
      font-size: 0.75rem;
      padding: 0.5rem 0.75rem;
    }
    :host ::ng-deep .p-table-nested tbody tr td {
      padding: 0.5rem 0.75rem;
      font-size: 0.875rem;
    }
  `]
})
export class NovelsManager implements OnInit, OnDestroy {
  novels: NovelRow[] = [];
  totalRecords = 0;
  loading = false;
  pageSize = 50;
  searchTerm = '';
  syncStarting = false;
  syncStatus: CatalogSyncStatus | null = null;

  /** Volúmenes cargados por obra (nivel 2, lazy al expandir). */
  volumes: Record<string, CatalogVolume[]> = {};
  volumeLoading: Record<string, boolean> = {};
  actionBusy: Record<string, boolean> = {};
  expandedRowKeys: Record<string, boolean> = {};

  private volumePollSubscription?: Subscription;
  private syncPollSubscription?: Subscription;
  private catalogSyncSubscription?: Subscription;
  private currentPage = 0;
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

  async loadCatalog(event: { first?: number | null; rows?: number | null }): Promise<void> {
    this.loading = true;
    const page = Math.floor((event.first ?? 0) / (event.rows || this.pageSize));
    try {
      const result = await firstValueFrom(
        this.novelsService.getCatalog(this.searchTerm, page, this.pageSize));
      this.currentPage = page;
      this.novels = result.content.map(n => ({ ...n, volumesLoaded: false }));
      this.totalRecords = result.totalElements;
      this.volumes = {};
      this.expandedRowKeys = {};
    } catch {
      this.messageService.add({
        severity: 'error',
        summary: 'Error',
        detail: 'No se pudo cargar el catálogo de novelas.'
      });
    } finally {
      this.loading = false;
      this.cdr.markForCheck();
    }
  }

  reloadCatalog(resetPage = true): void {
    const first = resetPage ? 0 : this.currentPage * this.pageSize;
    this.loadCatalog({ first, rows: this.pageSize });
  }

  /**
   * Refresco periódico (10s) mientras la sincronización corre: actualiza la
   * página actual del catálogo conservando la expansión y el caché de
   * volúmenes, y recarga los volúmenes de las obras expandidas para que los
   * nuevos descubiertos por el sync aparezcan sin colapsar la fila.
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
        return { ...n, volumesLoaded: previous?.volumesLoaded ?? false };
      });
      for (const novelId of Object.keys(this.expandedRowKeys)) {
        if (this.volumes[novelId]) {
          this.volumes[novelId] = await firstValueFrom(this.novelsService.getVolumes(novelId));
        }
      }
    } catch {
      // Refresco transitorio fallido durante el sync: se conserva el último
      // estado válido hasta el siguiente tick.
    } finally {
      this.cdr.markForCheck();
    }
  }

  onNovelExpand(event: { data: NovelRow }): void {
    // PrimeNG reemplaza su objeto interno de expandedRowKeys (rowExpandMode
    // 'single') y no lo emite de vuelta, así que la expansión se rastrea en
    // paralelo para saber qué volúmenes refrescar durante el auto-refresh.
    this.expandedRowKeys[event.data.id] = true;
    if (!event.data.volumesLoaded) {
      this.loadVolumes(event.data.id);
    }
  }

  onNovelCollapse(event: { data: NovelRow }): void {
    delete this.expandedRowKeys[event.data.id];
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
        severity: 'error',
        summary: 'Error',
        detail: 'No se pudieron cargar los volúmenes de la obra.'
      });
    } finally {
      this.volumeLoading[novelId] = false;
      this.cdr.markForCheck();
    }
  }

  /**
   * Sondea los volúmenes mientras alguno tenga un proceso del pipeline en
   * curso (traducción/audio) para refrescar los estados dependientes.
   */
  private watchRunningVolumes(novelId: string): void {
    const anyRunning = (this.volumes[novelId] || []).some(v => v.running);
    if (!anyRunning) {
      return;
    }
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
              severity: 'warn',
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

  // ------- Acciones con dependencias Split -> Traducir -> Audio -------

  /** Paso 1: prepara el texto del volumen y lleva al wizard para hacer el split. */
  async prepareSplit(volume: CatalogVolume, novel: NovelRow): Promise<void> {
    if (!volume.canPrepareText || this.actionBusy[volume.id]) {
      return;
    }
    this.actionBusy[volume.id] = true;
    this.cdr.markForCheck();
    try {
      const prepared = await firstValueFrom(
        this.novelsService.prepareVolumeText(volume.id));
      this.messageService.add({
        severity: 'success',
        summary: 'Texto listo',
        detail: `"${prepared.bookName}" quedó enlazado al volumen; continúa con el split en el editor.`
      });
      // El split se realiza en el wizard existente (editor blockML).
      this.router.navigate(['/book-translator/upload'], {
        queryParams: { bookId: prepared.bookId, name: prepared.bookName }
      });
    } catch (err: any) {
      const detail = err?.error?.error || 'No se pudo preparar el texto del volumen.';
      this.messageService.add({ severity: 'error', summary: 'Error', detail });
    } finally {
      this.actionBusy[volume.id] = false;
      this.cdr.markForCheck();
    }
  }

  /** Paso 2: traduce (requiere split hecho; habilitado según canTranslate). */
  async translateVolume(volume: CatalogVolume, novel: NovelRow): Promise<void> {
    await this.runVolumeStage(volume, novel, 'TRANSLATE', 'Traducción iniciada');
  }

  /** Paso 3: genera el audio (requiere traducción completa; canCreateAudio). */
  async createAudio(volume: CatalogVolume, novel: NovelRow): Promise<void> {
    await this.runVolumeStage(volume, novel, 'AUDIO', 'Generación de audio iniciada');
  }

  private async runVolumeStage(volume: CatalogVolume, novel: NovelRow,
                               target: 'TRANSLATE' | 'AUDIO', okDetail: string): Promise<void> {
    if (!volume.bookId) {
      return;
    }
    this.actionBusy[volume.id] = true;
    this.cdr.markForCheck();
    try {
      await firstValueFrom(this.novelsService.processVolumeBook(volume.bookId, target));
      this.messageService.add({ severity: 'info', summary: 'Pipeline', detail: okDetail });
      volume.running = true;
      this.watchRunningVolumes(novel.id);
    } catch (err: any) {
      const detail = err?.error?.error || 'No se pudo iniciar la etapa del pipeline.';
      this.messageService.add({ severity: 'error', summary: 'Error', detail });
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
        severity: 'success',
        summary: 'Sincronización',
        detail: 'Sincronización de metadatos iniciada.'
      });
      this.pollSync();
      this.startCatalogAutoRefresh();
    } catch (err: any) {
      const detail = err?.error?.error || err?.message || 'No se pudo iniciar la sincronización.';
      this.messageService.add({ severity: 'error', summary: 'Error', detail });
    } finally {
      this.syncStarting = false;
      this.cdr.markForCheck();
    }
  }

  async cancelSync(): Promise<void> {
    try {
      await firstValueFrom(this.novelsService.cancelSync());
    } catch {
      // La cancelación es best-effort.
    }
  }

  private pollSync(): void {
    this.syncPollSubscription?.unsubscribe();
    this.syncPollSubscription = this.novelsService.pollSyncStatus(2000).subscribe({
      next: status => {
        const finished = status.state !== 'RUNNING';
        this.syncStatus = status;
        if (finished) {
          this.catalogSyncSubscription?.unsubscribe();
          // Refresco final conservando la página actual del usuario.
          this.reloadCatalog(false);
        }
        this.cdr.markForCheck();
      }
    });
  }

  // ------- Helpers de plantilla -------

  volumeLabel(volume: CatalogVolume): string {
    if (volume.volumeNumber !== null && volume.volumeNumber !== undefined) {
      return `Volume ${String(volume.volumeNumber).padStart(2, '0')}`;
    }
    return volume.label || '—';
  }

  formatSize(bytes: number | null | undefined): string {
    if (!bytes && bytes !== 0) {
      return 'tamaño desconocido';
    }
    if (bytes < 1024 * 1024) {
      return `${Math.round(bytes / 1024)} KB`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  syncRunning(): boolean {
    return this.syncStatus?.state === 'RUNNING';
  }

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
}
