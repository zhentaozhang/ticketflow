<template>
  <svg class="tf-icon" aria-hidden="true" :width="size" :height="size" viewBox="0 0 1024 1024" :fill="color">
    <!-- Using a generic ticket icon as placeholder for all category icons -->
    <path :d="iconPath"></path>
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 48 },
  color: { type: String, default: 'currentColor' }
});

const defaultPath = 'M853.333333 341.333333v-170.666666H170.666667v170.666666c47.146667 0 85.333333 38.186667 85.333333 85.333334s-38.186667 85.333333-85.333333 85.333333v170.666667h682.666666v-170.666667c-47.146667 0-85.333333-38.186667-85.333333-85.333333s38.186667-85.333333 85.333333-85.333334z m-85.333333 246.357334V597.333333H256v-9.685333C326.698667 550.058667 375.466667 492.373333 375.466667 426.666667s-48.768-123.392-119.466667-160.981334V256h512v9.685333C697.301333 303.274667 648.533333 360.96 648.533333 426.666667s48.768 123.392 119.466667 160.981334z';

const paths: Record<string, string> = {
  // Add specific SVG paths here if needed later.
};

const iconPath = computed(() => paths[props.name] || defaultPath);
</script>

<style scoped>
.tf-icon {
  vertical-align: middle;
  fill: currentColor;
  overflow: hidden;
  transition: all 0.3s;
  display: block;
  margin: 0 auto;
}
</style>
