import request from '@/utils/request'

export function calculateSurvey(data) {
  return request({ url: '/api/algorithm/survey/calculate', method: 'post', data })
}

export function exportSurvey(data) {
  return request({ url: '/api/algorithm/survey/export', method: 'post', data, responseType: 'blob' })
}

export function importSurveyFile(formData) {
  // Let the browser/Axios generate the multipart boundary.  Sending a bare
  // Content-Type without that boundary makes Spring's multipart resolver
  // reject otherwise valid survey files before the gateway can forward them.
  return request({ url: '/api/algorithm/survey/import', method: 'post', data: formData, timeout: 0 })
}

export function checkSurveyQuality(data) {
  return request({ url: '/api/algorithm/survey/quality-check', method: 'post', data })
}

export function previewIncrementalSurvey(data) {
  return request({ url: '/api/algorithm/survey/import/incremental', method: 'post', data })
}

export function scanAntiCollision(data) {
  return request({ url: '/api/algorithm/anti-collision/scan', method: 'post', data })
}

export function scanAntiCollisionNetwork(data, options = {}) {
  return request({ url: '/api/algorithm/anti-collision/network-scan', method: 'post', data, timeout: 0, ...options })
}

export function solveTrajectoryPlan(data, signal) {
  return request({ url: '/api/algorithm/trajectory-plan/solve', method: 'post', data, signal, timeout: 360000 })
}

export function ingestRealtime(data) {
  return request({ url: '/api/algorithm/realtime/ingest', method: 'post', data, timeout: 0 })
}

export function listCollisionTasks(fieldId) {
  return request({ url: '/api/algorithm/anti-collision/tasks', method: 'get', params: { fieldId } })
}

export function listCollisionAlarms(fieldId, status = 'ACTIVE') {
  return request({ url: '/api/algorithm/anti-collision/alarms', method: 'get', params: { fieldId, status } })
}

export function acknowledgeCollisionAlarm(alarmId) {
  return request({ url: `/api/algorithm/anti-collision/alarms/${alarmId}/acknowledge`, method: 'post' })
}

export function resolveCollisionAlarm(alarmId) {
  return request({ url: `/api/algorithm/anti-collision/alarms/${alarmId}/resolve`, method: 'post' })
}

export function listPlanningTasks(fieldId) {
  return request({ url: '/api/algorithm/trajectory-plan/tasks', method: 'get', params: { fieldId } })
}

export function getAlgorithmResources() {
  return request({ url: '/api/algorithm/resources', method: 'get' })
}

export function listTrainingTasks(fieldId) {
  return request({ url: '/api/train/task/list', method: 'get', params: fieldId ? { field_id: fieldId } : {} })
}

export function startTraining(data) {
  return request({ url: '/api/train/task/submit', method: 'post', data })
}

export function stopTraining(taskId) {
  return request({ url: `/api/train/task/${taskId}/stop`, method: 'post' })
}

export function getTrainingStatus(taskId) {
  return request({ url: `/api/train/task/${taskId}`, method: 'get' })
}

export function listModels(fieldId) {
  return request({ url: `/api/drilling/fields/${fieldId}/models`, method: 'get' })
}

export function downloadModel(modelVersionId, fieldId) {
  return request({ url: `/api/drilling/fields/${fieldId}/models/${modelVersionId}/download`, method: 'get', responseType: 'blob' })
}

export function exportDrillingReport(data) {
  return request({ url: '/api/algorithm/report/export', method: 'post', data, responseType: 'blob' })
}

export function startPrediction(data) {
  return request({ url: '/api/drilling/predictions', method: 'post', data, timeout: 310000 })
}

export function cancelPrediction(taskId) {
  return request({ url: `/api/drilling/predictions/${taskId}/cancel`, method: 'post' })
}

export function listPredictionTasks(fieldId) {
  return request({ url: '/api/drilling/predictions', method: 'get', params: fieldId ? { fieldId } : {} })
}

export function exportPredictionTrajectory(taskId) {
  return request({ url: `/api/drilling/predictions/${encodeURIComponent(taskId)}/export`, method: 'get', responseType: 'blob' })
}

export function exportPredictionTasks(fieldId) {
  return request({ url: '/api/drilling/predictions/export', method: 'get', params: { fieldId }, responseType: 'blob' })
}

export function testModelInference(fieldId, modelVersionId, data) {
  return request({ url: `/api/drilling/fields/${fieldId}/models/${modelVersionId}/infer-test`, method: 'post', data, timeout: 0 })
}
