<template>
  <div class="not-found">
    <div class="not-found__bg"></div>
    <div class="not-found__content">
      <div class="not-found__code">
        <span class="digit">4</span>
        <span class="digit digit--zero">0</span>
        <span class="digit">4</span>
      </div>
      <h1 class="not-found__title">页面走丢了</h1>
      <p class="not-found__desc">您要找的演出可能已下架，或该页面不存在</p>
      <div class="not-found__actions">
        <el-button type="primary" size="large" @click="$router.push('/')" class="home-btn">
          <el-icon><House /></el-icon>
          返回首页
        </el-button>
        <el-button size="large" plain @click="$router.go(-1)" class="back-btn">
          返回上一页
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { House } from '@element-plus/icons-vue'
</script>

<style scoped lang="scss">
.not-found {
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;

  &__bg {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(ellipse at 20% 50%, rgba(255, 55, 29, 0.08) 0%, transparent 60%),
      radial-gradient(ellipse at 80% 20%, rgba(79, 70, 229, 0.08) 0%, transparent 50%);
    pointer-events: none;
  }

  &__content {
    position: relative;
    z-index: 1;
    text-align: center;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 20px;
  }

  &__code {
    display: flex;
    align-items: center;
    gap: 4px;
    line-height: 1;
    margin-bottom: 8px;

    .digit {
      font-size: 140px;
      font-weight: 900;
      background: linear-gradient(135deg, var(--tf-primary) 0%, #FF8A70 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
      letter-spacing: -8px;
      filter: drop-shadow(0 4px 24px rgba(255, 55, 29, 0.3));

      &--zero {
        background: linear-gradient(135deg, var(--tf-text-muted) 0%, var(--tf-border) 100%);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        background-clip: text;
        filter: none;
      }
    }
  }

  &__title {
    font-size: 28px;
    font-weight: 700;
    color: var(--tf-text-primary);
    margin: 0;
  }

  &__desc {
    font-size: 15px;
    color: var(--tf-text-secondary);
    margin: 0;
    line-height: 1.6;
  }

  &__actions {
    display: flex;
    gap: 12px;
    margin-top: 8px;
  }
}

.home-btn {
  min-width: 140px;
  height: 46px;
  font-size: 15px;
  font-weight: 600;
  border-radius: var(--tf-radius-full) !important;
}

.back-btn {
  min-width: 120px;
  height: 46px;
  font-size: 15px;
  border-radius: var(--tf-radius-full) !important;
}
</style>
