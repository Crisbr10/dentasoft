import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

/** What a confirmation asks: translation keys, with the parameters of the message. */
export interface Confirmation {
  readonly titleKey: string;
  readonly messageKey: string;
  readonly messageParams?: Record<string, unknown>;
  readonly confirmKey: string;
}

/**
 * Asks before an action that is hard to undo (suspending a clinic, cancelling an invitation…).
 * Material's dialog keeps the focus inside, closes with Esc and gives the focus back; cancelling
 * is the first button, so pressing Enter right away does not confirm.
 */
@Component({
  selector: 'app-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule, TranslocoPipe],
  template: `
    <h2 mat-dialog-title>{{ data.titleKey | transloco }}</h2>
    <mat-dialog-content>
      <p>{{ data.messageKey | transloco: data.messageParams }}</p>
    </mat-dialog-content>
    <mat-dialog-actions [align]="'end'">
      <button
        mat-stroked-button
        type="button"
        class="confirm-dialog__cancel"
        [mat-dialog-close]="false"
        cdkFocusInitial
      >
        {{ 'dialogs.cancel' | transloco }}
      </button>
      <button
        mat-flat-button
        type="button"
        class="confirm-dialog__confirm"
        [mat-dialog-close]="true"
      >
        {{ data.confirmKey | transloco }}
      </button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog {
  protected readonly data = inject<Confirmation>(MAT_DIALOG_DATA);
}

/** Opens the confirmation and resolves to true only if the user confirmed. */
export async function confirm(dialog: MatDialog, confirmation: Confirmation): Promise<boolean> {
  const ref = dialog.open<ConfirmDialog, Confirmation, boolean>(ConfirmDialog, {
    data: confirmation,
    role: 'alertdialog',
  });
  return (await firstValueFrom(ref.afterClosed())) === true;
}
