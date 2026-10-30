import { Component } from '@angular/core';
import { SkeletonModule } from 'primeng/skeleton';
import { CardModule } from 'primeng/card';

@Component({
  selector: 'novel-card-skeleton',
  standalone: true,
  imports: [SkeletonModule, CardModule],
  template: `
    <p-card [style]="{'height': '100%', 'display': 'flex', 'flexDirection': 'column'}">
      <p-skeleton width="100%" height="280px" styleClass="novel-cover-img"></p-skeleton>
      <div class="p-3 novel-card-body">
        <p-skeleton width="80%" height="1.25rem" styleClass="mb-2"></p-skeleton>
        <p-skeleton width="40%" height="0.875rem" styleClass="mb-2"></p-skeleton>
        <p-skeleton width="60%" height="0.875rem" styleClass="mb-2"></p-skeleton>
        <p-skeleton width="100%" height="0.875rem" styleClass="mb-2"></p-skeleton>
        <p-skeleton width="100%" height="0.875rem" styleClass="mb-2"></p-skeleton>
        <p-skeleton width="100%" height="0.875rem" styleClass="mb-2"></p-skeleton>
        <p-skeleton width="100%" height="0.875rem" styleClass="mb-4"></p-skeleton>
        <div class="flex flex-wrap gap-2 pt-2 border-t mt-auto">
          <p-skeleton width="100px" height="32px"></p-skeleton>
          <p-skeleton width="32px" height="32px" styleClass="ml-auto"></p-skeleton>
        </div>
      </div>
    </p-card>
  `
})
export class NovelCardSkeletonComponent {}