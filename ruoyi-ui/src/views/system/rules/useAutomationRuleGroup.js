import { onBeforeUnmount, ref } from 'vue'
import { automationRuleApi } from '@/api/automationRules'
import { isUncertainOutcome } from '@/services/apiClient'

export function useAutomationRuleGroup(initialCategory = 'verify') {
  const category = ref(initialCategory), group = ref(null), loading = ref(false), saving = ref(false), error = ref(''), actionError = ref(''), uncertain = ref(''), recoveryRevision = ref(0)
  let sequence = 0

  async function load(nextCategory = category.value) {
    const changedCategory = nextCategory !== category.value
    category.value = nextCategory
    if (changedCategory) group.value = null
    const seq = ++sequence
    loading.value = true; error.value = ''; uncertain.value = ''
    try { const data = await automationRuleApi.group(nextCategory); if (seq === sequence) group.value = data }
    catch (exception) { if (seq === sequence) { group.value = null; error.value = exception.message } }
    finally { if (seq === sequence) loading.value = false }
  }

  async function mutate(operation) {
    if (saving.value || loading.value || !group.value) return false
    const operationCategory = category.value, operationSequence = sequence, operationVersion = group.value.version
    saving.value = true; actionError.value = ''; uncertain.value = ''
    try {
      const result = await operation(group.value)
      if (category.value !== operationCategory || sequence !== operationSequence || group.value?.version !== operationVersion) return false
      group.value = result
      return true
    }
    catch (exception) {
      const needsRefresh = isUncertainOutcome(exception) || exception?.status === 409 || exception?.code === 'VERSION_CONFLICT'
      if (needsRefresh) {
        uncertain.value = '保存结果需要确认，已重新读取服务器上的最新配置。请核对后再继续操作。'
        const message = uncertain.value
        await load(category.value)
        uncertain.value = message
        recoveryRevision.value += 1
      } else actionError.value = exception.message
      return false
    } finally { saving.value = false }
  }

  function clearActionError() { actionError.value = '' }

  onBeforeUnmount(() => { sequence += 1 })
  return { category, group, loading, saving, error, actionError, uncertain, recoveryRevision, load, mutate, clearActionError }
}
