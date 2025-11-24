package com.example

interface Runner {
  fun run()
}

abstract class BaseService {
  abstract fun process(name: String, count: Int): String

  protected open fun log(value: Int) {
    println("base=$value")
  }
}
