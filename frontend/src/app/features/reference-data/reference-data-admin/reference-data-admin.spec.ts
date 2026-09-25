import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import {
  Department,
  Designation,
  Grade,
  ReferenceData,
  SaveGradeRequest,
} from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { ReferenceDataAdmin } from './reference-data-admin';

const DATA: ReferenceData = {
  departments: [
    { id: 1, code: 'ENG', name: 'Engineering' },
    { id: 2, code: 'FIN', name: 'Finance' },
  ],
  designations: [{ id: 20, title: 'Software Engineer' }],
  grades: [
    { id: 30, name: 'G2', minCtc: '800000.00', maxCtc: '1500000.00' },
    { id: 31, name: 'G4', minCtc: '2500000.00' },
  ],
};

describe('ReferenceDataAdmin', () => {
  let fixture: ComponentFixture<ReferenceDataAdmin>;
  let component: ReferenceDataAdmin;

  let loads: number;
  let createdDepartments: { code: string; name: string }[];
  let renamedDepartments: { id: number; name: string }[];
  let createdDesignations: string[];
  let retitledDesignations: { id: number; title: string }[];
  let createdGrades: SaveGradeRequest[];
  let updatedGrades: { id: number; request: SaveGradeRequest }[];

  let allResult: () => Observable<ReferenceData>;
  let writeResult: () => Observable<unknown>;

  beforeEach(() => {
    loads = 0;
    createdDepartments = [];
    renamedDepartments = [];
    createdDesignations = [];
    retitledDesignations = [];
    createdGrades = [];
    updatedGrades = [];
    allResult = () => of(DATA);
    writeResult = () => of({});

    TestBed.configureTestingModule({
      providers: [
        {
          provide: ReferenceDataService,
          useValue: {
            all: () => {
              loads += 1;
              return allResult();
            },
            createDepartment: (request: { code: string; name: string }) => {
              createdDepartments.push(request);
              return writeResult() as Observable<Department>;
            },
            renameDepartment: (id: number, name: string) => {
              renamedDepartments.push({ id, name });
              return writeResult() as Observable<Department>;
            },
            createDesignation: (request: { title: string }) => {
              createdDesignations.push(request.title);
              return writeResult() as Observable<Designation>;
            },
            retitleDesignation: (id: number, request: { title: string }) => {
              retitledDesignations.push({ id, title: request.title });
              return writeResult() as Observable<Designation>;
            },
            createGrade: (request: SaveGradeRequest) => {
              createdGrades.push(request);
              return writeResult() as Observable<Grade>;
            },
            updateGrade: (id: number, request: SaveGradeRequest) => {
              updatedGrades.push({ id, request });
              return writeResult() as Observable<Grade>;
            },
          },
        },
      ],
    });
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(ReferenceDataAdmin);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('shows all three lists', async () => {
    await createComponent();

    expect(text()).toContain('Engineering');
    expect(text()).toContain('Software Engineer');
    expect(text()).toContain('G2');
  });

  it('says plainly that nothing can be deleted', async () => {
    // Employees reference these rows, so a delete button would usually fail.
    await createComponent();

    expect(text()).toContain('Nothing here can be deleted');
  });

  describe('departments', () => {
    it('adds one with its code', async () => {
      await createComponent();
      component.departmentForm.setValue({ code: ' ops ', name: ' Operations ' });

      component.submitDepartment();
      await settle();

      expect(createdDepartments).toEqual([{ code: 'ops', name: 'Operations' }]);
    });

    it('sends only the name when renaming, because the code is immutable', async () => {
      await createComponent();

      component.editDepartment(DATA.departments[0]);
      component.departmentForm.patchValue({ name: 'Engineering & QA' });
      component.submitDepartment();
      await settle();

      expect(renamedDepartments).toEqual([{ id: 1, name: 'Engineering & QA' }]);
      expect(createdDepartments).toEqual([]);
    });

    it('disables the code box while renaming rather than pretending it is editable', async () => {
      await createComponent();

      component.editDepartment(DATA.departments[0]);
      await settle();

      expect(component.departmentForm.controls.code.disabled).toBe(true);
      expect(component.isEditingDepartment()).toBe(true);
    });

    it('does not submit without a name', async () => {
      await createComponent();
      component.departmentForm.setValue({ code: 'OPS', name: '' });

      component.submitDepartment();
      await settle();

      expect(createdDepartments).toEqual([]);
    });
  });

  describe('designations', () => {
    it('adds one', async () => {
      await createComponent();
      component.designationForm.setValue({ title: ' QA Engineer ' });

      component.submitDesignation();
      await settle();

      expect(createdDesignations).toEqual(['QA Engineer']);
    });

    it('retitles the row being edited', async () => {
      await createComponent();

      component.editDesignation(DATA.designations[0]);
      component.designationForm.setValue({ title: 'Engineer' });
      component.submitDesignation();
      await settle();

      expect(retitledDesignations).toEqual([{ id: 20, title: 'Engineer' }]);
    });
  });

  describe('grades', () => {
    it('adds one with both bounds', async () => {
      await createComponent();
      component.gradeForm.setValue({ name: 'G5', minCtc: '4000000', maxCtc: '6000000' });

      component.submitGrade();
      await settle();

      expect(createdGrades).toEqual([
        { name: 'G5', minCtc: '4000000', maxCtc: '6000000' },
      ]);
    });

    it('sends a blank bound as null, not as zero', async () => {
      // FR-4.3: an absent bound is unbounded, so a top grade never rejects a package.
      await createComponent();
      component.gradeForm.setValue({ name: 'G6', minCtc: '6000000', maxCtc: '  ' });

      component.submitGrade();
      await settle();

      expect(createdGrades[0].maxCtc).toBeNull();
      expect(createdGrades[0].minCtc).toBe('6000000');
    });

    it('seeds the form from the grade being amended, blanks included', async () => {
      await createComponent();

      // G4 has no maximum at all.
      component.editGrade(DATA.grades[1]);
      await settle();

      expect(component.gradeForm.getRawValue()).toEqual({
        name: 'G4',
        minCtc: '2500000.00',
        maxCtc: '',
      });
    });

    it('amends the row being edited', async () => {
      await createComponent();

      component.editGrade(DATA.grades[0]);
      component.gradeForm.patchValue({ maxCtc: '1600000' });
      component.submitGrade();
      await settle();

      expect(updatedGrades).toEqual([
        { id: 30, request: { name: 'G2', minCtc: '800000.00', maxCtc: '1600000' } },
      ]);
    });

    it('shows an absent bound as "no maximum" rather than as zero', async () => {
      await createComponent();

      expect(text()).toContain('No maximum');
    });
  });

  it('refetches after a write rather than splicing the row in locally', async () => {
    // The server normalises what it stores — an uppercased code, a band at two decimals —
    // so the list it returns is the truth.
    await createComponent();
    expect(loads).toBe(1);

    component.designationForm.setValue({ title: 'QA Engineer' });
    component.submitDesignation();
    await settle();

    expect(loads).toBe(2);
    expect(component.outcome()).toBe('Designation added.');
  });

  it("shows the server's field error against the control it names", async () => {
    writeResult = () =>
      throwError(
        () =>
          new ApiFailure(409, 'A department with that code already exists', [
            { field: 'code', message: 'a department with code ENG already exists' },
          ]),
      );
    await createComponent();
    component.departmentForm.setValue({ code: 'ENG', name: 'Engineering' });

    component.submitDepartment();
    await settle();

    expect(component.fieldError('code')).toContain('already exists');
    expect(text()).toContain('already exists');
  });

  it("shows the server's band rule when a maximum sits below a minimum", async () => {
    // The rule lives in the entity, so the message comes from there rather than being
    // duplicated in the browser.
    writeResult = () =>
      throwError(
        () =>
          new ApiFailure(400, 'Validation failed', [
            { field: 'maxCtc', message: 'must not be less than minCtc' },
          ]),
      );
    await createComponent();
    component.gradeForm.setValue({ name: 'G7', minCtc: '6000000', maxCtc: '1000000' });

    component.submitGrade();
    await settle();

    expect(component.fieldError('maxCtc')).toBe('must not be less than minCtc');
  });

  it('leaves editing mode and clears the forms on cancel', async () => {
    await createComponent();
    component.editGrade(DATA.grades[0]);
    await settle();

    component.reset();
    await settle();

    expect(component.isEditingGrade()).toBe(false);
    expect(component.gradeForm.getRawValue()).toEqual({ name: '', minCtc: '', maxCtc: '' });
  });

  it("shows the server's message when the lists cannot be loaded", async () => {
    allResult = () => throwError(() => new ApiFailure(403, 'Not permitted'));
    await createComponent();

    expect(component.loadError()).toBe('Not permitted');
    expect(text()).toContain('Not permitted');
  });

  it('ignores a second submit while one is in flight', async () => {
    writeResult = () => new Observable<unknown>(() => undefined);
    await createComponent();
    component.designationForm.setValue({ title: 'QA Engineer' });

    component.submitDesignation();
    component.submitDesignation();

    expect(createdDesignations).toHaveLength(1);
  });
});
