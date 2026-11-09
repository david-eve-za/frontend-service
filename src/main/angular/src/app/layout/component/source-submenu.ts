import { Component, computed, inject, input, signal, HostListener, ViewChild, ElementRef, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { RippleModule } from 'primeng/ripple';
import { TooltipModule } from 'primeng/tooltip';
import { SkeletonModule } from 'primeng/skeleton';
import { ButtonModule } from 'primeng/button';
import { SourceMetadata } from '../../pages/book-translator/novels-manager/sources.service';
import { LayoutService } from '@/app/layout/service/layout.service';
import { Subject, takeUntil } from 'rxjs';

@Component({
    selector: 'app-source-submenu',
    standalone: true,
    imports: [CommonModule, RouterModule, RippleModule, TooltipModule, SkeletonModule, ButtonModule],
    template: `
        <div #submenuContainer 
             class="source-submenu-container" 
             role="menu" 
             aria-label="Seleccionar fuente de novelas"
             [class.mobile-open]="mobileOpen()"
             (clickOutside)="closeMobile()">
            
            <!-- Trigger button (shown in mobile or as part of parent menu) -->
            <button #triggerBtn
                    type="button"
                    class="source-submenu-trigger"
                    [attr.aria-expanded]="mobileOpen()"
                    [attr.aria-haspopup]="true"
                    [attr.aria-controls]="'source-submenu-' + triggerId()"
                    (click)="toggleMobile()"
                    (keydown)="onTriggerKeydown($event)"
                    pRipple
                    pTooltip="Cambiar fuente de novelas"
                    tooltipPosition="right">
                <i [ngClass]="triggerIcon()" class="text-xl"></i>
                <span class="hidden sm:inline">{{ triggerLabel() }}</span>
                <i class="pi pi-chevron-down ml-1" [class.rotate-180]="mobileOpen()"></i>
            </button>

            <!-- Submenu panel -->
            <div #submenuPanel
                 id="source-submenu-{{ triggerId() }}"
                 class="source-submenu-panel"
                 role="menu"
                 [@submenuAnimation]="mobileOpen() ? 'open' : 'closed'"
                 *ngIf="mobileOpen() || isDesktop()">
                
                <!-- Header -->
                <div class="source-submenu-header">
                    <h3 class="font-semibold text-gray-800">Fuentes disponibles</h3>
                    <button type="button"
                            class="p-button-rounded p-button-text p-button-sm text-gray-500 hover:text-gray-700"
                            (click)="closeMobile()"
                            aria-label="Cerrar menú de fuentes"
                            *ngIf="mobileOpen()">
                        <i class="pi pi-times"></i>
                    </button>
                </div>

                <!-- Loading state -->
                <div class="source-submenu-loading" *ngIf="loading()" role="status" aria-live="polite">
                    <div class="flex items-center justify-center gap-2 p-4">
                        <i class="pi pi-spin pi-spinner text-2xl text-primary"></i>
                        <span class="text-gray-600">Cargando fuentes...</span>
                    </div>
                </div>

                <!-- Error state -->
                <div class="source-submenu-error" *ngIf="error()" role="alert" aria-live="assertive">
                    <div class="p-4 text-center">
                        <i class="pi pi-exclamation-triangle text-2xl text-red-500 mb-2 block"></i>
                        <p class="text-sm text-red-600 mb-3">{{ error() }}</p>
                        <p-button label="Reintentar" 
                                  icon="pi pi-refresh" 
                                  styleClass="p-button-sm p-button-outlined"
                                  (onClick)="retryLoad.emit()"></p-button>
                    </div>
                </div>

                <!-- Empty state -->
                <div class="source-submenu-empty" *ngIf="!loading() && !error() && sources().length === 0" role="status">
                    <div class="p-4 text-center text-gray-500">
                        <i class="pi pi-book text-3xl mb-2 block"></i>
                        <p class="text-sm">No hay fuentes configuradas</p>
                    </div>
                </div>

                <!-- Source list -->
                <ul class="source-submenu-list" 
                    role="listbox" 
                    aria-label="Lista de fuentes"
                    *ngIf="!loading() && !error() && sources().length > 0">
                    
                    @for (source of sources(); track source.id; let i = $index) {
                        <li role="option" 
                            [attr.aria-selected]="source.id === selectedSourceId()"
                            [attr.aria-disabled]="!source.enabled"
                            class="source-submenu-item"
                            [class.selected]="source.id === selectedSourceId()"
                            [class.disabled]="!source.enabled"
                            (click)="selectSource(source)"
                            (keydown)="onItemKeydown($event, source, i)"
                            tabindex="0">
                            
                            <div class="flex items-center gap-3 p-3 rounded-lg transition-colors hover:bg-gray-50">
                                <!-- Language flag/icon -->
                                <div class="flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center bg-gray-100 text-gray-600 text-xs font-medium uppercase">
                                    {{ source.language }}
                                </div>
                                
                                <!-- Source info -->
                                <div class="flex-1 min-w-0">
                                    <div class="flex items-center gap-2">
                                        <i [ngClass]="source.icon" class="text-xl text-primary"></i>
                                        <span class="font-medium text-gray-800 truncate">{{ source.displayName }}</span>
                                        @if (source.id === selectedSourceId()) {
                                            <i class="pi pi-check text-primary text-sm" aria-hidden="true"></i>
                                        }
                                    </div>
                                    <div class="text-xs text-gray-500 truncate">{{ source.baseUrl }}</div>
                                </div>
                                
                                <!-- Tags -->
                                <div class="flex flex-wrap gap-1" *ngIf="source.tags && source.tags.length > 0">
                                    @for (tag of source.tags; track tag) {
                                        <span class="px-2 py-0.5 text-xs bg-gray-100 text-gray-600 rounded-full">{{ tag }}</span>
                                    }
                                </div>
                            </div>
                        </li>
                    }
                </ul>

                <!-- Footer with health status -->
                <div class="source-submenu-footer" *ngIf="!loading() && !error() && sources().length > 0">
                    <div class="border-t pt-3">
                        <div class="flex items-center justify-between text-xs text-gray-500">
                            <span>{{ sources().filter(s => s.enabled).length }} de {{ sources().length }} fuentes activas</span>
                            <span class="flex items-center gap-1" [class.text-green-600]="healthStatus().healthy" [class.text-red-600]="!healthStatus().healthy">
                                <i [class]="healthStatus().healthy ? 'pi pi-check-circle' : 'pi pi-times-circle'"></i>
                                <span>{{ healthStatus().healthy ? 'Todas saludables' : healthStatus().details }}</span>
                            </span>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Backdrop for mobile -->
            <div class="source-submenu-backdrop fixed inset-0 bg-black/30 z-40 lg:hidden" 
                 *ngIf="mobileOpen()" 
                 (click)="closeMobile()"
                 aria-hidden="true"></div>
        </div>
    `,
    styles: [`
        .source-submenu-container {
            position: relative;
            display: inline-flex;
        }

        .source-submenu-trigger {
            display: flex;
            align-items: center;
            gap: 0.5rem;
            padding: 0.5rem 0.75rem;
            background: transparent;
            border: 1px solid transparent;
            border-radius: 0.5rem;
            color: #374151;
            font-size: 0.875rem;
            transition: all 0.2s ease;
            white-space: nowrap;
        }

        .source-submenu-trigger:hover {
            background: #f3f4f6;
            border-color: #d1d5db;
        }

        .source-submenu-trigger:focus {
            outline: none;
            box-shadow: 0 0 0 2px #3b82f6;
        }

        .source-submenu-panel {
            position: absolute;
            top: 100%;
            right: 0;
            margin-top: 0.5rem;
            width: 320px;
            max-height: 70vh;
            background: white;
            border: 1px solid #e5e7eb;
            border-radius: 0.75rem;
            box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1);
            z-index: 50;
            overflow: hidden;
            display: flex;
            flex-direction: column;
        }

        .source-submenu-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 1rem;
            border-bottom: 1px solid #e5e7eb;
            background: #f9fafb;
        }

        .source-submenu-header h3 {
            margin: 0;
        }

        .source-submenu-list {
            list-style: none;
            margin: 0;
            padding: 0.5rem;
            overflow-y: auto;
            flex: 1;
        }

        .source-submenu-item {
            border-radius: 0.5rem;
            cursor: pointer;
            transition: background-color 0.15s ease;
        }

        .source-submenu-item:hover:not(.disabled) {
            background-color: #f3f4f6;
        }

        .source-submenu-item:focus {
            outline: none;
            box-shadow: inset 0 0 0 2px #3b82f6;
        }

        .source-submenu-item.selected {
            background-color: #eff6ff;
        }

        .source-submenu-item.selected:hover {
            background-color: #dbeafe;
        }

        .source-submenu-item.disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }

        .source-submenu-footer {
            padding: 0.5rem 1rem;
        }

        .source-submenu-loading,
        .source-submenu-error,
        .source-submenu-empty {
            padding: 1rem;
            text-align: center;
        }

        .source-submenu-backdrop {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
        }

        /* Animations */
        @keyframes submenu-enter {
            from {
                opacity: 0;
                transform: translateY(-8px) scale(0.95);
            }
            to {
                opacity: 1;
                transform: translateY(0) scale(1);
            }
        }

        @keyframes submenu-leave {
            from {
                opacity: 1;
                transform: translateY(0) scale(1);
            }
            to {
                opacity: 0;
                transform: translateY(-8px) scale(0.95);
            }
        }

        /* Mobile styles */
        @media (max-width: 1023px) {
            .source-submenu-panel {
                position: fixed;
                top: 0;
                right: 0;
                bottom: 0;
                width: 100%;
                max-width: 360px;
                max-height: 100vh;
                border-radius: 0;
                border-left: 1px solid #e5e7eb;
                z-index: 50;
                animation: submenu-enter 300ms cubic-bezier(0.16, 1, 0.3, 1);
            }

            .source-submenu-container {
                width: 100%;
            }

            .source-submenu-trigger {
                width: 100%;
                justify-content: space-between;
                padding: 0.75rem 1rem;
                font-size: 1rem;
            }
        }

        /* Desktop: show as dropdown from menu */
        @media (min-width: 1024px) {
            .source-submenu-panel {
                min-width: 280px;
            }
        }

        /* High contrast mode */
        @media (prefers-contrast: high) {
            .source-submenu-panel {
                border-width: 2px;
            }
            .source-submenu-item:focus {
                box-shadow: inset 0 0 0 3px #000;
            }
        }

        /* Reduced motion */
        @media (prefers-reduced-motion: reduce) {
            .source-submenu-panel {
                animation: none;
            }
            .source-submenu-trigger,
            .source-submenu-item {
                transition: none;
            }
        }
    `]
})
export class SourceSubmenu implements OnInit, OnDestroy {
    private router = inject(Router);
    private layoutService = inject(LayoutService);
    private destroy$ = new Subject<void>();

    // Inputs
    sources = input.required<SourceMetadata[]>();
    selectedSourceId = input<string>('elscione');
    loading = input<boolean>(false);
    error = input<string | null>(null);
    healthStatus = input<{ healthy: boolean; details: string }>({ healthy: true, details: 'Todas saludables' });

    // Outputs
    @Output() sourceSelected = new EventEmitter<SourceMetadata>();
    @Output() retryLoad = new EventEmitter<void>();

    // Internal state
    mobileOpen = signal(false);
    triggerId = signal(Math.random().toString(36).substr(2, 9));
    focusedIndex = signal(-1);

    @ViewChild('submenuPanel') submenuPanel!: ElementRef<HTMLDivElement>;
    @ViewChild('triggerBtn') triggerBtn!: ElementRef<HTMLButtonElement>;

    ngOnInit() {
        // Close on route change
        this.router.events.pipe(takeUntil(this.destroy$)).subscribe(() => {
            if (this.mobileOpen()) {
                this.closeMobile();
            }
        });

        // Close on Escape key
        document.addEventListener('keydown', this.onGlobalKeydown.bind(this));
    }

    ngOnDestroy() {
        this.destroy$.next();
        this.destroy$.complete();
        document.removeEventListener('keydown', this.onGlobalKeydown.bind(this));
    }

    @HostListener('document:click', ['$event'])
    onDocumentClick(event: MouseEvent) {
        if (this.mobileOpen() && this.submenuPanel) {
            const target = event.target as HTMLElement;
            if (!this.submenuPanel.nativeElement.contains(target) && 
                !this.triggerBtn?.nativeElement.contains(target)) {
                this.closeMobile();
            }
        }
    }

    onGlobalKeydown(event: KeyboardEvent) {
        if (event.key === 'Escape' && this.mobileOpen()) {
            this.closeMobile();
            this.triggerBtn?.nativeElement.focus();
        }
    }

    triggerLabel(): string {
        const source = this.sources().find(s => s.id === this.selectedSourceId());
        return source?.displayName || 'Fuentes';
    }

    triggerIcon(): string {
        const source = this.sources().find(s => s.id === this.selectedSourceId());
        return source?.icon || 'pi pi-globe';
    }

    isDesktop(): boolean {
        return window.innerWidth >= 1024;
    }

    toggleMobile(): void {
        this.mobileOpen.update(v => !v);
        if (this.mobileOpen()) {
            this.focusedIndex.set(0);
            // Focus first item after animation
            setTimeout(() => this.focusCurrentItem(), 100);
        } else {
            this.focusedIndex.set(-1);
        }
    }

    closeMobile(): void {
        this.mobileOpen.set(false);
        this.focusedIndex.set(-1);
    }

    selectSource(source: SourceMetadata): void {
        if (!source.enabled) return;
        
        this.sourceSelected.emit(source);
        this.closeMobile();
        
        // Navigate with query param
        this.router.navigate([], {
            queryParams: { source: source.id },
            queryParamsHandling: 'merge'
        });
    }

    onTriggerKeydown(event: KeyboardEvent): void {
        switch (event.key) {
            case 'Enter':
            case ' ':
                event.preventDefault();
                this.toggleMobile();
                break;
            case 'ArrowDown':
                event.preventDefault();
                this.toggleMobile();
                setTimeout(() => this.focusCurrentItem(), 100);
                break;
            case 'Escape':
                this.closeMobile();
                break;
        }
    }

    onItemKeydown(event: KeyboardEvent, source: SourceMetadata, index: number): void {
        switch (event.key) {
            case 'Enter':
            case ' ':
                event.preventDefault();
                if (source.enabled) {
                    this.selectSource(source);
                }
                break;
            case 'ArrowDown':
                event.preventDefault();
                this.focusNextItem();
                break;
            case 'ArrowUp':
                event.preventDefault();
                this.focusPreviousItem();
                break;
            case 'Home':
                event.preventDefault();
                this.focusedIndex.set(0);
                this.focusCurrentItem();
                break;
            case 'End':
                event.preventDefault();
                this.focusedIndex.set(this.sources().length - 1);
                this.focusCurrentItem();
                break;
            case 'Escape':
                this.closeMobile();
                this.triggerBtn?.nativeElement.focus();
                break;
            case 'Tab':
                // Allow natural tab behavior
                break;
        }
    }

    private focusNextItem(): void {
        const enabledSources = this.sources().filter(s => s.enabled);
        if (enabledSources.length === 0) return;
        
        let nextIndex = this.focusedIndex() + 1;
        while (nextIndex < this.sources().length && !this.sources()[nextIndex].enabled) {
            nextIndex++;
        }
        if (nextIndex >= this.sources().length) nextIndex = this.findFirstEnabledIndex();
        this.focusedIndex.set(nextIndex);
        this.focusCurrentItem();
    }

    private focusPreviousItem(): void {
        const enabledSources = this.sources().filter(s => s.enabled);
        if (enabledSources.length === 0) return;
        
        let prevIndex = this.focusedIndex() - 1;
        while (prevIndex >= 0 && !this.sources()[prevIndex].enabled) {
            prevIndex--;
        }
        if (prevIndex < 0) prevIndex = this.findLastEnabledIndex();
        this.focusedIndex.set(prevIndex);
        this.focusCurrentItem();
    }

    private findFirstEnabledIndex(): number {
        return this.sources().findIndex(s => s.enabled) ?? 0;
    }

    private findLastEnabledIndex(): number {
        for (let i = this.sources().length - 1; i >= 0; i--) {
            if (this.sources()[i].enabled) return i;
        }
        return 0;
    }

    private focusCurrentItem(): void {
        if (this.submenuPanel) {
            const items = this.submenuPanel.nativeElement.querySelectorAll('.source-submenu-item:not(.disabled)');
            if (items[this.focusedIndex()]) {
                (items[this.focusedIndex()] as HTMLElement).focus();
            }
        }
    }
}