import { useAuthStore } from '@/stores/auth.js';

export default {
  mounted(element, binding) {
    const auth = useAuthStore();
    const required = Array.isArray(binding.value) ? binding.value : [binding.value];
    if (!required.some(code => auth.hasPermission(code))) element.remove();
  }
};
