import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatListModule } from '@angular/material/list';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { PLATFORM_ROUTE, withoutClinics } from '../../core/auth/guards';
import { Session } from '../../core/auth/session';
import { ActiveClinic } from '../../core/clinic/active-clinic';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * Chooses the clinic to work in: suspended clinics are shown but cannot be chosen, and pending
 * invitations can be accepted here. Choosing another clinic starts again from the home page,
 * so every page reads the data of the new clinic.
 */
@Component({
  selector: 'app-clinic-selector',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatListModule,
    MatProgressSpinnerModule,
    TranslocoPipe,
  ],
  templateUrl: './clinic-selector.html',
  styleUrl: './clinic-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClinicSelector implements OnInit {
  protected readonly clinic = inject(ActiveClinic);
  private readonly session = inject(Session);
  private readonly router = inject(Router);

  protected readonly state = signal<LoadState>('loading');
  /** The invitation being accepted, and the one that could not be. */
  protected readonly accepting = signal<string | null>(null);
  protected readonly acceptFailed = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.state.set('loading');
    try {
      await this.clinic.load();
    } catch {
      this.state.set('error');
      return;
    }
    if (withoutClinics(this.clinic) && (await this.session.checkPlatformAdmin())) {
      await this.router.navigateByUrl(PLATFORM_ROUTE);
      return;
    }
    this.state.set('ready');
  }

  protected async choose(clinicId: string | undefined): Promise<void> {
    if (clinicId && this.clinic.select(clinicId)) {
      await this.router.navigateByUrl('/');
    }
  }

  protected async accept(invitationId: string | undefined): Promise<void> {
    if (!invitationId) {
      return;
    }
    this.accepting.set(invitationId);
    this.acceptFailed.set(null);
    try {
      await this.clinic.accept(invitationId);
    } catch {
      this.acceptFailed.set(invitationId);
    } finally {
      this.accepting.set(null);
    }
  }
}
