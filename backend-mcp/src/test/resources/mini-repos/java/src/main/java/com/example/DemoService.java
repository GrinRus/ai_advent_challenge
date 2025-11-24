package com.example;

import com.example.repository.UserRepository;

/** Demo service doc. */
public class DemoService extends BaseService implements Runner, Runnable {

  private final UserRepository repository = new UserRepository();

  @Override
  public void run() {
    process("run", 1);
  }

  /** Process request with name and count. */
  public String process(String name, int count) {
    repository.findUser(name);
    helper(count);
    return name + count;
  }

  /** Overloaded process with default count. */
  public String process(String name) {
    return process(name, 1);
  }

  private void helper(int count) {
    this.log(count);
  }

  @Override
  protected void log(int value) {
    System.out.println("value=" + value);
  }
}

interface Runner {
  void run();
}

class BaseService {
  protected void baseMethod() {}

  protected void log(int value) {
    System.out.println("base=" + value);
  }
}
