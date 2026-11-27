import { Injectable, computed, inject, signal } from '@angular/core';

import { Api } from '../../api/api';
import { accept } from '../../api/fn/me-controller/accept';
import { me } from '../../api/fn/me-controller/me';
import { MeResponse } from '../../api/models/me-response';
import { MyClinic } from '../../api/models/my-clinic';
import { MyInvitation } from '../../api/models/my-invitation';

/** A role inside a clinic. PLATFORM_ADMIN is not one: it is a realm role of the token. */
export type ClinicRole = NonNullable<MyClinic['role']>;

export const CLINIC_ROLES: readonly ClinicRole[] = [
  'ADMIN_CLINICA',
  'ODONTOLOGO',
  'HIGIENISTA',
  'RECEPCION',
  'FACTURACION',
];

/** Survives a page reload in the same tab; logging out clears it with the rest of sessionStorage. */
export const ACTIVE_CLINIC_KEY = 'dentasoft.activeClinicId';

/**
 * The signed-in user's clinics (GET /api/v1/me) and the one they work in. The backend checks
 * the X-Clinic-Id header against the membership on every request (ADR 0015); this store only
 * decides which clinic the app asks for.
 */
@Injectable({ providedIn: 'root' })
export class ActiveClinic {
  private readonly api = inject(Api);
  private readonly me = signal<MeResponse | null>(null);
  private readonly selectedId = signal<string | null>(sessionStorage.getItem(ACTIVE_CLINIC_KEY));
  private loading: Promise<void> | null = null;

  readonly loaded = computed(() => this.me() !== null);
  readonly clinics = computed<readonly MyClinic[]>(() => this.me()?.clinics ?? []);
  readonly invitations = computed<readonly MyInvitation[]>(() => this.me()?.invitations ?? []);
  /** Suspended clinics are listed, so the user knows they exist, but cannot be chosen. */
  readonly selectable = computed(() => this.clinics().filter((clinic) => !clinic.suspended));
  readonly current = computed<MyClinic | null>(
    () => this.selectable().find((clinic) => clinic.clinicId === this.selectedId()) ?? null,
  );
  readonly id = computed(() => this.current()?.clinicId ?? null);
  readonly role = computed<ClinicRole | null>(() => this.current()?.role ?? null);

  /** Reads /me once; later calls wait for the same read. */
  ensureLoaded(): Promise<void> {
    this.loading ??= this.load().catch((error: unknown) => {
      this.loading = null;
      throw error;
    });
    return this.loading;
  }

  /** Reads /me again. A user with a single clinic gets it chosen without asking. */
  async load(): Promise<void> {
    this.me.set(await this.api.invoke(me));
    const selectable = this.selectable();
    if (!this.current()) {
      this.store(selectable.length === 1 ? (selectable[0].clinicId ?? null) : null);
    }
  }

  /** Works in another clinic. False, and nothing changes, unless it is one of the user's active clinics. */
  select(clinicId: string): boolean {
    if (!this.selectable().some((clinic) => clinic.clinicId === clinicId)) {
      return false;
    }
    this.store(clinicId);
    return true;
  }

  /**
   * After a 403 from the clinic API: reads /me again and says whether the active clinic is still
   * usable. False when the membership ended or the clinic was suspended: the user chooses again.
   */
  async revalidate(): Promise<boolean> {
    const before = this.id();
    await this.load();
    return before !== null && this.id() === before;
  }

  /** Accepts an invitation (M02-T05): the clinic is the user's from the next request. */
  async accept(invitationId: string): Promise<void> {
    await this.api.invoke(accept, { id: invitationId });
    await this.load();
  }

  /** Forgets everything, as on logout: the next user of the tab starts from scratch. */
  clear(): void {
    this.me.set(null);
    this.loading = null;
    this.store(null);
  }

  private store(clinicId: string | null): void {
    this.selectedId.set(clinicId);
    if (clinicId) {
      sessionStorage.setItem(ACTIVE_CLINIC_KEY, clinicId);
    } else {
      sessionStorage.removeItem(ACTIVE_CLINIC_KEY);
    }
  }
}
