export interface Runner { run(): void; }

export abstract class BaseService {
  abstract process(name: string, count: number): string;
  protected log(value: number): void {
    console.log("base", value);
  }
}

export class DemoService extends BaseService implements Runner {
  /** Runs the demo service. */
  public run(): void {
    this.process("demo", 3);
  }

  /** Processes a request with count. */
  process(name: string, count: number): string {
    this.log(count);
    helper(count);
    return `${name}-${count}`;
  }

  processOnce(name: string): string {
    return this.process(name, 1);
  }

  protected log(value: number): void {
    console.log("value", value);
  }
}

export function helper(count: number): void {
  console.log("count", count);
}
