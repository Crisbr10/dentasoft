import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { activate } from '../../api/fn/practitioner-roles-controller/activate';
import { deactivate1 } from '../../api/fn/practitioner-roles-controller/deactivate-1';
import { list1 } from '../../api/fn/practitioner-roles-controller/list-1';
import { PagedModelPractitionerDetails } from '../../api/models/paged-model-practitioner-details';
import { PractitionerDetails } from '../../api/models/practitioner-details';
import { confirm } from '../../shared/dialogs/confirm-dialog';
import { FailureMessage } from '../../shared/forms/failure-message';
import { SaveFailure, toActionFailure } from '../../shared/forms/save-failure';
import { providePaginatorIntl } from '../../shared/lists/paginator-intl';
import { ConfigurationSections } from '../configuration/configuration-sections';
import { PractitionerForm, SavedPractitioner } from './practitioner-form';
import { PractitionerStatus } from './practitioner-rules';

/** Practitioners per page of the list (the backend accepts up to 50). */
export const PRACTITIONERS_PAGE_SIZE = 20;

const STATUSES: readonly PractitionerStatus[] = ['ACTIVE', 'INACTIVE'];

/** What the status region announces after a change. */
interface Notice {
  readonly key: 'created' | 'updated' | 'deactivated' | 'activated';
  readonly name: string;
}

/**
 * «Profesionales» of the configuration area (M02-T06, M02-T08): the practitioner records of the
 * active clinic, active or inactive, paged in the backend's order (by name). ADMIN_CLINICA (access
 * EDIT) adds and edits them and deactivates or activates them, with the version they saw; the other
 * roles that see the section (access VIEW) only read the list. Hiding the form and the actions is
 * only a convenience: the backend allows the changes to ADMIN_CLINICA alone.
 */
@Component({
  selector: 'app-practitioners-home',
  imports: [
    FailureMessage,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    PractitionerForm,
    TranslocoPipe,
  ],
  providers: [providePaginatorIntl()],
  templateUrl: './practitioners-home.html',
  styleUrl: './practitioners-home.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PractitionersHome implements OnInit {
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);
  private readonly sections = inject(ConfigurationSections);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly pageSize = PRACTITIONERS_PAGE_SIZE;
  protected readonly statuses = STATUSES;
  protected readonly canEdit = computed(() => this.sections.accessTo('PRACTITIONERS') === 'EDIT');

  protected readonly status = signal<PractitionerStatus>('ACTIVE');
  protected readonly page = signal<PagedModelPractitionerDetails | null>(null);
  protected readonly pageIndex = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);

  /** The record an action is running on; the other actions wait for it. */
  protected readonly busy = signal<string | null>(null);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly notice = signal<Notice | null>(null);
  /** The record shown in the form; none while the form adds a new one. */
  protected readonly editing = signal<PractitionerDetails | null>(null);

  ngOnInit(): void {
    void this.load(0);
  }

  // The backend's default order is the name; no sort is sent (see issue #160).
  protected async load(pageIndex: number): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const page = await this.api.invoke(list1, {
        status: this.status(),
        pageable: { page: pageIndex, size: PRACTITIONERS_PAGE_SIZE },
      });
      this.page.set(page);
      this.pageIndex.set(pageIndex);
    } catch {
      this.loadFailed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected filter(status: PractitionerStatus): Promise<void> {
    this.status.set(status);
    this.clearOutcome();
    return this.load(0);
  }

  protected changePage(event: PageEvent): Promise<void> {
    this.clearOutcome();
    return this.load(event.pageIndex);
  }

  protected edit(practitioner: PractitionerDetails): void {
    this.clearOutcome();
    this.editing.set(practitioner);
  }

  protected cancelEdit(): void {
    const id = this.editing()?.id ?? '';
    this.editing.set(null);
    this.focusRow(id);
  }

  protected async saved({ practitioner, created }: SavedPractitioner): Promise<void> {
    this.failure.set(null);
    const name = practitioner.displayName ?? '';
    if (created) {
      // The new record may now be on this page.
      this.notice.set({ key: 'created', name });
      await this.load(this.pageIndex());
      return;
    }
    this.replace(practitioner);
    this.editing.set(null);
    this.notice.set({ key: 'updated', name });
    this.focusRow(practitioner.id ?? '');
  }

  protected async deactivateOne(practitioner: PractitionerDetails): Promise<void> {
    this.notice.set(null);
    const confirmed = await confirm(this.dialog, {
      titleKey: 'practitioners.deactivateConfirm.title',
      messageKey: 'practitioners.deactivateConfirm.message',
      messageParams: { name: practitioner.displayName ?? '' },
      confirmKey: 'practitioners.deactivateConfirm.confirm',
    });
    if (!confirmed) {
      return;
    }
    await this.act(practitioner, 'deactivated', () =>
      this.api.invoke(deactivate1, {
        id: practitioner.id ?? '',
        body: { version: practitioner.version ?? 0 },
      }),
    );
  }

  protected activateOne(practitioner: PractitionerDetails): Promise<void> {
    return this.act(practitioner, 'activated', () =>
      this.api.invoke(activate, {
        id: practitioner.id ?? '',
        body: { version: practitioner.version ?? 0 },
      }),
    );
  }

  private async act(
    practitioner: PractitionerDetails,
    key: 'deactivated' | 'activated',
    action: () => Promise<PractitionerDetails>,
  ): Promise<void> {
    this.busy.set(practitioner.id ?? '');
    this.clearOutcome();
    try {
      const changed = await action();
      this.replace(changed);
      // Its button is gone: the focus goes to the row, instead of being lost.
      this.focusRow(changed.id ?? '');
      this.notice.set({ key, name: changed.displayName ?? '' });
    } catch (error) {
      this.failure.set(toActionFailure(error));
    } finally {
      this.busy.set(null);
    }
  }

  private clearOutcome(): void {
    this.notice.set(null);
    this.failure.set(null);
  }

  /** The record as the backend returned it after a change, with its new version. */
  private replace(practitioner: PractitionerDetails): void {
    this.page.update(
      (page) =>
        page && {
          ...page,
          content: page.content.map((row) => (row.id === practitioner.id ? practitioner : row)),
        },
    );
    if (this.editing()?.id === practitioner.id) {
      this.editing.set(practitioner);
    }
  }

  private focusRow(id: string): void {
    afterNextRender(
      () =>
        this.host.nativeElement
          .querySelector<HTMLElement>(`[data-practitioner="${id}"] > th`)
          ?.focus(),
      { injector: this.injector },
    );
  }
}
