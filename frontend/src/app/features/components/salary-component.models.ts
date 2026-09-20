import { CalculationType, ComponentType } from '../structures/salary-structure.models';

/**
 * Salary component definitions — the vocabulary a package is built from.
 *
 * Reference data that changes what payroll computes for everyone, which is why only ADMIN
 * may define one while HR may read them (FR-3.4).
 */

/** The code of the one component every package must contain, with a positive amount. */
export const BASIC_CODE = 'BASIC';

/**
 * @property value the default offered when the component is added to a package — a monthly
 *     amount for `FLAT`, a percentage for `PERCENT_OF_BASIC`. A string, like all money
 *     (ADR-006).
 * @property active a retired component stays valid on the historical packages that already
 *     use it, but cannot be added to a new one (ADR-014).
 */
export interface SalaryComponentDefinition {
  id: number;
  code: string;
  name: string;
  type: ComponentType;
  calculationType: CalculationType;
  value: string;
  taxable: boolean;
  active: boolean;
}

/** Request body for `POST /salary-components` (ADMIN only). */
export interface CreateSalaryComponentRequest {
  code: string;
  name: string;
  type: ComponentType;
  calculationType: CalculationType;
  value: string;
  taxable: boolean;
}

export const COMPONENT_TYPES: readonly ComponentType[] = ['EARNING', 'DEDUCTION'];

export const CALCULATION_TYPES: readonly CalculationType[] = ['FLAT', 'PERCENT_OF_BASIC'];

/** How each calculation type reads in a form or a table. */
export function calculationTypeLabel(calculationType: CalculationType): string {
  return calculationType === 'PERCENT_OF_BASIC' ? 'Percentage of basic' : 'Flat amount';
}

export function componentTypeLabel(type: ComponentType): string {
  return type === 'EARNING' ? 'Earning' : 'Deduction';
}
