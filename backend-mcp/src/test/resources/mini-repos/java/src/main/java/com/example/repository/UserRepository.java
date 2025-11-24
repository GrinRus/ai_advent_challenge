package com.example.repository;

/** Base contract for repositories. */
public interface Repository<T> {
  T findUser(String name);
}

/** Simple in-memory repository used in mini-repo fixtures. */
public class UserRepository implements Repository<String> {

  /** Finds a pseudo user and prints a log line. */
  @Override
  public String findUser(String name) {
    recordAccess(name);
    return name == null ? "" : name.toUpperCase();
  }

  private void recordAccess(String name) {
    if (name != null && !name.isEmpty()) {
      System.out.println("access " + name);
    }
  }
}
