import { Injectable, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { TranslocoService } from '@jsverse/transloco';

/** The texts of the clinics paginator, from Transloco instead of Material's English defaults. */
@Injectable()
export class ClinicsPaginatorIntl extends MatPaginatorIntl {
  private readonly transloco = inject(TranslocoService);

  constructor() {
    super();
    this.transloco
      .selectTranslation()
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.itemsPerPageLabel = this.text('itemsPerPage');
        this.firstPageLabel = this.text('first');
        this.previousPageLabel = this.text('previous');
        this.nextPageLabel = this.text('next');
        this.lastPageLabel = this.text('last');
        this.changes.next();
      });
  }

  override getRangeLabel = (page: number, pageSize: number, length: number): string => {
    if (length === 0) {
      return this.text('empty');
    }
    const start = page * pageSize + 1;
    const end = Math.min((page + 1) * pageSize, length);
    return this.transloco.translate('platform.paginator.range', { start, end, total: length });
  };

  private text(key: string): string {
    return this.transloco.translate(`platform.paginator.${key}`);
  }
}
