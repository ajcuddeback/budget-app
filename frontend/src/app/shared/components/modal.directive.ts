import { Directive, ElementRef, OnDestroy, afterNextRender, inject, output } from '@angular/core';

/**
 * Turns a `<dialog>` into a modal for as long as it exists.
 *
 * The native element does the hard parts correctly — focus is moved in and trapped, the page
 * behind is inert, Escape closes it, and focus returns to the control that opened it. This only
 * opens it, reports that the person dismissed it (Escape), and closes it on destroy so that the
 * return of focus still happens when the owner removes it with `@if`.
 */
@Directive({
  selector: 'dialog[appModal]',
  host: { '(close)': 'onClose()' },
})
export class ModalDirective implements OnDestroy {
  private readonly dialog = inject<ElementRef<HTMLDialogElement>>(ElementRef).nativeElement;
  private destroyed = false;

  /** The dialog closed without the owner asking it to — Escape. */
  readonly dismissed = output<void>();

  constructor() {
    afterNextRender(() => {
      if (typeof this.dialog.showModal === 'function') {
        this.dialog.showModal();
      } else {
        // An environment without the dialog API (a test DOM) still gets an open dialog.
        this.dialog.setAttribute('open', '');
      }
    });
  }

  protected onClose(): void {
    if (!this.destroyed) {
      this.dismissed.emit();
    }
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.dialog.open && typeof this.dialog.close === 'function') {
      this.dialog.close();
    }
  }
}
