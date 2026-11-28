import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';

import { es, translocoTesting } from '../../../testing/i18n';
import { confirm } from './confirm-dialog';

const CONFIRMATION = {
  titleKey: 'dialogs.cancel',
  messageKey: 'dialogs.cancel',
  confirmKey: 'forms.save',
};

describe('confirm', () => {
  let dialog: MatDialog;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [translocoTesting()] });
    dialog = TestBed.inject(MatDialog);
  });

  afterEach(() => dialog.closeAll());

  function button(selector: string): HTMLButtonElement {
    const found = document.querySelector<HTMLButtonElement>(selector);
    if (!found) {
      throw new Error(`No ${selector}`);
    }
    return found;
  }

  it('resolves to true when the user confirms', async () => {
    const answer = confirm(dialog, CONFIRMATION);
    await vi.waitFor(() =>
      expect(button('.confirm-dialog__confirm').textContent?.trim()).toBe(es.forms.save),
    );
    button('.confirm-dialog__confirm').click();
    await expect(answer).resolves.toBe(true);
  });

  it('resolves to false when the user cancels', async () => {
    const answer = confirm(dialog, CONFIRMATION);
    await vi.waitFor(() => button('.confirm-dialog__cancel'));
    button('.confirm-dialog__cancel').click();
    await expect(answer).resolves.toBe(false);
  });

  it('resolves to false when the dialog is closed any other way', async () => {
    const answer = confirm(dialog, CONFIRMATION);
    await vi.waitFor(() => button('.confirm-dialog__cancel'));
    dialog.closeAll();
    await expect(answer).resolves.toBe(false);
  });
});
