import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The bootstrap component: nothing but the router's outlet.
 *
 * Deliberately empty of chrome. The header and navigation belong to `Shell`, which is a
 * routed component wrapping the authenticated routes — putting them here instead would
 * wrap the login screen in a menu the visitor cannot use.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {}
