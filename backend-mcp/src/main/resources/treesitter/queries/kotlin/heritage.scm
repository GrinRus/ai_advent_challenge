; class Child : Base()
(class_declaration
  name: (simple_identifier) @heritage.child
  delegation_specifiers: (delegation_specifiers
                            (constructor_invocation
                              (user_type (simple_identifier) @heritage.base)))) @heritage.relation

; interface Child : Base
(interface_declaration
  name: (simple_identifier) @heritage.child
  delegation_specifiers: (delegation_specifiers
                            (user_type (simple_identifier) @heritage.base))) @heritage.relation
