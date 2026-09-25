import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, FormArray, FormControl, Validators } from '@angular/forms';
import { Subject, takeUntil, debounceTime, distinctUntilChanged } from 'rxjs';
import { RegexPatternService, RegexPattern } from './regex-pattern.service';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { CheckboxModule } from 'primeng/checkbox';
import { SelectModule } from 'primeng/select';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ToolbarModule } from 'primeng/toolbar';
import { DividerModule } from 'primeng/divider';
import { FieldsetModule } from 'primeng/fieldset';

@Component({
  selector: 'app-regex-pattern-form',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    CardModule,
    InputTextModule,
    TextareaModule,
    CheckboxModule,
    SelectModule,
    ButtonModule,
    InputNumberModule,
    TagModule,
    ToastModule,
    ConfirmDialogModule,
    ToolbarModule,
    DividerModule,
    FieldsetModule
  ],
  providers: [ConfirmationService, MessageService],
  template: `
    <p-toast></p-toast>
    <p-confirmDialog></p-confirmDialog>

    <div class="card">
      <div class="flex justify-content-between align-items-center mb-4">
        <div class="font-semibold text-xl">Gestión de Expresiones Regulares</div>
        <p-button label="Nuevo Patrón" icon="pi pi-plus" (click)="addPattern()" styleClass="p-button-primary"></p-button>
      </div>

      <p-fieldset legend="Patrones de Limpieza de Texto" [toggleable]="true" *ngIf="patterns.length > 0">
        <form [formGroup]="form" (ngSubmit)="saveAll()">
          <div formArrayName="patterns" class="grid">
            <div *ngFor="let pattern of patterns.controls; let i = index" class="col-12 md:col-6 lg:col-4" [formGroupName]="i">
              <p-card [style]="{ marginBottom: '1rem' }">
                <ng-template pTemplate="header">
                  <div class="flex justify-content-between align-items-center">
                    <span class="font-medium">{{ getPatternDisplayName(i) }}</span>
                    <div class="flex gap-2">
                      <p-tag [value]="getPatternTypeLabel(patterns.controls[i].get('patternType')?.value)"
                             [severity]="getPatternTypeSeverity(patterns.controls[i].get('patternType')?.value)"></p-tag>
                      <p-button icon="pi pi-trash" severity="danger" text (click)="removePattern(i)" pTooltip="Eliminar"></p-button>
                    </div>
                  </div>
                </ng-template>

                <div class="grid">
                  <div class="col-12">
                    <label class="block text-sm font-medium mb-1">Nombre *</label>
                    <input pInputText formControlName="name" placeholder="Identificador único (ej: url-cleaner)" />
                    <small class="p-error" *ngIf="getControl(i, 'name').invalid && getControl(i, 'name').touched">
                      El nombre es requerido
                    </small>
                  </div>

                  <div class="col-12">
                    <label class="block text-sm font-medium mb-1">Nombre para mostrar *</label>
                    <input pInputText formControlName="displayName" placeholder="Nombre visible en la UI (ej: Limpiador de URLs)" />
                    <small class="p-error" *ngIf="getControl(i, 'displayName').invalid && getControl(i, 'displayName').touched">
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
                    <input pInputText formControlName="pattern" placeholder="Expresión regular (ej: https?://\\S+)" style="font-family: monospace;" />
                    <small class="p-error" *ngIf="getControl(i, 'pattern').invalid && getControl(i, 'pattern').touched">
                      El patrón es requerido
                    </small>
                    <div class="flex gap-2 mt-2">
                      <p-button label="Validar" icon="pi pi-check" (click)="validatePattern(i)" [loading]="validating[i]" styleClass="p-button-text"></p-button>
                      <span class="flex align-items-center" *ngIf="validationResult[i] !== undefined">
                        <p-tag [value]="validationResult[i] ? 'Válido' : 'Inválido'" [severity]="validationResult[i] ? 'success' : 'danger'"></p-tag>
                        <span class="ml-2 text-sm text-gray-500" *ngIf="!validationResult[i]">{{ validationMessage[i] }}</span>
                      </span>
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

                  <div class="col-12 md:col-6">
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

                <p-divider></p-divider>

                <div class="flex justify-content-end gap-2">
                  <p-button label="Mover ↑" icon="pi pi-chevron-up" (click)="moveUp(i)" [disabled]="i === 0" styleClass="p-button-text p-button-sm"></p-button>
                  <p-button label="Mover ↓" icon="pi pi-chevron-down" (click)="moveDown(i)" [disabled]="i === patterns.length - 1" styleClass="p-button-text p-button-sm"></p-button>
                  <p-button label="Duplicar" icon="pi pi-copy" (click)="duplicatePattern(i)" styleClass="p-button-text p-button-sm"></p-button>
                </div>
              </p-card>
            </div>
          </div>

          <div class="flex justify-content-end gap-2 mt-4">
            <p-button label="Cancelar" icon="pi pi-times" (click)="resetForm()" styleClass="p-button-secondary"></p-button>
            <p-button label="Guardar Todo" icon="pi pi-save" type="submit" [loading]="saving" styleClass="p-button-primary"></p-button>
          </div>
        </form>
      </p-fieldset>

      <div class="text-center py-8" *ngIf="patterns.length === 0">
        <i class="pi pi-info-circle text-4xl text-gray-400"></i>
        <p class="mt-2 text-gray-500">No hay patrones configurados. Haz clic en "Nuevo Patrón" para agregar uno.</p>
        <p-button label="Nuevo Patrón" icon="pi pi-plus" (click)="addPattern()" styleClass="p-button-primary mt-2"></p-button>
      </div>
    </div>
  `,
  styles: [`
    :host ::ng-deep .p-card {
      box-shadow: 0 2px 8px rgba(0,0,0,0.08);
      border-radius: 8px;
    }
    :host ::ng-deep .p-fieldset .p-fieldset-legend {
      font-weight: 600;
    }
  `]
})
export class RegexPatternFormComponent implements OnInit, OnDestroy {
  form: FormGroup;
  destroying$ = new Subject<void>();
  saving = false;
  validating: boolean[] = [];
  validationResult: (boolean | undefined)[] = [];
  validationMessage: string[] = [];

  patternTypes = [
    { label: 'URL', value: 'URL' },
    { label: 'Redes Sociales', value: 'SOCIAL_MEDIA' },
    { label: 'ISBN', value: 'ISBN' },
    { label: 'Número de Página', value: 'PAGE_NUMBER' },
    { label: 'Espacios en Blanco', value: 'WHITESPACE' },
    { label: 'Personalizado', value: 'CUSTOM' }
  ];

  constructor(
    private fb: FormBuilder,
    private regexPatternService: RegexPatternService,
    private confirmationService: ConfirmationService,
    private messageService: MessageService,
    private cdr: ChangeDetectorRef
  ) {
    this.form = this.fb.group({
      patterns: this.fb.array([])
    });
  }

  get patterns(): FormArray {
    return this.form.get('patterns') as FormArray;
  }

  ngOnInit() {
    this.loadPatterns();
  }

  ngOnDestroy() {
    this.destroying$.next();
    this.destroying$.complete();
  }

  loadPatterns() {
    this.regexPatternService.getEnabled().pipe(takeUntil(this.destroying$)).subscribe({
      next: (patterns) => {
        this.patterns.clear();
        patterns.forEach(p => this.patterns.push(this.createPatternGroup(p)));
        this.validating = new Array(this.patterns.length).fill(false);
        this.validationResult = new Array(this.patterns.length).fill(undefined);
        this.validationMessage = new Array(this.patterns.length).fill('');
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.messageService.add({ severity: 'error', summary: 'Error', detail: 'No se pudieron cargar los patrones' });
      }
    });
  }

  createPatternGroup(pattern?: RegexPattern): FormGroup {
    return this.fb.group({
      id: [pattern?.id || null],
      name: [pattern?.name || '', Validators.required],
      displayName: [pattern?.displayName || '', Validators.required],
      description: [pattern?.description || ''],
      pattern: [pattern?.pattern || '', Validators.required],
      replacement: [pattern?.replacement || ''],
      patternType: [pattern?.patternType || 'CUSTOM', Validators.required],
      enabled: [pattern?.enabled !== false],
      caseInsensitive: [pattern?.caseInsensitive || false],
      multiline: [pattern?.multiline || false],
      dotAll: [pattern?.dotAll || false],
      orderIndex: [pattern?.orderIndex || 0, [Validators.required, Validators.min(0)]]
    });
  }

  getControl(index: number, controlName: string): FormControl {
    return this.patterns.at(index).get(controlName) as FormControl;
  }

  getPatternDisplayName(index: number): string {
    const displayName = this.patterns.at(index).get('displayName')?.value;
    const patternType = this.patterns.at(index).get('patternType')?.value;
    return displayName || patternType || `Patrón ${index + 1}`;
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

  addPattern() {
    this.patterns.push(this.createPatternGroup({
      name: '',
      displayName: '',
      pattern: '',
      patternType: 'CUSTOM',
      enabled: true,
      caseInsensitive: false,
      multiline: false,
      dotAll: false,
      orderIndex: this.patterns.length
    }));
    this.validating.push(false);
    this.validationResult.push(undefined);
    this.validationMessage.push('');
  }

  removePattern(index: number) {
    this.confirmationService.confirm({
      message: '¿Estás seguro de eliminar este patrón?',
      header: 'Confirmar eliminación',
      icon: 'pi pi-exclamation-triangle',
      accept: () => {
        const pattern = this.patterns.at(index);
        if (pattern.get('id')?.value) {
          this.regexPatternService.delete(pattern.get('id')?.value).subscribe({
            next: () => {
              this.patterns.removeAt(index);
              this.validating.splice(index, 1);
              this.validationResult.splice(index, 1);
              this.validationMessage.splice(index, 1);
              this.messageService.add({ severity: 'success', summary: 'Éxito', detail: 'Patrón eliminado' });
              this.cdr.markForCheck();
            },
            error: () => this.messageService.add({ severity: 'error', summary: 'Error', detail: 'No se pudo eliminar' })
          });
        } else {
          this.patterns.removeAt(index);
          this.validating.splice(index, 1);
          this.validationResult.splice(index, 1);
          this.validationMessage.splice(index, 1);
        }
      }
    });
  }

  duplicatePattern(index: number) {
    const source = this.patterns.at(index);
    const copy = this.createPatternGroup({
      ...source.value,
      id: undefined,
      name: `${source.get('name')?.value}_copy`,
      displayName: `${source.get('displayName')?.value} (Copia)`,
      orderIndex: this.patterns.length
    });
    this.patterns.push(copy);
    this.validating.push(false);
    this.validationResult.push(undefined);
    this.validationMessage.push('');
  }

  moveUp(index: number) {
    if (index > 0) {
      const temp = this.patterns.at(index);
      this.patterns.setControl(index, this.patterns.at(index - 1));
      this.patterns.setControl(index - 1, temp);
      [this.validating[index], this.validating[index - 1]] = [this.validating[index - 1], this.validating[index]];
      [this.validationResult[index], this.validationResult[index - 1]] = [this.validationResult[index - 1], this.validationResult[index]];
      [this.validationMessage[index], this.validationMessage[index - 1]] = [this.validationMessage[index - 1], this.validationMessage[index]];
    }
  }

  moveDown(index: number) {
    if (index < this.patterns.length - 1) {
      const temp = this.patterns.at(index);
      this.patterns.setControl(index, this.patterns.at(index + 1));
      this.patterns.setControl(index + 1, temp);
      [this.validating[index], this.validating[index + 1]] = [this.validating[index + 1], this.validating[index]];
      [this.validationResult[index], this.validationResult[index + 1]] = [this.validationResult[index + 1], this.validationResult[index]];
      [this.validationMessage[index], this.validationMessage[index + 1]] = [this.validationMessage[index + 1], this.validationMessage[index]];
    }
  }

  validatePattern(index: number) {
    const pattern = this.getControl(index, 'pattern').value;
    if (!pattern) return;

    this.validating[index] = true;
    this.regexPatternService.validate(pattern).pipe(takeUntil(this.destroying$)).subscribe({
      next: (result) => {
        this.validating[index] = false;
        this.validationResult[index] = result.valid;
        this.validationMessage[index] = result.message;
        this.messageService.add({
          severity: result.valid ? 'success' : 'error',
          summary: result.valid ? 'Patrón válido' : 'Patrón inválido',
          detail: result.message
        });
        this.cdr.markForCheck();
      },
      error: () => {
        this.validating[index] = false;
        this.cdr.markForCheck();
      }
    });
  }

  saveAll() {
    if (this.form.invalid) {
      this.markAllAsTouched();
      this.messageService.add({ severity: 'error', summary: 'Error', detail: 'Hay campos inválidos en el formulario' });
      return;
    }

    this.saving = true;
    const patterns = this.patterns.value;

    // Save each pattern sequentially
    const saveNext = (index: number) => {
      if (index >= patterns.length) {
        this.saving = false;
        this.messageService.add({ severity: 'success', summary: 'Éxito', detail: 'Todos los patrones guardados' });
        this.loadPatterns();
        this.cdr.markForCheck();
        return;
      }

      const pattern = patterns[index];
      const id = pattern.id;

      if (id) {
        this.regexPatternService.update(id, pattern).subscribe({
          next: () => saveNext(index + 1),
          error: () => {
            this.saving = false;
            this.messageService.add({ severity: 'error', summary: 'Error', detail: `Error guardando ${pattern.displayName}` });
            this.cdr.markForCheck();
          }
        });
      } else {
        this.regexPatternService.create(pattern).subscribe({
          next: () => saveNext(index + 1),
          error: () => {
            this.saving = false;
            this.messageService.add({ severity: 'error', summary: 'Error', detail: `Error creando ${pattern.displayName}` });
            this.cdr.markForCheck();
          }
        });
      }
    };

    saveNext(0);
  }

  resetForm() {
    this.form.reset();
    this.patterns.clear();
    this.loadPatterns();
  }

  private markAllAsTouched() {
    this.patterns.controls.forEach(group => {
      const formGroup = group as FormGroup;
      Object.keys(formGroup.controls).forEach(key => formGroup.get(key)?.markAsTouched());
    });
  }
}