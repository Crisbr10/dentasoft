import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslocoPipe } from '@jsverse/transloco';
import { map, startWith } from 'rxjs';

import { Api } from '../../api/api';
import { replaceWeek } from '../../api/fn/opening-hours-controller/replace-week';
import { week } from '../../api/fn/opening-hours-controller/week';
import { Slot } from '../../api/models/slot';
import { SlotRequest } from '../../api/models/slot-request';
import { ControlError } from '../../shared/forms/control-error';
import { FormActions } from '../../shared/forms/form-actions';
import { SaveFailure, toSaveFailure } from '../../shared/forms/save-failure';
import { DAYS, DayOfWeek, MAX_SLOTS, dayRules, slotRules, wallClock } from './clinic-rules';

type SlotForm = FormGroup<{
  openingTime: FormControl<string>;
  closingTime: FormControl<string>;
}>;
type DayForm = FormArray<SlotForm>;
type WeekForm = FormGroup<Record<DayOfWeek, DayForm>>;

/** The slots of a day, in the order they open. */
interface DayHours {
  readonly day: DayOfWeek;
  readonly slots: readonly { readonly opening: string; readonly closing: string }[];
}

/** No more than {@link MAX_SLOTS} slots in the week, as the backend accepts. */
function weekRules(week: AbstractControl): ValidationErrors | null {
  const count = Object.values((week as WeekForm).controls).reduce(
    (total, day) => total + day.length,
    0,
  );
  return count > MAX_SLOTS ? { tooManySlots: true } : null;
}

function slotForm(opening = '', closing = ''): SlotForm {
  return new FormGroup(
    {
      openingTime: new FormControl(opening, { nonNullable: true, validators: Validators.required }),
      closingTime: new FormControl(closing, { nonNullable: true, validators: Validators.required }),
    },
    { validators: slotRules },
  );
}

/**
 * The clinic's opening hours (GET and PUT /api/v1/clinic/hours-of-operation, M01-T06): the wall
 * clock time of each day in the clinic's time zone, with several slots a day (morning and
 * afternoon) and no slot on the days it is closed. The week is saved whole; the backend checks
 * again that the slots of a day do not overlap. ADMIN_CLINICA edits it; the other roles see it as
 * a table.
 */
@Component({
  selector: 'app-opening-hours-form',
  imports: [
    ControlError,
    FormActions,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    ReactiveFormsModule,
    TranslocoPipe,
  ],
  template: `
    <h3 id="clinic-hours-title">{{ 'clinic.hours.title' | transloco }}</h3>
    <p class="clinic-block__hint">{{ 'clinic.hours.intro' | transloco }}</p>
    @if (loading()) {
      <p class="clinic-block__loading" role="status">
        <mat-spinner diameter="20" aria-hidden="true" />
        <span>{{ 'clinic.hours.loading' | transloco }}</span>
      </p>
    } @else if (loadFailed()) {
      <p role="alert">{{ 'clinic.hours.loadError' | transloco }}</p>
      <button mat-stroked-button type="button" (click)="load()">
        <mat-icon svgIcon="refresh" />
        {{ 'clinic.retry' | transloco }}
      </button>
    }

    @if (loaded()) {
      @if (canEdit()) {
        <form class="clinic-hours" [attr.aria-labelledby]="'clinic-hours-title'" [formGroup]="form">
          @for (day of days; track day) {
            <fieldset class="clinic-hours__day" [attr.data-day]="day">
              <legend>{{ 'clinic.hours.days.' + day | transloco }}</legend>
              @let slots = form.controls[day];
              @if (slots.length === 0) {
                <p class="clinic-hours__closed">{{ 'clinic.hours.closed' | transloco }}</p>
              }
              @for (slot of slots.controls; track slot; let index = $index) {
                <div
                  class="clinic-hours__slot"
                  role="group"
                  [attr.aria-label]="
                    'clinic.hours.slot'
                      | transloco
                        : { day: 'clinic.hours.days.' + day | transloco, number: index + 1 }
                  "
                  [formGroup]="slot"
                >
                  <mat-form-field>
                    <mat-label>{{ 'clinic.hours.opening' | transloco }}</mat-label>
                    <input matInput type="time" formControlName="openingTime" required />
                    <mat-error
                      ><app-control-error [control]="slot.controls.openingTime"
                    /></mat-error>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>{{ 'clinic.hours.closing' | transloco }}</mat-label>
                    <input matInput type="time" formControlName="closingTime" required />
                    <mat-error
                      ><app-control-error [control]="slot.controls.closingTime"
                    /></mat-error>
                  </mat-form-field>
                  <button mat-button type="button" (click)="removeSlot(day, index)">
                    {{ 'clinic.hours.removeSlot' | transloco }}
                  </button>
                  @if (errorsShown() && slot.hasError('closesBeforeOpening')) {
                    <p class="clinic-block__error">
                      {{ 'clinic.hours.errors.closesBeforeOpening' | transloco }}
                    </p>
                  }
                </div>
              }
              @if (errorsShown() && slots.hasError('overlapping')) {
                <p class="clinic-block__error">
                  {{ 'clinic.hours.errors.overlapping' | transloco }}
                </p>
              }
              <div>
                <button
                  mat-stroked-button
                  type="button"
                  class="clinic-hours__add"
                  [disabled]="full()"
                  (click)="addSlot(day)"
                >
                  {{ 'clinic.hours.addSlot' | transloco }}
                </button>
              </div>
            </fieldset>
          }
          @if (full()) {
            <p class="clinic-block__hint">
              {{ 'clinic.hours.full' | transloco: { max: maxSlots } }}
            </p>
          }

          <app-form-actions
            [form]="form"
            [saving]="saving()"
            [failure]="failure()"
            (save)="save()"
            (discard)="discard()"
          />
          <p class="clinic-block__notice" role="status">
            @if (saved()) {
              {{ 'clinic.hours.saved' | transloco }}
            }
          </p>
        </form>
      } @else {
        <table class="clinic-hours__table">
          <caption>
            {{
              'clinic.hours.caption' | transloco
            }}
          </caption>
          <tbody>
            @for (day of week(); track day.day) {
              <tr>
                <th [attr.scope]="'row'">{{ 'clinic.hours.days.' + day.day | transloco }}</th>
                <td>
                  @if (day.slots.length > 0) {
                    <ul class="clinic-hours__ranges">
                      @for (slot of day.slots; track $index) {
                        <li>
                          {{
                            'clinic.hours.range'
                              | transloco: { opening: slot.opening, closing: slot.closing }
                          }}
                        </li>
                      }
                    </ul>
                  } @else {
                    {{ 'clinic.hours.closed' | transloco }}
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
  styleUrl: './clinic-block.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OpeningHoursForm implements OnInit {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** ADMIN_CLINICA (access EDIT) edits; the other roles only see the week. */
  readonly canEdit = input(false);

  protected readonly days = DAYS;
  protected readonly maxSlots = MAX_SLOTS;

  /** The week as the backend last returned it. */
  private readonly stored = signal<readonly Slot[] | null>(null);
  protected readonly loaded = computed(() => this.stored() !== null);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly failure = signal<SaveFailure | null>(null);
  protected readonly saved = signal(false);
  /** The rules of the week show once the user tried to save it. */
  protected readonly errorsShown = signal(false);

  protected readonly form: WeekForm = new FormGroup(
    Object.fromEntries(DAYS.map((day) => [day, new FormArray<SlotForm>([], dayRules)])) as Record<
      DayOfWeek,
      DayForm
    >,
    { validators: weekRules },
  );

  protected readonly full = toSignal(
    this.form.events.pipe(
      startWith(null),
      map(() => this.slotCount() >= MAX_SLOTS),
    ),
    { initialValue: false },
  );

  protected readonly week = computed<readonly DayHours[]>(() => {
    const slots = this.stored() ?? [];
    return DAYS.map((day) => ({
      day,
      slots: slots
        .filter((slot) => slot.dayOfWeek === day)
        .map((slot) => ({
          opening: wallClock(slot.openingTime),
          closing: wallClock(slot.closingTime),
        }))
        .sort((a, b) => a.opening.localeCompare(b.opening)),
    }));
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      this.show(await this.api.invoke(week, {}));
    } catch {
      this.loadFailed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected addSlot(day: DayOfWeek): void {
    const slots = this.form.controls[day];
    slots.push(slotForm());
    this.form.markAsDirty();
    this.saved.set(false);
    const index = slots.length - 1;
    afterNextRender(() => this.openingTimes(day)[index]?.focus(), { injector: this.injector });
  }

  protected removeSlot(day: DayOfWeek, index: number): void {
    this.form.controls[day].removeAt(index);
    this.form.markAsDirty();
    this.saved.set(false);
    // Its button is gone: the focus stays in the day, on its «add» button.
    afterNextRender(
      () =>
        this.host.nativeElement
          .querySelector<HTMLElement>(`[data-day="${day}"] .clinic-hours__add`)
          ?.focus(),
      { injector: this.injector },
    );
  }

  protected async save(): Promise<void> {
    this.saved.set(false);
    this.errorsShown.set(true);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.failure.set({ kind: 'validation' });
      this.focusInvalid();
      return;
    }
    this.saving.set(true);
    this.failure.set(null);
    try {
      this.show(await this.api.invoke(replaceWeek, { body: { slots: this.slots() } }));
      this.saved.set(true);
    } catch (error) {
      // The backend's field names are those of the flat list, not of the days: its errors show
      // as its ProblemDetail.
      this.failure.set(toSaveFailure(this.form, error));
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    this.fill(this.stored() ?? []);
    this.failure.set(null);
    this.saved.set(false);
  }

  private show(slots: readonly Slot[]): void {
    this.stored.set(slots);
    this.fill(slots);
  }

  private fill(slots: readonly Slot[]): void {
    this.errorsShown.set(false);
    for (const day of DAYS) {
      const array = this.form.controls[day];
      array.clear({ emitEvent: false });
      slots
        .filter((slot) => slot.dayOfWeek === day)
        .map((slot) => ({
          opening: wallClock(slot.openingTime),
          closing: wallClock(slot.closingTime),
        }))
        .sort((a, b) => a.opening.localeCompare(b.opening))
        .forEach((slot) => array.push(slotForm(slot.opening, slot.closing), { emitEvent: false }));
    }
    this.form.reset(this.form.getRawValue());
  }

  /** The week as the request lists it, day by day. */
  private slots(): SlotRequest[] {
    return DAYS.flatMap((day) =>
      this.form.controls[day].getRawValue().map((slot) => ({
        dayOfWeek: day,
        openingTime: wallClock(slot.openingTime),
        closingTime: wallClock(slot.closingTime),
      })),
    );
  }

  private slotCount(): number {
    return DAYS.reduce((total, day) => total + this.form.controls[day].length, 0);
  }

  /**
   * The first field to fix: an empty time, or else the opening time of the first slot or day that
   * breaks a rule of the week.
   */
  private focusInvalid(): void {
    const host = this.host.nativeElement;
    const field = host.querySelector<HTMLElement>('input.ng-invalid');
    if (field) {
      field.focus();
      return;
    }
    const day = DAYS.find((candidate) => this.form.controls[candidate].invalid);
    if (!day) {
      return;
    }
    const index = Math.max(
      0,
      this.form.controls[day].controls.findIndex((slot) => slot.invalid),
    );
    this.openingTimes(day)[index]?.focus();
  }

  /** The opening time inputs of `day`, in the order of its slots. */
  private openingTimes(day: DayOfWeek): NodeListOf<HTMLElement> {
    return this.host.nativeElement.querySelectorAll<HTMLElement>(
      `[data-day="${day}"] [formcontrolname="openingTime"]`,
    );
  }
}
