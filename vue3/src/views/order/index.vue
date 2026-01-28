<template>
  <Header></Header>
  <div class="order-confirm-page">
    <div class="order-confirm-container">
      <!-- 演出信息头部卡片 -->
      <div class="show-header-card">
        <h1 class="show-header-card__title">{{ detailList.title }}</h1>
        <div class="show-header-card__meta">
          <span>📍 {{ detailList.areaName }} | {{ detailList.place }}</span>
          <span>📅 {{ formatDateWithWeekday(detailList.showTime, detailList.showWeekTime) }}</span>
        </div>
        <div class="show-header-card__price-tag">
          票档：<span class="price">￥<template v-if="allPrice == ''">{{ countPrice }}</template><template v-else>{{ allPrice }}</template></span>
          × <span class="count"><template v-if="allPrice == ''">1</template><template v-else>{{ num }}</template></span> 张
        </div>
        <!-- 选座信息 -->
        <div class="seat-info" v-if="isChooseSeat && selectedSeatsData.length > 0">
          <span class="seat-label">已选座位：</span>
          <span class="seat-list">
            <span v-for="seat in selectedSeatsData" :key="seat.id" class="seat-item">
              {{ seat.rowCode }}排{{ seat.colCode }}座 (￥{{ seat.price }})
            </span>
          </span>
        </div>
        <div class="order-notice">按付款顺序配票，优先连座配票</div>
      </div>

      <!-- 表单主体卡片 -->
      <div class="order-card-shell">
        <ServiceFeatures :detailList="detailList" />

        <!-- 实名观演人 -->
        <div class="section-block">
          <div class="section-block__header">
            <div class="section-block__title">
              实名观演人 <span class="sub-tip">仅需选择一位，入场时需携带对应证件</span>
            </div>
            <el-button type="primary" size="small" plain @click="buyTicketInfo">+ 新增观演人</el-button>
          </div>
          <div class="ticket-user-grid" v-if="ticketInfoArr && ticketInfoArr.length">
            <div class="user-pill" v-for="item in ticketInfoArr" :key="item.id">
              <div class="user-pill__info">
                <span class="user-pill__name">{{ item.relName }}</span>
                <span class="user-pill__id">{{ getIdTypeName(item.idType) }} {{ item.idNumber }}</span>
              </div>
              <el-checkbox
                class="user-pill__checkbox"
                :value="item.id"
                size="large"
                @change="getSelectTicketUser(item.id, $event)"
              />
            </div>
          </div>
        </div>

        <!-- 配送方式 -->
        <div class="section-block">
          <div class="section-block__title">配送方式</div>
          <div class="delivery-info" v-if="detailList.electronicDeliveryTicket == '1'">
            <div class="delivery-badge">电子票 <el-tag size="small" type="success" round>直接入场</el-tag></div>
            <div class="delivery-desc">支付成功后，无需取票，前往票夹查看入场凭证</div>
          </div>
        </div>

        <!-- 联系方式 -->
        <div class="section-block">
          <div class="section-block__title">联系方式</div>
          <div class="contact-num">{{ telNum }}</div>
        </div>

        <!-- 支付方式 -->
        <div class="section-block">
          <div class="section-block__title">支付方式</div>
          <div class="pay-option">
            <img :src="pay" alt="Alipay" class="pay-icon" />
            <span class="pay-name">支付宝</span>
            <el-radio class="pay-radio" value="1" size="large" model-value="1" />
          </div>
        </div>

        <!-- 提交结算条 -->
        <div class="checkout-bar">
          <div class="checkout-bar__terms">
            由于票品为特殊价票券，其背后承载的文化服务具有时效性、稀缺性等特征，一旦订购成功，不支持退换。
          </div>
          <div class="checkout-bar__actions">
            <div class="total-price">
              合计：<span class="currency">￥</span>
              <span class="amount" v-if="allPrice == ''">{{ countPrice }}</span>
              <span class="amount" v-else>{{ allPrice }}</span>
            </div>
            <el-button type="primary" size="large" class="submit-btn" :loading="submitLoading" @click="submitOrder">提交订单</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 排队提示对话框 -->
    <el-dialog v-model="dialogVisible" title="出票排队提示" width="440px" center>
      <div class="queue-dialog-content">系统正在为您高并发排队出票，请稍候...</div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">关闭</el-button>
          <el-button type="primary" :loading="submitLoading" @click="fallbackSyncOrder">快捷出票 (同步提交)</el-button>
        </div>
      </template>
    </el-dialog>

    <Footer></Footer>
  </div>
</template>

<script setup lang="ts" name="orderIndex">
import { ref, nextTick, onActivated, onMounted, onBeforeUnmount } from 'vue'
import Header from '@/components/header/index.vue'
import Footer from '@/components/footer/index.vue'
import pay from "@/assets/section/pay.png"
import { formatDateWithWeekday } from '@/utils/index'
import { useRoute, useRouter } from 'vue-router'
import ServiceFeatures from './components/ServiceFeatures.vue'
import { getUserIdKey } from "@/utils/auth"
import { getPersonInfoId } from '@/api/personInfo'
import { getTicketUser } from "@/api/buyTicketUser"
import { getIdTypeName } from '@/utils/idType'
import { getOrderCacheApi, orderCreateV1Api, orderCreateV2Api, orderCreateV3Api, orderCreateV4Api } from '@/api/order'
import { ElMessage } from "element-plus"
import { useAuthStore } from '@/store/modules/auth'

const useUser = useAuthStore()
const router = useRouter()
const detailList = ref<any>({})
const allPrice = ref('')
const countPrice = ref('')
const num = ref('')
const telNum = ref('')
const ticketInfoArr = ref<any[]>([])
const dialogVisible = ref(false)
const isSHowInfo = ref(true)
const ticketUserIdArr = ref<any[]>([])
const ticketCategoryId = ref('')
const orderNumberCache = ref('')
const loading = ref(false)
const submitLoading = ref(false)

const seatIdList = ref<any[]>([])
const isChooseSeat = ref(false)
const selectedSeatsData = ref<any[]>([])
const pollingTimer = ref<any>(null)
const timeoutTimer = ref<any>(null)
const tenSecond = 10000

onMounted(() => {
  let orderData = history.state
  if (!orderData || !orderData.detailList) {
    const backup = sessionStorage.getItem('tf_pending_order')
    if (backup) {
      try {
        orderData = JSON.parse(backup)
      } catch (e) {}
    }
  }

  if (orderData && orderData.detailList) {
    detailList.value = typeof orderData.detailList === 'string' ? JSON.parse(orderData.detailList) : orderData.detailList
    allPrice.value = orderData.allPrice || ''
    countPrice.value = orderData.countPrice || ''
    num.value = orderData.num || ''
    ticketCategoryId.value = orderData.ticketCategoryId || ''
    if (orderData.isChooseSeat) {
      isChooseSeat.value = true
      seatIdList.value = typeof orderData.seatIdList === 'string' ? JSON.parse(orderData.seatIdList || '[]') : orderData.seatIdList
      selectedSeatsData.value = typeof orderData.selectedSeats === 'string' ? JSON.parse(orderData.selectedSeats || '[]') : orderData.selectedSeats
    }
  }
})

getPersonInfoIdList()
getTicketUserList()

async function getPersonInfoIdList() {
  const id = getUserIdKey()
  getPersonInfoId({ id }).then(response => {
    let { mobile } = response.data
    telNum.value = mobile
  })
}

async function getTicketUserList() {
  const id = getUserIdKey()
  getTicketUser({ userId: id }).then(response => {
    ticketInfoArr.value = response.data
  })
}

function buyTicketInfo() {
  router.replace({ name: 'BuyTicketUser' })
}

function getSelectTicketUser(ticketUserId: any, isChecked: boolean) {
  if (isChecked) {
    ticketUserIdArr.value.push(ticketUserId)
  } else {
    ticketUserIdArr.value = ticketUserIdArr.value.filter((item) => item !== ticketUserId)
  }
}

function getOrderCache(orderNumber: string) {
  const orderNumberParams = { orderNumber }
  return getOrderCacheApi(orderNumberParams).then(response => {
    if (response.code == '0' && response.data != null) {
      orderNumberCache.value = response.data
    }
  }).catch(() => {})
}

const pollingMaxTime = 20000

const startPolling = (orderNumber: string, startTime: number) => {
  pollingTimer.value = setInterval(async () => {
    const currentTime = Date.now()
    if (currentTime - startTime >= pollingMaxTime) {
      stopPolling()
      loadingClose()
      dialogShow()
      return
    }
    await getOrderCache(orderNumber)
    if (orderNumberCache.value !== null && orderNumberCache.value !== '') {
      stopPolling()
      loadingClose()
      dialogVisible.value = false
      ElMessage.success('异步出票成功，正在跳转支付...')
      router.replace({ name: 'PayMethod', state: { orderNumber: orderNumberCache.value } })
    }
  }, 300)
}

const stopPolling = () => {
  clearInterval(pollingTimer.value)
  pollingTimer.value = null
  clearTimeout(timeoutTimer.value)
  timeoutTimer.value = null
}

function handleOrderCreate(apiFn: Function, params: any) {
  submitLoading.value = true
  loadingShow()
  apiFn(params).then((response: any) => {
    if (response.code == '0' && response.data) {
      ElMessage.success('订单创建成功，正在为您跳转支付...')
      dialogVisible.value = false
      router.replace({ name: 'PayMethod', state: { orderNumber: response.data } })
    } else {
      dialogShow()
    }
  }).catch((err: any) => {
    ElMessage.error(err?.message || '订单创建失败，请检查填写信息后重试')
  }).finally(() => {
    submitLoading.value = false
    loadingClose()
  })
}

function buildOrderParams() {
  let orderCreateParams: any = {
    programId: detailList.value.id,
    userId: useUser.userId,
    ticketUserIdList: ticketUserIdArr.value,
  }

  if (isChooseSeat.value && selectedSeatsData.value.length > 0) {
    orderCreateParams.seatDtoList = selectedSeatsData.value.map(seat => ({
      id: seat.id,
      ticketCategoryId: seat.ticketCategoryId,
      rowCode: parseInt(seat.rowCode),
      colCode: parseInt(seat.colCode),
      price: seat.price,
    }))
  } else {
    orderCreateParams.ticketCategoryId = ticketCategoryId.value
    orderCreateParams.ticketCount = num.value
  }
  return orderCreateParams
}

function fallbackSyncOrder() {
  const params = buildOrderParams()
  handleOrderCreate(orderCreateV1Api, params)
}

function submitOrder() {
  if (!ticketInfoArr.value || ticketInfoArr.value.length === 0) {
    ElMessage.warning('请先添加至少一位实名观演人！')
    return
  }

  // 若用户未勾选观演人但存在观演人，自动帮忙勾选第一位
  if (ticketUserIdArr.value.length === 0 && ticketInfoArr.value.length > 0) {
    ticketUserIdArr.value = [ticketInfoArr.value[0].id]
  }

  const requiredCount = Number(num.value) || 1
  if (ticketUserIdArr.value.length !== requiredCount) {
    ElMessage.error(`您购买了 ${requiredCount} 张票，请勾选相等数量 (${requiredCount} 位) 的实名观演人！`)
    return
  }

  const orderCreateParams = buildOrderParams()
  const createOrderVersion = Number(import.meta.env.VITE_CREATE_ORDER_VERSION)

  if (createOrderVersion >= 1 && createOrderVersion <= 3) {
    const apiMap: Record<number, any> = { 1: orderCreateV1Api, 2: orderCreateV2Api, 3: orderCreateV3Api }
    handleOrderCreate(apiMap[createOrderVersion], orderCreateParams)
  } else if (createOrderVersion == 4) {
    submitLoading.value = true
    loadingShow()
    orderCreateV4Api(orderCreateParams).then((response: any) => {
      if (response.code == '0' && response.data != null) {
        startPolling(response.data, Date.now())
        timeoutTimer.value = setTimeout(() => {
          if (pollingTimer.value) stopPolling()
          if (!orderNumberCache.value) {
            submitLoading.value = false
            dialogShow()
          }
        }, tenSecond)
      } else {
        // V4 如果由于中间件未就绪返回异常，自动兜底调用 V1 同步创单
        handleOrderCreate(orderCreateV1Api, orderCreateParams)
      }
    }).catch(() => {
      submitLoading.value = false
      loadingClose()
      handleOrderCreate(orderCreateV1Api, orderCreateParams)
    })
  } else {
    handleOrderCreate(orderCreateV1Api, orderCreateParams)
  }
}

function dialogShow() {
  dialogVisible.value = true
  isSHowInfo.value = false
}

function loadingShow() {
  loading.value = true
  isSHowInfo.value = false
}

function loadingClose() {
  loading.value = false
  isSHowInfo.value = true
}

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped lang="scss">
.order-confirm-page {
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  flex-direction: column;
}

.order-confirm-container {
  width: 1100px;
  margin: 24px auto 60px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 头部项目信息卡片 */
.show-header-card {
  background: linear-gradient(135deg, var(--tf-primary) 0%, #E02B13 100%);
  border-radius: var(--tf-radius-lg);
  padding: 32px 40px;
  color: #fff;
  box-shadow: var(--tf-shadow-md);
  display: flex;
  flex-direction: column;
  gap: 12px;

  &__title {
    font-size: 24px;
    font-weight: 700;
    margin: 0;
    line-height: 1.3;
  }

  &__meta {
    display: flex;
    gap: 24px;
    font-size: 14px;
    opacity: 0.95;
  }

  &__price-tag {
    font-size: 16px;
    font-weight: 500;
    margin-top: 4px;

    .price {
      font-size: 22px;
      font-weight: 700;
      color: #FFD700;
    }

    .count {
      font-weight: 700;
    }
  }

  .seat-info {
    margin-top: 4px;
    padding: 10px 16px;
    background: rgba(255, 255, 255, 0.15);
    backdrop-filter: blur(8px);
    border-radius: var(--tf-radius-sm);
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;

    .seat-item {
      margin-right: 8px;
      font-weight: 600;
    }
  }

  .order-notice {
    font-size: 12px;
    opacity: 0.8;
  }
}

/* 主卡片外观 */
.order-card-shell {
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-sm);
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.section-block {
  display: flex;
  flex-direction: column;
  gap: 14px;

  &__header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  &__title {
    font-size: 16px;
    font-weight: 700;
    color: var(--tf-text-primary);
    display: flex;
    align-items: center;
    gap: 8px;

    .sub-tip {
      font-size: 12px;
      font-weight: 400;
      color: var(--tf-text-secondary);
    }
  }
}

/* 实名观演人列表网格 */
.ticket-user-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;

  .user-pill {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 14px 18px;
    border: 1px solid var(--tf-border);
    border-radius: var(--tf-radius-md);
    background: var(--tf-bg);
    transition: all 0.2s;

    &:hover {
      border-color: var(--tf-primary);
      background: var(--tf-surface);
    }

    &__info {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    &__name {
      font-size: 15px;
      font-weight: 600;
      color: var(--tf-text-primary);
    }

    &__id {
      font-size: 12px;
      color: var(--tf-text-secondary);
      font-family: monospace;
    }
  }
}

/* 配送信息 */
.delivery-info {
  display: flex;
  flex-direction: column;
  gap: 6px;

  .delivery-badge {
    font-size: 15px;
    font-weight: 600;
    color: var(--tf-text-primary);
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .delivery-desc {
    font-size: 13px;
    color: var(--tf-text-secondary);
  }
}

.contact-num {
  font-size: 15px;
  color: var(--tf-text-primary);
  font-weight: 500;
  font-family: monospace;
}

/* 支付选项 */
.pay-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border: 1px solid var(--tf-primary);
  background: var(--tf-primary-light);
  border-radius: var(--tf-radius-md);
  width: fit-content;

  .pay-icon {
    width: 28px;
    height: 28px;
    object-fit: contain;
  }

  .pay-name {
    font-size: 15px;
    font-weight: 600;
    color: var(--tf-text-primary);
  }

  .pay-radio {
    margin-left: 12px;
  }
}

/* 结算条 */
.checkout-bar {
  margin-top: 12px;
  padding-top: 24px;
  border-top: 1px solid var(--tf-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 32px;

  &__terms {
    flex: 1;
    font-size: 12px;
    color: var(--tf-text-secondary);
    line-height: 1.6;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 24px;

    .total-price {
      font-size: 14px;
      color: var(--tf-text-primary);

      .currency {
        color: var(--tf-primary);
        font-weight: 700;
      }

      .amount {
        font-size: 28px;
        font-weight: 700;
        color: var(--tf-primary);
      }
    }

    .submit-btn {
      padding: 0 36px;
      font-size: 16px;
      font-weight: 600;
      height: 48px;
      border-radius: var(--tf-radius-full);
    }
  }
}

.queue-dialog-content {
  text-align: center;
  font-size: 15px;
  color: var(--tf-text-primary);
  padding: 20px 0;
}
</style>
