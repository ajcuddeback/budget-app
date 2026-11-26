import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { CategoryDialogComponent } from '../components/category-dialog.component';
import {
  CategoryFormValue,
  buildCategoryCreate,
  buildCategoryPatch,
  patchIsEmpty,
} from '../data/category-patch';
import { CategoryGroup, groupByKind } from '../data/category-tree';
import { Category, categoryKindKey } from '../data/category.models';
import { PayeeRulesComponent } from '../../check-in/components/payee-rules.component';
import { CategoriesService } from '../data/categories.service';

/** What the dialog is open for. A `null` category means "add one". */
interface DialogTarget {
  category: Category | null;
  /** Pre-selected parent, when the person asked for a sub-category of a specific row. */
  parentId: string | null;
}

interface PendingAction {
  kind: 'archive' | 'delete';
  category: Category;
}

/**
 * How the household classifies what its money was for.
 *
 * Smart: it fetches, coordinates and holds the state; the dialog below takes inputs and emits
 * outputs. A failed write leaves the screen as it was and says what happened.
 *
 * Two behaviours here are the feature's rules rather than UI taste:
 *
 * - **Deleting a category is offered plainly, because it cannot lose a transaction.** The server
 *   nulls the `category_id` of anything filed under it — they become "Needs a category" — so the
 *   confirmation says where those transactions go rather than warning about data loss that does
 *   not happen. Deleting a *parent* also deletes its children, which it does say.
 * - **Archiving and deleting answer different questions.** Archiving means "I no longer use this
 *   and want my history to still read correctly"; deleting means "this was a mistake". Both are
 *   offered, with archiving first.
 */
@Component({
  selector: 'app-categories-page',
  imports: [
    TranslatePipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    CategoryDialogComponent,
    ConfirmDialogComponent,
    PayeeRulesComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './categories.page.html',
  styleUrl: './categories.page.css',
})
export class CategoriesPage {
  private readonly service = inject(CategoriesService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);
  private readonly categories = signal<readonly Category[]>([]);

  protected readonly showArchived = signal(false);

  /** A `VIEWER` may read everything here and write nothing. The server agrees, and is the gate. */
  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly all = computed(() => this.categories());
  protected readonly active = computed(() => this.categories().filter((c) => !c.archived));
  protected readonly archived = computed(() => this.categories().filter((c) => c.archived));
  protected readonly groups = computed<readonly CategoryGroup[]>(() => groupByKind(this.active()));
  protected readonly archivedGroups = computed<readonly CategoryGroup[]>(() =>
    groupByKind(this.archived()),
  );
  protected readonly kindKey = categoryKindKey;

  protected readonly dialog = signal<DialogTarget | null>(null);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);

  protected readonly busyId = signal<string | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly actionErrorCategory = signal<string | null>(null);
  protected readonly notice = signal<MessageKey | null>(null);
  protected readonly pending = signal<PendingAction | null>(null);

  /** Whether the category in the dialog has children, which stops it becoming a child itself. */
  protected readonly dialogHasChildren = computed(() => {
    const category = this.dialog()?.category;
    return category ? this.categories().some((c) => c.parentId === category.id) : false;
  });

  protected readonly confirmText = computed(() => {
    const action = this.pending();
    if (!action) {
      return null;
    }
    const name = action.category.name;
    if (action.kind === 'archive') {
      return {
        heading: this.i18n.t('categories.archiveHeading', { name }),
        message: this.i18n.t('categories.archiveMessage'),
        action: this.i18n.t('categories.archive'),
        // Reversible, so it does not wear the colour that means "this cannot be undone".
        tone: 'ordinary' as const,
      };
    }
    const childCount = this.categories().filter((c) => c.parentId === action.category.id).length;
    return {
      heading: this.i18n.t('categories.deleteHeading', { name }),
      // The honest message depends on whether children go with it. Both say that transactions
      // survive, because that is the thing somebody is actually afraid of.
      message:
        childCount > 0
          ? this.i18n.t('categories.deleteMessageWithChildren', { count: childCount })
          : this.i18n.t('categories.deleteMessage'),
      action: this.i18n.t('categories.delete'),
      tone: 'destructive' as const,
    };
  });

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.auth.ensureLoaded();
    if (!this.auth.membership()) {
      this.noHousehold.set(true);
      this.loading.set(false);
      return;
    }
    try {
      const page = await firstValueFrom(this.service.list());
      this.categories.set(page.content);
      this.noHousehold.set(false);
    } catch (thrown) {
      const error = asApiError(thrown);
      if (error.status === 403) {
        this.noHousehold.set(true);
      } else {
        this.loadError.set(error);
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected toggleArchived(): void {
    this.showArchived.update((shown) => !shown);
  }

  protected openAdd(): void {
    this.resetDialogState();
    this.dialog.set({ category: null, parentId: null });
  }

  protected openAddChild(parent: Category): void {
    this.resetDialogState();
    this.dialog.set({ category: null, parentId: parent.id });
  }

  protected openEdit(category: Category): void {
    this.resetDialogState();
    this.dialog.set({ category, parentId: null });
  }

  protected closeDialog(): void {
    this.dialog.set(null);
    this.resetDialogState();
  }

  protected async submit(form: CategoryFormValue): Promise<void> {
    const target = this.dialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target.category === null) {
        await firstValueFrom(this.service.create(buildCategoryCreate(form)));
        this.notice.set('categories.created');
      } else {
        const patch = buildCategoryPatch(target.category, form);
        if (patchIsEmpty(patch)) {
          // Nothing changed. Closing is the honest response to "save" on an untouched form —
          // a request would spend a round trip and bump the version for no reason.
          this.closeDialog();
          return;
        }
        await firstValueFrom(this.service.update(target.category.id, patch));
        this.notice.set('categories.updated');
      }
      this.dialog.set(null);
      await this.load();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  protected askToArchive(category: Category): void {
    this.pending.set({ kind: 'archive', category });
  }

  protected askToDelete(category: Category): void {
    this.pending.set({ kind: 'delete', category });
  }

  protected cancelPending(): void {
    this.pending.set(null);
  }

  protected async confirmPending(): Promise<void> {
    const action = this.pending();
    if (!action) {
      return;
    }
    this.pending.set(null);
    if (action.kind === 'archive') {
      await this.run(
        action.category,
        () => this.service.archive(action.category.id),
        'categories.archived',
      );
    } else {
      await this.run(
        action.category,
        () => this.service.remove(action.category.id),
        'categories.deleted',
      );
    }
  }

  protected async unarchive(category: Category): Promise<void> {
    await this.run(category, () => this.service.unarchive(category.id), 'categories.unarchived');
  }

  /**
   * Runs one row-level request, keeping the failure beside the row it was about.
   *
   * A refused archive must not empty the list or clear the screen: the state is reloaded only on
   * success, and on failure the list is exactly as it was with an explanation naming the category.
   */
  private async run(
    category: Category,
    request: () => Observable<unknown>,
    said: MessageKey,
  ): Promise<void> {
    this.busyId.set(category.id);
    this.actionError.set(null);
    this.actionErrorCategory.set(null);
    this.notice.set(null);
    try {
      await firstValueFrom(request());
      this.notice.set(said);
      await this.load();
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
      this.actionErrorCategory.set(category.name);
    } finally {
      this.busyId.set(null);
    }
  }

  private resetDialogState(): void {
    this.dialogBusy.set(false);
    this.dialogError.set(null);
    this.notice.set(null);
    this.actionError.set(null);
    this.actionErrorCategory.set(null);
  }
}
