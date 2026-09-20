import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth/auth.guard';

/**
 * Application routes.
 *
 * Every feature is lazy-loaded, so an EMPLOYEE never downloads the employee-management
 * code. That is a bundle-size decision and explicitly not a security one — the control is
 * the API's authorisation, and code that is not downloaded is not code that is protected
 * (architecture §6.1).
 *
 * The authenticated routes are children of one shell route, so the header and navigation
 * are not rebuilt on every navigation.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in · ACME Salary Management',
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: '',
    loadComponent: () => import('./core/layout/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'employees',
      },
      {
        path: 'employees',
        // The API allows ADMIN and HR here. The guard mirrors that so an EMPLOYEE gets an
        // explanation instead of a screen of 403s.
        canActivate: [roleGuard('ADMIN', 'HR')],
        children: [
          {
            path: '',
            title: 'Employees · ACME Salary Management',
            loadComponent: () =>
              import('./features/employees/employee-list/employee-list').then(
                (m) => m.EmployeeList,
              ),
          },
          {
            path: ':id',
            title: 'Employee · ACME Salary Management',
            loadComponent: () =>
              import('./features/employees/employee-detail/employee-detail').then(
                (m) => m.EmployeeDetail,
              ),
          },
          {
            // Before `:id/...` would be ambiguous — it is not, because the segment after
            // the id is literal. Kept as a sibling rather than a child of the detail route
            // so the form is a full screen rather than something rendered beneath a record.
            path: ':id/salary-structures/new',
            title: 'Assign package · ACME Salary Management',
            loadComponent: () =>
              import('./features/structures/assign-structure/assign-structure').then(
                (m) => m.AssignStructure,
              ),
          },
        ],
      },
      {
        path: 'reports/compensation',
        title: 'Compensation · ACME Salary Management',
        // An aggregate over salaries is not anonymous — a department of one discloses that
        // person's pay exactly — so this is ADMIN/HR with no relaxed variant (NFR-2.7).
        canActivate: [roleGuard('ADMIN', 'HR')],
        loadComponent: () =>
          import('./features/reports/compensation-dashboard/compensation-dashboard').then(
            (m) => m.CompensationDashboard,
          ),
      },
      {
        path: 'salary-components',
        title: 'Salary components · ACME Salary Management',
        canActivate: [roleGuard('ADMIN', 'HR')],
        loadComponent: () =>
          import('./features/components/component-list/component-list').then(
            (m) => m.ComponentList,
          ),
      },
      {
        path: 'not-authorised',
        title: 'No access · ACME Salary Management',
        loadComponent: () =>
          import('./features/errors/not-authorised').then((m) => m.NotAuthorised),
      },
      {
        path: '**',
        title: 'Not found · ACME Salary Management',
        loadComponent: () => import('./features/errors/not-found').then((m) => m.NotFound),
      },
    ],
  },
];
