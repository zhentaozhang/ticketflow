<template>
  <router-view :key="route.fullPath" />

  <!-- 全局悬浮 Dock -->
  <transition name="dock-fade">
    <div class="global-dock" v-if="showDock">
      <button class="dock-btn dock-btn--top" @click="scrollToTop" title="返回顶部">
        <el-icon :size="18"><ArrowUp /></el-icon>
      </button>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowUp } from '@element-plus/icons-vue'

const route = useRoute()
const showDock = ref(false)

function handleScroll() {
  showDock.value = window.scrollY > 300
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})
</script>

<style>
/* 全局悬浮 Dock */
.global-dock {
  position: fixed;
  right: 24px;
  bottom: 40px;
  z-index: 999;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dock-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid var(--tf-border);
  background: var(--tf-surface);
  color: var(--tf-text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--tf-shadow-md);
  transition: all 0.25s;

  &:hover {
    background: var(--tf-primary);
    color: #ffffff;
    border-color: var(--tf-primary);
    transform: translateY(-2px);
    box-shadow: 0 8px 20px rgba(255, 55, 29, 0.3);
  }
}

/* Dock 出现动画 */
.dock-fade-enter-active,
.dock-fade-leave-active {
  transition: opacity 0.3s, transform 0.3s;
}
.dock-fade-enter-from,
.dock-fade-leave-to {
  opacity: 0;
  transform: translateY(16px);
}
</style>
