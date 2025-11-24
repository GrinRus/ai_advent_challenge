export interface Repository<T> {
  find(name: string): T;
}

/** Stores demo entities in memory. */
export class UserRepository implements Repository<string> {
  private last: string | undefined;

  find(name: string): string {
    this.last = name;
    return name.toUpperCase();
  }

  lastValue(): string | undefined {
    return this.last;
  }
}
