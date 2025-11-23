; obj.method()
(call_expression
  callee: (navigation_expression
            left: (_) @call.receiver
            right: (simple_identifier) @call.name)) @call.expr

; func()
(call_expression
  callee: (simple_identifier) @call.name) @call.expr
