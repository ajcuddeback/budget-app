import { Directive, ElementRef, OnDestroy, afterNextRender, inject, output } from '@angular/core';

/**
 * Turns a `<dialog>` into a modal for as long as it exists.
 *
 * The native element does the hard parts correctly — focus is moved in and trapped, the page
 * behind is inert, and Escape closes it. This opens it, reports that the person dismissed it
 * (Escape), and closes it on destroy.
 *
 * **Returning focus to the opener is done here rather than left to the browser.** The native
 * `close()` restores focus only while the dialog is still in the document, and Angular detaches
 * the element before `ngOnDestroy` runs — so a dialog removed by its owner's `@if` (Cancel, Save,
 * a confirmed action) left focus on `<body>`, with the next Tab starting from the skip link.
 * Escape happened to work, because there the browser closes the dialog itself. Remembering the
 * opener and focusing it back makes every way out behave the same (WCAG 2.4.3).
 */
@Directive({
  selector: 'dialog[appModal]',
  host: { '(close)': 'onClose()' },
})
export class ModalDirective implements OnDestroy {
  private readonly dialog = inject<ElementRef<HTMLDialogElement>>(ElementRef).nativeElement;
  private destroyed = false;
  /** Whatever had focus when this opened — the button that opened it, in every real case. */
  private opener: HTMLElement | null = null;

  /** The dialog closed without the owner asking it to — Escape. */
  readonly dismissed = output<void>();

  constructor() {
    afterNextRender(() => {
      const active = this.dialog.ownerDocument.activeElement;
      this.opener = active instanceof HTMLElement ? active : null;
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
    // Harmless when Escape already restored it, and the only thing that does so otherwise. An
    // opener the action has since removed from the page cannot take focus, which is correct.
    if (this.opener?.isConnected) {
      this.opener.focus();
    }
  }
}
