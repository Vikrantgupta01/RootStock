/** ENERGY_BILL → Energy bill */
export function codeLabel(code: string): string {
  const words = code.replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** visitDate → Visit date */
export function fieldLabel(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}
