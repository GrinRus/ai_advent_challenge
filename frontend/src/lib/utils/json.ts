import { z } from 'zod';

export function parseJsonField<T>(
  label: string,
  value: string,
  schema: z.ZodSchema<T>,
): T | undefined {
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(trimmed);
    return schema.parse(parsed);
  } catch (error) {
    const message =
      error instanceof Error ? error.message : 'некорректный JSON или схема';
    throw new Error(`Поле "${label}" содержит некорректное значение: ${message}`);
  }
}
