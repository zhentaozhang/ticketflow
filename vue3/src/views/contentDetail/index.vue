<template>
  <!-- 点击进入单独界面详情 -->
  <Header></Header>
  <div class="app-container">
    <div class="breadcrumb-bar">
      <el-button class="back-btn" plain size="small" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回</span>
      </el-button>
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item :to="{ path: '/allType' }">全部分类</el-breadcrumb-item>
        <el-breadcrumb-item>{{ detailList.title || '演出详情' }}</el-breadcrumb-item>
      </el-breadcrumb>
    </div>
    <div class="wrapper">
      <div class="box-left">
        <!-- 2026 现代核心演出 Hero 详情卡片 -->
        <div class="box-detail">
          <div class="detail-hero">
            <!-- 海报图区 -->
            <div class="poster-box">
              <img :src="detailList.itemPicture" alt="Poster" class="poster-img" />
              <span class="poster-badge" v-if="detailList.preSell === '1'">预售中</span>
            </div>

            <!-- 详情与选购区 -->
            <div class="info-box">
              <!-- 标题与类型标签 -->
              <div class="title-row">
                <span class="tips-pill">🎟️ 电子票</span>
                <h1 class="main-title">{{ detailList.title }}</h1>
              </div>

              <!-- 时间与场馆 -->
              <div class="meta-rows">
                <div class="meta-item">
                  <el-icon><Calendar /></el-icon>
                  <span class="meta-label">时间：</span>
                  <span class="meta-value">{{ formatDateWithWeekday(detailList.showTime, detailList.showWeekTime) }}</span>
                </div>
                <div class="meta-item">
                  <el-icon><Location /></el-icon>
                  <span class="meta-label">场馆：</span>
                  <span class="meta-value">{{ detailList.areaName }} | {{ detailList.place }}</span>
                </div>
              </div>

              <!-- 预售须知 Banner -->
              <div class="notice-banner" v-if="detailList.preSell === '1'">
                <div class="notice-banner__tag">⚡ 预售提醒</div>
                <div class="notice-banner__content">
                  <div>{{ detailList.preSellInstruction }}</div>
                  <div class="sub-text">{{ detailList.importantNotice }}</div>
                </div>
              </div>

              <!-- 城市选择 -->
              <div class="option-row">
                <span class="option-label">城市</span>
                <div class="option-content">
                  <span class="city-chip active-chip">{{ detailList.areaName }}</span>
                </div>
              </div>

              <!-- 场次时间 -->
              <div class="option-row">
                <span class="option-label">场次</span>
                <div class="option-content">
                  <span class="session-chip active-chip">
                    {{ formatDateWithWeekday(detailList.showTime, detailList.showWeekTime) }}
                  </span>
                  <span class="notice-hint">（演出时间均为当地时间）</span>
                </div>
              </div>

              <!-- 票档选择 -->
              <div class="option-row">
                <span class="option-label">票档</span>
                <div class="option-content ticket-grid">
                  <div
                    v-for="(item, index) in ticketCategoryVoList"
                    :key="index"
                    class="ticket-chip"
                    :class="{ 'ticket-chip--active': actvieIndex === index }"
                    @click="ticketClick(item, index)"
                  >
                    <span class="price-text">{{ item.introduce }}</span>
                  </div>
                </div>
              </div>

              <!-- 数量加减 -->
              <div class="option-row">
                <span class="option-label">数量</span>
                <div class="option-content quantity-wrapper">
                  <el-input-number v-model="num" :min="1" :max="6" @change="handleChange" size="default" />
                  <span class="num-limit">每笔订单限购 6 张</span>
                </div>
              </div>

              <!-- 合计金额与购买按钮 Bar -->
              <div class="checkout-bar">
                <div class="price-block">
                  <span class="price-label">合计</span>
                  <span class="price-symbol">￥</span>
                  <span class="price-amount">{{ allPrice === '' ? countPrice : allPrice }}</span>
                </div>

                <div class="action-block">
                  <button
                    v-if="detailList.permitChooseSeat !== '1'"
                    class="buy-btn buy-btn--primary"
                    @click="nowBuy"
                  >
                    立即购买
                  </button>
                  <button
                    v-if="detailList.permitChooseSeat === '1'"
                    class="buy-btn buy-btn--accent"
                    @click="seatBuy"
                  >
                    选座购买
                  </button>
                </div>
              </div>

            </div>
          </div>
        </div>

        <!-- 详细信息 Tab 栏与内容 -->
        <div class="box-item">
          <div class="box-menu">
            <a
              href="#projectDetial"
              class="menu-children"
              :class="{ menuActive: menuActive == 1 }"
              @click="detailClick('#projectDetial', 1)"
            >项目详情</a>
            <a
              href="#ticketNeed"
              class="menu-children"
              :class="{ menuActive: menuActive == 2 }"
              @click="detailClick('#ticketNeed', 2)"
            >购票须知</a>
            <a
              href="#watchNeed"
              class="menu-children"
              :class="{ menuActive: menuActive == 3 }"
              @click="detailClick('#watchNeed', 3)"
            >观演须知</a>
          </div>

          <!-- 项目详情内容区 -->
          <div id="projectDetial" class="detail-section">
            <div class="section-title">
              <span class="accent-bar"></span>
              <span>活动介绍</span>
            </div>
            <div class="detail-content">
              <img :src="detailList.detail" alt="Detail" class="detail-img" />
            </div>
          </div>

          <!-- 购票须知区 -->
          <div id="ticketNeed" class="detail-section">
            <div class="section-title">
              <span class="accent-bar"></span>
              <span>购票须知</span>
            </div>
            <div class="rules-grid">
              <div v-for="item in ticketNeedInfo" :key="item.name" class="rule-card">
                <span class="rule-name">{{ item.name }}</span>
                <div class="rule-value">{{ item.value || '暂无说明' }}</div>
              </div>
            </div>
          </div>

          <!-- 观演须知区 -->
          <div id="watchNeed" class="detail-section">
            <div class="section-title">
              <span class="accent-bar"></span>
              <span>观演须知</span>
            </div>
            <div class="rules-grid">
              <div v-for="item in watchNeedInfo" :key="item.name" class="rule-card">
                <template v-if="item.value !== ''">
                  <span class="rule-name">{{ item.name }}</span>
                  <div class="rule-value">{{ item.value }}</div>
                </template>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧侧边栏：服务卡片与推荐列表 -->
      <div class="box-right">
        <!-- 2026 现代化服务保障卡片 -->
        <div class="service-card">
          <div class="service-card__header">
            <el-icon :size="18"><Lock /></el-icon>
            <span>服务说明与须知</span>
          </div>
          <div class="service-card__body">
            <div class="service-item" v-if="detailList.permitRefund !== ''">
              <div class="service-item__head">
                <span class="service-badge" :class="detailList.permitRefund === '0' ? 'service-badge--warn' : 'service-badge--success'">
                  <el-icon v-if="detailList.permitRefund === '0'"><CircleClose /></el-icon>
                  <el-icon v-else><CircleCheck /></el-icon>
                  {{ detailList.permitRefund === '0' ? '不支持退' : (detailList.permitRefund === '1' ? '条件退' : '全部退') }}
                </span>
              </div>
              <div class="service-item__desc" v-if="detailList.refundExplain">{{ detailList.refundExplain }}</div>
            </div>

            <div class="service-item" v-if="detailList.relNameTicketEntrance !== ''">
              <div class="service-item__head">
                <span class="service-badge" :class="detailList.relNameTicketEntrance === '0' ? 'service-badge--info' : 'service-badge--success'">
                  <el-icon v-if="detailList.relNameTicketEntrance === '0'"><User /></el-icon>
                  <el-icon v-else><UserFilled /></el-icon>
                  {{ detailList.relNameTicketEntrance === '0' ? '不实名购票和入场' : '实名购票和入场' }}
                </span>
              </div>
              <div class="service-item__desc" v-if="detailList.relNameTicketEntranceExplain">{{ detailList.relNameTicketEntranceExplain }}</div>
            </div>

            <div class="service-item" v-if="detailList.permitChooseSeat !== ''">
              <div class="service-item__head">
                <span class="service-badge" :class="detailList.permitChooseSeat === '0' ? 'service-badge--info' : 'service-badge--success'">
                  <el-icon><Ticket /></el-icon>
                  {{ detailList.permitChooseSeat === '0' ? '不支持选座' : '支持选座' }}
                </span>
              </div>
              <div class="service-item__desc" v-if="detailList.chooseSeatExplain">{{ detailList.chooseSeatExplain }}</div>
            </div>

            <div class="service-item" v-if="detailList.electronicDeliveryTicket !== ''">
              <div class="service-item__head">
                <span class="service-badge service-badge--success">
                  <el-icon><Iphone /></el-icon>
                  {{ detailList.electronicDeliveryTicket === '0' ? '无票' : (detailList.electronicDeliveryTicket === '1' ? '电子票' : '快递票') }}
                </span>
              </div>
              <div class="service-item__desc" v-if="detailList.electronicDeliveryTicketExplain">{{ detailList.electronicDeliveryTicketExplain }}</div>
            </div>

            <div class="service-item" v-if="detailList.electronicInvoice !== ''">
              <div class="service-item__head">
                <span class="service-badge service-badge--success">
                  <el-icon><Document /></el-icon>
                  {{ detailList.electronicInvoice === '0' ? '纸质发票' : '电子发票' }}
                </span>
              </div>
              <div class="service-item__desc" v-if="detailList.electronicInvoiceExplain">{{ detailList.electronicInvoiceExplain }}</div>
            </div>
          </div>
        </div>

        <!-- 推荐列表 -->
        <div class="recommend-box">
          <div class="box-like">为你推荐</div>
          <ul class="search__box">
            <li class="search__item" v-for="item in recommendList" :key="item.id">
              <router-link :to="{ name: 'detail', params: { id: item.id } }" class="link">
                <img :src="item.itemPicture" alt="Recommend" />
              </router-link>

              <div class="search_item_info">
                <router-link :to="{ name: 'detail', params: { id: item.id } }" class="link__title">
                  {{ item.title }}
                </router-link>
                <div class="search__item__info__venue">{{ item.place }}</div>
                <div class="search__item__info__venue">{{ formatDateWithWeekday(item.showTime, item.showWeekTime) }}</div>
                <div class="search__item__info__price">￥<strong>{{ item.minPrice }}</strong> 起</div>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="detail">
import Header from '@/components/header/index.vue'
import { formatDateWithWeekday } from '@/utils/index'
import { useRoute, useRouter } from 'vue-router'
import { getProgramDetails } from '@/api/contentDetail'
import { ref, reactive, computed } from 'vue'
import { useMitt } from '@/utils/index'
import { getProgramRecommendList } from '@/api/recommendlist'
import { getToken } from '@/utils/auth'
import { ElMessage } from 'element-plus'
import { Calendar, Location, Lock, CircleClose, CircleCheck, User, UserFilled, Ticket, Iphone, Document, ArrowLeft } from '@element-plus/icons-vue'

const emitter = useMitt()
const route = useRoute()
const router = useRouter()

function goBack(): void {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/')
  }
}

const paramValue = Number(route.params.id)
const detailList = ref<any>({})
const ticketCategoryVoList = ref<any[]>([])
const actvieIndex = ref<number | string>('')
const menuActive = ref<number | string>(1)
const ticketNeedInfo = ref<{ name: string; value: string }[]>([])
const watchNeedInfo = ref<{ name: string; value: string }[]>([])
const num = ref<number>(1)
const countPrice = ref<number | string>('')
const allPrice = computed(() => Number(countPrice.value) * num.value)
const ticketCategoryId = ref<string>('')

const recommendParams = reactive<any>({
  areaId: undefined,
  parentProgramCategoryId: undefined,
  programId: paramValue,
})

const recommendList = ref<any[]>([])
getProgramDetailsList()

function getProgramDetailsList(): void {
  getProgramDetails({ id: paramValue }).then((response: any) => {
    detailList.value = response.data
    ticketCategoryVoList.value = detailList.value.ticketCategoryVoList ?? []
    if (ticketCategoryVoList.value.length) {
      countPrice.value = ticketCategoryVoList.value[0].price
      ticketCategoryId.value = ticketCategoryVoList.value[0].id
      actvieIndex.value = 0
    }
    ticketNeedInfo.value = [
      { name: '限购规则', value: detailList.value.purchaseLimitRule },
      { name: '退票/换票规则', value: detailList.value.refundTicketRule },
      { name: '入场规则', value: detailList.value.entryRule },
      { name: '儿童购票', value: detailList.value.childPurchase },
      { name: '发票说明', value: detailList.value.invoiceSpecification },
      { name: '实名购票规则', value: detailList.value.realNameRule },
      { name: '异常排查', value: detailList.value.abnormalTroubleshooting },
    ]
    watchNeedInfo.value = [
      { name: '演出时长', value: detailList.value.performanceDuration },
      { name: '入场时间', value: detailList.value.entryTime },
      { name: '主要演员', value: detailList.value.mainActor },
      { name: '最低演出时长', value: detailList.value.minimumPerformanceDuration },
      { name: '禁止携带物品', value: detailList.value.prohibitedItems },
      { name: '寄存说明', value: detailList.value.depositDescription },
    ]
  })
}

function handleChange(value: number): void {
  num.value = value
}

function ticketClick(item: any, index: number): void {
  actvieIndex.value = index
  countPrice.value = item.price
  ticketCategoryId.value = item.id
}

function detailClick(selector: string, index: number): void {
  menuActive.value = index
  const element = document.querySelector(selector)
  if (element) {
    element.scrollIntoView({ behavior: 'smooth' })
  }
}

const nowBuy = (): void => {
  if (!ticketCategoryId.value) {
    ElMessage.warning('请选择您要购买的票档！')
    return
  }
  const token = getToken()
  if (!token) {
    ElMessage.warning('购买门票需要先登录，请登录后继续')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }

  const orderState = {
    detailList: JSON.stringify(detailList.value),
    allPrice: allPrice.value,
    countPrice: countPrice.value,
    num: num.value,
    ticketCategoryId: ticketCategoryId.value,
  }

  sessionStorage.setItem('tf_pending_order', JSON.stringify(orderState))

  router.push({
    path: '/order/index',
    name: 'orderIndex',
    state: orderState,
  })
}

const seatBuy = (): void => {
  const token = getToken()
  if (!token) {
    ElMessage.warning('选座购买需要先登录，请登录后继续')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  router.push({
    name: 'SeatSelect',
    state: { detailList: JSON.stringify(detailList.value) },
  })
}

getRecommendList()

function getRecommendList(): void {
  getProgramRecommendList(recommendParams).then((response: any) => {
    recommendList.value = response.data ? response.data.slice(0, 6) : []
  })
}
</script>

<style scoped lang="scss">
.app-container {
  max-width: var(--tf-max-width, 1200px);
  width: 100%;
  margin: 24px auto 40px;
  padding: 0 20px;
  box-sizing: border-box;
  overflow-x: hidden;

  .breadcrumb-bar {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 20px;

    .back-btn {
      border-radius: var(--tf-radius-full);
      font-weight: 500;
      display: flex;
      align-items: center;
      gap: 4px;
    }

    :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
      color: var(--tf-text-primary);
      font-weight: 600;
    }
  }

  .wrapper {
    display: flex;
    gap: 24px;
    width: 100%;
    box-sizing: border-box;

    .box-left {
      flex: 1;
      min-width: 0;
      box-sizing: border-box;

      /* 2026 Hero Card */
      .box-detail {
        background: var(--tf-surface);
        border-radius: var(--tf-radius-lg);
        border: 1px solid var(--tf-border);
        box-shadow: var(--tf-shadow-sm);
        padding: 28px 24px;
        margin-bottom: 24px;
        box-sizing: border-box;
        width: 100%;
        overflow: hidden;
      }

      .detail-hero {
        display: flex;
        gap: 24px;
        width: 100%;
        box-sizing: border-box;
      }

      .poster-box {
        position: relative;
        width: 230px;
        height: 310px;
        flex-shrink: 0;

        .poster-img {
          width: 100%;
          height: 100%;
          object-fit: cover;
          border-radius: var(--tf-radius-md);
          box-shadow: var(--tf-shadow-md);
          border: 1px solid var(--tf-border);
        }

        .poster-badge {
          position: absolute;
          top: 12px;
          left: 12px;
          padding: 4px 10px;
          background: rgba(255, 55, 29, 0.9);
          color: #fff;
          font-size: 12px;
          font-weight: 700;
          border-radius: var(--tf-radius-full);
          backdrop-filter: blur(8px);
          box-shadow: 0 2px 8px rgba(0,0,0,0.15);
        }
      }

      .info-box {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 16px;
        box-sizing: border-box;

        .title-row {
          display: flex;
          align-items: flex-start;
          gap: 10px;

          .tips-pill {
            flex-shrink: 0;
            padding: 4px 10px;
            background: var(--tf-primary-light);
            color: var(--tf-primary);
            font-size: 12px;
            font-weight: 700;
            border-radius: var(--tf-radius-full);
            margin-top: 4px;
          }

          .main-title {
            margin: 0;
            font-size: 22px;
            font-weight: 700;
            line-height: 1.4;
            color: var(--tf-text-primary);
          }
        }

        .meta-rows {
          display: flex;
          flex-direction: column;
          gap: 8px;

          .meta-item {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 14px;
            color: var(--tf-text-secondary);

            .el-icon {
              color: var(--tf-primary);
              font-size: 16px;
            }

            .meta-label {
              color: var(--tf-text-muted);
            }

            .meta-value {
              color: var(--tf-text-primary);
              font-weight: 500;
            }
          }
        }

        .notice-banner {
          display: flex;
          gap: 12px;
          padding: 12px 16px;
          background: #FFF7ED;
          border: 1px solid #FFEDD5;
          border-radius: var(--tf-radius-md);

          &__tag {
            flex-shrink: 0;
            font-size: 12px;
            font-weight: 700;
            color: #C2410C;
          }

          &__content {
            font-size: 12px;
            color: #9A3412;
            line-height: 1.5;

            .sub-text {
              color: #C2410C;
              margin-top: 2px;
            }
          }
        }

        .option-row {
          display: flex;
          align-items: flex-start;
          gap: 16px;

          .option-label {
            width: 48px;
            font-size: 14px;
            font-weight: 600;
            color: var(--tf-text-secondary);
            padding-top: 6px;
            flex-shrink: 0;
          }

          .option-content {
            flex: 1;
            display: flex;
            align-items: center;
            gap: 10px;
            flex-wrap: wrap;
          }

          .active-chip {
            padding: 6px 16px;
            background: #F1F5F9;
            border: 1px solid var(--tf-border);
            border-radius: var(--tf-radius-sm);
            font-size: 13px;
            color: var(--tf-text-primary);
            font-weight: 500;
          }

          .notice-hint {
            font-size: 12px;
            color: var(--tf-text-muted);
          }

          .ticket-grid {
            display: flex;
            flex-wrap: wrap;
            gap: 10px;
          }

          .ticket-chip {
            padding: 8px 18px;
            background: #F8FAFC;
            border: 1px solid var(--tf-border);
            border-radius: var(--tf-radius-sm);
            cursor: pointer;
            transition: all 0.2s;
            user-select: none;

            .price-text {
              font-size: 13px;
              color: var(--tf-text-primary);
              font-weight: 500;
            }

            &:hover {
              border-color: var(--tf-primary);
              color: var(--tf-primary);
              background: var(--tf-primary-light);
            }

            &--active {
              background: var(--tf-primary) !important;
              border-color: var(--tf-primary) !important;

              .price-text {
                color: #FFFFFF !important;
                font-weight: 700;
              }
            }
          }

          .quantity-wrapper {
            display: flex;
            align-items: center;
            gap: 12px;

            .num-limit {
              font-size: 12px;
              color: var(--tf-text-muted);
            }
          }
        }

        .checkout-bar {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding-top: 16px;
          border-top: 1px solid var(--tf-border);
          margin-top: 8px;

          .price-block {
            display: flex;
            align-items: baseline;
            gap: 4px;

            .price-label {
              font-size: 14px;
              color: var(--tf-text-secondary);
              margin-right: 6px;
            }

            .price-symbol {
              font-size: 16px;
              font-weight: 700;
              color: var(--tf-primary);
            }

            .price-amount {
              font-size: 28px;
              font-weight: 800;
              color: var(--tf-primary);
              line-height: 1;
            }
          }

          .buy-btn {
            height: 44px;
            padding: 0 36px;
            border: none;
            border-radius: var(--tf-radius-full);
            font-size: 15px;
            font-weight: 700;
            cursor: pointer;
            transition: all 0.2s;

            &--primary {
              background: var(--tf-primary);
              color: #FFFFFF;
              box-shadow: 0 4px 14px rgba(255, 55, 29, 0.3);

              &:hover {
                background: var(--tf-primary-hover);
                transform: translateY(-1px);
                box-shadow: 0 6px 18px rgba(255, 55, 29, 0.4);
              }
            }

            &--accent {
              background: var(--tf-accent);
              color: #FFFFFF;
              box-shadow: 0 4px 14px rgba(79, 70, 229, 0.3);

              &:hover {
                opacity: 0.95;
                transform: translateY(-1px);
              }
            }
          }
        }
      }

      /* 详请 Tab 栏与 Section */
      .box-item {
        background: #FFFFFF;
        border-radius: var(--tf-radius-lg);
        border: 1px solid var(--tf-border);
        box-shadow: var(--tf-shadow-sm);
        padding: 24px 32px 32px;
      }

      .box-menu {
        position: sticky;
        top: 64px;
        z-index: 100;
        display: flex;
        gap: 12px;
        padding: 12px 0;
        background: rgba(255, 255, 255, 0.95);
        backdrop-filter: blur(12px);
        border-bottom: 1px solid var(--tf-border);
        margin-bottom: 24px;

        .menu-children {
          padding: 8px 20px;
          border-radius: var(--tf-radius-full);
          font-size: 14px;
          font-weight: 500;
          color: var(--tf-text-secondary);
          text-decoration: none;
          transition: all 0.2s;

          &:hover {
            color: var(--tf-primary);
            background: var(--tf-primary-light);
          }

          &.menuActive {
            background: var(--tf-primary);
            color: #FFFFFF !important;
            font-weight: 700;
          }
        }
      }

      .detail-section {
        margin-bottom: 36px;

        .section-title {
          display: flex;
          align-items: center;
          gap: 10px;
          font-size: 18px;
          font-weight: 700;
          color: var(--tf-text-primary);
          margin-bottom: 20px;

          .accent-bar {
            width: 4px;
            height: 18px;
            background: var(--tf-primary);
            border-radius: 2px;
          }
        }

        .detail-content {
          width: 100%;
          overflow: hidden;
          box-sizing: border-box;

          img, .detail-img {
            max-width: 100% !important;
            height: auto !important;
            border-radius: var(--tf-radius-md);
            display: block;
            margin: 0 auto;
          }
        }

        .rules-grid {
          display: flex;
          flex-direction: column;
          gap: 16px;
        }

        .rule-card {
          padding: 16px 20px;
          background: #F8FAFC;
          border: 1px solid var(--tf-border);
          border-radius: var(--tf-radius-md);

          .rule-name {
            display: inline-block;
            font-size: 13px;
            font-weight: 700;
            color: var(--tf-text-secondary);
            margin-bottom: 6px;
          }

          .rule-value {
            font-size: 14px;
            color: var(--tf-text-primary);
            line-height: 1.6;
          }
        }
      }
    }

    /* 侧边栏推荐列表与服务卡片 */
    .box-right {
      width: 300px;
      flex-shrink: 0;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      gap: 20px;

      .service-card {
        background: #FFFFFF;
        border: 1px solid var(--tf-border);
        border-radius: var(--tf-radius-lg);
        padding: 18px 16px;
        box-shadow: var(--tf-shadow-sm);
        box-sizing: border-box;
        width: 100%;

        &__header {
          display: flex;
          align-items: center;
          gap: 8px;
          font-size: 15px;
          font-weight: 700;
          color: var(--tf-text-primary);
          padding-bottom: 12px;
          border-bottom: 1px dashed var(--tf-border);
          margin-bottom: 14px;

          .el-icon {
            color: var(--tf-primary);
          }
        }

        &__body {
          display: flex;
          flex-direction: column;
          gap: 12px;
        }

        .service-item {
          display: flex;
          flex-direction: column;
          gap: 4px;

          &__head {
            display: flex;
            align-items: center;
          }

          &__desc {
            font-size: 12px;
            color: var(--tf-text-secondary);
            line-height: 1.5;
            padding-left: 2px;
          }
        }

        .service-badge {
          display: inline-flex;
          align-items: center;
          gap: 5px;
          font-size: 12px;
          font-weight: 600;
          padding: 4px 10px;
          border-radius: var(--tf-radius-full);

          &--success {
            background: #ECFDF5;
            color: #059669;
          }

          &--warn {
            background: #FFF7ED;
            color: #EA580C;
          }

          &--info {
            background: #F1F5F9;
            color: #475569;
          }
        }
      }

      .recommend-box {
        background: #FFFFFF;
        border: 1px solid var(--tf-border);
        border-radius: var(--tf-radius-lg);
        padding: 20px 18px;
        box-shadow: var(--tf-shadow-sm);

        .box-like {
          font-size: 16px;
          font-weight: 700;
          color: var(--tf-text-primary);
          margin-bottom: 16px;
          padding-bottom: 10px;
          border-bottom: 1px solid var(--tf-border);
        }

        .search__box {
          list-style: none;
          margin: 0;
          padding: 0;
          display: flex;
          flex-direction: column;
          gap: 16px;

          .search__item {
            display: flex;
            gap: 12px;

            .link {
              flex-shrink: 0;
              img {
                width: 72px;
                height: 96px;
                object-fit: cover;
                border-radius: var(--tf-radius-sm);
                border: 1px solid var(--tf-border);
                transition: transform 0.2s;

                &:hover {
                  transform: scale(1.03);
                }
              }
            }

            .search_item_info {
              flex: 1;
              min-width: 0;
              display: flex;
              flex-direction: column;
              gap: 4px;

              .link__title {
                font-size: 13px;
                font-weight: 600;
                color: var(--tf-text-primary);
                text-decoration: none;
                display: -webkit-box;
                -webkit-line-clamp: 2;
                -webkit-box-orient: vertical;
                overflow: hidden;
                line-height: 1.4;

                &:hover {
                  color: var(--tf-primary);
                }
              }

              .search__item__info__venue {
                font-size: 12px;
                color: var(--tf-text-muted);
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
              }

              .search__item__info__price {
                font-size: 12px;
                color: var(--tf-primary);
                margin-top: auto;

                strong {
                  font-size: 16px;
                  font-weight: 700;
                }
              }
            }
          }
        }
      }
    }
  }
}
</style>
