; Classes
(class_definition
  name: (identifier) @symbol.name) @symbol.decl

; Functions
(function_definition
  name: (identifier) @symbol.name
  parameters: (parameters) @symbol.params) @symbol.decl
