<template>
  <Header></Header>
  <div class="pay-method-page">
    <div class="pay-card">
      <div class="pay-card__header">
        <el-button icon="ArrowLeft" circle @click="router.back()" />
        <div class="pay-card__title">订单支付</div>
      </div>
      <div class="pay-card__body">
        <div class="order-summary" v-if="orderDetailData">
          <div class="order-summary__title">{{ orderDetailData.programTitle }}</div>
          <div class="order-summary__price">￥{{ orderDetailData.orderPrice }}</div>
        </div>

        <div class="channel-card">
          <img :src="pay" alt="Alipay" class="channel-card__logo" />
          <div class="channel-card__info">
            <div class="channel-card__name">支付宝安全支付</div>
            <div class="channel-card__tip">支持支付宝 APP / 网页一键快捷支付</div>
          </div>
        </div>

        <el-button type="primary" size="large" class="pay-submit-btn" @click="continuePay">
          前往支付宝支付
        </el-button>
      </div>
    </div>
  </div>
  <Footer></Footer>
</template>

<script setup lang="ts" name="PayMethod">
import Header from '@/components/header/index.vue'
import Footer from '@/components/footer/index.vue'
import pay from "@/assets/section/pay.png"
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getOrderDetailApi, orderPayApi } from "@/api/order"
import { ElMessage } from "element-plus"
import { ArrowLeft } from '@element-plus/icons-vue'

const orderNumber = ref('')
const orderDetailData = ref<any>(null)
const route = useRoute()
const router = useRouter()

function continuePay() {
  if (!orderDetailData.value) {
    getOrderDetail()
  }

  const orderPayParams = {
    platform: 3,
    orderNumber: orderNumber.value,
    subject: orderDetailData.value?.programTitle || '门票订购',
    price: orderDetailData.value?.orderPrice || 0,
    channel: 'alipay',
    payBillType: 1
  }

  orderPayApi(orderPayParams).then((response: any) => {
    if (response.code == '0') {
      document.write(response.data)
    } else {
      ElMessage.error(response.message || '支付请求失败')
    }
  }).catch(() => {
    ElMessage.error('网络异常，支付请求失败')
  })
}

onMounted(() => {
  getOrderDetail()
})

function getOrderDetail() {
  orderNumber.value = history.state?.orderNumber || localStorage.getItem('orderNumber') || ''
  if (!orderNumber.value) return
  const orderDetailParams = { orderNumber: orderNumber.value }
  localStorage.setItem('orderNumber', orderNumber.value)
  getOrderDetailApi(orderDetailParams).then((response: any) => {
    orderDetailData.value = response.data
  }).catch(() => {})
}
</script>

<style scoped lang="scss">
.pay-method-page {
  min-height: calc(100vh - 280px);
  background: var(--tf-bg);
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 60px 0;
}

.pay-card {
  width: 520px;
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-md);
  overflow: hidden;

  &__header {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 20px 24px;
    border-bottom: 1px solid var(--tf-border);
  }

  &__title {
    font-size: 18px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }

  &__body {
    padding: 32px;
    display: flex;
    flex-direction: column;
    gap: 24px;
  }
}

.order-summary {
  background: var(--tf-bg);
  padding: 20px;
  border-radius: var(--tf-radius-md);
  border: 1px solid var(--tf-border);
  text-align: center;

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: var(--tf-text-primary);
    margin-bottom: 8px;
  }

  &__price {
    font-size: 32px;
    font-weight: 700;
    color: var(--tf-primary);
  }
}

.channel-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  border: 2px solid var(--tf-primary);
  border-radius: var(--tf-radius-md);
  background: var(--tf-primary-light);

  &__logo {
    width: 36px;
    height: 36px;
    object-fit: contain;
  }

  &__info {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__name {
    font-size: 15px;
    font-weight: 600;
    color: var(--tf-text-primary);
  }

  &__tip {
    font-size: 12px;
    color: var(--tf-text-secondary);
  }
}

.pay-submit-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 600;
  border-radius: var(--tf-radius-full);
}
</style>
