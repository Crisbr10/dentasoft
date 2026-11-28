import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormControl,
  FormGroup,
  FormGroupDirective,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, merge } from 'rxjs';

import { Api } from '../../api/api';
import { create2 } from '../../api/fn/locations-controller/create-2';
import { update1 } from '../../api/fn/locations-controller/update-1';
import { LocationDetails } from '../../api/models/location-details';
import { ControlError } from '../../shared/forms/control-error';
import { FormActions } from '../../shared/forms/form-actions';
import { focusFirstInvalid } from '../../shared/forms/form-focus';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';
import { LOCATION_NAME_MAX, plainText, roomColor } from './clinic-rules';

/** The colour a new room starts with; the native picker always has one. */
const DEFAULT_COLOR = '#1E88E5';

/** What the form saved, so the list can show it. */
export interface SavedLocation {
  readonly location: LocationDetails;
  readonly created: boolean;
}

/**
 * Creates a treatment room of the active clinic (POST /api/v1/clinic/locations) or, with
 * `location`, renames it or changes its colour with the version the user saw (PUT …/{id}), as
 * M01-T06 defines them. Two active rooms never share a name: the backend answers that, and a
 * version conflict, with its ProblemDetail, shown in the form. Only for ADMIN_CLINICA.
 */
@Component({
  selector: 'app-location-form',
  imports: [
    ControlError,
    FormActions,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h4 id="location-form-title">
      @if (location(); as editing) {
        {{ 'clinic.locations.form.editTitle' | transloco: { name: editing.name } }}
      } @else {
        {{ 'clinic.locations.form.newTitle' | transloco }}
      }
    </h4>
    <form
      class="clinic-block__form"
      [attr.aria-labelledby]="'location-form-title'"
      [formGroup]="form"
      (ngSubmit)="save()"
    >
      <mat-form-field>
        <mat-label>{{ 'clinic.locations.form.name' | transloco }}</mat-label>
        <input matInput formControlName="name" autocomplete="off" required />
        <mat-error>
          @if (nameInvalid()) {
            {{ 'clinic.errors.plainText' | transloco }}
          } @else {
            <app-control-error [control]="form.controls.name" />
          }
        </mat-error>
      </mat-form-field>

      <div class="clinic-block__color">
        <label for="location-color">{{ 'clinic.locations.form.color' | transloco }}</label>
        <input
          id="location-color"
          type="color"
          formControlName="color"
          [attr.aria-describedby]="'location-color-value'"
        />
        <span id="location-color-value" class="clinic-block__color-value">{{ color() }}</span>
      </div>

      <app-form-actions
        [form]="form"
        [saving]="saving()"
        [failure]="failure()"
        [saveKey]="location() ? 'forms.save' : 'clinic.locations.form.create'"
        (save)="save()"
        (discard)="discard()"
      />
      @if (location()) {
        <div class="clinic-block__cancel">
          <button mat-button type="button" (click)="cancelled.emit()">
            {{ 'clinic.locations.form.cancelEdit' | transloco }}
          </button>
        </div>
      }
    </form>
  `,
  styleUrl: './clinic-block.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationForm {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** The room to edit; none for a new one. */
  readonly location = input<LocationDetails | null>(null);
  readonly saved = output<SavedLocation>();
  /** The user left the edit without saving. */
  readonly cancelled = output<void>();

  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(LOCATION_NAME_MAX), plainText],
    }),
    color: new FormControl(DEFAULT_COLOR.toLowerCase(), {
      nonNullable: true,
      validators: [Validators.required, roomColor],
    }),
  });
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);

  protected readonly color = toSignal(
    this.form.controls.color.valueChanges.pipe(map((value) => value.toUpperCase())),
    { initialValue: DEFAULT_COLOR },
  );

  protected readonly nameInvalid = toSignal(
    merge(this.form.events, this.form.controls.name.events).pipe(
      map(() => {
        const control = this.form.controls.name;
        return control.hasError('plainText') && !control.hasError('server');
      }),
    ),
    { initialValue: false },
  );

  private readonly formDirective = viewChild(FormGroupDirective);

  /** The room whose data the form shows; null for a new one. */
  private shownId: string | null = null;

  constructor() {
    // A new room, or another one to edit: the form shows its data. The same room with a new
    // version (activated or deactivated from its row while open here) keeps what the user typed;
    // saving sends that new version.
    effect(() => {
      const location = this.location();
      untracked(() => {
        const id = location?.id ?? null;
        if (id !== null && id === this.shownId && this.form.dirty) {
          return;
        }
        this.shownId = id;
        // Leaving a field marks it as touched, and the list moves the focus to the room's row
        // once the edit ends: leave it before the reset, so the empty new-room form shows no error.
        const focused = document.activeElement;
        if (focused instanceof HTMLElement && this.host.nativeElement.contains(focused)) {
          focused.blur();
        }
        this.fill(location);
        this.failure.set(null);
        if (location) {
          afterNextRender(
            () =>
              this.host.nativeElement
                .querySelector<HTMLElement>('[formcontrolname="name"]')
                ?.focus(),
            { injector: this.injector },
          );
        }
      });
    });
  }

  protected async save(): Promise<void> {
    // Enter in the name field submits the form too: once is enough.
    if (this.saving()) {
      return;
    }
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.failure.set({ kind: 'validation' });
      focusFirstInvalid(this.form, this.host.nativeElement);
      return;
    }
    const editing = this.location();
    const value = this.form.getRawValue();
    // The backend keeps the colour in upper case, as `#RRGGBB`.
    const body = { name: value.name.trim(), color: value.color.toUpperCase() };
    this.saving.set(true);
    this.failure.set(null);
    try {
      const location = editing
        ? await this.api.invoke(update1, {
            id: editing.id ?? '',
            body: { ...body, version: editing.version ?? 0 },
          })
        : await this.api.invoke(create2, { body });
      if (!editing) {
        this.fill(null);
      }
      this.saved.emit({ location, created: !editing });
    } catch (error) {
      this.failure.set(toSaveFailure(this.form, error));
      focusFirstInvalid(this.form, this.host.nativeElement);
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    this.fill(this.location());
    this.failure.set(null);
  }

  private fill(location: LocationDetails | null): void {
    const value = {
      name: location?.name ?? '',
      // The native picker only takes lower case.
      color: (location?.color ?? DEFAULT_COLOR).toLowerCase(),
    };
    // A submit with Enter leaves the form "submitted", and Material then marks every invalid
    // field, even an empty one the user has not touched yet: resetForm clears that too.
    const directive = this.formDirective();
    if (directive?.submitted) {
      directive.resetForm(value);
    } else {
      this.form.reset(value);
    }
  }
}
