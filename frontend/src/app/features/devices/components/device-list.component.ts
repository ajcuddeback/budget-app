import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { DateTimePipe } from '../../../core/i18n/format.pipes';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { Device } from '../data/device.models';
import { summariseUserAgent } from '../data/device-label';

/**
 * The caller's own signed-in browsers and phones. Presentational: it lists them and reports which
 * one the person asked to sign out.
 */
@Component({
  selector: 'app-device-list',
  imports: [TranslatePipe, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './device-list.component.html',
})
export class DeviceListComponent {
  readonly devices = input.required<readonly Device[]>();
  readonly busyId = input<string | null>(null);

  readonly revokeRequested = output<Device>();

  protected kindKey(device: Device): MessageKey {
    return device.kind === 'SESSION' ? 'devices.kindSession' : 'devices.kindApp';
  }

  protected actionKey(device: Device): MessageKey {
    return device.current ? 'devices.signOutHere' : 'devices.signOut';
  }

  protected summary(device: Device): ReturnType<typeof summariseUserAgent> {
    return device.kind === 'SESSION' ? summariseUserAgent(device.label) : null;
  }
}
