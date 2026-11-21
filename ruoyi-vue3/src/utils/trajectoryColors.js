// Fixed engineering legend colors.  They are semantic colors, so changing the
// selected well never changes what a line means.
export const trajectoryColors = Object.freeze({
  ACTUAL: '#2563eb',
  DESIGN: '#7c3aed',
  PREDICTED: '#16a34a',
  NEIGHBOR: '#9ca3af',
  PLANNED: '#000000'
})

export const riskColors = Object.freeze({
  RED: '#dc2626',
  ORANGE: '#ea580c',
  YELLOW: '#ca8a04',
  BLUE: '#2563eb'
})

export const riskLabels = Object.freeze({
  RED: '危险',
  ORANGE: '警告',
  YELLOW: '关注',
  BLUE: '安全'
})
