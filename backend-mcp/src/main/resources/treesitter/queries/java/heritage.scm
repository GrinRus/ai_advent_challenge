; class Child extends Base
(class_declaration
  name: (identifier) @heritage.child
  super_class: (type_identifier) @heritage.base) @heritage.relation

; class Child implements IFoo, IBar
(class_declaration
  name: (identifier) @heritage.child
  interfaces: (super_interfaces
                (type_identifier) @heritage.base)) @heritage.relation

; interface Child extends Base
(interface_declaration
  name: (identifier) @heritage.child
  extends_interfaces: (super_interfaces
                        (type_identifier) @heritage.base)) @heritage.relation
