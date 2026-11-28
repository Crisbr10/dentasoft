import { Injectable, Provider, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { TranslocoService } from '@jsverse/transloco';

/**
 * The texts of a Material paginator, from Transloco (`lists.paginator`) instead of Material's
 * English defaults. Each list names its own paginator with an aria-label.
 */
@Injectable()
export class PaginatorIntl extends MatPaginatorIntl {
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
    return this.transloco.translate('lists.paginator.range', { start, end, total: length });
  };

  private text(key: string): string {
    return this.transloco.translate(`lists.paginator.${key}`);
  }
}

/** For the `providers` of a component with a paginated list. */
export function providePaginatorIntl(): Provider {
  return { provide: MatPaginatorIntl, useClass: PaginatorIntl };
}
