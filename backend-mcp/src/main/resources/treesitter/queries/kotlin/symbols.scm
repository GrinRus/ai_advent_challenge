; Classes / objects / interfaces
(class_declaration
  name: (simple_identifier) @symbol.name) @symbol.decl

(object_declaration
  name: (simple_identifier) @symbol.name) @symbol.decl

(interface_declaration
  name: (simple_identifier) @symbol.name) @symbol.decl

; Functions / constructors
(function_declaration
  name: (simple_identifier) @symbol.name
  value_parameters: (value_parameters) @symbol.params) @symbol.decl

(secondary_constructor
  value_parameters: (value_parameters) @symbol.params) @symbol.decl
