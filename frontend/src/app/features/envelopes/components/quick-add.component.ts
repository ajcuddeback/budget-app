import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { EntryFormValue } from '../data/envelope-patch';
import { Envelope, EnvelopeEntry } from '../data/envelope.models';

/** 1–500 characters, matching the server's `ck_envelope_entries_note`. */
const NOTE_MAX_LENGTH = 500;

/** Positive, at most four decimal places — an entry records how much was used. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Log spend against an envelope — the action somebody takes eighty times a month.
 *
 * Three things here are the feature's rules rather than UI taste:
 *
 * - **The amount is first and is focused on open.** This is usually done one-handed, in a queue.
 *   Everything else on the form is optional and sits below it.
 * - **`inputmode="decimal"` opens the keypad**, and the field is still `type="text"`: a number
 *   input hands back a `number`, and an amount that has been through a double has already lost
 *   whatever it lost (ADR-0006).
 * - **Nothing but the amount is required.** No payee, no note, no date — the date defaults to
 *   today on the server. A form that demanded more would have broken the feature.
 */
@Component({
  selector: 'app-quick-add',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './quick-add.component.html',
})
export class QuickAddComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly envelope = input.required<Envelope>();
  /** The entry being corrected, or null to log a new one. */
  readonly entry = input<EnvelopeEntry | null>(null);
  /** Today, as `YYYY-MM-DD`, supplied by the page so this component has no clock of its own. */
  readonly today = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<EntryFormValue>();
  readonly closed = output<void>();

  protected readonly editing = computed(() => this.entry() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'envelopes.editEntryTitle' : 'envelopes.logTitle',
  );

  readonly form = this.fb.group({
    amount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    date: [''],
    payeeName: [''],
    note: ['', [Validators.maxLength(NOTE_MAX_LENGTH)]],
  });

  constructor() {
    effect(() => {
      const entry = this.entry();
      this.form.reset(
        entry
          ? {
              amount: entry.amount,
              date: entry.date,
              payeeName: entry.payeeName ?? '',
              note: entry.note ?? '',
            }
          : { amount: '', date: this.today(), payeeName: '', note: '' },
      );
    });

    effect(() => {
      const failure = this.error();
      if (failure) {
        applyServerFieldErrors(this.form, failure);
      }
    });
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitted.emit(this.form.getRawValue());
  }
}
