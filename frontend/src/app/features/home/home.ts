import { httpResource } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';

import { RuntimeConfigStore } from '../../core/config/runtime-config';
import { HEALTH_PATH, HealthResponse, toHealthStatus } from './health';

@Component({
  selector: 'app-home',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressSpinnerModule, TranslocoPipe],
  templateUrl: './home.html',
  styleUrl: './home.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Home {
  private readonly apiUrl = inject(RuntimeConfigStore).config.apiUrl;

  protected readonly health = httpResource<HealthResponse>(() => `${this.apiUrl}${HEALTH_PATH}`);

  // value() throws while the resource is in an error state, so read it only when it has one.
  protected readonly status = computed(() =>
    toHealthStatus(
      this.health.isLoading(),
      this.health.hasValue() ? this.health.value() : undefined,
      this.health.error(),
    ),
  );

  protected retry(): void {
    this.health.reload();
  }
}
