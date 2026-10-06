import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { DeviceListComponent } from '../components/device-list.component';
import { Device } from '../data/device.models';
import { DevicesService } from '../data/devices.service';

/**
 * Where am I signed in, and make one of them stop. Revocation is immediate (ADR-0018) — the reason
 * mobile tokens are opaque and server-side rather than self-contained.
 */
@Component({
  selector: 'app-devices-page',
  imports: [TranslatePipe, ErrorAlertComponent, DeviceListComponent, ConfirmDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './devices.page.html',
})
export class DevicesPage {
  private readonly service = inject(DevicesService);
  private readonly auth = inject(AuthService);
  protected readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly devices = signal<readonly Device[]>([]);

  protected readonly busyId = signal<string | null>(null);
  protected readonly revokeError = signal<ApiError | null>(null);
  protected readonly revokedNotice = signal(false);
  protected readonly confirmingCurrent = signal<Device | null>(null);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.devices.set(await firstValueFrom(this.service.list()));
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    } finally {
      this.loading.set(false);
    }
  }

  protected requestRevoke(device: Device): void {
    this.revokeError.set(null);
    this.revokedNotice.set(false);
    if (device.current) {
      // Revoking the session you are using signs you out here: say so first.
      this.confirmingCurrent.set(device);
    } else {
      void this.revoke(device);
    }
  }

  protected cancelConfirm(): void {
    this.confirmingCurrent.set(null);
  }

  protected async revoke(device: Device): Promise<void> {
    this.busyId.set(device.id);
    try {
      await firstValueFrom(this.service.revoke(device.id));
      this.confirmingCurrent.set(null);
      if (device.current) {
        await this.auth.sessionEnded();
        return;
      }
      this.devices.update((all) => all.filter((d) => d.id !== device.id));
      this.revokedNotice.set(true);
    } catch (thrown) {
      this.confirmingCurrent.set(null);
      this.revokeError.set(asApiError(thrown));
    } finally {
      this.busyId.set(null);
    }
  }
}
