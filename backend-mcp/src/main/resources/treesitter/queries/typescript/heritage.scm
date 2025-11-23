; class Child extends Base
(class_declaration
  name: (identifier) @heritage.child
  heritage: (class_heritage
              (extends_clause (identifier) @heritage.base))) @heritage.relation

; class Child implements IFoo
(class_declaration
  name: (identifier) @heritage.child
  heritage: (class_heritage
              (implements_clause (identifier) @heritage.base))) @heritage.relation
