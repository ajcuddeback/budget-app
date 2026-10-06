import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { SetupFormComponent } from '../components/setup-form.component';
import { FirstUserRequest } from '../../../core/setup.models';
import { SetupService } from '../../../core/setup.service';

/**
 * First run: create the first account, which owns a new household and becomes the operator of this
 * instance (ADR-0026). Reachable only while the instance has no users.
 */
@Component({
  selector: 'app-setup-page',
  imports: [SetupFormComponent, ErrorAlertComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './setup.page.html',
})
export class SetupPage {
  private readonly setup = inject(SetupService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected async create(request: FirstUserRequest): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.setup.createFirstUser(request));
    } catch (thrown) {
      this.error.set(asApiError(thrown));
      this.busy.set(false);
      return;
    }
    // Setup creates the account and hands out no credential (the server's choice). Signing in
    // with the credentials just entered is the same act the person would otherwise repeat on the
    // next screen.
    try {
      await this.auth.login({ email: request.email, password: request.password });
      await this.router.navigate(['/']);
    } catch {
      await this.router.navigate(['/login'], { queryParams: { reason: 'created' } });
    } finally {
      this.busy.set(false);
    }
  }
}
