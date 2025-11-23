; obj.method()
(call
  function: (attribute
              object: (_) @call.receiver
              attribute: (identifier) @call.name)) @call.expr

; func()
(call
  function: (identifier) @call.name) @call.expr
