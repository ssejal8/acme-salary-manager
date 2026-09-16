import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { Role } from './auth.models';
import { authGuard, roleGuard } from './auth.guard';
import { AuthService } from './auth.service';

interface AuthStub {
  isAuthenticated: boolean;
  expired: boolean;
  role: Role | null;
}

describe('route guards', () => {
  let stub: AuthStub;
  let logout: ReturnType<typeof vi.fn>;
  let createUrlTree: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    stub = { isAuthenticated: false, expired: false, role: null };
    logout = vi.fn();
    // A recognisable stand-in, so a test can assert which tree was produced.
    createUrlTree = vi.fn((commands: unknown[], options?: { queryParams?: unknown }) => ({
      commands,
      queryParams: options?.queryParams,
    }));

    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => stub.isAuthenticated,
            isAccessTokenExpired: () => stub.expired,
            hasAnyRole: (...roles: Role[]) => stub.role !== null && roles.includes(stub.role),
            logout,
          },
        },
        { provide: Router, useValue: { createUrlTree } },
      ],
    });
  });

  function runAuthGuard(url = '/employees'): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  function runRoleGuard(...allowed: Role[]): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      roleGuard(...allowed)({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  describe('authGuard', () => {
    it('admits a signed-in user with a live token', () => {
      stub = { isAuthenticated: true, expired: false, role: 'HR' };

      expect(runAuthGuard()).toBe(true);
      expect(createUrlTree).not.toHaveBeenCalled();
    });

    it('sends a visitor to the login screen', () => {
      expect(runAuthGuard()).not.toBe(true);
      expect(createUrlTree).toHaveBeenCalledWith(['/login'], {
        queryParams: { redirectTo: '/employees' },
      });
    });

    it('remembers where the visitor was going, so a pasted link still works', () => {
      runAuthGuard('/employees/1001');

      expect(createUrlTree).toHaveBeenCalledWith(['/login'], {
        queryParams: { redirectTo: '/employees/1001' },
      });
    });

    it('treats an expired session as a timeout and says so', () => {
      // The difference between "you were signed out" and an unexplained login screen.
      stub = { isAuthenticated: true, expired: true, role: 'HR' };

      expect(runAuthGuard()).toBe(false);
      expect(logout).toHaveBeenCalledWith({ expired: true });
    });

    it('does not sign out a visitor who was never signed in', () => {
      runAuthGuard();

      expect(logout).not.toHaveBeenCalled();
    });
  });

  describe('roleGuard', () => {
    it('admits a role on the list', () => {
      stub = { isAuthenticated: true, expired: false, role: 'HR' };

      expect(runRoleGuard('ADMIN', 'HR')).toBe(true);
    });

    it('turns away a role that is not', () => {
      stub = { isAuthenticated: true, expired: false, role: 'EMPLOYEE' };

      expect(runRoleGuard('ADMIN', 'HR')).not.toBe(true);
    });

    it('sends them to the no-access page, not back to sign in', () => {
      // They are signed in. Offering the login form would suggest a different password
      // would help, when the answer is that this role does not have access.
      stub = { isAuthenticated: true, expired: false, role: 'EMPLOYEE' };

      runRoleGuard('ADMIN', 'HR');

      expect(createUrlTree).toHaveBeenCalledWith(['/not-authorised']);
    });
  });
});
