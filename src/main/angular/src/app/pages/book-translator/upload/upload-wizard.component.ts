import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StepsModule } from 'primeng/steps';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { UploadStep1FilesComponent, SelectedFile } from './upload-step1-files.component';
import { UploadStep2ConfigComponent, ProcessingConfig } from './upload-step2-config.component';
import { UploadStep3ConfirmComponent } from './upload-step3-confirm.component';
import { MenuItem } from 'primeng/api';

@Component({
  selector: 'app-upload-wizard',
  standalone: true,
  imports: [CommonModule, StepsModule, ToastModule, UploadStep1FilesComponent, UploadStep2ConfigComponent, UploadStep3ConfirmComponent],
  template: `
    <p-toast></p-toast>

    <div class="card">
      <!-- Steps Indicator -->
      <p-steps [model]="items" [activeIndex]="activeStep" [readonly]="true"></p-steps>

      <!-- Step 1: Upload Files -->
      <div *ngIf="activeStep === 0" class="mt-6">
        <app-upload-step1-files
          (filesSelected)="onFilesSelected($event)"
          (continue)="onStep1Continue()">
        </app-upload-step1-files>
      </div>

      <!-- Step 2: Configure Processing -->
      <div *ngIf="activeStep === 1" class="mt-6">
        <app-upload-step2-config
          [config]="processingConfig"
          [selectedFiles]="fileList"
          (configChange)="onConfigChange($event)"
          (back)="onStep2Back()"
          (continue)="onStep2Continue()">
        </app-upload-step2-config>
      </div>

      <!-- Step 3: Confirm & Process -->
      <div *ngIf="activeStep === 2" class="mt-6">
        <app-upload-step3-confirm
          [selectedFiles]="fileList"
          [config]="processingConfig"
          (back)="onStep3Back()"
          (viewHistory)="onViewHistory()"
          (processingComplete)="onProcessingComplete($event)">
        </app-upload-step3-confirm>
      </div>
    </div>
  `,
  providers: [MessageService],
  styles: [`
    :host ::ng-deep .p-steps .p-steps-item .p-steps-number {
      width: 2.5rem;
      height: 2.5rem;
      line-height: 2.5rem;
    }
    :host ::ng-deep .p-steps .p-steps-item .p-steps-title {
      font-size: 0.875rem;
    }
  `]
})
export class UploadWizardComponent implements OnInit {
  activeStep = 0;
  selectedFiles: SelectedFile[] = [];
  processingConfig: ProcessingConfig = {
    targetLanguage: 'es',
    ttsVoice: 'Paulina',
    regexPatternIds: [],
    splitIntoChunks: true,
    generateAudio: true
  };

  get fileList(): File[] {
    return this.selectedFiles.map(f => f.file);
  }

  items: MenuItem[] = [
    { label: 'Subir Archivos', routerLink: '' },
    { label: 'Configurar', routerLink: '' },
    { label: 'Confirmar', routerLink: '' }
  ];

  constructor(private messageService: MessageService) {}

  ngOnInit() {}

  onFilesSelected(files: SelectedFile[]) {
    this.selectedFiles = files;
  }

  onStep1Continue() {
    if (this.selectedFiles.some(f => f.valid)) {
      this.activeStep = 1;
    }
  }

  onStep2Back() {
    this.activeStep = 0;
  }

  onConfigChange(config: ProcessingConfig) {
    this.processingConfig = { ...config };
  }

  onStep2Continue() {
    if (this.processingConfig.targetLanguage && this.processingConfig.ttsVoice) {
      this.activeStep = 2;
    }
  }

  onStep3Back() {
    this.activeStep = 1;
  }

  onViewHistory() {
    // Navigate to history page
    window.location.href = '/book-translator/history';
  }

  onProcessingComplete(results: any[]) {
    this.messageService.add({
      severity: 'success',
      summary: 'Completado',
      detail: `${results.length} archivo(s) procesado(s) correctamente`
    });
    // Optionally navigate to history
    setTimeout(() => {
      window.location.href = '/book-translator/history';
    }, 2000);
  }
}