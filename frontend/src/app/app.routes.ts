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
            // Before `:id`, and it has to stay there: routes match in order, so declared
            // after it this would resolve as the detail screen for an employee called
            // "new".
            path: 'new',
            title: 'Add employee · ACME Salary Management',
            loadComponent: () =>
              import('./features/employees/employee-form/employee-form').then(
                (m) => m.EmployeeForm,
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
            // Two segments, so there is no ambiguity with `:id` above. The same component
            // as `new`: the two screens differ by two fields and a verb.
            path: ':id/edit',
            title: 'Edit employee · ACME Salary Management',
            loadComponent: () =>
              import('./features/employees/employee-form/employee-form').then(
                (m) => m.EmployeeForm,
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
        // Before `payslips/:id`: routes match in order, so declared after it this would
        // resolve as the payslip view for a payslip called "all".
        //
        // ADMIN/HR only. Every other role reaches their own payslips through `/payslips`,
        // and the API narrows this query to the caller's own rows regardless — the guard
        // exists so an employee is not offered a screen of filters that can only ever
        // show them what they already have.
        path: 'payslips/all',
        title: 'All payslips · ACME Salary Management',
        canActivate: [roleGuard('ADMIN', 'HR')],
        loadComponent: () =>
          import('./features/payslips/payslip-register/payslip-register').then(
            (m) => m.PayslipRegister,
          ),
      },
      {
        path: 'payslips/:id',
        title: 'Payslip · ACME Salary Management',
        loadComponent: () =>
          import('./features/payslips/payslip-view/payslip-view').then((m) => m.PayslipView),
      },
      {
        // ADMIN/HR throughout, matching the endpoints: a draft run holds every salary in
        // the organisation, so none of this is reachable by an employee.
        path: 'payroll-runs',
        canActivate: [roleGuard('ADMIN', 'HR')],
        children: [
          {
            path: '',
            title: 'Payroll runs · ACME Salary Management',
            loadComponent: () =>
              import('./features/payroll/run-list/run-list').then((m) => m.RunList),
          },
          {
            // Before `:id`, as with employees: routes match in order.
            path: 'new',
            title: 'Run payroll · ACME Salary Management',
            loadComponent: () =>
              import('./features/payroll/start-run/start-run').then((m) => m.StartRun),
          },
          {
            path: ':id',
            title: 'Payroll run · ACME Salary Management',
            loadComponent: () =>
              import('./features/payroll/run-review/run-review').then((m) => m.RunReview),
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
        // Every authenticated role: changing your own password is not a privilege, and the
        // endpoint behind it takes no user id, so there is nothing here to restrict.
        path: 'change-password',
        title: 'Change password · ACME Salary Management',
        loadComponent: () =>
          import('./features/auth/change-password/change-password').then((m) => m.ChangePassword),
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
