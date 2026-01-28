<template>
  <div class="pay-success-page">
    <Header></Header>
    <div class="pay-success-container">
      <div class="success-card">
        <div class="success-card__icon">
          <el-icon size="64" color="#10B981"><CircleCheckFilled /></el-icon>
        </div>
        <h1 class="success-card__title">支付成功</h1>
        <p class="success-card__desc">感谢您的购买，您可以在“我的订单”中查看出票状态与票夹详情。</p>
        <div class="success-card__actions">
          <el-button size="large" plain round @click="continueQuery">继续逛逛</el-button>
          <el-button size="large" type="primary" round @click="orderQuery">查看订单</el-button>
        </div>
      </div>
    </div>
    <Footer></Footer>
  </div>
</template>

<script setup lang="ts" name="PaySuccess">
import Header from '@/components/header/index.vue'
import Footer from '@/components/footer/index.vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const orderNumber = ref('')

const continueQuery = () => {
  router.replace({ name: 'Home' })
}

const orderQuery = () => {
  router.push({ name: 'OrderManagement' })
}

onMounted(() => {
  orderNumber.value = localStorage.getItem('orderNumber') || ''
  localStorage.removeItem('orderNumber')
})
</script>

<style scoped lang="scss">
.pay-success-page {
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  flex-direction: column;
}

.pay-success-container {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 60px 20px;
}

.success-card {
  width: 520px;
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-md);
  padding: 48px 40px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__icon {
    margin-bottom: 20px;
  }

  &__title {
    font-size: 24px;
    font-weight: 700;
    color: var(--tf-text-primary);
    margin: 0 0 12px 0;
  }

  &__desc {
    font-size: 14px;
    color: var(--tf-text-secondary);
    line-height: 1.6;
    margin: 0 0 32px 0;
  }

  &__actions {
    display: flex;
    gap: 16px;
    width: 100%;
    justify-content: center;

    .el-button {
      min-width: 130px;
      font-weight: 600;
    }
  }
}
</style>
