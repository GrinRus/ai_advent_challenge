; Classes
(class_declaration
  name: (identifier) @symbol.name) @symbol.decl

; Methods
(method_definition
  name: (property_identifier) @symbol.name
  parameters: (formal_parameters) @symbol.params) @symbol.decl

; Functions
(function_declaration
  name: (identifier) @symbol.name
  parameters: (formal_parameters) @symbol.params) @symbol.decl
