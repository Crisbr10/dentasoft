import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  OnInit,
  afterNextRender,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';

import { Api } from '../../api/api';
import { list } from '../../api/fn/clinic-administration-controller/list';
import { PagedModelPlatformClinicDetails } from '../../api/models/paged-model-platform-clinic-details';
import { PlatformClinicDetails } from '../../api/models/platform-clinic-details';
import { ClinicPanel } from './clinic-panel';
import { providePaginatorIntl } from '../../shared/lists/paginator-intl';
import { NewClinicForm } from './new-clinic-form';

/** Clinics per page of the list. */
export const CLINICS_PAGE_SIZE = 20;

/**
 * The clinics of the platform for PLATFORM_ADMIN (M01-T05, M01-T11, M02-T11): a paged list by
 * name, the creation of a clinic and the panel of the chosen one. The backend checks the role on
 * every request; these calls never carry X-Clinic-Id (the interceptor only adds it to the clinic
 * API).
 */
@Component({
  selector: 'app-platform-home',
  imports: [
    ClinicPanel,
    MatButtonModule,
    MatIconModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    NewClinicForm,
    TranslocoPipe,
  ],
  providers: [providePaginatorIntl()],
  templateUrl: './platform-home.html',
  styleUrl: './platform-home.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformHome implements OnInit {
  private readonly api = inject(Api);
  private readonly injector = inject(Injector);
  private readonly panel = viewChild(ClinicPanel);

  protected readonly pageSize = CLINICS_PAGE_SIZE;
  protected readonly page = signal<PagedModelPlatformClinicDetails | null>(null);
  protected readonly pageIndex = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly selected = signal<PlatformClinicDetails | null>(null);

  ngOnInit(): void {
    void this.load(0);
  }

  protected async load(pageIndex: number): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const page = await this.api.invoke(list, {
        pageable: { page: pageIndex, size: CLINICS_PAGE_SIZE, sort: ['name'] },
      });
      this.page.set(page);
      this.pageIndex.set(pageIndex);
    } catch {
      this.loadFailed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected changePage(event: PageEvent): Promise<void> {
    return this.load(event.pageIndex);
  }

  protected select(clinic: PlatformClinicDetails): void {
    this.selected.set(clinic);
    this.focusPanel();
  }

  /** A new clinic: the list is read again (it may fall on this page) and the clinic is opened. */
  protected async created(clinic: PlatformClinicDetails): Promise<void> {
    this.selected.set(clinic);
    this.focusPanel();
    await this.load(this.pageIndex());
  }

  /** A change made in the panel shows in the panel and in its row. */
  protected changed(clinic: PlatformClinicDetails): void {
    this.selected.set(clinic);
    this.page.update(
      (page) =>
        page && {
          ...page,
          content: page.content.map((row) => (row.id === clinic.id ? clinic : row)),
        },
    );
  }

  private focusPanel(): void {
    afterNextRender(() => this.panel()?.focus(), { injector: this.injector });
  }
}
