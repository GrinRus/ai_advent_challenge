import { z } from 'zod';

export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | JsonObject;

export type JsonObject = { [key: string]: JsonValue };

const isJsonValue = (value: unknown): value is JsonValue => {
  if (value === null) {
    return true;
  }
  const type = typeof value;
  if (type === 'string' || type === 'number' || type === 'boolean') {
    return true;
  }
  if (Array.isArray(value)) {
    return value.every(isJsonValue);
  }
  if (type === 'object') {
    return Object.values(value as Record<string, unknown>).every(isJsonValue);
  }
  return false;
};

const isJsonObject = (value: unknown): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value) && isJsonValue(value);

export const JsonValueSchema = z.custom<JsonValue>(isJsonValue, {
  message: 'Значение должно быть корректным JSON',
});

export const JsonObjectSchema = z.custom<JsonObject>(isJsonObject, {
  message: 'Объект должен быть корректным JSON',
});
