import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/** Entry of the platform area for PLATFORM_ADMIN. Its clinic screens come with M02-T08. */
@Component({
  selector: 'app-platform-home',
  imports: [TranslocoPipe],
  template: `
    <h1>{{ 'platform.title' | transloco }}</h1>
    <p>{{ 'platform.intro' | transloco }}</p>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformHome {}
