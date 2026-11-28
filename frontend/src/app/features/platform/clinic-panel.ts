import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { TranslocoPipe } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { reactivate } from '../../api/fn/clinic-administration-controller/reactivate';
import { suspend } from '../../api/fn/clinic-administration-controller/suspend';
import { PlatformClinicDetails } from '../../api/models/platform-clinic-details';
import { confirm } from '../../shared/dialogs/confirm-dialog';
import { FailureMessage } from '../../shared/forms/failure-message';
import { SaveFailure, toActionFailure } from '../../shared/forms/save-failure';
import { FirstAdmin } from './first-admin';
import { FiscalDataForm } from './fiscal-data-form';

type Notice = 'suspended' | 'reactivated';

/**
 * One clinic for PLATFORM_ADMIN: its status, with suspension (asked first) and reactivation, the
 * correction of its fiscal data, and the invitation of its first administrator.
 */
@Component({
  selector: 'app-clinic-panel',
  imports: [FailureMessage, FirstAdmin, FiscalDataForm, MatButtonModule, TranslocoPipe],
  template: `
    <h3 #heading tabindex="-1">{{ clinic().name }}</h3>
    <div class="clinic-panel__status">
      <p>
        {{
          'platform.panel.status'
            | transloco: { status: ('platform.status.' + clinic().status | transloco) }
        }}
      </p>
      @if (clinic().status === 'SUSPENDED') {
        <button mat-flat-button type="button" [disabled]="busy()" (click)="reactivateClinic()">
          {{ (busy() ? 'platform.panel.reactivating' : 'platform.panel.reactivate') | transloco }}
        </button>
      } @else {
        <button mat-stroked-button type="button" [disabled]="busy()" (click)="suspendClinic()">
          {{ (busy() ? 'platform.panel.suspending' : 'platform.panel.suspend') | transloco }}
        </button>
      }
    </div>
    <app-failure-message [failure]="failure()" />
    <p class="platform-notice" role="status">
      @if (notice(); as notice) {
        {{ 'platform.panel.' + notice | transloco }}
      }
    </p>

    <section class="clinic-panel__part">
      <app-fiscal-data-form [clinic]="clinic()" (changed)="changed.emit($event)" />
    </section>
    <section class="clinic-panel__part">
      <app-first-admin [clinicId]="clinic().id ?? ''" />
    </section>
  `,
  styles: `
    .clinic-panel__status {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16px;
    }
    .clinic-panel__part {
      margin-block-start: 24px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClinicPanel {
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

  readonly clinic = input.required<PlatformClinicDetails>();
  /** The clinic after a change made here: status, fiscal data and version. */
  readonly changed = output<PlatformClinicDetails>();

  protected readonly busy = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly notice = signal<Notice | null>(null);

  /** Takes the focus to the panel, when it opens for a clinic. */
  focus(): void {
    this.heading().nativeElement.focus();
  }

  protected async suspendClinic(): Promise<void> {
    this.notice.set(null);
    const confirmed = await confirm(this.dialog, {
      titleKey: 'platform.panel.suspendConfirm.title',
      messageKey: 'platform.panel.suspendConfirm.message',
      messageParams: { name: this.clinic().name },
      confirmKey: 'platform.panel.suspendConfirm.confirm',
    });
    if (confirmed) {
      await this.act(suspend, 'suspended');
    }
  }

  protected reactivateClinic(): Promise<void> {
    return this.act(reactivate, 'reactivated');
  }

  private async act(action: typeof suspend | typeof reactivate, notice: Notice): Promise<void> {
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    try {
      this.changed.emit(await this.api.invoke(action, { id: this.clinic().id ?? '' }));
      this.notice.set(notice);
    } catch (error) {
      this.failure.set(toActionFailure(error));
    } finally {
      this.busy.set(false);
    }
  }
}
