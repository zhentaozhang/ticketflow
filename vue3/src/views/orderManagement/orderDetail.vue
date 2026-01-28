<template>
  <div class="orderDetail">
    <Header></Header>
    <div class="app-container" v-if="orderData[0]">
      <!-- 订单状态头部 -->
      <div class="status-header">
        <div class="status-header__left">
          <h2 class="status-title">{{ getOrderStatus(orderData[0].orderStatus) }}</h2>
          <span class="order-num">订单号: {{ orderData[0].orderNumber }}</span>
        </div>
        <div class="status-header__right">
          <template v-if="orderData[0].orderStatus == 1 || orderData[0].orderStatus == 2 || orderData[0].orderStatus == 4">
            <div class="price-action-group">
              <span class="price-label">需付款:</span>
              <span class="price">￥{{ orderData[0].orderPrice }}</span>
              <template v-if="orderData[0].orderStatus == 1">
                <el-button class="action-btn" type="primary" @click="payOrder(orderData[0].orderNumber)">去支付</el-button>
                <el-button class="action-btn" type="danger" plain @click="cancelOrder(orderData[0].orderNumber)">取消订单</el-button>
              </template>
            </div>
          </template>
          <template v-if="orderData[0].orderStatus == 3">
            <div class="price-action-group">
              <span class="price-label">实付款:</span>
              <span class="price">￥{{ orderData[0].orderPrice }}</span>
            </div>
          </template>
        </div>
      </div>

      <!-- 节目表格 -->
      <div class="detail-card">
        <h3 class="detail-card__title">项目信息</h3>
        <el-table :data="orderData" class="tf-table" header-cell-class-name="tf-table-header">
          <el-table-column label="项目信息" min-width="400">
            <template #default="scope">
              <div class="project-info">
                <img :src="scope.row.programItemPicture" class="project-img" alt="">
                <div class="project-desc">
                  <div class="title">{{ scope.row.programTitle }}</div>
                  <div class="meta">演出场次: {{ scope.row.programShowTime }}</div>
                  <div class="meta">演出场馆: {{ scope.row.programPlace }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="座位信息" align="center">
            <template #default="scope">
              <div v-for="item in (scope.row.orderTicketInfoVoList || [])" :key="item.seatInfo" class="cell-line">
                {{ item.seatInfo }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="单价" align="center">
            <template #default="scope">
              <div v-for="item in (scope.row.orderTicketInfoVoList || [])" :key="item.seatInfo" class="cell-line">
                ￥{{ item.price }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="数量" align="center">
            <template #default="scope">
              <div v-for="item in (scope.row.orderTicketInfoVoList || [])" :key="item.seatInfo" class="cell-line">
                {{ item.quantity }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="优惠" align="center">
            <template #default="scope">
              <div v-for="item in (scope.row.orderTicketInfoVoList || [])" :key="item.seatInfo" class="cell-line">
                {{ item.favourablePrice || '-' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="小计" align="center">
            <template #default="scope">
              <div v-for="item in (scope.row.orderTicketInfoVoList || [])" :key="item.seatInfo" class="cell-line highlight">
                ￥{{ item.relPrice }}
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 订单信息网格 -->
      <div class="info-grid">
        <div class="info-card">
          <h3 class="info-card__title">配送信息</h3>
          <div class="info-card__content">
            <div class="info-item"><label>配送方式：</label><span>{{ orderData[0].distributionMode }}</span></div>
            <div class="info-item"><label>取票方式：</label><span>{{ orderData[0].takeTicketMode }}</span></div>
            <div class="info-item"><label>收货人：</label><span>{{ orderData[0]?.userAndTicketUserInfoVo?.userInfoVo?.name || '-' }}</span></div>
            <div class="info-item"><label>手机号：</label><span>{{ orderData[0]?.userAndTicketUserInfoVo?.userInfoVo?.mobile || '-' }}</span></div>
          </div>
        </div>
        <div class="info-card">
          <h3 class="info-card__title">订单信息</h3>
          <div class="info-card__content">
            <div class="info-item"><label>订单编号：</label><span>{{ orderData[0].orderNumber }}</span></div>
            <div class="info-item"><label>创建时间：</label><span>{{ orderData[0].createOrderTime }}</span></div>
          </div>
        </div>
        <div class="info-card">
          <h3 class="info-card__title">发票信息</h3>
          <div class="info-card__content">
            <div class="info-item"><label>发票类型：</label><span>请在演出开始前，在程序上开具发票</span></div>
          </div>
        </div>
        <div class="info-card">
          <h3 class="info-card__title">金额明细</h3>
          <div class="info-card__content">
            <div class="info-item"><label>商品总价：</label><span class="highlight">￥{{ orderData[0].orderPrice }}</span></div>
          </div>
        </div>
      </div>

      <!-- 购票人信息 -->
      <div class="detail-card">
        <h3 class="detail-card__title">购票人信息</h3>
        <div class="ticket-users">
          <div class="user-card" v-for="(ticketUserInfo, index) in (orderData[0]?.userAndTicketUserInfoVo?.ticketUserInfoVoList || [])" :key="index">
            <div class="user-card__header">{{ ticketUserInfo.relName }}</div>
            <div class="user-card__body">
              <div class="info-item"><label>证件类型：</label><span>{{ getIdTypeName(ticketUserInfo.idType) }}</span></div>
              <div class="info-item"><label>证件号码：</label><span>{{ ticketUserInfo.idNumber }}</span></div>
            </div>
          </div>
        </div>
      </div>

    </div>
    <div class="app-container empty-state" v-else>
      <el-empty description="加载中或暂无订单数据..." />
    </div>
    <Footer></Footer>
  </div>
</template>

<script setup lang="ts" name="OrderDetail">
import { ref, onMounted } from 'vue'
import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import { useRoute, useRouter } from 'vue-router'
import { getIdTypeName } from '@/utils/idType'
import { getOrderStatus } from '@/utils/index'
import { getOrderDetailApi, cancelOrderApi } from '@/api/order'
import { ElMessage, ElMessageBox } from 'element-plus'

interface OrderDetailParams {
  orderNumber: string | string[] | undefined
}

const orderDetailParams = ref<OrderDetailParams>({
  orderNumber: undefined,
})
const orderData = ref<any[]>([])
const route = useRoute()
const router = useRouter()

orderDetailParams.value.orderNumber = route.params.orderNumber

onMounted(() => {
  getOrderDetail()
})

function getOrderDetail(): void {
  getOrderDetailApi(orderDetailParams.value).then((response: any) => {
    if (response.code === '0' && response.data) {
      orderData.value.push(response.data)
    } else {
      ElMessage.error(response.message || '获取订单详情失败或未找到该订单')
    }
  }).catch((err) => {
    ElMessage.error('网络异常，获取订单详情失败')
  })
}

function cancelOrder(orderNumber: string): void {
  ElMessageBox.confirm(
    '确认取消该订单吗？',
    '提示',
    {
      confirmButtonText: '确定',
      cancelButtonText: '暂不',
      type: 'warning',
    }
  ).then(() => {
    cancelOrderApi({ orderNumber }).then((response: any) => {
      if (response.code === '0') {
        ElMessage({ message: '取消成功', type: 'success' })
        orderData.value = []
        getOrderDetail()
      } else {
        ElMessage({ message: response.message, type: 'error' })
      }
    })
  }).catch(() => {})
}

function payOrder(orderNumber: string): void {
  router.replace({ name: 'PayMethod', state: { orderNumber } })
}
</script>

<style scoped lang="scss">
.orderDetail {
  width: 100%;
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  flex-direction: column;

  .app-container {
    width: 1200px;
    margin: 24px auto 60px;
    flex: 1;

    /* 状态头部 */
    .status-header {
      background: var(--tf-surface);
      border: 1px solid var(--tf-border);
      border-radius: var(--tf-radius-lg);
      padding: 30px 40px;
      margin-bottom: 24px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      box-shadow: var(--tf-shadow-sm);

      &__left {
        display: flex;
        flex-direction: column;
        gap: 8px;

        .status-title {
          font-size: 28px;
          font-weight: 700;
          color: var(--tf-text-primary);
          margin: 0;
        }

        .order-num {
          font-size: 14px;
          color: var(--tf-text-secondary);
          font-family: monospace;
        }
      }

      &__right {
        .price-action-group {
          display: flex;
          align-items: center;
          gap: 12px;
        }

        .price-label {
          font-size: 14px;
          color: var(--tf-text-secondary);
        }

        .price {
          font-size: 28px;
          font-weight: 700;
          color: var(--tf-primary);
        }
        
        .action-btn {
          margin-left: 8px;
        }
      }
    }

    /* 详情卡片通用样式 */
    .detail-card {
      background: var(--tf-surface);
      border: 1px solid var(--tf-border);
      border-radius: var(--tf-radius-lg);
      padding: 24px 32px;
      margin-bottom: 24px;
      box-shadow: var(--tf-shadow-sm);

      &__title {
        font-size: 18px;
        font-weight: 600;
        color: var(--tf-text-primary);
        margin: 0 0 20px 0;
        padding-bottom: 16px;
        border-bottom: 1px solid var(--tf-border);
      }
    }

    /* 订单信息网格 */
    .info-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;
      margin-bottom: 24px;

      .info-card {
        background: var(--tf-surface);
        border: 1px solid var(--tf-border);
        border-radius: var(--tf-radius-lg);
        padding: 24px;
        box-shadow: var(--tf-shadow-sm);

        &__title {
          font-size: 16px;
          font-weight: 600;
          color: var(--tf-text-primary);
          margin: 0 0 16px 0;
        }

        &__content {
          display: flex;
          flex-direction: column;
          gap: 12px;
        }
      }
    }

    /* 信息条目通用样式 */
    .info-item {
      display: flex;
      font-size: 14px;
      line-height: 1.5;

      label {
        color: var(--tf-text-secondary);
        width: 80px;
        flex-shrink: 0;
      }

      span {
        color: var(--tf-text-primary);
        word-break: break-all;

        &.highlight {
          color: var(--tf-primary);
          font-weight: 600;
        }
      }
    }

    /* 表格自定义样式 */
    .tf-table {
      :deep(th.tf-table-header) {
        background-color: var(--tf-bg) !important;
        color: var(--tf-text-secondary);
        font-weight: 500;
        border-bottom: none;
      }

      .project-info {
        display: flex;
        gap: 16px;
        align-items: flex-start;

        .project-img {
          width: 72px;
          height: 96px;
          object-fit: cover;
          border-radius: var(--tf-radius-md);
          border: 1px solid var(--tf-border);
        }

        .project-desc {
          display: flex;
          flex-direction: column;
          gap: 6px;

          .title {
            font-size: 15px;
            font-weight: 600;
            color: var(--tf-text-primary);
          }

          .meta {
            font-size: 13px;
            color: var(--tf-text-secondary);
          }
        }
      }

      .cell-line {
        line-height: 24px;
        padding: 4px 0;

        &.highlight {
          color: var(--tf-primary);
          font-weight: 600;
        }
      }
    }

    /* 购票人卡片 */
    .ticket-users {
      display: flex;
      gap: 20px;
      flex-wrap: wrap;

      .user-card {
        width: 280px;
        border: 1px solid var(--tf-border);
        border-radius: var(--tf-radius-md);
        overflow: hidden;

        &__header {
          background: var(--tf-bg);
          padding: 12px 16px;
          font-weight: 600;
          color: var(--tf-text-primary);
          border-bottom: 1px solid var(--tf-border);
        }

        &__body {
          padding: 16px;
          display: flex;
          flex-direction: column;
          gap: 12px;
        }
      }
    }
  }
}
</style>
