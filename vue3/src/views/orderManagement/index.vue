<template>
<!--订单管理-->
  <Header></Header>
  <div class="red-line"></div>
  <div class="section">
    <MenuSideBar class="sidebarMenu" activeIndex="5"></MenuSideBar>
    <div class="right-section">
      <div class="page-card">
        <div class="page-card__header">
          <span class="page-card__title">我的订单</span>
        </div>

        <div v-if="orderList.length === 0" class="empty-state">
          <el-empty description="暂无订单记录" />
        </div>

        <div class="order-list" v-else>
          <div class="order-item" v-for="(order, index) in orderList" :key="index">
            <!-- 订单头部 -->
            <div class="order-item__header">
              <span class="order-item__num">订单号：{{ order.orderNumber }}</span>
              <el-tag :type="statusTagType(order.orderStatus)" size="small" round>
                {{ getOrderStatus(order.orderStatus) }}
              </el-tag>
            </div>

            <!-- 订单内容 -->
            <div class="order-item__body">
              <div class="order-item__show">
                <img :src="order.programItemPicture" alt="" class="order-item__cover">
                <div class="order-item__info">
                  <div class="order-item__title">{{ order.programTitle }}</div>
                  <div class="order-item__meta">演出场次：{{ order.programShowTime }}</div>
                  <div class="order-item__meta">演出场馆：{{ order.programPlace }}</div>
                </div>
              </div>

              <div class="order-item__stats">
                <div class="order-item__count">{{ order.ticketCount }} 张</div>
              </div>

              <div class="order-item__price-col">
                <div class="order-item__price">￥{{ order.orderPrice }}</div>
                <div class="order-item__price-note">含运费￥0.00</div>
              </div>

              <div class="order-item__actions">
                <router-link :to="{name:'orderDetail', params:{orderNumber:order.orderNumber}}">
                  <el-button size="small" plain>订单详情</el-button>
                </router-link>
                <template v-if="order.orderStatus === 1">
                  <el-button size="small" type="primary" @click="payOrder(order.orderNumber)">支付订单</el-button>
                  <el-button size="small" type="danger" plain @click="cancelOrder(order.orderNumber)">取消订单</el-button>
                </template>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
  <Footer class="foot"></Footer>
</template>

<script setup lang="ts" name="OrderManagement">
import { ref, onMounted } from 'vue'
import MenuSideBar from '@/components/menuSidebar/index'
import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import { useRouter } from 'vue-router'
import { cancelOrderApi, getOrderListApi } from '@/api/order'
import { ElMessage } from 'element-plus'
import { getOrderStatus } from '@/utils/index'
import { useAuthStore } from '@/store/modules/auth'

interface OrderItem {
  orderNumber: string
  programItemPicture: string
  programTitle: string
  programShowTime: string
  programPlace: string
  ticketCount: number
  orderPrice: number
  orderStatus: number
}

const router = useRouter()
const orderList = ref<OrderItem[]>([])
const useUser = useAuthStore()
const orderListParams = { userId: useUser.userId }

const getOrderList = (): void => {
  getOrderListApi(orderListParams).then((response: any) => {
    orderList.value = response.data
  }).catch(() => {})
}

function cancelOrder(orderNumber: string): void {
  const orderNumberParams = { orderNumber }
  cancelOrderApi(orderNumberParams).then((response: any) => {
    if (response.code === '0') {
      ElMessage({ message: '取消成功', type: 'success' })
      getOrderList()
    } else {
      ElMessage({ message: response.message, type: 'error' })
    }
  })
}

function payOrder(orderNumber: string): void {
  router.replace({ name: 'PayMethod', state: { orderNumber } })
}

function statusTagType(status: number): string {
  const map: Record<number, string> = { 1: 'warning', 2: '', 3: 'success', 4: '' }
  return map[status] ?? ''
}

onMounted(() => {
  getOrderList()
})
</script>



<style scoped lang="scss">
.red-line {
  border-bottom: 4px solid var(--tf-primary);
}

.section {
  width: 1200px;
  margin: 24px auto 0;
  display: flex;
  gap: 16px;
  align-items: flex-start;

  .sidebarMenu { flex-shrink: 0; }

  .right-section {
    flex: 1;
    min-width: 0;
  }
}

/* 页面卡片容器 */
.page-card {
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-sm);
  overflow: hidden;

  &__header {
    padding: 20px 24px;
    border-bottom: 1px solid var(--tf-border);
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: 17px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }
}

.empty-state {
  padding: 60px 0;
}

/* 订单列表 */
.order-list {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-item {
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-md);
  overflow: hidden;
  transition: box-shadow 0.2s;

  &:hover { box-shadow: var(--tf-shadow-md); }

  &__header {
    padding: 10px 16px;
    background: var(--tf-bg);
    border-bottom: 1px solid var(--tf-border);
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__num {
    font-size: 12px;
    color: var(--tf-text-secondary);
    font-family: monospace;
  }

  &__body {
    display: grid;
    grid-template-columns: 1fr 80px 130px 180px;
    align-items: center;
    padding: 14px 16px;
    gap: 12px;
  }

  &__show {
    display: flex;
    gap: 12px;
    align-items: flex-start;
  }

  &__cover {
    width: 60px;
    height: 80px;
    object-fit: cover;
    border-radius: var(--tf-radius-sm);
    flex-shrink: 0;
    border: 1px solid var(--tf-border);
  }

  &__info { flex: 1; min-width: 0; }

  &__title {
    font-size: 14px;
    font-weight: 600;
    color: var(--tf-text-primary);
    margin-bottom: 4px;
    line-height: 1.4;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    overflow: hidden;
  }

  &__meta {
    font-size: 12px;
    color: var(--tf-text-secondary);
    margin-top: 2px;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__stats {
    text-align: center;
    font-size: 14px;
    color: var(--tf-text-primary);
  }

  &__count { font-weight: 600; }

  &__price-col {
    text-align: center;
  }

  &__price {
    font-size: 18px;
    font-weight: 700;
    color: var(--tf-primary);
  }

  &__price-note {
    font-size: 11px;
    color: var(--tf-text-secondary);
    margin-top: 2px;
  }

  &__actions {
    display: flex;
    flex-direction: column;
    gap: 8px;
    align-items: flex-end;

    a { text-decoration: none; }
  }
}

.foot {
  margin-top: 60px;
}
</style>
