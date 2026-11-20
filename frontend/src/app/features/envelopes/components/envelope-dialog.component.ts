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
import { Category } from '../../categories/data/category.models';
import { pickableCategories } from '../../categories/data/category-tree';
import { EnvelopeFormValue } from '../data/envelope-patch';
import { Envelope } from '../data/envelope.models';

const NAME_MAX_LENGTH = 60;

/** Unsigned: you cannot set aside a negative amount, so the sign is not part of the shape. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Decide an envelope, or change your mind about one.
 *
 * **The month cannot be changed on an edit.** Moving an envelope between months would carry its
 * entries with it and change what two periods' totals mean — the server does not accept it, so the
 * control is disabled with the reason beside it rather than hidden.
 */
@Component({
  selector: 'app-envelope-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './envelope-dialog.component.html',
})
export class EnvelopeDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly envelope = input<Envelope | null>(null);
  readonly categories = input.required<readonly Category[]>();
  /** The month the screen is showing, used for a new envelope. */
  readonly period = input.required<string>();
  readonly currency = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<EnvelopeFormValue>();
  readonly closed = output<void>();

  protected readonly editing = computed(() => this.envelope() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'envelopes.editTitle' : 'envelopes.addTitle',
  );
  protected readonly choosableCategories = computed(() => pickableCategories(this.categories()));

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(NAME_MAX_LENGTH)]],
    period: ['', [Validators.required]],
    amount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    categoryId: [''],
  });

  constructor() {
    effect(() => {
      const envelope = this.envelope();
      this.form.reset(
        envelope
          ? {
              name: envelope.name,
              period: envelope.period,
              amount: envelope.amount,
              categoryId: envelope.categoryId ?? '',
            }
          : { name: '', period: this.period(), amount: '', categoryId: '' },
      );
    });

    effect(() => {
      if (this.editing()) {
        this.form.controls.period.disable({ emitEvent: false });
      } else {
        this.form.controls.period.enable({ emitEvent: false });
      }
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
    // Raw, because the period control is disabled on an edit and a disabled control is left out
    // of `value` — which would submit an envelope with no month at all.
    this.submitted.emit(this.form.getRawValue());
  }
}
