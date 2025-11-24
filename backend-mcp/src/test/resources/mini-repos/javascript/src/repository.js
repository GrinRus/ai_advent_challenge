export class UserRepository {
  constructor() {
    this._last = null;
  }

  /** Saves a user name and returns the uppercase variant. */
  save(name) {
    this._last = name;
    return typeof name === "string" ? name.toUpperCase() : "";
  }

  last() {
    return this._last;
  }
}
