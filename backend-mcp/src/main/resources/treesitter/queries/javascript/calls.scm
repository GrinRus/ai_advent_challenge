; obj.method(...)
(call_expression
  function: (member_expression
              object: (_) @call.receiver
              property: (property_identifier) @call.name)) @call.expr

; func()
(call_expression
  function: (identifier) @call.name) @call.expr
