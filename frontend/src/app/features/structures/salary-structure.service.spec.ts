import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SalaryStructureService } from './salary-structure.service';

describe('SalaryStructureService', () => {
  let structures: SalaryStructureService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    structures = TestBed.inject(SalaryStructureService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('requests the history nested under the employee', () => {
    structures.history(1001).subscribe();

    const request = backend.expectOne('/api/v1/employees/1001/salary-structures');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('asks for the history only, not also for the current package', () => {
    // The history response already flags the current revision, so a second request to
    // /current would be two sources that could disagree — and it answers 204 when nothing
    // is assigned, which is another empty-state path for no gain.
    structures.history(1001).subscribe();

    backend.expectOne('/api/v1/employees/1001/salary-structures').flush([]);
    backend.expectNone('/api/v1/employees/1001/salary-structures/current');
  });

  it('passes an empty history through as an empty array', () => {
    // An employee with no package is a coverage gap, not an error.
    let received: unknown;
    structures.history(1010).subscribe((history) => (received = history));

    backend.expectOne('/api/v1/employees/1010/salary-structures').flush([]);

    expect(received).toEqual([]);
  });
});
