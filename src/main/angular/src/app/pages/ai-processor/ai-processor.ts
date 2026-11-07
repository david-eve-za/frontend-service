import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AiProcessorService, AiProcessRequest, AiProcessResponse } from './ai-processor.service';

@Component({
    selector: 'app-ai-processor',
    standalone: true,
    imports: [CommonModule, FormsModule],
    template: `
        <div class="card">
            <div class="font-semibold text-xl mb-4">AI Data Processor</div>
            <p class="text-muted-color mb-6">Send text to the AI model for processing and view the results.</p>

            <!-- Input Form -->
            <div class="grid gap-4 mb-6">
                <div>
                    <label for="inputText" class="block text-sm font-medium mb-2">Input Text</label>
                    <textarea
                        id="inputText"
                        [(ngModel)]="inputText"
                        rows="6"
                        class="w-full p-3 border border-surface-300 dark:border-surface-600 rounded-lg bg-surface-0 dark:bg-surface-900 text-surface-900 dark:text-surface-0 placeholder:text-surface-400 dark:placeholder:text-surface-500 focus:ring-2 focus:ring-primary-500 focus:border-transparent resize-none"
                        placeholder="Enter text to process..."
                    ></textarea>
                </div>

                <div class="grid gap-3 sm:grid-cols-2">
                    <div>
                        <label for="modelSelect" class="block text-sm font-medium mb-2">Model</label>
                        <select
                            id="modelSelect"
                            [(ngModel)]="selectedModel"
                            class="w-full p-3 border border-surface-300 dark:border-surface-600 rounded-lg bg-surface-0 dark:bg-surface-900 text-surface-900 dark:text-surface-0 focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                        >
                            <option value="">Auto (default)</option>
                            <option value="gemma-3-27b-it">Gemma 3 27B</option>
                            <option value="deepseek-r1-distill-llama-70b">DeepSeek R1 Distill Llama 70B</option>
                            <option value="llama-3.1-70b-instruct">Llama 3.1 70B Instruct</option>
                            <option value="nemotron-3-ultra">Nemotron 3 Ultra</option>
                        </select>
                    </div>

                    <div>
                        <label for="taskSelect" class="block text-sm font-medium mb-2">Task</label>
                        <select
                            id="taskSelect"
                            [(ngModel)]="selectedTask"
                            class="w-full p-3 border border-surface-300 dark:border-surface-600 rounded-lg bg-surface-0 dark:bg-surface-900 text-surface-900 dark:text-surface-0 focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                        >
                            <option value="summarize">Summarize & Analyze</option>
                            <option value="translate-es">Translate to Spanish</option>
                            <option value="translate-en">Translate to English</option>
                            <option value="key-points">Extract Key Points</option>
                            <option value="sentiment">Sentiment Analysis</option>
                            <option value="custom">Custom Instruction</option>
                        </select>
                    </div>
                </div>

                <div *ngIf="selectedTask === 'custom'" class="grid gap-2">
                    <label for="customInstruction" class="block text-sm font-medium">Custom Instruction</label>
                    <input
                        id="customInstruction"
                        type="text"
                        [(ngModel)]="customInstruction"
                        class="w-full p-3 border border-surface-300 dark:border-surface-600 rounded-lg bg-surface-0 dark:bg-surface-900 text-surface-900 dark:text-surface-0 focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                        placeholder="e.g., Rewrite in a more formal tone, fix grammar, etc."
                    >
                </div>

                <div class="flex gap-3">
                    <button
                        (click)="processText()"
                        [disabled]="isLoading() || !inputText.trim()"
                        class="flex-1 p-3 bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium"
                    >
                        <span *ngIf="!isLoading()">Process Text</span>
                        <span *ngIf="isLoading()" class="flex items-center justify-center gap-2">
                            <i class="pi pi-spin pi-spinner"></i>
                            Processing...
                        </span>
                    </button>

                    <button
                        (click)="clearForm()"
                        [disabled]="isLoading()"
                        class="px-6 p-3 border border-surface-300 dark:border-surface-600 text-surface-700 dark:text-surface-200 rounded-lg hover:bg-surface-100 dark:hover:bg-surface-800 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium"
                    >
                        Clear
                    </button>
                </div>
            </div>

            <!-- Error Message -->
            <div *ngIf="errorMessage()" class="mb-4 p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg text-red-700 dark:text-red-300">
                <div class="flex items-center gap-2">
                    <i class="pi pi-exclamation-triangle"></i>
                    <span>{{ errorMessage() }}</span>
                </div>
            </div>

            <!-- Result Display -->
            @if (result(); as res) {
                <div class="result-section">
                    <div class="flex items-center justify-between mb-3">
                        <h3 class="font-semibold text-lg">Result</h3>
                        <div class="flex items-center gap-2 text-sm text-surface-500 dark:text-surface-400">
                            <i class="pi pi-microchip"></i>
                            <span>Model: {{ res.modelUsed || 'unknown' }}</span>
                        </div>
                    </div>

                    <div class="bg-surface-100 dark:bg-surface-800 p-4 rounded-lg border border-surface-200 dark:border-surface-700 whitespace-pre-wrap text-surface-900 dark:text-surface-100 font-mono text-sm">
                        {{ res.processedResult }}
                    </div>

                    <div class="mt-3 flex gap-2">
                        <button
                            (click)="copyResult()"
                            class="px-4 py-2 border border-surface-300 dark:border-surface-600 text-surface-700 dark:text-surface-200 rounded-lg hover:bg-surface-100 dark:hover:bg-surface-800 transition-colors text-sm"
                        >
                            <i class="pi pi-copy mr-1"></i> Copy
                        </button>
                        <button
                            (click)="useAsInput()"
                            class="px-4 py-2 border border-surface-300 dark:border-surface-600 text-surface-700 dark:text-surface-200 rounded-lg hover:bg-surface-100 dark:hover:bg-surface-800 transition-colors text-sm"
                        >
                            <i class="pi pi-arrow-up-left mr-1"></i> Use as Input
                        </button>
                    </div>
                </div>

                <!-- Original Text Display (collapsible) -->
                <div class="mt-6">
                    <button
                        (click)="showOriginal = !showOriginal"
                        class="text-sm text-primary-500 hover:underline flex items-center gap-1"
                    >
                        <i class="pi" [ngClass]="showOriginal ? 'pi-chevron-down' : 'pi-chevron-right'"></i>
                        Show Original Text ({{ res.originalText.length }} chars)
                    </button>

                    @if (showOriginal) {
                        <div class="mt-3 bg-surface-100 dark:bg-surface-800 p-4 rounded-lg border border-surface-200 dark:border-surface-700 whitespace-pre-wrap text-surface-900 dark:text-surface-100 font-mono text-sm max-h-64 overflow-auto">
                            {{ res.originalText }}
                        </div>
                    }
                </div>
            }
        </div>
    `,
    styles: [`
        :host {
            display: block;
        }
        .result-section {
            animation: fadeIn 0.3s ease-out;
        }
        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(10px); }
            to { opacity: 1; transform: translateY(0); }
        }
    `]
})
export class AiProcessor {
    protected inputText = '';
    protected selectedModel = '';
    protected selectedTask = 'summarize';
    protected customInstruction = '';
    protected result = signal<AiProcessResponse | null>(null);
    protected isLoading = signal(false);
    protected errorMessage = signal<string | null>(null);
    protected showOriginal = false;

    constructor(private aiProcessorService: AiProcessorService) {}

    processText(): void {
        if (!this.inputText.trim()) return;

        this.isLoading.set(true);
        this.errorMessage.set(null);
        this.result.set(null);

        // Build parameters based on selections
        const parameters: Record<string, string> = {};

        if (this.selectedModel) {
            parameters['model'] = this.selectedModel;
        }

        switch (this.selectedTask) {
            case 'summarize':
                parameters['task'] = 'summarize and analyze';
                break;
            case 'translate-es':
                parameters['task'] = 'translate to Spanish';
                parameters['language'] = 'Spanish';
                break;
            case 'translate-en':
                parameters['task'] = 'translate to English';
                parameters['language'] = 'English';
                break;
            case 'key-points':
                parameters['task'] = 'extract key points and main ideas';
                break;
            case 'sentiment':
                parameters['task'] = 'analyze sentiment and emotional tone';
                break;
            case 'custom':
                if (this.customInstruction.trim()) {
                    parameters['instruction'] = this.customInstruction.trim();
                }
                break;
        }

        const request: AiProcessRequest = {
            text: this.inputText,
            parameters
        };

        this.aiProcessorService.processData(request).subscribe({
            next: (response) => {
                this.result.set(response);
                this.isLoading.set(false);
            },
            error: (error) => {
                console.error('AI Processing error:', error);
                this.errorMessage.set(
                    error?.error?.message ||
                    error?.message ||
                    'Failed to process text. Please try again.'
                );
                this.isLoading.set(false);
            }
        });
    }

    clearForm(): void {
        this.inputText = '';
        this.result.set(null);
        this.errorMessage.set(null);
        this.showOriginal = false;
    }

    copyResult(): void {
        if (this.result()) {
            navigator.clipboard.writeText(this.result()!.processedResult).then(() => {
                // Could add a toast notification here
                console.log('Result copied to clipboard');
            });
        }
    }

    useAsInput(): void {
        if (this.result()) {
            this.inputText = this.result()!.processedResult;
            this.result.set(null);
            this.showOriginal = false;
        }
    }
}