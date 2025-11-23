; Types (struct/interface/etc.)
(type_declaration
  (type_spec name: (type_identifier) @symbol.name)) @symbol.decl

; Functions
(function_declaration
  name: (identifier) @symbol.name
  parameters: (parameter_list) @symbol.params) @symbol.decl

; Methods
(method_declaration
  name: (field_identifier) @symbol.name
  parameters: (parameter_list) @symbol.params) @symbol.decl
