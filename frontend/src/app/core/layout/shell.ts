import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { LoadingService } from '../http/loading.service';

interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly available: boolean;
}

/**
 * The application frame: header, role-aware navigation, and the routed outlet.
 *
 * Wraps every authenticated route, so the header is not re-created on navigation and the
 * progress indicator is not torn down mid-request.
 *
 * The menu hides what a role cannot use. That is a courtesy and nothing more — the API
 * authorises every request independently (NFR-2.2), so this is about not offering someone
 * a screen that would only fill with 403s.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly auth = inject(AuthService);
  private readonly loading = inject(LoadingService);

  readonly currentUser = this.auth.currentUser;
  readonly isLoading = this.loading.isLoading;

  /** Drives the mobile disclosure; ignored from the `sm` breakpoint up. */
  readonly navExpanded = signal(false);

  readonly navItems = computed<NavItem[]>(() => [
    {
      path: '/employees',
      label: 'Employees',
      available: this.auth.canManageEmployees(),
    },
  ]);

  readonly visibleNavItems = computed(() => this.navItems().filter((item) => item.available));

  toggleNav(): void {
    this.navExpanded.update((expanded) => !expanded);
  }

  collapseNav(): void {
    this.navExpanded.set(false);
  }

  signOut(): void {
    this.auth.logout();
  }
}
