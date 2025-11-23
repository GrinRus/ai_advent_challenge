; obj.method()
(call_expression
  function: (selector_expression
              operand: (_) @call.receiver
              field: (field_identifier) @call.name)) @call.expr

; func()
(call_expression
  function: (identifier) @call.name) @call.expr
