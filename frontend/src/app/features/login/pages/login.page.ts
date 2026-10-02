import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { LoginRequest } from '../../../core/auth.models';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { LoginFormComponent } from '../components/login-form.component';

const NOTICES: Readonly<Record<string, MessageKey>> = {
  expired: 'login.notice.expired',
  created: 'login.notice.created',
  joined: 'login.notice.joined',
};

@Component({
  selector: 'app-login-page',
  imports: [LoginFormComponent, ErrorAlertComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login.page.html',
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly form = viewChild.required(LoginFormComponent);

  private readonly reason = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => params.get('reason'))),
    { initialValue: null },
  );

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  /** Why the visitor is here, when it is not simply that they have not signed in yet. */
  protected readonly notice = computed<MessageKey | null>(() => {
    const reason = this.reason();
    return reason !== null && Object.hasOwn(NOTICES, reason) ? NOTICES[reason] : null;
  });

  protected async signIn(credentials: LoginRequest): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.auth.login(credentials);
      await this.router.navigate(['/']);
    } catch (thrown) {
      this.error.set(asApiError(thrown));
      this.form().clearPassword();
    } finally {
      this.busy.set(false);
    }
  }
}
