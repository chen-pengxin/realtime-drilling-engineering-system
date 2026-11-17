import { ref } from 'vue'

// Shared by the safety gate and live alarms, so two blocking dialogs cannot
// compete for focus. Confirmation itself remains authoritative on the server.
export const engineConfirmationOpen = ref(false)
