; obj.field
(field_access
  object: (_) @field.receiver
  field: (identifier) @field.name) @field.access

; this.field
(field_access
  object: (this) @field.receiver
  field: (identifier) @field.name) @field.access
