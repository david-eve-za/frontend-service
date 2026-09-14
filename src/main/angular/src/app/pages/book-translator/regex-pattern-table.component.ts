import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { RegexPatternService, RegexPattern } from './regex-pattern.service';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { CheckboxModule } from 'primeng/checkbox';
import { SelectModule } from 'primeng/select';
import { InputNumberModule } from 'primeng/inputnumber';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ToolbarModule } from 'primeng/toolbar';
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { MessageService, ConfirmationService } from 'primeng/api';

@Component({
  selector: 'app-regex-pattern-table',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    TableModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    TextareaModule,
    CheckboxModule,
    SelectModule,
    InputNumberModule,
    TagModule,
    ToastModule,
    ConfirmDialogModule,
    ToolbarModule,
    IconFieldModule,
    InputIconModule
  ],
  providers: [MessageService, ConfirmationService],
  template: `
    <p-toast></p-toast>
    <p-confirmDialog></p-confirmDialog>

    <div class="card">
      <p-toolbar>
        <ng-template pTemplate="start">
          <div class="flex gap-2">
            <p-button label="Nuevo Patrón" icon="pi pi-plus" (click)="openNew()" styleClass="p-button-primary"></p-button>
            <p-button label="Actualizar" icon="pi pi-refresh" (click)="loadPatterns()" styleClass="p-button-secondary" [loading]="loading"></p-button>
          </div>
        </ng-template>
        <ng-template pTemplate="end">
          <p-iconField>
            <p-inputIcon class="pi pi-search" />
            <input pInputText type="text" (input)="onGlobalFilter($event)" placeholder="Buscar..." style="width: 250px" />
          </p-iconField>
        </ng-template>
      </p-toolbar>

      <p-table 
        [value]="patterns" 
        [loading]="loading"
        [paginator]="true"
        [rows]="10"
        [showCurrentPageReport]="true"
        currentPageReportTemplate="Mostrando {first} a {last} de {totalRecords} patrones"
        [rowsPerPageOptions]="[10, 25, 50]"
        [globalFilterFields]="['name', 'displayName', 'pattern', 'patternType']"
        responsiveLayout="scroll"
        dataKey="id">
        
        <ng-template pTemplate="header">
          <tr>
            <th style="width: 50px">#</th>
            <th pSortableColumn="orderIndex">Orden <p-sortIcon field="orderIndex"></p-sortIcon></th>
            <th pSortableColumn="displayName">Nombre <p-sortIcon field="displayName"></p-sortIcon></th>
            <th pSortableColumn="patternType">Tipo <p-sortIcon field="patternType"></p-sortIcon></th>
            <th pSortableColumn="pattern">Patrón <p-sortIcon field="pattern"></p-sortIcon></th>
            <th pSortableColumn="replacement">Reemplazo <p-sortIcon field="replacement"></p-sortIcon></th>
            <th pSortableColumn="enabled">Estado <p-sortIcon field="enabled"></p-sortIcon></th>
            <th style="width: 120px">Acciones</th>
          </tr>
        </ng-template>
        
        <ng-template pTemplate="body" let-pattern let-rowIndex="rowIndex">
          <tr>
            <td>{{ rowIndex + 1 }}</td>
            <td>{{ pattern.orderIndex }}</td>
            <td>
              <div class="font-medium">{{ pattern.displayName }}</div>
              <div class="text-sm text-gray-500">{{ pattern.name }}</div>
            </td>
            <td>
              <p-tag 
                [value]="getPatternTypeLabel(pattern.patternType)" 
                [severity]="getPatternTypeSeverity(pattern.patternType)">
              </p-tag>
            </td>
            <td>
              <code class="text-sm" style="max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: block;">
                {{ pattern.pattern }}
              </code>
            </td>
            <td>
              <code class="text-sm" style="max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: block;">
                {{ pattern.replacement || '(vacío = eliminar)' }}
              </code>
            </td>
            <td>
              <p-tag 
                [value]="pattern.enabled ? 'Activo' : 'Inactivo'" 
                [severity]="pattern.enabled ? 'success' : 'danger'">
              </p-tag>
            </td>
            <td>
              <div class="flex gap-1">
                <p-button 
                  icon="pi pi-pencil" 
                  pTooltip="Editar" 
                  (click)="openEdit(pattern)" 
                  styleClass="p-button-text p-button-sm p-button-rounded p-button-info"
                  [disabled]="saving">
                </p-button>
                <p-button 
                  icon="pi pi-eye" 
                  pTooltip="Ver detalles" 
                  (click)="openView(pattern)" 
                  styleClass="p-button-text p-button-sm p-button-rounded p-button-secondary"
                  [disabled]="saving">
                </p-button>
                <p-button 
                  icon="pi pi-trash" 
                  pTooltip="Eliminar" 
                  (click)="confirmDelete(pattern)" 
                  styleClass="p-button-text p-button-sm p-button-rounded p-button-danger"
                  [disabled]="saving">
                </p-button>
              </div>
            </td>
          </tr>
        </ng-template>
        
        <ng-template pTemplate="emptymessage">
          <tr>
            <td colspan="8" class="text-center py-8">
              <i class="pi pi-info-circle text-4xl text-gray-400"></i>
              <p class="mt-2 text-gray-500">No hay patrones configurados</p>
              <p-button label="Crear primer patrón" icon="pi pi-plus" (click)="openNew()" styleClass="p-button-primary mt-2"></p-button>
            </td>
          </tr>
        </ng-template>
      </p-table>
    </div>

    <!-- Dialog para Crear/Editar -->
    <p-dialog 
      [(visible)]="dialogVisible" 
      [header]="dialogHeader" 
      [modal]="true" 
      [style]="{ width: '600px' }" 
      [draggable]="false" 
      [resizable]="false"
      [closeOnEscape]="true"
      (onHide)="onDialogHide()">
      
      <form [formGroup]="form" (ngSubmit)="savePattern()">
        <div class="grid">
          <div class="col-12">
            <label class="block text-sm font-medium mb-1">Nombre *</label>
            <input pInputText formControlName="name" placeholder="Identificador único (ej: url-cleaner)" />
            <small class="p-error" *ngIf="form.get('name')?.invalid && form.get('name')?.touched">
              El nombre es requerido
            </small>
          </div>

          <div class="col-12">
            <label class="block text-sm font-medium mb-1">Nombre para mostrar *</label>
            <input pInputText formControlName="displayName" placeholder="Nombre visible en la UI (ej: Limpiador de URLs)" />
            <small class="p-error" *ngIf="form.get('displayName')?.invalid && form.get('displayName')?.touched">
              El nombre para mostrar es requerido
            </small>
          </div>

          <div class="col-12">
            <label class="block text-sm font-medium mb-1">Descripción</label>
            <textarea pInputTextarea formControlName="description" rows="2" placeholder="Describe qué hace este patrón..."></textarea>
          </div>

          <div class="col-12 md:col-6">
            <label class="block text-sm font-medium mb-1">Tipo de Patrón *</label>
            <p-select formControlName="patternType" [options]="patternTypes" optionLabel="label" optionValue="value" placeholder="Seleccionar tipo"></p-select>
          </div>

          <div class="col-12 md:col-6">
            <label class="block text-sm font-medium mb-1">Orden *</label>
            <p-inputNumber formControlName="orderIndex" [min]="0" [step]="1" [showButtons]="true"></p-inputNumber>
          </div>

          <div class="col-12">
            <label class="block text-sm font-medium mb-1">Expresión Regular *</label>
            <div class="flex gap-2">
              <input pInputText formControlName="pattern" placeholder="Expresión regular (ej: https?://\\S+)" style="font-family: monospace; flex: 1;" />
              <p-button label="Validar" icon="pi pi-check" (click)="validatePattern()" [loading]="validating" styleClass="p-button-text"></p-button>
            </div>
            <small class="p-error" *ngIf="form.get('pattern')?.invalid && form.get('pattern')?.touched">
              El patrón es requerido
            </small>
            <div class="flex gap-2 mt-2" *ngIf="validationResult !== undefined">
              <p-tag [value]="validationResult ? 'Válido' : 'Inválido'" [severity]="validationResult ? 'success' : 'danger'"></p-tag>
              <span class="flex align-items-center text-sm text-gray-500" *ngIf="!validationResult">{{ validationMessage }}</span>
            </div>
          </div>

          <div class="col-12">
            <label class="block text-sm font-medium mb-1">Reemplazo (vacío para eliminar coincidencias)</label>
            <input pInputText formControlName="replacement" placeholder="Texto de reemplazo (ej: ' ' para espacio)" />
          </div>

          <div class="col-12 md:col-6">
            <div class="flex items-center gap-2">
              <p-checkbox formControlName="enabled" [binary]="true"></p-checkbox>
              <label class="text-sm">Activado</label>
            </div>
          </div>

          <div class="col-12 md:col-4">
            <div class="flex flex-wrap gap-4">
              <div class="flex items-center gap-2">
                <p-checkbox formControlName="caseInsensitive" [binary]="true"></p-checkbox>
                <label class="text-sm">Ignorar mayúsculas</label>
              </div>
              <div class="flex items-center gap-2">
                <p-checkbox formControlName="multiline" [binary]="true"></p-checkbox>
                <label class="text-sm">Multilínea</label>
              </div>
              <div class="flex items-center gap-2">
                <p-checkbox formControlName="dotAll" [binary]="true"></p-checkbox>
                <label class="text-sm">Dot All (.) = todo</label>
              </div>
            </div>
          </div>
        </div>

        <div class="flex justify-content-end gap-2 mt-4">
          <p-button label="Cancelar" icon="pi pi-times" (click)="closeDialog()" styleClass="p-button-secondary"></p-button>
          <p-button label="Guardar" icon="pi pi-save" type="submit" [loading]="saving" styleClass="p-button-primary"></p-button>
        </div>
      </form>
    </p-dialog>

    <!-- Dialog solo vista (readonly) -->
    <p-dialog 
      [(visible)]="viewDialogVisible" 
      [header]="'Detalles del Patrón'" 
      [modal]="true" 
      [style]="{ width: '600px' }" 
      [draggable]="false" 
      [resizable]="false">
      
      <div class="grid" *ngIf="selectedPattern">
        <div class="col-12">
          <label class="block text-sm font-medium mb-1">ID</label>
          <code class="text-sm">{{ selectedPattern.id }}</code>
        </div>
        <div class="col-12 md:col-6">
          <label class="block text-sm font-medium mb-1">Nombre</label>
          <p>{{ selectedPattern.name }}</p>
        </div>
        <div class="col-12 md:col-6">
          <label class="block text-sm font-medium mb-1">Nombre para mostrar</label>
          <p>{{ selectedPattern.displayName }}</p>
        </div>
        <div class="col-12 md:col-6">
          <label class="block text-sm font-medium mb-1">Tipo</label>
          <p-tag [value]="getPatternTypeLabel(selectedPattern.patternType)" [severity]="getPatternTypeSeverity(selectedPattern.patternType)"></p-tag>
        </div>
        <div class="col-12 md:col-6">
          <label class="block text-sm font-medium mb-1">Orden</label>
          <p>{{ selectedPattern.orderIndex }}</p>
        </div>
        <div class="col-12">
          <label class="block text-sm font-medium mb-1">Patrón</label>
          <code class="block p-2 bg-gray-100 rounded">{{ selectedPattern.pattern }}</code>
        </div>
        <div class="col-12">
          <label class="block text-sm font-medium mb-1">Reemplazo</label>
          <code class="block p-2 bg-gray-100 rounded">{{ selectedPattern.replacement || '(vacío = eliminar)' }}</code>
        </div>
        <div class="col-12 md:col-6">
          <label class="block text-sm font-medium mb-1">Estado</label>
          <p-tag [value]="selectedPattern.enabled ? 'Activo' : 'Inactivo'" [severity]="selectedPattern.enabled ? 'success' : 'danger'"></p-tag>
        </div>
        <div class="col-12 md:col-4">
          <label class="block text-sm font-medium mb-1">Flags</label>
          <div class="flex flex-wrap gap-2">
            <p-tag *ngIf="selectedPattern.caseInsensitive" value="Ignore Case" severity="info"></p-tag>
            <p-tag *ngIf="selectedPattern.multiline" value="Multiline" severity="info"></p-tag>
            <p-tag *ngIf="selectedPattern.dotAll" value="Dot All" severity="info"></p-tag>
          </div>
        </div>
        <div class="col-12">
          <label class="block text-sm font-medium mb-1">Descripción</label>
          <p>{{ selectedPattern.description || 'Sin descripción' }}</p>
        </div>
        <div class="col-12 md:col-6">
          <label class="block text-sm font-medium mb-1">Creado</label>
          <p>{{ selectedPattern.createdAt | date:'dd/MM/yyyy HH:mm' }}</p>
        </div>
        <div class="col-12 md:col-6">
          <label class="block text-sm font-medium mb-1">Actualizado</label>
          <p>{{ selectedPattern.updatedAt | date:'dd/MM/yyyy HH:mm' }}</p>
        </div>
      </div>
      
      <ng-template pTemplate="footer">
        <p-button label="Cerrar" icon="pi pi-times" (click)="viewDialogVisible = false" styleClass="p-button-secondary"></p-button>
      </ng-template>
    </p-dialog>

    <p-toast></p-toast>
    <p-confirmDialog></p-confirmDialog>
  `,
  styles: [`
    :host ::ng-deep .p-dialog .p-dialog-content {
      padding: 1.5rem;
    }
    :host ::ng-deep .p-dialog .p-dialog-header {
      padding: 1rem 1.5rem;
    }
    :host ::ng-deep .p-datatable .p-datatable-thead > tr > th {
      padding: 0.75rem 1rem;
    }
    :host ::ng-deep .p-datatable .p-datatable-tbody > tr > td {
      padding: 0.75rem 1rem;
    }
  `]
})
export class RegexPatternTableComponent implements OnInit, OnDestroy {
  patterns: RegexPattern[] = [];
  loading = false;
  saving = false;
  validating = false;
  validationResult: boolean | undefined;
  validationMessage = '';

  dialogVisible = false;
  viewDialogVisible = false;
  dialogHeader = '';
  editing = false;
  selectedPattern: RegexPattern | null = null;

  private destroying$ = new Subject<void>();

  patternTypes = [
    { label: 'URL', value: 'URL' },
    { label: 'Redes Sociales', value: 'SOCIAL_MEDIA' },
    { label: 'ISBN', value: 'ISBN' },
    { label: 'Número de Página', value: 'PAGE_NUMBER' },
    { label: 'Espacios en Blanco', value: 'WHITESPACE' },
    { label: 'Personalizado', value: 'CUSTOM' }
  ];

  form: FormGroup;

  constructor(
    private fb: FormBuilder,
    private regexPatternService: RegexPatternService,
    private messageService: MessageService,
    private confirmationService: ConfirmationService
  ) {
    this.form = this.createForm();
  }

  ngOnInit() {
    this.loadPatterns();
  }

  ngOnDestroy() {
    this.destroying$.next();
    this.destroying$.complete();
  }

  createForm(): FormGroup {
    return this.fb.group({
      id: [null],
      name: ['', Validators.required],
      displayName: ['', Validators.required],
      description: [''],
      pattern: ['', Validators.required],
      replacement: [''],
      patternType: ['CUSTOM', Validators.required],
      enabled: [true],
      caseInsensitive: [false],
      multiline: [false],
      dotAll: [false],
      orderIndex: [0, [Validators.required, Validators.min(0)]]
    });
  }

  loadPatterns() {
    this.loading = true;
    this.regexPatternService.getEnabled().pipe(takeUntil(this.destroying$)).subscribe({
      next: (patterns) => {
        this.patterns = patterns;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.messageService.add({ severity: 'error', summary: 'Error', detail: 'No se pudieron cargar los patrones' });
      }
    });
  }

  onGlobalFilter(event: Event) {
    const input = event.target as HTMLInputElement;
    // Table filtering is handled by p-table's globalFilterFields
  }

  openNew() {
    this.editing = false;
    this.dialogHeader = 'Nuevo Patrón de Limpieza';
    this.form.reset({
      name: '',
      displayName: '',
      description: '',
      pattern: '',
      replacement: '',
      patternType: 'CUSTOM',
      enabled: true,
      caseInsensitive: false,
      multiline: false,
      dotAll: false,
      orderIndex: this.patterns.length
    });
    this.validationResult = undefined;
    this.validationMessage = '';
    this.dialogVisible = true;
  }

  openEdit(pattern: RegexPattern) {
    this.editing = true;
    this.dialogHeader = 'Editar Patrón';
    this.form.patchValue({
      id: pattern.id,
      name: pattern.name,
      displayName: pattern.displayName,
      description: pattern.description || '',
      pattern: pattern.pattern,
      replacement: pattern.replacement || '',
      patternType: pattern.patternType,
      enabled: pattern.enabled,
      caseInsensitive: pattern.caseInsensitive,
      multiline: pattern.multiline,
      dotAll: pattern.dotAll,
      orderIndex: pattern.orderIndex
    });
    this.validationResult = undefined;
    this.validationMessage = '';
    this.dialogVisible = true;
  }

  openView(pattern: RegexPattern) {
    this.selectedPattern = pattern;
    this.viewDialogVisible = true;
  }

  closeDialog() {
    this.dialogVisible = false;
    this.form.reset();
    this.validationResult = undefined;
    this.validationMessage = '';
  }

  onDialogHide() {
    this.form.reset();
    this.validationResult = undefined;
    this.validationMessage = '';
  }

  validatePattern() {
    const pattern = this.form.get('pattern')?.value;
    if (!pattern) return;

    this.validating = true;
    this.regexPatternService.validate(pattern).pipe(takeUntil(this.destroying$)).subscribe({
      next: (result) => {
        this.validating = false;
        this.validationResult = result.valid;
        this.validationMessage = result.message;
        this.messageService.add({
          severity: result.valid ? 'success' : 'error',
          summary: result.valid ? 'Patrón válido' : 'Patrón inválido',
          detail: result.message
        });
      },
      error: () => {
        this.validating = false;
      }
    });
  }

  savePattern() {
    if (this.form.invalid) {
      Object.keys(this.form.controls).forEach(key => {
        this.form.get(key)?.markAsTouched();
      });
      this.messageService.add({ severity: 'error', summary: 'Error', detail: 'Hay campos inválidos en el formulario' });
      return;
    }

    this.saving = true;
    const patternData = this.form.value;

    if (this.editing && patternData.id) {
      this.regexPatternService.update(patternData.id, patternData).pipe(takeUntil(this.destroying$)).subscribe({
        next: () => {
          this.saving = false;
          this.messageService.add({ severity: 'success', summary: 'Éxito', detail: 'Patrón actualizado' });
          this.loadPatterns();
          this.closeDialog();
        },
        error: () => {
          this.saving = false;
          this.messageService.add({ severity: 'error', summary: 'Error', detail: 'No se pudo actualizar el patrón' });
        }
      });
    } else {
      this.regexPatternService.create(patternData).pipe(takeUntil(this.destroying$)).subscribe({
        next: () => {
          this.saving = false;
          this.messageService.add({ severity: 'success', summary: 'Éxito', detail: 'Patrón creado' });
          this.loadPatterns();
          this.closeDialog();
        },
        error: () => {
          this.saving = false;
          this.messageService.add({ severity: 'error', summary: 'Error', detail: 'No se pudo crear el patrón' });
        }
      });
    }
  }

  confirmDelete(pattern: RegexPattern) {
    this.confirmationService.confirm({
      message: `¿Estás seguro de eliminar el patrón "${pattern.displayName}"?`,
      header: 'Confirmar eliminación',
      icon: 'pi pi-exclamation-triangle',
      accept: () => {
        this.regexPatternService.delete(pattern.id!).pipe(takeUntil(this.destroying$)).subscribe({
          next: () => {
            this.messageService.add({ severity: 'success', summary: 'Éxito', detail: 'Patrón eliminado' });
            this.loadPatterns();
          },
          error: () => this.messageService.add({ severity: 'error', summary: 'Error', detail: 'No se pudo eliminar' })
        });
      }
    });
  }

  getPatternTypeLabel(type: string): string {
    const found = this.patternTypes.find(t => t.value === type);
    return found ? found.label : type;
  }

  getPatternTypeSeverity(type: string): 'success' | 'info' | 'warn' | 'danger' {
    const severityMap: Record<string, 'success' | 'info' | 'warn' | 'danger'> = {
      'URL': 'info',
      'SOCIAL_MEDIA': 'success',
      'ISBN': 'warn',
      'PAGE_NUMBER': 'info',
      'WHITESPACE': 'success',
      'CUSTOM': 'info'
    };
    return severityMap[type] || 'info';
  }
}