// All amounts in Milk Web are Indian rupees (owner decision, docs/financial-policy-decisions.md).
const rupeeNumber = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** Formats an amount as `₹1,234.50`, with Indian digit grouping. */
export function formatRupees(value: number) {
  return '₹' + rupeeNumber.format(value)
}
