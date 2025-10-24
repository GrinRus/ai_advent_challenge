export type FormControlType =
  | 'text'
  | 'textarea'
  | 'number'
  | 'select'
  | 'multiselect'
  | 'radio'
  | 'checkbox'
  | 'toggle'
  | 'date'
  | 'datetime'
  | 'file'
  | 'json';

export type InteractionFormField = {
  name: string;
  label: string;
  control: FormControlType;
  required: boolean;
  enumValues?: string[];
  schema?: unknown;
};

export type ExtractedInteractionSchema = {
  fields: InteractionFormField[];
  initialValues: Record<string, unknown>;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const extractEnum = (schema: unknown): string[] | undefined => {
  if (!isRecord(schema) || !Array.isArray(schema.enum)) {
    return undefined;
  }
  return schema.enum.filter((item): item is string => typeof item === 'string');
};

const resolveControl = (schema: unknown): FormControlType => {
  if (!isRecord(schema)) {
    return 'json';
  }
  const type = typeof schema.type === 'string' ? schema.type : 'string';
  const format = typeof schema.format === 'string' ? schema.format : undefined;

  if (type === 'string') {
    if (format === 'textarea') return 'textarea';
    if (format === 'date') return 'date';
    if (format === 'date-time') return 'datetime';
    if (format === 'binary') return 'file';
    if (format === 'json') return 'json';
    if (format === 'radio') return 'radio';
    if (Array.isArray(schema.enum)) return 'select';
    return 'text';
  }
  if (type === 'number' || type === 'integer') {
    return 'number';
  }
  if (type === 'boolean') {
    return format === 'toggle' ? 'toggle' : 'checkbox';
  }
  if (type === 'array') {
    const items = schema.items;
    if (isRecord(items) && Array.isArray(items.enum)) {
      return 'multiselect';
    }
    return 'json';
  }
  return 'json';
};

const createLabel = (name: string, schema: unknown): string => {
  if (isRecord(schema) && typeof schema.title === 'string' && schema.title.trim()) {
    return schema.title;
  }
  return name;
};

const deriveInitialValue = (control: FormControlType, payload: unknown): unknown => {
  switch (control) {
    case 'number':
      if (typeof payload === 'number') return String(payload);
      if (typeof payload === 'string') return payload;
      return '';
    case 'checkbox':
    case 'toggle':
      return Boolean(payload);
    case 'multiselect':
      return Array.isArray(payload)
        ? payload.filter((item) => typeof item === 'string')
        : [];
    case 'json':
      return payload ? JSON.stringify(payload, null, 2) : '';
    default:
      return payload ?? '';
  }
};

export const formatValueForField = (
  field: InteractionFormField,
  payload: unknown,
): unknown => deriveInitialValue(field.control, payload);

export const extractInteractionSchema = (
  schema: unknown,
  existingPayload?: unknown,
): ExtractedInteractionSchema => {
  if (!isRecord(schema)) {
    return {
      fields: [
        {
          name: 'value',
          label: 'Значение',
          control: 'json',
          required: false,
        },
      ],
      initialValues: {
        value: existingPayload ? JSON.stringify(existingPayload, null, 2) : '',
      },
    };
  }

  const type = typeof schema.type === 'string' ? schema.type : 'object';
  if (type !== 'object' || !isRecord(schema.properties)) {
    const control = resolveControl(schema);
    const enumValues = extractEnum(schema) ?? (isRecord(schema.items) ? extractEnum(schema.items) : undefined);
    return {
      fields: [
        {
          name: 'value',
          label: createLabel('value', schema),
          control,
          required: Boolean(Array.isArray(schema.required) && schema.required.length),
          enumValues,
          schema,
        },
      ],
      initialValues: {
        value: deriveInitialValue(control, existingPayload),
      },
    };
  }

  const requiredFields = new Set<string>();
  if (Array.isArray(schema.required)) {
    schema.required
      .filter((item): item is string => typeof item === 'string')
      .forEach((item) => requiredFields.add(item));
  }

  const fields: InteractionFormField[] = [];
  const initialValues: Record<string, unknown> = {};
  const properties = schema.properties as Record<string, unknown>;
  for (const [name, definition] of Object.entries(properties)) {
    const control = resolveControl(definition);
    fields.push({
      name,
      label: createLabel(name, definition),
      control,
      required: requiredFields.has(name),
      enumValues: extractEnum(definition),
      schema: definition,
    });
    const value = isRecord(existingPayload) ? existingPayload[name] : undefined;
    initialValues[name] = deriveInitialValue(control, value);
  }

  return { fields, initialValues };
};

const isEmptyValue = (value: unknown): boolean => {
  if (value === null || value === undefined) {
    return true;
  }
  if (typeof value === 'string') {
    return value.trim() === '';
  }
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  return false;
};

const coerceValueForSubmit = (control: FormControlType, value: unknown): unknown => {
  switch (control) {
    case 'number': {
      if (value === '' || value === null || value === undefined) {
        return undefined;
      }
      const parsed = Number(value);
      if (Number.isNaN(parsed)) {
        throw new Error('Введите корректное число');
      }
      return parsed;
    }
    case 'checkbox':
    case 'toggle':
      return Boolean(value);
    case 'multiselect':
      return Array.isArray(value) ? value : [];
    case 'json':
      if (value === '' || value === null || value === undefined) {
        return undefined;
      }
      if (typeof value !== 'string') {
        return value;
      }
      return JSON.parse(value);
    case 'file':
      return value ?? null;
    default:
      return value === '' ? undefined : value;
  }
};

export const buildSubmissionPayload = (
  fields: InteractionFormField[],
  values: Record<string, unknown>,
): Record<string, unknown> | undefined => {
  if (fields.length === 0) {
    return undefined;
  }

  const payload: Record<string, unknown> = {};
  fields.forEach((field) => {
    const value = values[field.name];
    if (isEmptyValue(value)) {
      return;
    }
    payload[field.name] = coerceValueForSubmit(field.control, value);
  });

  if (Object.keys(payload).length === 0) {
    return undefined;
  }

  return payload;
};

export const isRecordLike = isRecord;
export const __test_utils = {
  resolveControl,
  deriveInitialValue,
  coerceValueForSubmit,
  isEmptyValue,
};

export const computeSuggestedActionUpdates = (
  fields: InteractionFormField[],
  rawPayload: unknown,
): { updates: Record<string, unknown>; appliedFields: string[] } => {
  const updates: Record<string, unknown> = {};
  const applied: string[] = [];

  if (rawPayload !== null
      && typeof rawPayload === 'object'
      && !Array.isArray(rawPayload)) {
    const payloadRecord = rawPayload as Record<string, unknown>;
    fields.forEach((field) => {
      if (Object.prototype.hasOwnProperty.call(payloadRecord, field.name)) {
        updates[field.name] = formatValueForField(field, payloadRecord[field.name]);
        applied.push(field.name);
      }
    });
    return { updates, appliedFields: applied };
  }

  if (fields.length === 1) {
    const field = fields[0];
    updates[field.name] = formatValueForField(field, rawPayload);
    applied.push(field.name);
  }

  return { updates, appliedFields: applied };
};
