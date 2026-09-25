import { Component, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/theme.service';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly theme = inject(ThemeService);

  protected readonly mode = this.theme.mode;
  protected readonly nextModeLabel = computed(() =>
    this.theme.mode() === 'dark' ? 'Switch to light theme' : 'Switch to dark theme',
  );

  protected toggleTheme(): void {
    this.theme.toggle();
  }
}
