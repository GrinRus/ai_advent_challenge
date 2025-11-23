; Instance method calls: obj.method(...)
(method_invocation
  object: (_) @call.receiver
  name: (identifier) @call.name) @call.expr

; Static / unqualified method calls: func()
(method_invocation
  name: (identifier) @call.name) @call.expr

; Constructor calls: new Foo(...)
(object_creation_expression
  type: (type_identifier) @call.name) @call.expr
