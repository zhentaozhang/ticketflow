<template>
  <Header></Header>
  <div class="app-container">
    <!-- 2026 探索大厅头部 Header -->
    <div class="explore-header">
      <div class="explore-header__left">
        <h1 class="explore-title">演出探索大厅</h1>
        <span class="explore-count">共 <strong>{{ total || goods }}</strong> 个精彩剧目</span>
      </div>
    </div>

    <div class="box-main">
      <div class="box-main-left">
        <!-- 2026 级联胶囊筛选面板 (彻底替换 el-collapse) -->
        <div class="filter-panel">
          <!-- 城市筛选 -->
          <div class="filter-row">
            <span class="filter-label">城市：</span>
            <div class="filter-chips">
              <span
                v-for="(item, index) in (isShow ? cityArr.slice(0, CITY_DISPLAY_LIMIT) : cityArr)"
                :key="item.id"
                class="filter-chip"
                :class="{ 'filter-chip--active': item.name === currentCity || (item.name === '全部' && !currentCity) }"
                @click="cityClick(item, index)"
              >
                {{ item.name }}
              </span>
              <button v-if="cityArr.length > CITY_DISPLAY_LIMIT" class="toggle-more-btn" @click="isShow = !isShow">
                {{ isShow ? '更多 ▾' : '收起 ▴' }}
              </button>
            </div>
          </div>

          <!-- 主分类筛选 -->
          <div class="filter-row">
            <span class="filter-label">分类：</span>
            <div class="filter-chips">
              <span
                v-for="(item, ind) in categoryArr"
                :key="item.id"
                class="filter-chip"
                :class="{
                  'filter-chip--active': $route.query.name === item.name || ($route.query.name !== item.name && item.name === '全部' && isActive) || ($route.query.name !== item.name && item.name !== '全部' && activeIndex === ind)
                }"
                @click="categoryClick(item, ind)"
              >
                {{ item.name }}
              </span>
            </div>
          </div>

          <!-- 子分类筛选 (联动) -->
          <div class="filter-row filter-row--sub" v-if="isShowChildren && childrenArr.length">
            <span class="filter-label">子类：</span>
            <div class="filter-chips">
              <span
                v-for="(item, index) in childrenArr"
                :key="item.id"
                class="filter-chip"
                :class="{ 'filter-chip--active': activeChildrenIndex === index }"
                @click="childrenClick(item, index)"
              >
                {{ item.name }}
              </span>
            </div>
          </div>

          <!-- 时间筛选 -->
          <div class="filter-row">
            <span class="filter-label">时间：</span>
            <div class="filter-chips">
              <span
                v-for="(item, index) in timeArr"
                :key="item.id"
                class="filter-chip"
                :class="{ 'filter-chip--active': activeTimeIndex === index }"
                @click="timeClick(item, index)"
              >
                {{ item.name }}
              </span>
              <div v-if="isShowDate" class="date-picker-wrap">
                <el-date-picker
                  v-model="value1"
                  type="daterange"
                  start-placeholder="开始时间"
                  end-placeholder="结束时间"
                  size="default"
                  @change="handleChangeDate"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- 排序选项卡与列表 -->
        <div class="box-sort">
          <el-tabs type="border-card" class="box-tabs" @tab-click="handleClickTab">
            <el-tab-pane label="综合相关">
              <ProgramList :list="cardArr" :total="total" :titleIsShow="titleIsShow" :queryParams="queryParams" @pagination="getList" />
            </el-tab-pane>
            <el-tab-pane label="推荐排序">
              <ProgramList :list="cardArr" :total="total" :titleIsShow="titleIsShow" :queryParams="queryParams" @pagination="getList" />
            </el-tab-pane>
            <el-tab-pane label="最近开场">
              <ProgramList :list="cardArr" :total="total" :titleIsShow="titleIsShow" :queryParams="queryParams" @pagination="getList" />
            </el-tab-pane>
            <el-tab-pane label="最新上架">
              <ProgramList :list="cardArr" :total="total" :titleIsShow="titleIsShow" :queryParams="queryParams" @pagination="getList" />
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>

      <!-- 右侧推荐列表 Sidebar -->
      <div class="box-main-right">
        <div class="recommend-card">
          <div class="recommend-title">热门巡演推荐</div>
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
    <Footer></Footer>
  </div>
</template>

<script setup lang="ts">
import { getCurrentInstance, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { getCurrentCity, getOtherCity } from '@/api/area'
import { getcategoryType } from "@/api/index"
import { getCurrentDate, useMitt, formatDateWithWeekday } from "@/utils/index"
import { getChildrenType, getProgramPageType } from "@/api/allType"
import { getProgramRecommendList } from "@/api/recommendlist"
import { CITY_DISPLAY_LIMIT, DEFAULT_PAGE_SIZE } from '@/utils/constants'
import { useRouter } from 'vue-router'
import ProgramList from '@/components/program-list/index.vue'

const router = useRouter()
const emitter = useMitt()

const goods = ref(5)

const cityArr = ref<any[]>([])
const categoryArr = ref<any[]>([])
const childrenArr = ref<any[]>([])
const currentCity = ref('')
const parentProgramCategoryId = ref('')
const isShow = ref(true)
const activeIndex = ref<any>('')
const activeCityIndex = ref<any>('')
const activeChildrenIndex = ref<any>('')
const activeTimeIndex = ref<any>(0)
const isShowChildren = ref(false)
const queryParams = ref({ pageNum: 1, pageSize: DEFAULT_PAGE_SIZE })
const total = ref(0)
const isShowDate = ref(false)
const value1 = ref<any>([])
const timeType = ref(0)
const cardArr = ref<any[]>([])
const titleIsShow = ref(true)
const recommendList = ref<any[]>([])
const isActive = ref(false)

const pageParams = ref<any>({
  areaId: undefined,
  endDateTime: undefined,
  pageNumber: undefined,
  pageSize: undefined,
  parentProgramCategoryId: undefined,
  programCategoryId: undefined,
  startDateTime: undefined,
  timeType: undefined,
  type: 1
})

const recommendParams = reactive<any>({
  areaId: undefined,
  parentProgramCategoryId: 1,
  programId: undefined
})

const { proxy } = getCurrentInstance()!

// 获取城市数据
const getcityList = () => {
  getOtherCity().then((response: any) => {
    cityArr.value = response.data || []
    cityArr.value.unshift({ name: '全部', id: '' })
  })
}
getcityList()

// 当前城市
const getCurrent = () => {
  getCurrentCity().then((response: any) => {
    if (response.data) {
      let { name } = response.data
      currentCity.value = name
    }
  })
}
getCurrent()

// 获取分类
const getTypeList = () => {
  getcategoryType({ type: 1 }).then((response: any) => {
    categoryArr.value = response.data || []
    categoryArr.value.unshift({ name: '全部', id: '' })
  })
}
getTypeList()

// 获取子类
const getChildrenTypeList = () => {
  getChildrenType({ parentProgramCategoryId: parentProgramCategoryId.value }).then((response: any) => {
    childrenArr.value = response.data || []
    childrenArr.value.unshift({ name: '全部', id: '' })
    if (childrenArr.value.length <= 1) {
      isShowChildren.value = false
    }
  })
}

// 点击分类每一项
const categoryClick = (item: any, ind: any) => {
  if (proxy && proxy.$route && proxy.$route.query) {
    proxy.$route.query.name = ''
  }
  activeIndex.value = ind
  if (item.name === '全部') {
    isActive.value = true
    isShowChildren.value = false
    parentProgramCategoryId.value = ''
    pageParams.value.parentProgramCategoryId = undefined
  } else {
    isActive.value = false
    isShowChildren.value = true
    parentProgramCategoryId.value = item.id
    pageParams.value.parentProgramCategoryId = item.id
    recommendParams.parentProgramCategoryId = item.id
    getChildrenTypeList()
  }
  getList()
  getRecommendList()
}

// 点击城市
const cityClick = (item: any, index: any) => {
  activeCityIndex.value = index
  currentCity.value = item.name === '全部' ? '' : item.name
  pageParams.value.areaId = item.id || undefined
  recommendParams.areaId = item.id || undefined
  getList()
  getRecommendList()
}

// 点击子类
const childrenClick = (item: any, index: any) => {
  activeChildrenIndex.value = index
  pageParams.value.programCategoryId = item.id || undefined
  getList()
}

// 点击时间
const timeClick = (item: any, index: any) => {
  activeTimeIndex.value = index
  timeType.value = item.id
  pageParams.value.timeType = item.id
  if (item.id === 5) {
    isShowDate.value = true
  } else {
    isShowDate.value = false
    pageParams.value.startDateTime = undefined
    pageParams.value.endDateTime = undefined
    getList()
  }
}

const handleChangeDate = (selection: any) => {
  if (selection && selection.length === 2) {
    pageParams.value.startDateTime = getCurrentDate(selection[0])
    pageParams.value.endDateTime = getCurrentDate(selection[1])
    getList()
  }
}

// 时间数组
const timeArr = ref([
  { name: '全部', id: 0 },
  { name: '今天', id: 1 },
  { name: '明天', id: 2 },
  { name: '本周末', id: 3 },
  { name: '一个月内', id: 4 },
  { name: '按日历', id: 5 },
])

const getList = () => {
  pageParams.value.timeType = timeType.value
  pageParams.value.pageNumber = queryParams.value.pageNum
  pageParams.value.pageSize = queryParams.value.pageSize
  getProgramPageType(pageParams.value).then((response: any) => {
    if (response.data) {
      cardArr.value = response.data.list || []
      total.value = Number(response.data.totalSize || 0)
    }
  })
}

// 节目推荐列表
const getRecommendList = () => {
  if (!recommendParams.parentProgramCategoryId) {
    recommendParams.parentProgramCategoryId = 1
  }
  getProgramRecommendList(recommendParams).then((response: any) => {
    recommendList.value = response.data ? response.data.slice(0, 5) : []
  })
}

const searchListHandler = (data: any) => {
  cardArr.value = data.list || []
  total.value = Number(data.totalSize || 0)
  titleIsShow.value = false
}

onMounted(() => {
  pageParams.value.pageNumber = 1
  pageParams.value.pageSize = 10
  pageParams.value.areaId = currentCity.value
  pageParams.value.timeType = timeType.value
  if (proxy && proxy.$route && proxy.$route.query) {
    pageParams.value.parentProgramCategoryId = proxy.$route.query.id
  }
  getList()
  getRecommendList()
  emitter.on('searchList', searchListHandler)
})

onBeforeUnmount(() => {
  emitter.off('searchList', searchListHandler)
})

function handleClickTab(tab: any) {
  pageParams.value.type = Number(tab.index) + 1
  getList()
}
</script>

<style scoped lang="scss">
.app-container {
  max-width: var(--tf-max-width, 1200px);
  width: 100%;
  margin: 24px auto 60px;
  padding: 0 20px;
  box-sizing: border-box;

  .explore-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20px;

    &__left {
      display: flex;
      align-items: baseline;
      gap: 12px;
    }

    .explore-title {
      font-size: 24px;
      font-weight: 800;
      color: var(--tf-text-primary);
      margin: 0;
    }

    .explore-count {
      font-size: 14px;
      color: var(--tf-text-secondary);

      strong {
        color: var(--tf-primary);
        font-weight: 700;
      }
    }
  }

  .box-main {
    display: flex;
    gap: 24px;
    align-items: flex-start;

    .box-main-left {
      flex: 1;
      min-width: 0;

      /* 2026 一体化 Segmented Filter Panel */
      .filter-panel {
        background: var(--tf-surface);
        border-radius: var(--tf-radius-lg);
        border: 1px solid var(--tf-border);
        padding: 24px;
        margin-bottom: 24px;
        box-shadow: var(--tf-shadow-sm);
        display: flex;
        flex-direction: column;
        gap: 16px;
      }

      .filter-row {
        display: flex;
        align-items: flex-start;
        gap: 12px;

        &--sub {
          padding-left: 20px;
          border-left: 2px solid var(--tf-primary);
        }

        .filter-label {
          width: 54px;
          font-size: 14px;
          font-weight: 700;
          color: var(--tf-text-secondary);
          padding-top: 5px;
          flex-shrink: 0;
          text-align: right;
        }

        .filter-chips {
          flex: 1;
          display: flex;
          flex-wrap: wrap;
          align-items: center;
          gap: 8px 10px;
        }

        .filter-chip {
          cursor: pointer;
          font-size: 13px;
          color: var(--tf-text-primary);
          transition: all 0.2s;
          padding: 5px 14px;
          border-radius: var(--tf-radius-full);
          background: var(--tf-bg);
          border: 1px solid var(--tf-border);
          user-select: none;

          &:hover {
            color: var(--tf-primary);
            background: var(--tf-primary-light);
            border-color: var(--tf-primary);
          }

          &--active {
            background: var(--tf-primary) !important;
            color: #ffffff !important;
            font-weight: 700;
            border-color: var(--tf-primary) !important;
            box-shadow: 0 2px 8px rgba(255, 55, 29, 0.25);
          }
        }

        .toggle-more-btn {
          background: transparent;
          border: none;
          color: var(--tf-primary);
          font-size: 13px;
          font-weight: 600;
          cursor: pointer;
          padding: 4px 8px;

          &:hover {
            text-decoration: underline;
          }
        }

        .date-picker-wrap {
          margin-left: 8px;
        }
      }

      /* 排序 Tab 栏 */
      .box-sort {
        background: var(--tf-surface);
        border-radius: var(--tf-radius-lg);
        border: 1px solid var(--tf-border);
        box-shadow: var(--tf-shadow-sm);
        overflow: hidden;
        min-height: 400px;

        :deep(.el-tabs--border-card) {
          border: none;
          box-shadow: none;
          background: transparent;
        }

        :deep(.el-tabs__header) {
          background: var(--tf-bg);
          border-bottom: 1px solid var(--tf-border);
        }

        :deep(.el-tabs__item) {
          font-size: 14px;
          font-weight: 600;
          color: var(--tf-text-secondary);
          height: 48px;
          line-height: 48px;

          &.is-active {
            color: var(--tf-primary) !important;
            background: var(--tf-surface) !important;
            border-right-color: var(--tf-border) !important;
            border-left-color: var(--tf-border) !important;
          }
        }
      }
    }

    /* 右侧推荐面板 */
    .box-main-right {
      width: 300px;
      flex-shrink: 0;

      .recommend-card {
        background: var(--tf-surface);
        border: 1px solid var(--tf-border);
        border-radius: var(--tf-radius-lg);
        padding: 20px 18px;
        box-shadow: var(--tf-shadow-sm);

        .recommend-title {
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
