package com.example

/**
 * Stores users in memory. We only need it to produce AST edges.
 */
class UserRepository {
  private val values = mutableListOf<String>()

  fun save(name: String) {
    values.add(name)
  }

  fun findAll(): List<String> = values.toList()
}
