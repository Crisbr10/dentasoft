import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  inject,
  input,
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
import { activate1 } from '../../api/fn/locations-controller/activate-1';
import { deactivate2 } from '../../api/fn/locations-controller/deactivate-2';
import { list2 } from '../../api/fn/locations-controller/list-2';
import { LocationDetails } from '../../api/models/location-details';
import { PagedModelLocationDetails } from '../../api/models/paged-model-location-details';
import { confirm } from '../../shared/dialogs/confirm-dialog';
import { FailureMessage } from '../../shared/forms/failure-message';
import { SaveFailure, toActionFailure } from '../../shared/forms/save-failure';
import { providePaginatorIntl } from '../../shared/lists/paginator-intl';
import { LocationStatus } from './clinic-rules';
import { LocationForm, SavedLocation } from './location-form';

/** Rooms per page of the list (the backend accepts up to 50). */
export const LOCATIONS_PAGE_SIZE = 20;

const STATUSES: readonly LocationStatus[] = ['ACTIVE', 'INACTIVE'];

/** What the status region announces after a change. */
interface Notice {
  readonly key: 'created' | 'updated' | 'deactivated' | 'activated';
  readonly name: string;
}

/**
 * The clinic's treatment rooms (gabinetes, M01-T06), active or inactive, paged in the backend's
 * order (by name). ADMIN_CLINICA (access EDIT) adds and edits them, and deactivates or activates
 * them with the version they saw: a room is never deleted. The other roles only read the list;
 * hiding the form and the actions is only a convenience, the backend allows the changes to
 * ADMIN_CLINICA alone.
 */
@Component({
  selector: 'app-clinic-locations',
  imports: [
    FailureMessage,
    LocationForm,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    TranslocoPipe,
  ],
  providers: [providePaginatorIntl()],
  template: `
    <h3 id="clinic-locations-title">{{ 'clinic.locations.title' | transloco }}</h3>
    <p class="clinic-block__hint">{{ 'clinic.locations.intro' | transloco }}</p>

    <mat-button-toggle-group
      class="clinic-locations__filter"
      [attr.aria-label]="'clinic.locations.filter.label' | transloco"
      [hideSingleSelectionIndicator]="true"
      [value]="status()"
      [disabled]="loading()"
      (change)="filter($event.value)"
    >
      @for (option of statuses; track option) {
        <mat-button-toggle [value]="option">{{
          'clinic.locations.filter.' + option | transloco
        }}</mat-button-toggle>
      }
    </mat-button-toggle-group>

    @if (loading()) {
      <p class="clinic-block__loading" role="status">
        <mat-spinner diameter="20" aria-hidden="true" />
        <span>{{ 'clinic.locations.list.loading' | transloco }}</span>
      </p>
    } @else if (loadFailed()) {
      <p role="alert">{{ 'clinic.locations.list.loadError' | transloco }}</p>
      <button mat-stroked-button type="button" (click)="load(pageIndex())">
        <mat-icon svgIcon="refresh" />
        {{ 'clinic.retry' | transloco }}
      </button>
    }

    @if (page(); as page) {
      @if (page.content.length > 0) {
        <div
          class="clinic-locations__table-wrapper"
          tabindex="0"
          role="region"
          [attr.aria-labelledby]="'clinic-locations-caption'"
        >
          <table class="clinic-locations__table">
            <caption id="clinic-locations-caption">
              {{
                'clinic.locations.list.caption' | transloco
              }}
            </caption>
            <thead>
              <tr>
                <th [attr.scope]="'col'">{{ 'clinic.locations.list.name' | transloco }}</th>
                <th [attr.scope]="'col'">{{ 'clinic.locations.list.color' | transloco }}</th>
                <th [attr.scope]="'col'">{{ 'clinic.locations.list.status' | transloco }}</th>
                @if (canEdit()) {
                  <th [attr.scope]="'col'">{{ 'clinic.locations.list.actions' | transloco }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (location of page.content; track location.id) {
                <tr [attr.data-location]="location.id">
                  <th [attr.scope]="'row'" tabindex="-1">{{ location.name }}</th>
                  <td [attr.data-label]="'clinic.locations.list.color' | transloco">
                    @if (location.color) {
                      <span class="clinic-block__color">
                        <span
                          class="clinic-block__swatch"
                          aria-hidden="true"
                          [style.background-color]="location.color"
                        ></span>
                        <span class="clinic-block__color-value">{{ location.color }}</span>
                      </span>
                    } @else {
                      {{ 'clinic.locations.list.noColor' | transloco }}
                    }
                  </td>
                  <td [attr.data-label]="'clinic.locations.list.status' | transloco">
                    <span class="clinic-locations__status" [attr.data-status]="location.status">
                      {{ 'clinic.locations.status.' + location.status | transloco }}
                    </span>
                  </td>
                  @if (canEdit()) {
                    <td>
                      <div
                        class="clinic-locations__actions"
                        role="group"
                        [attr.aria-label]="
                          'clinic.locations.actions.group' | transloco: { name: location.name }
                        "
                      >
                        <button
                          mat-stroked-button
                          type="button"
                          [disabled]="busy() !== null"
                          (click)="edit(location)"
                        >
                          {{ 'clinic.locations.actions.edit' | transloco }}
                        </button>
                        @if (location.status === 'ACTIVE') {
                          <button
                            mat-button
                            type="button"
                            [disabled]="busy() !== null"
                            (click)="deactivateOne(location)"
                          >
                            {{ 'clinic.locations.actions.deactivate' | transloco }}
                          </button>
                        } @else {
                          <button
                            mat-button
                            type="button"
                            [disabled]="busy() !== null"
                            (click)="activateOne(location)"
                          >
                            {{ 'clinic.locations.actions.activate' | transloco }}
                          </button>
                        }
                      </div>
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
        <mat-paginator
          [attr.aria-label]="'clinic.locations.list.paginator' | transloco"
          [length]="page.page.totalElements"
          [pageIndex]="page.page.number"
          [pageSize]="pageSize"
          [hidePageSize]="true"
          [disabled]="loading()"
          (page)="changePage($event)"
        />
      } @else {
        <p class="clinic-locations__empty" role="status">
          {{ 'clinic.locations.list.empty' | transloco }}
        </p>
      }
    }

    <div class="clinic-locations__outcome">
      <app-failure-message [failure]="failure()" />
      <p class="clinic-block__notice" role="status">
        @if (notice(); as notice) {
          {{ 'clinic.locations.notices.' + notice.key | transloco: { name: notice.name } }}
        }
      </p>
    </div>

    @if (canEdit()) {
      <app-location-form
        [location]="editing()"
        (saved)="saved($event)"
        (cancelled)="cancelEdit()"
      />
    }
  `,
  styleUrl: './clinic-block.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClinicLocations implements OnInit {
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** ADMIN_CLINICA (access EDIT) manages the rooms; the other roles only read the list. */
  readonly canEdit = input(false);

  protected readonly pageSize = LOCATIONS_PAGE_SIZE;
  protected readonly statuses = STATUSES;

  protected readonly status = signal<LocationStatus>('ACTIVE');
  protected readonly page = signal<PagedModelLocationDetails | null>(null);
  protected readonly pageIndex = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);

  /** The room an action is running on; the other actions wait for it. */
  protected readonly busy = signal<string | null>(null);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly notice = signal<Notice | null>(null);
  /** The room shown in the form; none while the form adds a new one. */
  protected readonly editing = signal<LocationDetails | null>(null);

  ngOnInit(): void {
    void this.load(0);
  }

  // The backend's default order is the name; no sort is sent (see issue #160).
  protected async load(pageIndex: number): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const page = await this.api.invoke(list2, {
        status: this.status(),
        pageable: { page: pageIndex, size: LOCATIONS_PAGE_SIZE },
      });
      this.page.set(page);
      this.pageIndex.set(pageIndex);
    } catch {
      this.loadFailed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected filter(status: LocationStatus): Promise<void> {
    this.status.set(status);
    this.clearOutcome();
    return this.load(0);
  }

  protected changePage(event: PageEvent): Promise<void> {
    this.clearOutcome();
    return this.load(event.pageIndex);
  }

  protected edit(location: LocationDetails): void {
    this.clearOutcome();
    this.editing.set(location);
  }

  protected cancelEdit(): void {
    const id = this.editing()?.id ?? '';
    this.editing.set(null);
    this.focusRow(id);
  }

  protected async saved({ location, created }: SavedLocation): Promise<void> {
    this.failure.set(null);
    const name = location.name ?? '';
    if (created) {
      // The new room may now be on this page.
      this.notice.set({ key: 'created', name });
      await this.load(this.pageIndex());
      return;
    }
    this.replace(location);
    this.editing.set(null);
    this.notice.set({ key: 'updated', name });
    this.focusRow(location.id ?? '');
  }

  protected async deactivateOne(location: LocationDetails): Promise<void> {
    this.notice.set(null);
    const confirmed = await confirm(this.dialog, {
      titleKey: 'clinic.locations.deactivateConfirm.title',
      messageKey: 'clinic.locations.deactivateConfirm.message',
      messageParams: { name: location.name ?? '' },
      confirmKey: 'clinic.locations.deactivateConfirm.confirm',
    });
    if (!confirmed) {
      return;
    }
    await this.act(location, 'deactivated', () =>
      this.api.invoke(deactivate2, {
        id: location.id ?? '',
        body: { version: location.version ?? 0 },
      }),
    );
  }

  protected activateOne(location: LocationDetails): Promise<void> {
    return this.act(location, 'activated', () =>
      this.api.invoke(activate1, {
        id: location.id ?? '',
        body: { version: location.version ?? 0 },
      }),
    );
  }

  private async act(
    location: LocationDetails,
    key: 'deactivated' | 'activated',
    action: () => Promise<LocationDetails>,
  ): Promise<void> {
    this.busy.set(location.id ?? '');
    this.clearOutcome();
    try {
      const changed = await action();
      this.replace(changed);
      // Its button is gone: the focus goes to the row, instead of being lost.
      this.focusRow(changed.id ?? '');
      this.notice.set({ key, name: changed.name ?? '' });
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

  /** The room as the backend returned it after a change, with its new version. */
  private replace(location: LocationDetails): void {
    this.page.update(
      (page) =>
        page && {
          ...page,
          content: page.content.map((row) => (row.id === location.id ? location : row)),
        },
    );
    if (this.editing()?.id === location.id) {
      this.editing.set(location);
    }
  }

  private focusRow(id: string): void {
    afterNextRender(
      () =>
        this.host.nativeElement.querySelector<HTMLElement>(`[data-location="${id}"] > th`)?.focus(),
      { injector: this.injector },
    );
  }
}
