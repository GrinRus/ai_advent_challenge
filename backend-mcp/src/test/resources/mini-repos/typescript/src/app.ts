export interface Runner { run(): void; }

export class DemoService implements Runner {
  public run(): void {
    this.process("demo", 3);
  }

  process(name: string, count: number): string {
    helper(count);
    return `${name}-${count}`;
  }
}

export function helper(count: number): void {
  console.log("count", count);
}
