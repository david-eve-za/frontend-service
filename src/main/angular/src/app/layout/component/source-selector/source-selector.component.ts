import { Component, inject, input, output, signal, computed, ViewChild, ElementRef, OnInit, OnDestroy, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, takeUntil, debounceTime, distinctUntilChanged } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TooltipModule } from 'primeng/tooltip';
import { RippleModule } from 'primeng/ripple';
import { TagModule } from 'primeng/tag';
import { ScrollPanelModule } from 'primeng/scrollpanel';
import { SourceMetadata } from '../../../pages/book-translator/novels-manager/sources.service';
import { LanguageGroup, groupSourcesByLanguage, getLanguageColor, getLanguageBgColor, getLanguageTextColor, getLanguageMetadata } from './language-metadata';

export interface HealthStatus { healthy: boolean; details: string; }

@Component({
  selector: 'app-source-selector',
  standalone: true,
  imports: [CommonModule, FormsModule, ButtonModule, InputTextModule, TooltipModule, RippleModule, TagModule, ScrollPanelModule],
  templateUrl: './source-selector.component.html',
  styleUrls: ['./source-selector.component.scss']
})
export class SourceSelectorComponent implements OnInit, OnDestroy {
  private router = inject(Router);
  private destroy$ = new Subject<void>();
  private searchSubject = new Subject<string>();

  // Inputs
  sources = input.required<SourceMetadata[]>();
  selectedSourceId = input<string>('elscione');
  loading = input<boolean>(false);
  error = input<string | null>(null);
  healthStatus = input<HealthStatus>({ healthy: true, details: 'Todas saludables' });

  // Outputs
  sourceSelected = output<SourceMetadata>();
  retry = output<void>();

  // ViewChild
  @ViewChild('listContainer') listContainer!: ElementRef<HTMLDivElement>;

  // Internal State
  panelOpen = signal(false);
  triggerId = signal(Math.random().toString(36).substring(2, 9));
  searchTerm = signal('');
  isMobile = signal(false);

  // Computed
  currentSource = computed(() => this.sources().find(s => s.id === this.selectedSourceId()));
  currentSourceDisplayName = computed(() => this.currentSource()?.displayName || 'Seleccionar fuente');
  currentSourceIcon = computed(() => this.currentSource()?.icon || 'pi pi-globe');
  currentSourceLanguage = computed(() => this.currentSource()?.language || 'es');
  currentSourceLanguageName = computed(() => getLanguageMetadata(this.currentSourceLanguage()).nativeName);
  currentSourceColor = computed(() => getLanguageColor(this.currentSourceLanguage()));
  currentSourceBgColor = computed(() => getLanguageBgColor(this.currentSourceLanguage()));
  currentSourceTextColor = computed(() => getLanguageTextColor(this.currentSourceLanguage()));

  languageGroups = computed(() => groupSourcesByLanguage(this.sources()));

  filteredLanguageGroups = computed(() => {
    const term = this.searchTerm().toLowerCase().trim();
    if (!term) return this.languageGroups();
    return this.languageGroups()
      .map(group => ({
        ...group,
        sources: group.sources.filter(source => 
          source.displayName.toLowerCase().includes(term) ||
          source.baseUrl.toLowerCase().includes(term) ||
          group.nativeName.toLowerCase().includes(term) ||
          group.languageName.toLowerCase().includes(term) ||
          source.tags?.some(tag => tag.toLowerCase().includes(term))
        ),
        expanded: group.sources.some(s => 
          s.displayName.toLowerCase().includes(term) ||
          s.baseUrl.toLowerCase().includes(term) ||
          s.tags?.some(tag => tag.toLowerCase().includes(term))
        )
      }))
      .filter(group => group.sources.length > 0);
  });

  totalSourcesCount = computed(() => this.sources().length);

  ngOnInit() {
    this.updateMobile();
    window.addEventListener('resize', this.updateMobile.bind(this));

    this.router.events.pipe(takeUntil(this.destroy$)).subscribe(() => {
      if (this.panelOpen()) this.closePanel();
    });

    this.searchSubject.pipe(
      debounceTime(150),
      distinctUntilChanged(),
      takeUntil(this.destroy$)
    ).subscribe(term => {
      this.searchTerm.set(term);
      const groups = this.filteredLanguageGroups();
      if (groups.length > 0 && groups[0].expanded === false) {
        groups[0].expanded = true;
      }
    });

    document.addEventListener('keydown', this.onGlobalKeydown.bind(this), { passive: true });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
    window.removeEventListener('resize', this.updateMobile.bind(this));
    document.removeEventListener('keydown', this.onGlobalKeydown.bind(this));
  }

  private updateMobile = () => { this.isMobile.set(window.innerWidth < 640); }

  private onGlobalKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && this.panelOpen()) this.closePanel();
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (this.panelOpen()) {
      const target = event.target as HTMLElement;
      const panel = document.getElementById(`source-panel-${this.triggerId()}`);
      const triggerBtn = document.querySelector('.source-trigger');
      if (panel && !panel.contains(target) && !triggerBtn?.contains(target)) {
        this.closePanel();
      }
    }
  }

  // Public Methods
  togglePanel(): void {
    if (this.loading()) return;
    this.panelOpen.update(v => !v);
    if (this.panelOpen()) {
      setTimeout(() => {
        const searchInput = document.getElementById(`source-search-${this.triggerId()}`);
        searchInput?.focus();
      }, 100);
    }
  }

  closePanel(): void { this.panelOpen.set(false); }

  onTriggerKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'Enter': case ' ': event.preventDefault(); this.togglePanel(); break;
      case 'ArrowDown': event.preventDefault(); if (!this.panelOpen()) this.togglePanel(); break;
      case 'Escape': this.closePanel(); break;
    }
  }

  onSearchTermChange(term: string): void { this.searchTerm.set(term); }
  clearSearch(): void { this.searchTerm.set(''); }

  toggleGroup(group: LanguageGroup): void { group.expanded = !group.expanded; }

  onGroupKeydown(event: KeyboardEvent, group: LanguageGroup): void {
    switch (event.key) {
      case 'Enter': case ' ': event.preventDefault(); this.toggleGroup(group); break;
      case 'ArrowRight': event.preventDefault(); if (!group.expanded) group.expanded = true; break;
      case 'ArrowLeft': event.preventDefault(); if (group.expanded) group.expanded = false; break;
    }
  }

  selectSource(source: SourceMetadata): void {
    if (!source.enabled) return;
    this.sourceSelected.emit(source);
    this.closePanel();
    this.router.navigate([], { queryParams: { source: source.id }, queryParamsHandling: 'merge' });
  }

  onItemKeydown(event: KeyboardEvent, source: SourceMetadata, group: LanguageGroup): void {
    switch (event.key) {
      case 'Enter': case ' ': event.preventDefault(); if (source.enabled) this.selectSource(source); break;
      case 'ArrowDown': event.preventDefault(); this.focusNextItem(group); break;
      case 'ArrowUp': event.preventDefault(); this.focusPreviousItem(group); break;
      case 'Home': event.preventDefault(); this.focusFirstItem(group); break;
      case 'End': event.preventDefault(); this.focusLastItem(group); break;
      case 'Escape': this.closePanel(); break;
    }
  }

  private focusNextItem(group: LanguageGroup): void {
    const items = this.listContainer?.nativeElement?.querySelectorAll(
      `.source-item[data-language="${group.language}"]:not(.disabled)`
    ) as NodeListOf<HTMLElement>;
    if (!items.length) return;
    const currentIndex = Array.from(items).findIndex(el => el === document.activeElement);
    const nextIndex = (currentIndex + 1) % items.length;
    items[nextIndex]?.focus();
  }

  private focusPreviousItem(group: LanguageGroup): void {
    const items = this.listContainer?.nativeElement?.querySelectorAll(
      `.source-item[data-language="${group.language}"]:not(.disabled)`
    ) as NodeListOf<HTMLElement>;
    if (!items.length) return;
    const currentIndex = Array.from(items).findIndex(el => el === document.activeElement);
    const prevIndex = (currentIndex - 1 + items.length) % items.length;
    items[prevIndex]?.focus();
  }

  private focusFirstItem(group: LanguageGroup): void {
    const items = this.listContainer?.nativeElement?.querySelectorAll(
      `.source-item[data-language="${group.language}"]:not(.disabled)`
    ) as NodeListOf<HTMLElement>;
    items[0]?.focus();
  }

  private focusLastItem(group: LanguageGroup): void {
    const items = this.listContainer?.nativeElement?.querySelectorAll(
      `.source-item[data-language="${group.language}"]:not(.disabled)`
    ) as NodeListOf<HTMLElement>;
    items[items.length - 1]?.focus();
  }

  navigateToSourcesPage(): void { this.closePanel(); this.router.navigate(['/book-translator/sources']); }
}