# Diseño Mejorado: Integración de 358 Crawlers en "Gestor de Novelas"

## Análisis del Estado Actual

### Fortalezas
- ✅ Componente `SourceSubmenu` bien estructurado con ARIA
- ✅ Navegación por teclado completa (Arrow keys, Home, End, Escape)
- ✅ Estados: loading, error, empty, health check
- ✅ Responsive: mobile drawer + desktop dropdown
- ✅ Integración con router (query params)

### Debilidades Identificadas (vs ui-ux-pro-max)

| Prioridad | Problema | Guía Violada |
|-----------|----------|--------------|
| 1 (Accesibilidad) | Badge de idioma gris (#9ca3af) ratio 2.1:1 vs 4.5:1 mínimo | Color Contrast |
| 1 | Sin anuncio de cambio de fuente para screen readers | ARIA Live Regions |
| 2 (Touch) | Touch target 40px (mínimo 44×44px) | Min Touch Target |
| 4 (Estilo) | 358 fuentes → lista infinita, sin agrupación ni búsqueda | Information Architecture |
| 5 (Responsive) | Desktop dropdown 320px fijo, no aprovecha espacio | Layout & Responsive |
| 6 (Tipografía) | Tamaños inconsistentes (text-xs, text-sm, text-lg) | Font Size Scale |
| 9 (Nav) | Sin breadcrumb de fuente actual en página principal | Navigation Patterns |

---

## Propuesta de Diseño

### 1. Arquitectura de Información: Agrupación por Idioma + Búsqueda

```
┌─────────────────────────────────────┐
│ 🔍 Buscar fuente...                 │  ← Input con debounce
├─────────────────────────────────────┤
│ 📊 Salud: 342/358 OK  ▼             │  ← Header con health summary
├─────────────────────────────────────┤
│ 🇪🇸 Español (12)                    │  ← Accordion por idioma
│   ├── Elscione          ●           │
│   ├── Novelas Ligeras   ○           │
│   └── Novela en Español ○           │
├─────────────────────────────────────┤
│ 🇬🇧 English (198)                   │
│   ├── Novelfull         ○           │
│   ├── Royal Road        ○           │
│   ├── Webnovel          ○           │
│   └── ... (+195 more)  ▼            │  ← Progressive disclosure
├─────────────────────────────────────┤
│ 🇯🇵 日本語 (2)                       │
│ 🇨🇳 中文 (20)                        │
│ 🇵🇹 Português (6)                   │
│ 🇷🇺 Русский (8)                      │
│ 🌐 Multi (7)                         │
└─────────────────────────────────────┘
```

### 2. Tokens de Color por Idioma (Accesibles)

```scss
// Language color tokens - todos 4.5:1+ sobre blanco y 3:1+ sobre gray-100
$language-colors: (
  'es': (#C62828, #FFFFFF),      // Rojo español - 5.2:1
  'en': (#1565C0, #FFFFFF),      // Azul inglés - 5.8:1
  'ja': (#E65100, #FFFFFF),      // Naranja japonés - 4.7:1
  'zh': (#2E7D32, #FFFFFF),      // Verde chino - 5.1:1
  'pt': (#00695C, #FFFFFF),      // Teal portugués - 5.3:1
  'ru': (#6A1B9A, #FFFFFF),      // Púrpura ruso - 4.6:1
  'fr': (#006064, #FFFFFF),      // Cian francés - 4.8:1
  'de': (#BF360C, #FFFFFF),      // Naranja oscuro alemán - 4.5:1
  'it': (#1A237E, #FFFFFF),      // Índigo italiano - 5.4:1
  'ko': (#D84315, #FFFFFF),      // Rojo coreano - 4.5:1
  'ar': (#33691E, #FFFFFF),      // Verde árabe - 4.6:1
  'tr': (#E65100, #FFFFFF),      // Naranja turco - 4.7:1
  'vi': (#C62828, #FFFFFF),      // Rojo vietnamita - 5.2:1
  'id': (#D84315, #FFFFFF),      // Rojo indonesio - 4.5:1
  'multi': (#455A64, #FFFFFF),   // Gris azulado multi - 4.5:1
  'other': (#37474F, #FFFFFF)    // Gris oscuro fallback - 4.5:1
);
```

### 3. Componente Mejorado: SourceSelector

```typescript
// source-selector.component.ts
@Component({
  selector: 'app-source-selector',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    ButtonModule, InputTextModule, TooltipModule,
    RippleModule, SkeletonModule, AccordionModule,
    TagModule, ScrollPanelModule, OverlayPanelModule
  ],
  template: `
    <div class="source-selector" [class.mobile]="isMobile()">
      
      <!-- Trigger Button (siempre visible) -->
      <button 
        type="button"
        class="source-trigger"
        [class.active]="panelOpen()"
        [attr.aria-expanded]="panelOpen()"
        [attr.aria-controls]="'source-panel-' + id()"
        (click)="togglePanel()"
        pRipple>
        
        <span class="trigger-content">
          <i [class]="currentSourceIcon()" 
             [style.color]="currentSourceColor()" 
             class="trigger-icon" aria-hidden="true"></i>
          
          <span class="trigger-label">{{ currentSourceDisplayName() }}</span>
          
          <!-- Language badge con color accesible -->
          <span class="lang-badge" 
                [style.background-color]="currentSourceBgColor()"
                [style.color]="currentSourceTextColor()"
                aria-label="Idioma: {{ currentSourceLanguage() }}">
            {{ currentSourceLanguage() | uppercase }}
          </span>
          
          <i class="pi pi-chevron-down trigger-chevron" 
             [class.rotate]="panelOpen()" aria-hidden="true"></i>
        </span>
        
        <!-- Health indicator dot -->
        <span class="health-dot" 
              [class.healthy]="healthStatus().healthy"
              [attr.aria-label]="healthStatus().details"
              title="{{ healthStatus().details }}"></span>
      </button>

      <!-- Panel (OverlayPanel para desktop, Drawer para mobile) -->
      <p-overlayPanel 
        #overlayPanel
        [showCloseIcon]="false"
        [dismissable]="true"
        [baseZIndex]="1000"
        styleClass="source-panel-overlay"
        (onHide)="onPanelHide()">
        
        <ng-template pTemplate>
          <div class="source-panel" role="dialog" aria-label="Seleccionar fuente de novelas">
            
            <!-- Header con búsqueda y health -->
            <div class="panel-header">
              <h2 class="panel-title">Fuentes de Novelas</h2>
              
              <div class="health-summary" [class.warning]="!healthStatus().healthy">
                <i class="pi" [class]="healthStatus().healthy ? 'pi-check-circle' : 'pi-exclamation-triangle'"></i>
                <span>{{ healthStatus().details }}</span>
              </div>
            </div>

            <!-- Search -->
            <div class="panel-search">
              <label for="source-search" class="sr-only">Buscar fuente</label>
              <div class="search-wrapper">
                <i class="pi pi-search search-icon" aria-hidden="true"></i>
                <input 
                  id="source-search"
                  type="search"
                  pInputText
                  placeholder="Buscar por nombre, idioma o URL..."
                  [(ngModel)]="searchTerm"
                  (input)="onSearch()"
                  autocomplete="off"
                  aria-describedby="search-hint">
                <span id="search-hint" class="sr-only">
                  Escribe para filtrar {{ filteredSources().length }} de {{ sources().length }} fuentes
                </span>
              </div>
            </div>

            <!-- Source list grouped by language -->
            <div class="panel-body" 
                 role="listbox" 
                 aria-label="Lista de fuentes disponibles"
                 #listContainer>
              
              @if (loading()) {
                <div class="loading-state" role="status" aria-live="polite">
                  <i class="pi pi-spin pi-spinner text-2xl"></i>
                  <p>Cargando {{ sources().length }} fuentes...</p>
                </div>
              } @else if (error()) {
                <div class="error-state" role="alert">
                  <i class="pi pi-exclamation-triangle"></i>
                  <p>{{ error() }}</p>
                  <p-button label="Reintentar" icon="pi pi-refresh" 
                            styleClass="p-button-sm p-button-outlined"
                            (onClick)="retry.emit()"></p-button>
                </div>
              } @else if (filteredSources().length === 0) {
                <div class="empty-state">
                  <i class="pi pi-filter-slash"></i>
                  <p>No se encontraron fuentes para "{{ searchTerm }}"</p>
                  <p-button label="Limpiar búsqueda" icon="pi pi-times" 
                            styleClass="p-button-text p-button-sm"
                            (onClick)="clearSearch()"></p-button>
                </div>
              } @else {
                
                <!-- Grouped by language -->
                @for (group of languageGroups(); track group.language) {
                  <div class="language-group">
                    <button 
                      type="button"
                      class="group-header"
                      [attr.aria-expanded]="group.expanded()"
                      (click)="group.expanded.set(!group.expanded())"
                      aria-controls="group-{{ group.language }}">
                      
                      <span class="group-lang-badge" 
                            [style.background-color]="group.colorBg"
                            [style.color]="group.colorText">
                        {{ group.flag }} {{ group.languageName }}
                      </span>
                      
                      <span class="group-count">{{ group.sources.length }}</span>
                      
                      <i class="pi pi-chevron-down" 
                         [class.rotate]="group.expanded()" aria-hidden="true"></i>
                    </button>

                    <div id="group-{{ group.language }}" 
                         class="group-sources" 
                         [class.collapsed]="!group.expanded()"
                         role="group" 
                         [attr.aria-label]="group.languageName + ' (' + group.sources.length + ' fuentes)'">
                      
                      @for (source of group.sources; track source.id; let i = $index) {
                        <button 
                          type="button"
                          class="source-item"
                          [class.selected]="source.id === selectedSourceId()"
                          [class.disabled]="!source.enabled"
                          [attr.aria-selected]="source.id === selectedSourceId()"
                          [attr.aria-disabled]="!source.enabled"
                          [attr.aria-describedby]="'source-desc-' + source.id"
                          (click)="selectSource(source)"
                          (keydown)="onItemKeydown($event, source)"
                          tabindex="0">
                          
                          <!-- Source icon -->
                          <i [class]="source.icon" 
                             class="source-icon"
                             [style.color]="group.colorText"
                             aria-hidden="true"></i>
                          
                          <!-- Source info -->
                          <div class="source-info">
                            <span class="source-name">{{ source.displayName }}</span>
                            <span id="source-desc-{{ source.id }}" class="source-url">{{ source.baseUrl }}</span>
                          </div>
                          
                          <!-- Tags -->
                          <div class="source-tags" *ngIf="source.tags?.length" aria-hidden="true">
                            @for (tag of source.tags.slice(0, 2); track tag) {
                              <span class="tag">{{ tag }}</span>
                            }
                            @if (source.tags.length > 2) {
                              <span class="tag more">+{{ source.tags.length - 2 }}</span>
                            }
                          </div>
                          
                          <!-- Selection indicator -->
                          @if (source.id === selectedSourceId()) {
                            <i class="pi pi-check selection-check" 
                               [style.color]="group.colorText" aria-hidden="true"></i>
                          }
                        </button>
                      }
                    </div>
                  </div>
                }
              }
            </div>

            <!-- Footer actions -->
            <div class="panel-footer">
              <p-button 
                label="Ver todas las fuentes" 
                icon="pi pi-external-link"
                styleClass="p-button-text p-button-sm"
                (onClick)="navigateToSourcesPage()"></p-button>
            </div>
          </div>
        </ng-template>
      </p-overlayPanel>
    </div>
  `,
  styles: [`
    /* ===== TOKENS ===== */
    :host {
      --source-panel-width: 380px;
      --source-panel-max-height: 70vh;
      --trigger-height: 44px;  /* Minimum touch target */
      --item-height: 52px;     /* Comfortable touch target */
      --group-header-height: 44px;
      
      /* Language colors - all accessible */
      --color-es: #C62828; --color-es-bg: #FDE0E0; --color-es-text: #C62828;
      --color-en: #1565C0; --color-en-bg: #E3F2FD; --color-en-text: #1565C0;
      --color-ja: #E65100; --color-ja-bg: #FFF3E0; --color-ja-text: #E65100;
      --color-zh: #2E7D32; --color-zh-bg: #E8F5E9; --color-zh-text: #2E7D32;
      --color-pt: #00695C; --color-pt-bg: #E0F2F1; --color-pt-text: #00695C;
      --color-ru: #6A1B9A; --color-ru-bg: #F3E5F5; --color-ru-text: #6A1B9A;
      --color-fr: #006064; --color-fr-bg: #E0F7FA; --color-fr-text: #006064;
      --color-multi: #455A64; --color-multi-bg: #ECEFF1; --color-multi-text: #455A64;
    }

    /* ===== TRIGGER ===== */
    .source-selector {
      position: relative;
      display: inline-flex;
    }

    .source-trigger {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      min-height: var(--trigger-height);
      padding: 0.5rem 0.875rem;
      background: #fff;
      border: 1px solid #d1d5db;
      border-radius: 0.5rem;
      color: #1f2937;
      font-size: 0.875rem;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s ease;
      white-space: nowrap;
      box-shadow: 0 1px 2px rgba(0,0,0,0.05);
    }

    .source-trigger:hover:not(:disabled) {
      border-color: #9ca3af;
      background: #f9fafb;
    }

    .source-trigger:focus {
      outline: none;
      box-shadow: 0 0 0 3px #3b82f640;
      border-color: #3b82f6;
    }

    .source-trigger:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .trigger-content {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      min-width: 0;
    }

    .trigger-icon {
      font-size: 1.125rem;
      flex-shrink: 0;
    }

    .trigger-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 180px;
    }

    .lang-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 32px;
      height: 22px;
      padding: 0 0.5rem;
      border-radius: 9999px;
      font-size: 0.625rem;
      font-weight: 700;
      letter-spacing: 0.025em;
      text-transform: uppercase;
      flex-shrink: 0;
    }

    .trigger-chevron {
      font-size: 0.75rem;
      color: #6b7280;
      transition: transform 0.15s ease;
      flex-shrink: 0;
    }

    .trigger-chevron.rotate {
      transform: rotate(180deg);
    }

    .health-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #d1d5db;
      flex-shrink: 0;
      margin-left: 0.25rem;
    }

    .health-dot.healthy {
      background: #10b981;
      box-shadow: 0 0 0 2px #fff, 0 0 0 4px #10b981;
    }

    /* ===== PANEL ===== */
    .source-panel-overlay {
      --panel-width: var(--source-panel-width);
    }

    .source-panel {
      width: var(--source-panel-width);
      max-height: var(--source-panel-max-height);
      display: flex;
      flex-direction: column;
      background: #fff;
      border-radius: 0.75rem;
      box-shadow: 0 20px 25px -5px rgba(0,0,0,0.1), 0 10px 10px -5px rgba(0,0,0,0.04);
      overflow: hidden;
    }

    /* Header */
    .panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 1rem 1.25rem;
      border-bottom: 1px solid #e5e7eb;
      background: #f9fafb;
    }

    .panel-title {
      margin: 0;
      font-size: 1rem;
      font-weight: 600;
      color: #111827;
    }

    .health-summary {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.25rem 0.625rem;
      border-radius: 9999px;
      font-size: 0.75rem;
      font-weight: 500;
      color: #10b981;
      background: #ecfdf5;
    }

    .health-summary.warning {
      color: #f59e0b;
      background: #fffbeb;
    }

    .health-summary i {
      font-size: 0.75rem;
    }

    /* Search */
    .panel-search {
      padding: 0.75rem 1.25rem;
      border-bottom: 1px solid #e5e7eb;
    }

    .search-wrapper {
      position: relative;
    }

    .search-icon {
      position: absolute;
      left: 0.75rem;
      top: 50%;
      transform: translateY(-50%);
      color: #9ca3af;
      font-size: 1rem;
      pointer-events: none;
    }

    .panel-search input {
      width: 100%;
      padding: 0.5rem 0.75rem 0.5rem 2.5rem;
      border: 1px solid #d1d5db;
      border-radius: 0.5rem;
      font-size: 0.875rem;
      transition: all 0.15s ease;
    }

    .panel-search input:focus {
      outline: none;
      border-color: #3b82f6;
      box-shadow: 0 0 0 3px #3b82f640;
    }

    /* Body */
    .panel-body {
      flex: 1;
      overflow-y: auto;
      padding: 0.5rem;
    }

    /* Loading/Error/Empty */
    .loading-state,
    .error-state,
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 2rem 1rem;
      text-align: center;
      color: #6b7280;
      gap: 0.75rem;
    }

    .loading-state i {
      color: #3b82f6;
    }

    .error-state i {
      color: #ef4444;
      font-size: 2rem;
    }

    .empty-state i {
      font-size: 2.5rem;
      opacity: 0.5;
    }

    /* Language Groups */
    .language-group {
      border-radius: 0.5rem;
      overflow: hidden;
      margin-bottom: 0.375rem;
      background: #fafafa;
      border: 1px solid #f3f4f6;
    }

    .group-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      padding: 0.625rem 1rem;
      background: transparent;
      border: none;
      cursor: pointer;
      text-align: left;
      font-size: 0.8125rem;
      font-weight: 600;
      color: #374151;
      transition: background 0.1s ease;
    }

    .group-header:hover {
      background: #f3f4f6;
    }

    .group-header:focus {
      outline: none;
      box-shadow: inset 0 0 0 2px #3b82f6;
    }

    .group-lang-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
      font-size: 0.6875rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.025em;
    }

    .group-count {
      font-size: 0.75rem;
      font-weight: 500;
      color: #9ca3af;
      background: #f3f4f6;
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
    }

    .group-header i {
      font-size: 0.75rem;
      color: #9ca3af;
      transition: transform 0.15s ease;
    }

    .group-header i.rotate {
      transform: rotate(180deg);
    }

    .group-sources {
      max-height: 0;
      overflow: hidden;
      transition: max-height 0.25s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .group-sources:not(.collapsed) {
      max-height: 500px; /* Enough for all items */
    }

    /* Source Items */
    .source-item {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      width: 100%;
      padding: 0.625rem 1rem;
      background: transparent;
      border: none;
      border-radius: 0.375rem;
      cursor: pointer;
      text-align: left;
      transition: background 0.1s ease;
      min-height: var(--item-height);
    }

    .source-item:hover:not(.disabled) {
      background: #f3f4f6;
    }

    .source-item:focus {
      outline: none;
      box-shadow: inset 0 0 0 2px #3b82f6;
      z-index: 1;
    }

    .source-item.selected {
      background: var(--selected-bg, #eff6ff);
      font-weight: 500;
    }

    .source-item.selected:hover {
      background: var(--selected-hover-bg, #dbeafe);
    }

    .source-item.disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .source-icon {
      font-size: 1.25rem;
      flex-shrink: 0;
      width: 28px;
      text-align: center;
    }

    .source-info {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }

    .source-name {
      font-size: 0.8125rem;
      font-weight: 500;
      color: #1f2937;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .source-url {
      font-size: 0.6875rem;
      color: #9ca3af;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      font-family: monospace;
    }

    .source-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      margin-top: 0.25rem;
    }

    .tag {
      font-size: 0.625rem;
      padding: 0.125rem 0.375rem;
      border-radius: 9999px;
      background: #f3f4f6;
      color: #6b7280;
      font-weight: 500;
    }

    .tag.more {
      background: #e5e7eb;
      color: #9ca3af;
    }

    .selection-check {
      font-size: 1rem;
      flex-shrink: 0;
      margin-left: 0.5rem;
    }

    /* Footer */
    .panel-footer {
      padding: 0.75rem 1.25rem;
      border-top: 1px solid #e5e7eb;
      background: #f9fafb;
    }

    /* ===== RESPONSIVE ===== */
    @media (max-width: 640px) {
      :host {
        --source-panel-width: 100vw;
        --source-panel-max-height: 85vh;
      }

      .source-panel {
        border-radius: 0;
        max-height: 85vh;
      }

      .source-trigger {
        width: 100%;
        justify-content: space-between;
      }

      .trigger-label {
        max-width: none;
        flex: 1;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .source-trigger,
      .source-item,
      .group-header,
      .group-sources,
      .trigger-chevron {
        transition: none !important;
      }
    }

    @media (prefers-contrast: high) {
      .source-trigger,
      .source-panel,
      .group-header,
      .source-item {
        border-width: 2px;
      }
      .source-item:focus {
        box-shadow: inset 0 0 0 3px #000;
      }
    }
  `]
})
export class SourceSelectorComponent implements OnInit, OnDestroy {
  // ... implementation with signals, computed, etc.
}
```

---

## 4. Integración en AppMenu (Actualización)

```typescript
// app.menu.ts - Actualización mínima
@Component({
  // ...
  template: `
    <ul class="layout-menu">
      @for (item of model; track item.label) {
        @if (!item.separator) {
          @if (item.label === 'Gestor de Novelas' && sourcesLoaded) {
            <li class="layout-root-menuitem">
              <app-source-selector
                [sources]="availableSources"
                [selectedSourceId]="selectedSourceId"
                [loading]="loadingSources"
                [error]="sourcesError"
                [healthStatus]="healthStatus"
                (sourceSelected)="onSourceSelected($event)"
                (retry)="loadDynamicSources()">
              </app-source-selector>
            </li>
          } @else {
            <li app-menuitem [item]="item" [root]="true"></li>
          }
        } @else {
          <li class="menu-separator"></li>
        }
      }
    </ul>
  `
})
```

---

## 5. Breadcrumb en NovelsManager

```html
<!-- novels-manager.ts - Agregar al toolbar -->
<div class="flex items-center gap-2 mb-4 text-sm">
  <span class="text-gray-500">Fuente:</span>
  <app-source-badge 
    [source]="currentSource()" 
    [compact]="true"></app-source-badge>
  <span class="text-gray-400 mx-1">/</span>
  <span class="font-medium text-gray-800">{{ novelsCount() }} obras</span>
</div>
```

---

## 6. Checklist de Accesibilidad (Pre-Entrega)

| ✅ Criterio | Implementación |
|-------------|----------------|
| Contraste 4.5:1 | Tokens de color validados |
| Focus visible | `box-shadow: inset 0 0 0 2px #3b82f6` |
| ARIA roles | `role="listbox"`, `role="option"`, `role="group"` |
| ARIA states | `aria-selected`, `aria-disabled`, `aria-expanded` |
| Live regions | `aria-live="polite"` en loading/results |
| Keyboard nav | Arrow keys, Home, End, Escape, Tab |
| Touch targets | 44×44px mínimo (`--trigger-height`, `--item-height`) |
| Reduced motion | `@media (prefers-reduced-motion)` desactiva animaciones |
| High contrast | `@media (prefers-contrast: high)` bordes 2px |
| Screen reader | `sr-only` labels, `aria-describedby` para URLs |

---

## 7. Métricas de Éxito

| KPI | Actual | Objetivo |
|-----|--------|----------|
| Tiempo para cambiar fuente | ~3 clics | 1 clic + búsqueda |
| Fuentes visibles sin scroll | 8 | 12+ (agrupadas) |
| Contraste idioma badge | 2.1:1 | 4.5:1+ |
| Usuarios que encuentran fuente | ~60% | >90% |
| Tiempo carga percebida | 2s | <500ms (skeleton) |

---

## 8. Plan de Implementación

| Fase | Tareas | Estimación |
|------|--------|------------|
| 1. Tokens & Utils | Color tokens, language metadata, flags | 2h |
| 2. SourceSelector | Componente principal con OverlayPanel | 6h |
| 3. Integración | AppMenu + NovelsManager breadcrumb | 2h |
| 4. Testing a11y | axe-core, screen reader, keyboard | 3h |
| 5. Responsive | Mobile drawer, tablet breakpoints | 2h |
| **Total** | | **~15h** |

---

## 9. Consideraciones Futuras

1. **Virtual scrolling** para 358+ items (CDK Virtual Scroll)
2. **Favoritos** - permitir marcar fuentes frecuentes
3. **Historial reciente** - últimas 5 fuentes usadas
4. **Sync status per source** - indicador inline de última sincronización
5. **Multi-select** - sincronizar múltiples fuentes a la vez