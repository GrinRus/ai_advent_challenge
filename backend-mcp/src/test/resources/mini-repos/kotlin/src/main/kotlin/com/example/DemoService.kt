package com.example

/**
 * Kotlin demo service with inheritance and overloaded methods.
 */
class DemoService(
    private val repository: UserRepository = UserRepository()
) : BaseService(), Runner {

  override fun run() {
    process("kotlin", 2)
  }

  override fun process(name: String, count: Int): String {
    log(count)
    repository.save(name)
    return "$name-$count"
  }

  fun processOnce(name: String): String = process(name, 1)

  override fun log(value: Int) {
    println("value=$value")
  }
}
