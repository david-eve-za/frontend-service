import {
    Component,
    OnDestroy,
    OnInit,
    computed,
    inject,
    signal,
    viewChild,
    ElementRef,
    ChangeDetectorRef,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';

/** Evento de log recibido por el stream SSE (LogEventDto del backend). */
interface LogEvent {
    id: string;
    timestamp: string;
    level: 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
    logger: string;
    thread: string;
    message: string;
    mdc: Record<string, string>;
}

type Level = LogEvent['level'];
type ConnectionState = 'connecting' | 'open' | 'closed';

/** Eventos retenidos en la vista (el backend retiene 1000 en su búfer). */
const MAX_DISPLAY = 500;

/** Frecuencia de volcado del lote pendiente a la señal (ms). */
const FLUSH_INTERVAL_MS = 250;

/** Niveles mostrados por defecto. */
const DEFAULT_LEVELS: Record<Level, boolean> = {
    TRACE: false,
    DEBUG: true,
    INFO: true,
    WARN: true,
    ERROR: true,
};

/**
 * Visor de logs en tiempo real: suscribe un EventSource al stream SSE del
 * backend (GET /api/logs/stream) y muestra los eventos con filtros por
 * nivel, clase, texto y traza MDC (bookId/traceId/step) del pipeline.
 * Sin aplicaciones externas: todo fluye en proceso desde Logback.
 */
@Component({
    selector: 'app-log-viewer',
    standalone: true,
    imports: [
        CommonModule,
        FormsModule,
        ButtonModule,
        CheckboxModule,
        InputTextModule,
        SelectModule,
        TagModule,
        TooltipModule,
        DatePipe,
    ],
    template: `
        <div class="card mb-4">
            <div
                class="flex flex-col lg:flex-row lg:items-center justify-between gap-4"
            >
                <div>
                    <div class="flex items-center gap-3">
                        <div class="font-semibold text-xl">Visor de Logs</div>
                        <p-tag
                            [value]="
                                connection() === 'open'
                                    ? 'En vivo'
                                    : connection() === 'connecting'
                                      ? 'Conectando…'
                                      : 'Desconectado'
                            "
                            [severity]="
                                connection() === 'open'
                                    ? 'success'
                                    : connection() === 'connecting'
                                      ? 'info'
                                      : 'danger'
                            "
                        />
                        @if (paused()) {
                            <p-tag value="En pausa" severity="warn" />
                        }
                    </div>
                    <p class="text-muted-color text-sm mt-1">
                        Eventos de la aplicación en tiempo real, con el contexto
                        de traza del pipeline (bookId / traceId / paso).
                    </p>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                    @if (pausedCount() > 0) {
                        <span class="text-sm text-amber-600"
                            >{{ pausedCount() }} mensajes descartados durante la
                            pausa</span
                        >
                    }
                    <p-button
                        [label]="paused() ? 'Reanudar' : 'Pausar'"
                        [icon]="paused() ? 'pi pi-play' : 'pi pi-pause'"
                        severity="secondary"
                        [outlined]="true"
                        (click)="togglePause()"
                    />
                    <p-button
                        label="Limpiar"
                        icon="pi pi-trash"
                        severity="secondary"
                        [outlined]="true"
                        (click)="clear()"
                    />
                </div>
            </div>

            <!-- Filtros -->
            <div class="mt-4 pt-4 border-t grid grid-cols-12 gap-4 items-end">
                <div class="col-span-12 lg:col-span-3">
                    <label class="block text-sm font-medium mb-2">Nivel</label>
                    <div class="flex flex-wrap gap-3">
                        @for (level of levelNames; track level) {
                            <label
                                class="flex items-center gap-2 cursor-pointer text-sm"
                            >
                                <p-checkbox
                                    [binary]="true"
                                    [ngModel]="levels()[level]"
                                    (ngModelChange)="setLevel(level, $event)"
                                />
                                <span [class]="levelTextColor(level)">{{
                                    level
                                }}</span>
                                <span class="text-xs text-muted-color"
                                    >({{ levelCounts()[level] ?? 0 }})</span
                                >
                            </label>
                        }
                    </div>
                </div>
                <div class="col-span-12 md:col-span-4 lg:col-span-2">
                    <label class="block text-sm font-medium mb-2">Buscar</label>
                    <input
                        pInputText
                        type="text"
                        class="w-full"
                        placeholder="Texto del mensaje o de la clase…"
                        [ngModel]="textFilter()"
                        (ngModelChange)="textFilter.set($event)"
                    />
                </div>
                <div class="col-span-12 md:col-span-4 lg:col-span-3">
                    <label class="block text-sm font-medium mb-2">Clase</label>
                    <p-select
                        [options]="classOptions()"
                        optionLabel="label"
                        optionValue="value"
                        [ngModel]="classFilter()"
                        (ngModelChange)="classFilter.set($event)"
                        placeholder="Todas las clases"
                        styleClass="w-full"
                        [showClear]="true"
                        [filter]="true"
                        filterPlaceHolder="Buscar clase…"
                    />
                </div>
                <div class="col-span-12 md:col-span-4 lg:col-span-2">
                    <label
                        class="block text-sm font-medium mb-2"
                        title="Oculta los eventos cuyo mensaje o clase contengan este texto (vacío = mostrar todo)"
                        >Excluir (clase o texto)</label
                    >
                    <input
                        pInputText
                        type="text"
                        class="w-full"
                        placeholder="Ej.: NovelsCatalogSync"
                        [ngModel]="excludeFilter()"
                        (ngModelChange)="excludeFilter.set($event)"
                    />
                </div>
                <div class="col-span-12 md:col-span-4 lg:col-span-2">
                    <label class="block text-sm font-medium mb-2"
                        >Traza (MDC)</label
                    >
                    <input
                        pInputText
                        type="text"
                        class="w-full"
                        placeholder="bookId, traceId o paso"
                        [ngModel]="traceFilter()"
                        (ngModelChange)="traceFilter.set($event)"
                    />
                </div>
            </div>
        </div>

        <div class="card">
            <div
                #container
                class="overflow-y-auto font-mono text-sm"
                style="height: 70vh"
                (scroll)="onScroll()"
            >
                @for (ev of filtered(); track ev.id) {
                    <div
                        class="flex items-start gap-2 px-2 py-1 border-b border-surface hover:bg-surface-50 dark:hover:bg-surface-800"
                    >
                        <span class="text-muted-color whitespace-nowrap">{{
                            ev.timestamp | date: 'HH:mm:ss.SSS'
                        }}</span>
                        <span
                            class="font-semibold whitespace-nowrap {{
                                levelTextColor(ev.level)
                            }}"
                            >{{ ev.level }}</span
                        >
                        <span
                            class="text-muted-color truncate max-w-56"
                            [title]="ev.logger"
                            >{{ ev.logger }}</span
                        >
                        @if (ev.mdc['bookId']) {
                            <span
                                class="whitespace-nowrap text-xs px-1 rounded bg-cyan-100 dark:bg-cyan-400/20 text-cyan-700 dark:text-cyan-300"
                                [title]="'bookId: ' + ev.mdc['bookId']"
                                >📘 {{ ev.mdc['bookId'].substring(0, 8) }}</span
                            >
                        }
                        @if (ev.mdc['step']) {
                            <span
                                class="whitespace-nowrap text-xs px-1 rounded bg-purple-100 dark:bg-purple-400/20 text-purple-700 dark:text-purple-300"
                                [title]="'paso: ' + ev.mdc['step']"
                                >{{ stepLabel(ev.mdc['step']) }}</span
                            >
                        }
                        @if (ev.mdc['traceId']) {
                            <span
                                class="whitespace-nowrap text-xs px-1 rounded bg-blue-100 dark:bg-blue-400/20 text-blue-700 dark:text-blue-300"
                                [title]="'traceId: ' + ev.mdc['traceId']"
                                >🔗
                                {{ ev.mdc['traceId'].substring(0, 8) }}</span
                            >
                        }
                        <span
                            class="whitespace-pre-wrap break-words flex-1 min-w-0"
                            >{{ ev.message }}</span
                        >
                    </div>
                } @empty {
                    <div
                        class="flex items-center justify-center h-full text-muted-color"
                    >
                        @if (events().length === 0) {
                            {{
                                connection() === 'open'
                                    ? 'Esperando eventos de log…'
                                    : 'Conectando al stream de logs…'
                            }}
                        } @else {
                            Ningún evento coincide con los filtros aplicados.
                        }
                    </div>
                }
            </div>
        </div>
    `,
})
export class LogViewer implements OnInit, OnDestroy {
    private readonly cdr = inject(ChangeDetectorRef);
    private readonly container =
        viewChild<ElementRef<HTMLDivElement>>('container');

    readonly levelNames: Level[] = ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];

    events = signal<LogEvent[]>([]);
    paused = signal(false);
    pausedCount = signal(0);
    connection = signal<ConnectionState>('connecting');
    levels = signal<Record<Level, boolean>>({ ...DEFAULT_LEVELS });
    textFilter = signal('');
    classFilter = signal<string | null>(null);
    traceFilter = signal('');
    /**
     * Exclusión por texto o clase: oculta el ruido dominante (por defecto
     * el sync del catálogo de elscione) para que el seguimiento de los
     * procesos del pipeline respire. Vacío = mostrar todo.
     */
    excludeFilter = signal('NovelsCatalogSync');

    private source?: EventSource;
    private pending: LogEvent[] = [];
    private flushTimer: ReturnType<typeof setTimeout> | null = null;
    private follow = true;

    levelCounts = computed(() => {
        const counts: Partial<Record<Level, number>> = {};
        for (const ev of this.events()) {
            counts[ev.level] = (counts[ev.level] ?? 0) + 1;
        }
        return counts;
    });

    /** Clases presentes en los eventos retenidos, con su conteo, ordenadas. */
    classOptions = computed(() => {
        const counts = new Map<string, number>();
        for (const ev of this.events()) {
            counts.set(ev.logger, (counts.get(ev.logger) ?? 0) + 1);
        }
        return Array.from(counts.entries())
            .sort((a, b) => a[0].localeCompare(b[0]))
            .map(([logger, count]) => ({
                label: `${logger} (${count})`,
                value: logger,
            }));
    });

    filtered = computed(() => {
        const text = this.textFilter().trim().toLowerCase();
        const cls = this.classFilter();
        const trace = this.traceFilter().trim().toLowerCase();
        const exclude = this.excludeFilter().trim().toLowerCase();
        const levels = this.levels();

        return this.events().filter((ev) => {
            if (!levels[ev.level]) return false;
            if (cls && ev.logger !== cls) return false;
            if (
                trace &&
                !Object.values(ev.mdc ?? {}).some((v) =>
                    v.toLowerCase().includes(trace),
                )
            )
                return false;
            const haystack = (ev.message + ' ' + ev.logger).toLowerCase();
            if (exclude && haystack.includes(exclude)) return false;
            if (text && !haystack.includes(text)) return false;
            return true;
        });
    });

    ngOnInit() {
        this.source = new EventSource('/api/logs/stream');
        this.source.onopen = () => this.connection.set('open');
        this.source.onerror = () => {
            // EventSource reintenta solo; CLOSED significa stream terminado.
            this.connection.set(
                this.source?.readyState === EventSource.CLOSED
                    ? 'closed'
                    : 'connecting',
            );
        };
        this.source.addEventListener('log', (e) => {
            this.onEvent(JSON.parse((e as MessageEvent).data) as LogEvent);
        });
    }

    ngOnDestroy() {
        this.source?.close();
        if (this.flushTimer !== null) {
            clearTimeout(this.flushTimer);
        }
    }

    clear() {
        this.events.set([]);
        this.pausedCount.set(0);
    }

    togglePause() {
        this.paused.update((p) => !p);
        if (!this.paused()) {
            this.pausedCount.set(0);
        }
    }

    setLevel(level: Level, value: boolean) {
        this.levels.update((levels) => ({ ...levels, [level]: value }));
    }

    onScroll() {
        const el = this.container()?.nativeElement;
        if (!el) return;
        // Sigue el stream solo si el usuario está al final del scroll.
        this.follow = el.scrollTop + el.clientHeight >= el.scrollHeight - 40;
    }

    levelTextColor(level: Level | string): string {
        switch (level) {
            case 'ERROR':
                return 'text-red-600';
            case 'WARN':
                return 'text-amber-600';
            case 'INFO':
                return 'text-blue-600';
            case 'DEBUG':
                return 'text-gray-500';
            default:
                return 'text-purple-600';
        }
    }

    stepLabel(step: string): string {
        const labels: Record<string, string> = {
            EXTRACT: 'Extracción',
            SPLIT: 'División',
            TRANSLATE: 'Traducción',
            AUDIO: 'Audio',
            FINALIZE: 'Finalización',
        };
        return labels[step] ?? step;
    }

    private onEvent(ev: LogEvent) {
        if (this.paused()) {
            this.pausedCount.update((n) => n + 1);
            return;
        }
        this.pending.push(ev);
        if (this.flushTimer === null) {
            this.flushTimer = setTimeout(() => this.flush(), FLUSH_INTERVAL_MS);
        }
    }

    /** Vuelca el lote pendiente a la señal y mantiene el límite de la vista. */
    private flush() {
        this.flushTimer = null;
        if (this.pending.length === 0) return;

        const merged = this.events().concat(this.pending);
        if (merged.length > MAX_DISPLAY) {
            merged.splice(0, merged.length - MAX_DISPLAY);
        }
        this.pending = [];
        this.events.set(merged);
        this.cdr.markForCheck();

        if (this.follow) {
            setTimeout(() => {
                const el = this.container()?.nativeElement;
                if (el) {
                    el.scrollTop = el.scrollHeight;
                }
            });
        }
    }
}
