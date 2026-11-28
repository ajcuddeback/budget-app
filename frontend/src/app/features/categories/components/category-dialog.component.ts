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
import { toSignal } from '@angular/core/rxjs-interop';
import { ApiError } from '../../../core/api-error';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { CategoryFormValue } from '../data/category-patch';
import { possibleParents } from '../data/category-tree';
import { CATEGORY_KINDS, Category, CategoryKind, categoryKindKey } from '../data/category.models';

/** The server's `CHECK (length(btrim(name)) BETWEEN 1 AND 60)`, mirrored for early feedback. */
const NAME_MAX_LENGTH = 60;

/**
 * Add a category, or correct one.
 *
 * Presentational: it validates for the person's benefit — the server validates for everyone's —
 * and emits what they typed.
 *
 * Three things here are the feature's rules rather than UI taste:
 *
 * - **A child's kind is its parent's and is not editable.** Choosing a parent replaces the kind
 *   question rather than sitting beside it, because a form that let the two disagree would be
 *   offering a `400`.
 * - **The kind of an existing category is shown and disabled, not hidden.** It is fixed at
 *   creation — re-kinding one in use would reinterpret every transaction filed under it — and
 *   somebody who cannot change it should learn why rather than wonder where it went.
 * - **A category with children of its own is not offered a parent.** The server refuses it with
 *   `409 category-depth-exceeded`; disabling the field says so before the round trip.
 */
@Component({
  selector: 'app-category-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './category-dialog.component.html',
})
export class CategoryDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  /** The category being corrected, or `null` to add one. */
  readonly category = input<Category | null>(null);
  /** Every category in the household, for working out which may be a parent. */
  readonly all = input.required<readonly Category[]>();
  /**
   * This category has children, so it cannot itself become a child — the hierarchy is two levels.
   */
  readonly hasChildren = input(false);
  /** Pre-selects a parent when the person pressed "Add a sub-category" on a specific row. */
  readonly defaultParentId = input<string | null>(null);
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<CategoryFormValue>();
  readonly closed = output<void>();

  protected readonly kinds = CATEGORY_KINDS;
  protected readonly kindKey = categoryKindKey;
  protected readonly editing = computed(() => this.category() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'categories.editTitle' : 'categories.addTitle',
  );

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(NAME_MAX_LENGTH)]],
    kind: ['EXPENSE' as CategoryKind, [Validators.required]],
    /** `''` is "no parent", i.e. top-level. */
    parentId: [''],
  });

  /**
   * The categories this one may sit under: top-level, unarchived, and of the same kind.
   *
   * Recomputed from the live form value, so changing the kind re-filters the list instead of
   * leaving an income parent selected under an expense category.
   */
  protected readonly parents = computed(() =>
    possibleParents(this.all(), this.selectedKind(), this.category()?.id),
  );
  /**
   * The form's value as a signal.
   *
   * Reactive forms are not signals, so `valueChanges` is bridged once here. The state below could
   * not read `getRawValue()` instead: a method call inside a `computed` is not tracked, so nothing
   * would recompute when somebody picked a parent.
   */
  private readonly formValue = toSignal(this.form.valueChanges, {
    initialValue: this.form.getRawValue() as Partial<CategoryFormValue>,
  });

  /** The kind the form is effectively on: a child's is its parent's, not the control's. */
  private readonly selectedKind = computed<CategoryKind>(() => {
    const parentId = this.formValue().parentId ?? '';
    return parentId === '' ? (this.formValue().kind ?? 'EXPENSE') : this.kindOf(parentId);
  });

  protected readonly kindLocked = computed(
    () => this.editing() || (this.formValue().parentId ?? '') !== '',
  );

  constructor() {
    effect(() => {
      const category = this.category();
      this.form.reset(
        category
          ? {
              name: category.name,
              kind: category.kind,
              parentId: category.parentId ?? '',
            }
          : {
              name: '',
              kind: 'EXPENSE',
              parentId: this.defaultParentId() ?? '',
            },
      );
    });

    effect(() => {
      // A child's kind comes from its parent, and an existing category's kind cannot change.
      if (this.kindLocked()) {
        this.form.controls.kind.disable({ emitEvent: false });
      } else {
        this.form.controls.kind.enable({ emitEvent: false });
      }
      if (this.hasChildren()) {
        this.form.controls.parentId.disable({ emitEvent: false });
      } else {
        this.form.controls.parentId.enable({ emitEvent: false });
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
    // Raw, because a locked kind or parent control is disabled, and a disabled control is left out
    // of `value` — which would submit a category with no kind at all.
    const raw = this.form.getRawValue();
    this.submitted.emit({
      name: raw.name,
      // The kind a child is created with has to be its parent's, whatever the disabled control
      // still holds from before a parent was chosen.
      kind: raw.parentId === '' ? raw.kind : this.kindOf(raw.parentId),
      parentId: raw.parentId,
    });
  }

  private kindOf(parentId: string): CategoryKind {
    return this.all().find((candidate) => candidate.id === parentId)?.kind ?? 'EXPENSE';
  }
}
