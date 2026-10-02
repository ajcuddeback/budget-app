import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, Validators } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { apiError, settle } from '../../testing/helpers';
import { ConfirmDialogComponent } from './confirm-dialog.component';
import { CurrencyFieldComponent } from './currency-field.component';
import { ErrorAlertComponent } from './error-alert.component';
import { FieldErrorComponent } from './field-error.component';
import { ModalDirective } from './modal.directive';
import { NoHouseholdComponent } from './no-household.component';

const providers = [provideHttpClient(), provideHttpClientTesting()];

describe('FieldErrorComponent', () => {
  function render(control: FormControl) {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(FieldErrorComponent);
    fixture.componentRef.setInput('control', control);
    fixture.componentRef.setInput('errorId', 'x-error');
    return fixture;
  }

  it('is silent until the field has been touched', async () => {
    const control = new FormControl('', Validators.required);
    const fixture = render(control);
    await settle(fixture);
    expect(fixture.nativeElement.textContent.trim()).toBe('');

    control.markAsTouched();
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('This field is required.');
    expect(fixture.nativeElement.querySelector('#x-error')).not.toBeNull();
  });

  it.each([
    [[Validators.email], 'nope', 'Enter a valid email address.'],
    [[Validators.minLength(12)], 'short', 'Use at least 12 characters.'],
    [[Validators.maxLength(3)], 'toolong', 'Use at most 3 characters.'],
  ])('says what is wrong with %#', async (validators, value, sentence) => {
    const control = new FormControl(value, validators);
    control.markAsDirty();
    const fixture = render(control);
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain(sentence);
  });

  it('explains a byte limit, a currency, and a server refusal', async () => {
    const control = new FormControl('x');
    control.markAsTouched();
    const fixture = render(control);

    control.setErrors({ maxBytes: { max: 72 } });
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('at most 72 bytes');

    control.setErrors({ currency: true });
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('three-letter currency code');

    control.setErrors({ server: true });
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('was not accepted');

    control.setErrors(null);
    await settle(fixture);
    expect(fixture.nativeElement.textContent.trim()).toBe('');
  });
});

describe('ErrorAlertComponent', () => {
  it('renders nothing without an error, and a sentence with one', async () => {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(ErrorAlertComponent);
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();

    fixture.componentRef.setInput('error', apiError(401, 'authentication-failed'));
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain(
      'The email address or password is not right.',
    );
  });

  it('shows the reference for a server fault only', async () => {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(ErrorAlertComponent);

    fixture.componentRef.setInput('error', apiError(500, 'internal-error'));
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('Reference: corr-1');

    fixture.componentRef.setInput('error', apiError(403, 'owner-only'));
    await settle(fixture);
    expect(fixture.nativeElement.textContent).not.toContain('Reference');
  });
});

describe('CurrencyFieldComponent', () => {
  it('lists currencies by code and name, with an optional "follow the default" first', async () => {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(CurrencyFieldComponent);
    fixture.componentRef.setInput('control', new FormControl('EUR', { nonNullable: true }));
    fixture.componentRef.setInput('fieldId', 'cur');
    fixture.componentRef.setInput('label', 'Currency');
    fixture.componentRef.setInput('hint', 'A hint');
    fixture.componentRef.setInput('emptyLabel', 'Follow the household');
    await settle(fixture);

    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    expect(fixture.nativeElement.querySelector('label').getAttribute('for')).toBe('cur');
    expect(select.options[0].textContent).toBe('Follow the household');
    expect(Array.from(select.options).some((o) => /USD — US Dollar/.test(o.textContent ?? ''))).toBe(true);
    expect(select.value).toBe('EUR');
    expect(fixture.nativeElement.textContent).toContain('A hint');
  });

  it('keeps a currency the browser does not know, rather than silently changing it', async () => {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(CurrencyFieldComponent);
    fixture.componentRef.setInput('control', new FormControl('ZZZ', { nonNullable: true }));
    fixture.componentRef.setInput('fieldId', 'cur');
    fixture.componentRef.setInput('label', 'Currency');
    await settle(fixture);

    expect((fixture.nativeElement.querySelector('select') as HTMLSelectElement).options[0].value).toBe('ZZZ');
  });
});

describe('NoHouseholdComponent', () => {
  it('explains that an invitation is the way in, without calling it an error', async () => {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(NoHouseholdComponent);
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('You are not part of a household yet');
    expect(fixture.nativeElement.textContent).toContain('invitation link');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
  });
});

describe('ConfirmDialogComponent and ModalDirective', () => {
  function render() {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(ConfirmDialogComponent);
    fixture.componentRef.setInput('heading', 'Remove Sam?');
    fixture.componentRef.setInput('message', 'They lose access.');
    fixture.componentRef.setInput('confirmLabel', 'Remove');
    return fixture;
  }

  it('is labelled by its heading and opens as a modal', async () => {
    const fixture = render();
    await settle(fixture);
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;

    expect(dialog.getAttribute('aria-labelledby')).toBe('confirm-title');
    expect(fixture.nativeElement.querySelector('#confirm-title').textContent).toBe('Remove Sam?');
    expect(dialog.hasAttribute('open')).toBe(true);
  });

  it('reports a confirmation and a cancellation', async () => {
    const fixture = render();
    const confirmed = vi.fn();
    const cancelled = vi.fn();
    fixture.componentInstance.confirmed.subscribe(confirmed);
    fixture.componentInstance.cancelled.subscribe(cancelled);
    await settle(fixture);
    const [cancel, confirm] = Array.from<HTMLButtonElement>(fixture.nativeElement.querySelectorAll('button'));

    cancel.click();
    confirm.click();

    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(confirmed).toHaveBeenCalledTimes(1);
  });

  it('disables the destructive button while the request is in flight', async () => {
    const fixture = render();
    fixture.componentRef.setInput('busy', true);
    await settle(fixture);

    expect(fixture.nativeElement.querySelectorAll('button')[1].disabled).toBe(true);
  });

  it('reports Escape as a dismissal, and not its own closing on destroy', async () => {
    const fixture = render();
    const cancelled = vi.fn();
    fixture.componentInstance.cancelled.subscribe(cancelled);
    await settle(fixture);

    fixture.nativeElement.querySelector('dialog').dispatchEvent(new Event('close'));
    expect(cancelled).toHaveBeenCalledTimes(1);

    fixture.destroy();
    await new Promise((resolve) => setTimeout(resolve));
    expect(cancelled).toHaveBeenCalledTimes(1);
  });

  it('uses the native modal when the browser has one, and closes it when removed', async () => {
    const show = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    const close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { value: show, configurable: true });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { value: close, configurable: true });
    try {
      const fixture = render();
      await settle(fixture);
      expect(show).toHaveBeenCalled();

      fixture.destroy();
      expect(close).toHaveBeenCalled();
    } finally {
      delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>)['showModal'];
      delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>)['close'];
    }
  });
});

@Component({ imports: [ModalDirective], template: `<dialog appModal (dismissed)="n.set(n() + 1)"></dialog>` })
class Host {
  readonly n = signal(0);
}

describe('ModalDirective on its own', () => {
  it('can be hosted by anything', async () => {
    const fixture = TestBed.createComponent(Host);
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('dialog').hasAttribute('open')).toBe(true);
  });
});
