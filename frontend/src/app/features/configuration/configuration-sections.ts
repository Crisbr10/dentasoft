import { Injectable, computed, inject, signal } from '@angular/core';

import { Api } from '../../api/api';
import { clinicSections } from '../../api/fn/configuration-sections-controller/clinic-sections';
import { platformSections } from '../../api/fn/configuration-sections-controller/platform-sections';
import { VisibleSection } from '../../api/models/visible-section';
import { Session } from '../../core/auth/session';
import { ActiveClinic } from '../../core/clinic/active-clinic';

export type SectionId = NonNullable<VisibleSection['section']>;
export type SectionAccess = NonNullable<VisibleSection['access']>;

/** A section of the configuration area: its route below /configuracion, its label and icon. */
export interface SectionEntry {
  readonly id: SectionId;
  readonly path: string;
  readonly labelKey: string;
  readonly icon: string;
}

/** A section the user sees, and whether they edit it or only see it. */
export interface VisibleEntry extends SectionEntry {
  readonly access: SectionAccess;
}

/**
 * Every section the frontend knows, in menu order. Which ones the user sees comes from the
 * backend (docs/plan/configuracion.md); a section added there also needs its entry and route here.
 */
export const SECTIONS: readonly SectionEntry[] = [
  { id: 'CLINIC', path: 'clinica', labelKey: 'configuration.sections.CLINIC', icon: 'business' },
  {
    id: 'USERS_AND_ROLES',
    path: 'usuarios',
    labelKey: 'configuration.sections.USERS_AND_ROLES',
    icon: 'group',
  },
  {
    id: 'PRACTITIONERS',
    path: 'profesionales',
    labelKey: 'configuration.sections.PRACTITIONERS',
    icon: 'person',
  },
  {
    id: 'PLATFORM',
    path: 'plataforma',
    labelKey: 'configuration.sections.PLATFORM',
    icon: 'settings',
  },
];

/**
 * The configuration sections the user sees (M02-T09): by their role in the active clinic and,
 * for PLATFORM_ADMIN, the platform ones. Read again when the active clinic changes. Only for the
 * menu and the guards: every endpoint behind a section checks the role itself.
 */
@Injectable({ providedIn: 'root' })
export class ConfigurationSections {
  private readonly api = inject(Api);
  private readonly clinic = inject(ActiveClinic);
  private readonly session = inject(Session);

  /** The sections read for one active clinic (null: none). */
  private readonly state = signal<{
    clinicId: string | null;
    sections: readonly VisibleSection[];
  } | null>(null);
  private loading: { clinicId: string | null; promise: Promise<void> } | null = null;

  /** The last read failed; the index page offers to try again. */
  readonly failed = signal(false);

  /** The sections the user sees, in menu order. */
  readonly visible = computed<readonly VisibleEntry[]>(() => {
    const sections = this.state()?.sections ?? [];
    return SECTIONS.flatMap((entry) => {
      const access = sections.find((section) => section.section === entry.id)?.access;
      return access ? [{ ...entry, access }] : [];
    });
  });

  /** Reads the sections of the active clinic unless they are already read; calls share one read. */
  ensureLoaded(): Promise<void> {
    const clinicId = this.clinic.id();
    if (this.state()?.clinicId === clinicId && !this.failed()) {
      return Promise.resolve();
    }
    if (this.loading?.clinicId !== clinicId) {
      const promise = this.load(clinicId).finally(() => {
        if (this.loading?.promise === promise) {
          this.loading = null;
        }
      });
      this.loading = { clinicId, promise };
    }
    return this.loading.promise;
  }

  /** What the user may do in a section; null when they do not see it. */
  accessTo(id: SectionId): SectionAccess | null {
    return this.visible().find((entry) => entry.id === id)?.access ?? null;
  }

  private async load(clinicId: string | null): Promise<void> {
    try {
      const platformAdmin = await this.session.checkPlatformAdmin();
      const [clinic, platform] = await Promise.all([
        clinicId ? this.api.invoke(clinicSections) : Promise.resolve([]),
        platformAdmin ? this.api.invoke(platformSections) : Promise.resolve([]),
      ]);
      this.state.set({ clinicId, sections: [...clinic, ...platform] });
      this.failed.set(false);
    } catch (error) {
      this.failed.set(true);
      throw error;
    }
  }
}
