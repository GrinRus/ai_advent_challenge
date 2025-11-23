package com.example;

/** Demo service doc. */
public class DemoService extends BaseService implements Runner, Runnable {

  @Override
  public void run() {
    process("run", 1);
  }

  /** Process request with name and count. */
  public String process(String name, int count) {
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
