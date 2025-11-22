package com.example;

/** Demo service doc. */
public class DemoService extends BaseService implements Runnable {

  @Override
  public void run() {
    process();
  }

  public String process(String name, int count) {
    helper(count);
    return name + count;
  }

  private void helper(int count) {
    this.log(count);
  }

  private void log(int value) {
    System.out.println("value=" + value);
  }
}

class BaseService {
  protected void baseMethod() {}
}
