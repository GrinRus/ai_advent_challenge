; Classes / interfaces / enums / annotations
(class_declaration
  name: (identifier) @symbol.name) @symbol.decl

(interface_declaration
  name: (identifier) @symbol.name) @symbol.decl

(enum_declaration
  name: (identifier) @symbol.name) @symbol.decl

(annotation_type_declaration
  name: (identifier) @symbol.name) @symbol.decl

; Methods / constructors
(method_declaration
  name: (identifier) @symbol.name
  parameters: (formal_parameters) @symbol.params) @symbol.decl

(constructor_declaration
  name: (identifier) @symbol.name
  parameters: (formal_parameters) @symbol.params) @symbol.decl
