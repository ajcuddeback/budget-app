import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { OwnPreferences } from '../../household/data/household.models';
import { HouseholdService } from '../../household/data/household.service';
import { PreferencesFormComponent } from '../components/preferences-form.component';

@Component({
  selector: 'app-preferences-page',
  imports: [TranslatePipe, ErrorAlertComponent, NoHouseholdComponent, PreferencesFormComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './preferences.page.html',
})
export class PreferencesPage {
  private readonly households = inject(HouseholdService);
  protected readonly auth = inject(AuthService);

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected async save(preferences: OwnPreferences): Promise<void> {
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);
    try {
      await firstValueFrom(this.households.updateOwnPreferences(preferences));
      // The profile carries the preference, and re-reading it applies the language straight away.
      await this.auth.refresh();
      this.saved.set(true);
    } catch (thrown) {
      this.error.set(asApiError(thrown));
    } finally {
      this.saving.set(false);
    }
  }
}
