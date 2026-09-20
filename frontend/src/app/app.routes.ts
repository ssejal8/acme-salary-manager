import { Routes } from '@angular/router';
import { authGuard, landingRedirect, roleGuard } from './core/auth/auth.guard';

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
        // Role-aware: see landingRedirect for why a fixed target was a dead end.
        redirectTo: landingRedirect,
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
        // Every authenticated role, with no roleGuard: "my own data" is not a privilege
        // to withhold, and an HR user on the payroll has payslips of their own. Which
        // rows each caller may have is the API's decision, enforced per record.
        path: 'payslips',
        title: 'My payslips · ACME Salary Management',
        loadComponent: () =>
          import('./features/payslips/my-payslips/my-payslips').then((m) => m.MyPayslips),
      },
      {
        path: 'payslips/:id',
        title: 'Payslip · ACME Salary Management',
        loadComponent: () =>
          import('./features/payslips/payslip-view/payslip-view').then((m) => m.PayslipView),
      },
      {
        // ADMIN/HR, matching the endpoint: a draft run holds every salary in the
        // organisation, so this is not a screen to leave reachable by an employee.
        //
        // `payroll-runs/new` rather than `payroll-runs`, because starting a run is not
        // the collection: the list and the review screen belong on the paths the API
        // already uses for them, and taking the bare path for a form would mean moving it
        // later.
        path: 'payroll-runs/new',
        title: 'Run payroll · ACME Salary Management',
        canActivate: [roleGuard('ADMIN', 'HR')],
        loadComponent: () =>
          import('./features/payroll/start-run/start-run').then((m) => m.StartRun),
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
