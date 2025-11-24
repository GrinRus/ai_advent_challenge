import { UserRepository } from "./repository.js";

class BaseService {
  process(name, count) {
    throw new Error("not implemented");
  }

  log(value) {
    console.log("base", value);
  }
}

export class DemoService extends BaseService {
  constructor() {
    super();
    this.repository = new UserRepository();
  }

  /** Runs the JS demo service. */
  run() {
    return this.process("js", 2);
  }

  process(name, count) {
    this.log(count);
    this.repository.save(name);
    helper(count);
    return `${name}-${count}`;
  }
}

export function helper(count) {
  console.log("count", count);
}
