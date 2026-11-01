import { Component } from '@angular/core';
import { SkeletonModule } from 'primeng/skeleton';
import { CardModule } from 'primeng/card';

@Component({
  selector: 'novel-card-skeleton',
  standalone: true,
  imports: [SkeletonModule, CardModule],
  template: `
    <p-card class="novel-card-skeleton" [style]="{'height': '100%', 'display': 'flex', 'flexDirection': 'column'}">
      <!-- Cover -->
      <p-skeleton width="100%" height="280px" styleClass="novel-cover-img-skeleton"></p-skeleton>

      <div class="p-3 novel-card-body-skeleton">
        <!-- Title -->
        <p-skeleton width="85%" height="1.25rem" styleClass="mb-3"></p-skeleton>

        <!-- Meta -->
        <div class="flex items-center gap-2 mb-3">
          <p-skeleton width="60px" height="20px" styleClass="rounded-full"></p-skeleton>
          <p-skeleton width="45px" height="20px" styleClass="rounded-full"></p-skeleton>
        </div>

        <!-- Enriched Info Skeleton -->
        <div class="border-t pt-3 mb-3">
          <!-- Score row -->
          <div class="flex flex-wrap gap-2 mb-2">
            <p-skeleton width="70px" height="24px" styleClass="rounded-full"></p-skeleton>
            <p-skeleton width="80px" height="24px" styleClass="rounded-full"></p-skeleton>
            <p-skeleton width="60px" height="24px" styleClass="rounded-full"></p-skeleton>
          </div>

          <!-- Genres row -->
          <div class="flex flex-wrap gap-1 mb-2">
            <p-skeleton width="60px" height="22px" styleClass="rounded-full"></p-skeleton>
            <p-skeleton width="70px" height="22px" styleClass="rounded-full"></p-skeleton>
            <p-skeleton width="65px" height="22px" styleClass="rounded-full"></p-skeleton>
            <p-skeleton width="40px" height="22px" styleClass="rounded-full"></p-skeleton>
          </div>

          <!-- Tags row -->
          <div class="flex flex-wrap gap-1 mb-3">
            <p-skeleton width="55px" height="22px" styleClass="rounded-full"></p-skeleton>
            <p-skeleton width="50px" height="22px" styleClass="rounded-full"></p-skeleton>
          </div>

          <!-- Description -->
          <div class="space-y-2">
            <p-skeleton width="100%" height="12px"></p-skeleton>
            <p-skeleton width="80%" height="12px"></p-skeleton>
            <p-skeleton width="60%" height="12px"></p-skeleton>
          </div>
        </div>

        <!-- Progress Section Skeleton -->
        <div class="border-t pt-3 mb-3">
          <div class="flex justify-between mb-2">
            <p-skeleton width="80px" height="14px" styleClass="rounded-full"></p-skeleton>
            <p-skeleton width="45px" height="14px" styleClass="rounded-full"></p-skeleton>
          </div>
          <p-skeleton width="100%" height="6px" styleClass="rounded-full"></p-skeleton>
        </div>

        <!-- Expand Toggle -->
        <div class="border-t pt-3">
          <p-skeleton width="140px" height="40px" styleClass="rounded-full mx-auto"></p-skeleton>
        </div>
      </div>
    </p-card>
  `,
  styles: [`
    .novel-card-skeleton {
      height: 100%;
      display: flex;
      flex-direction: column;
      border-radius: 12px;
      overflow: hidden;
      border: 1px solid #e5e7eb;
      background: #ffffff;
    }

    .novel-cover-img-skeleton {
      aspect-ratio: 2/3;
      border-radius: 12px 12px 0 0;
    }

    :host ::ng-deep .p-skeleton {
      background: linear-gradient(90deg, #f3f4f6 25%, #e5e7eb 37%, #f3f4f6 63%);
      background-size: 400% 100%;
      animation: skeleton-loading 1.4s ease infinite;
      border-radius: 4px;
    }

    @keyframes skeleton-loading {
      0% { background-position: 100% 50%; }
      100% { background-position: 0 50%; }
    }
  `]
})
export class NovelCardSkeletonComponent {}